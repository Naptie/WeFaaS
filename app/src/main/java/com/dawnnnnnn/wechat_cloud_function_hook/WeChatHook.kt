package com.dawnnnnnn.wechat_cloud_function_hook

import com.google.gson.Gson
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


data class InvokeRequest(
    val appId: String,
    val api: String,
    val data: String,
    val extra: String = "{}",
    val isSync: Boolean = false,
    val arg7: Int = -1
)

data class LogBroadcast(
    val type: String, // "request" or "response"
    val api: String,
    val data: String
)

// Per-app state containing the JNI instance and request counter
data class HookedAppState(
    @Volatile var appBrandCommonBindingJniInstance: Any? = null,
    val invokeAsyncRequestCounter: AtomicInteger = AtomicInteger(0)
)

class WeChatHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private var serverStarted = false
    // callAppId is used to track the currently processing request
    @Volatile
    private var callAppId: String? = null
    private val label = "[WeFaaS]"
    private val logList = mutableListOf<String>()
    private val maxLogSize = 1500

    // Map of appId -> HookedAppState (instance + counter)
    private val hookedApps = ConcurrentHashMap<String, HookedAppState>()

    // Map of JNI instance -> appId (to lookup appId from instance in hooks)
    private val instanceToAppId = ConcurrentHashMap<Any, String>()

    // Store pending requests: CallbackID -> Deferred Result
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    // WebSocket sessions for live broadcast
    private val webSocketSessions = Collections.synchronizedSet(mutableSetOf<io.ktor.http.cio.websocket.WebSocketSession>())
    private val gson = Gson()

    // Tracks whether hooks have been applied (to avoid duplicate hooks)
    private var requestResponseHooksApplied = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {

    }

    private fun setupHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Target the AppBrand processes
        if (!lpparam.processName.contains("com.tencent.mm:appbrand")) return

        log("$label Active in process ${lpparam.processName}. Waiting for UI to trigger hooks...")

        // 1. Hook the base Activity onCreate.
        // This is the "Frida trigger": once the mini-program UI exists, the engine MUST be loaded.
        XposedHelpers.findAndHookMethod("android.app.Activity",
            lpparam.classLoader,
            "onCreate",
            android.os.Bundle::class.java,
            object : XC_MethodHook() {
                private var getAppIdHooked = false

                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as android.app.Activity
                    val activityName = activity.javaClass.name

                    // Check if this is the AppBrandUI (the mini-program container)
                    if (activityName.contains("com.tencent.mm.plugin.appbrand.ui.AppBrandUI") && !getAppIdHooked) {
                        val realClassLoader = activity.classLoader
                        log("$label [UI DETECTED] $activityName found. Injecting hooks...")

                        if (injectHooks(realClassLoader)) {
                            getAppIdHooked = true // Only hook once per process
                        }
                    }
                }
            })
    }

    private fun injectHooks(classLoader: ClassLoader): Boolean {
        var success = false

        // Hook getAppId first - this is how we discover new appIds and set up their state
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.appbrand.y",
                classLoader,
                "getAppId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val appId = param.result as? String ?: return
                        
                        // Check if this app is already registered
                        if (hookedApps.containsKey(appId)) {
                            return
                        }

                        log("$label Found new AppID: $appId - Registering app state...")
                        
                        // Create state for this app
                        val appState = HookedAppState()
                        hookedApps[appId] = appState
                        
                        // Start server when first app is discovered
                        startServerIfNeeded()
                    }
                })
            log("$label Hooked getAppId successfully.")
            success = true
        } catch (e: Throwable) {
            log("$label Failed to hook getAppId: ${e.message}")
        }

        // Hook request/response only once (they're global hooks)
        if (!requestResponseHooksApplied) {
            injectRequestResponseHooks(classLoader)
            requestResponseHooksApplied = true
        }

        return success
    }

    private fun injectRequestResponseHooks(classLoader: ClassLoader) {
        try {
            // --- HOOK 1: REQUEST (nativeInvokeHandler) ---
            XposedHelpers.findAndHookMethod("com.tencent.mm.appbrand.commonjni.AppBrandCommonBindingJni",
                classLoader,
                "nativeInvokeHandler",
                String::class.java,           // api
                String::class.java,           // data
                String::class.java,           // extra
                "int",                        // callbackId
                "boolean",                    // isSync
                "int",                        // arg6
                "int",                        // arg7
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val instance = param.thisObject
                        val api = param.args[0] as String
                        val data = param.args[1] as String
                        val no = param.args[3] as Int

                        // Try to find appId for this instance
                        var appId = instanceToAppId[instance]
                        
                        // If not found, associate instance with the first available appId
                        if (appId == null) {
                            // Find an appState that doesn't have an instance yet
                            for ((id, state) in hookedApps) {
                                if (state.appBrandCommonBindingJniInstance == null) {
                                    state.appBrandCommonBindingJniInstance = instance
                                    instanceToAppId[instance] = id
                                    appId = id
                                    log("$label Associated instance with appId: $appId")
                                    break
                                }
                            }
                        }

                        // If still no appId, fall back to using "unknown"
                        val displayAppId = appId ?: "unknown"

                        if (no > 0) {
                            log("[REQ][$displayAppId] #$no -> $api | $data | ${
                                param.args.drop(2).joinToString { it?.toString() ?: "null" }
                            }")
                            // Broadcast to WebSocket clients
                            broadcastLog(LogBroadcast("request", api, data))
                        }
                    }
                })
            log("$label Hooked Request successfully.")
        } catch (e: Throwable) {
            log("$label Failed to hook Request: ${e.message}")
        }

        try {
            // --- HOOK 2: RESPONSE (invokeCallbackHandler) ---
            XposedHelpers.findAndHookMethod("com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding",
                classLoader,
                "invokeCallbackHandler",
                "int",              // callbackId
                String::class.java, // result
                String::class.java, // extra
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val id = param.args[0] as Int
                        val res = param.args[1] as String
                        
                        // Try to find the appId from the current callAppId (set during invoke)
                        val appId = callAppId ?: "unknown"
                        
                        log("[RES][$appId] #$id <- $res | ${
                            param.args.drop(2).joinToString { it?.toString() ?: "null" }
                        }")
                        
                        // Broadcast to WebSocket clients
                        broadcastLog(LogBroadcast("response", "callback", res))
                        
                        // Update per-app counter to match the system's counter if provided
                        val appState = hookedApps[appId]
                        if (appState != null) {
                            val current = appState.invokeAsyncRequestCounter.get()
                            if (id > current) {
                                appState.invokeAsyncRequestCounter.set(id)
                            }
                        }

                        // Check if this is a response for our active call
                        if (pendingRequests.containsKey(id)) {
                            log("$label [RES] Match found for ID: $id")
                            pendingRequests[id]?.complete(res)
                        }
                    }
                })
            log("$label Hooked Response successfully.")
        } catch (e: Throwable) {
            log("$label Failed to hook Response: ${e.message}")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.tencent.mm") {
            log("$label Found app ${lpparam.packageName} with processName: ${lpparam.processName}")
            setupHooks(lpparam)
        }
    }

    private fun startServerIfNeeded() {
        if (!serverStarted) {
            startServer()
            serverStarted = true
        }
    }

    private fun log(message: String) {
        XposedBridge.log(message)
        synchronized(logList) {
            if (logList.size >= maxLogSize) {
                logList.removeAt(0)
            }
            logList.add(message)
        }
    }

    private fun startServer() {
        log("$label Starting server on 0.0.0.0:59999")
        embeddedServer(Netty, port = 59999) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }
            install(WebSockets)
            install(StatusPages) {
                exception<Throwable> { cause ->
                    call.respond(HttpStatusCode.InternalServerError, "${cause.localizedMessage}\n${cause.stackTraceToString()}")
                }
            }
            routing {
                get("/") {
                    call.respond("ok")
                }
                post("/invoke") {
                    val request = call.receive<InvokeRequest>()
                    if (request.appId.isNotEmpty() && request.api.isNotEmpty() && request.data.isNotEmpty()) {
                        try {
                            val response = invoke(
                                request.appId,
                                request.api,
                                request.data,
                                request.extra,
                                request.isSync,
                                request.arg7
                            )
                            call.respondText(response, ContentType.Application.Json)
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
                        }
                    } else {
                        call.respond(HttpStatusCode.BadRequest, "Missing parameters")
                    }
                }
                get("/logs") {
                    call.respond(logList)
                }
                // WebSocket endpoint for live broadcast logs
                webSocket("/ws/logs") {
                    log("$label WebSocket client connected")
                    webSocketSessions.add(this)
                    try {
                        for (frame in incoming) {
                            // Keep connection alive, ignore incoming messages
                            if (frame is Frame.Text) {
                                frame.readText() // Just consume the message
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        log("$label WebSocket client disconnected")
                    } catch (e: Throwable) {
                        log("$label WebSocket error: ${e.message}")
                    } finally {
                        webSocketSessions.remove(this)
                    }
                }
            }
        }.start(wait = false)
    }

    private fun broadcastLog(logBroadcast: LogBroadcast) {
        val json = gson.toJson(logBroadcast)
        synchronized(webSocketSessions) {
            webSocketSessions.forEach { session ->
                try {
                    // Use runBlocking since we're in a non-suspend context from Xposed hooks
                    kotlinx.coroutines.runBlocking {
                        session.send(Frame.Text(json))
                    }
                } catch (e: Throwable) {
                    // Session might be closed, ignore
                }
            }
        }
    }

    private suspend fun invoke(
        appId: String,
        jsapiName: String,
        data: String,
        extra: String = "{}",
        isSync: Boolean = false,
        arg7: Int = -1
    ): String {
        // Get the app state from the map based on the incoming appId
        val appState = hookedApps[appId]
        if (appState == null) {
            val msg = "No hooked app found for appId: $appId. Please open the Mini Program first."
            log("$label $msg")
            throw IllegalStateException(msg)
        }

        val instance = appState.appBrandCommonBindingJniInstance
        if (instance == null) {
            val msg = "AppBrandCommonBindingJniInstance is null for appId: $appId. Please open the Mini Program first."
            log("$label $msg")
            throw IllegalStateException(msg)
        }

        // Track the currently processing request
        callAppId = appId
        val requestId = appState.invokeAsyncRequestCounter.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pendingRequests[requestId] = deferred

        log("$label [Dispatch][$appId] ID: $requestId | API: $jsapiName")

        try {
            val invokeMethod = instance::class.java.getMethod(
                "nativeInvokeHandler",
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.java,
                Boolean::class.java,
                Int::class.java,
                Int::class.java
            )
            invokeMethod.isAccessible = true

            invokeMethod.invoke(
                instance, jsapiName, data, extra, requestId, isSync, 0, arg7
            )

            // Wait for response
            return withTimeout(10000L) {
                deferred.await()
            }

        } catch (e: Exception) {
            log("$label Exception in invoke for appId $appId: ${e.message}")
            throw e
        } finally {
            pendingRequests.remove(requestId)
            callAppId = null
        }
    }
}