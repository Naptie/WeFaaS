package me.naptie.wefaas

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference


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
    val id: Int, val api: String, val data: String
)

class WeFaaS : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private val label = "[WeFaaS]"
    private val logList = mutableListOf<String>()
    private val maxLogSize = 1500

    private val apiMap =
        Collections.synchronizedMap(object : java.util.LinkedHashMap<Int, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) =
                size > 50
        })

    private val isHooked = AtomicReference(false)
    private val currentAppId = AtomicReference<String?>(null)
    private val appBrandCommonBindingJniInstance: AtomicReference<Any?> = AtomicReference(null)

    // Store pending requests: requestId -> Deferred Result
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    // WebSocket sessions for live broadcast
    private val webSocketSessions =
        Collections.synchronizedSet(mutableSetOf<io.ktor.http.cio.websocket.DefaultWebSocketSession>())
    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {

    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.tencent.mm") {
            log("$label Found process: ${lpparam.processName}")
            setupHooks(lpparam)
        }
    }

    private fun setupHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Target the AppBrand processes
        if (!lpparam.processName.contains("com.tencent.mm:appbrand")) return

        log("$label Active in process ${lpparam.processName}. Waiting for UI...")

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
                        val classLoader = activity.classLoader
                        log("$label Activity $activityName found. Injecting hooks...")

                        if (inject(classLoader)) {
                            getAppIdHooked = true // Only hook once per process
                        }
                    }
                }
            })
    }

    private fun inject(classLoader: ClassLoader): Boolean {
        var success = false

        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.appbrand.y",
                classLoader,
                "getAppId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val appId = param.result as? String ?: return

                        if (isHooked.compareAndSet(false, true)) {
                            injectHandlerHooks(classLoader)
                            startServer()
                        }

                        currentAppId.set(appId)
                    }
                })
            log("$label Hooked getAppId successfully.")
            success = true
        } catch (e: Throwable) {
            log("$label Failed to hook getAppId: ${e.message}")
        }

        return success
    }

    private fun injectHandlerHooks(classLoader: ClassLoader) {
        try {
            // REQUEST (nativeInvokeHandler)
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
                        val appId = currentAppId.get() ?: return
                        val instance = param.thisObject
                        val api = param.args[0] as String
                        val data = param.args[1] as String
                        val id = param.args[3] as Int

                        appBrandCommonBindingJniInstance.set(instance)

//                        if (id > 0) {
                        log("$label [REQ] $appId #$id -> $api | $data | ${
                            param.args.drop(2).joinToString { it?.toString() ?: "null" }
                        }")
                        // Broadcast to WebSocket clients
                        broadcastLog(LogBroadcast("request", id, api, data))
//                        }
                        apiMap[id] = api
                    }
                })
            log("$label Hooked nativeInvokeHandler successfully.")
        } catch (e: Throwable) {
            log("$label Failed to hook Request: ${e.message}")
        }

        try {
            // RESPONSE (invokeCallbackHandler)
            XposedHelpers.findAndHookMethod("com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding",
                classLoader,
                "invokeCallbackHandler",
                "int",              // callbackId
                String::class.java, // result
                String::class.java, // extra
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val appId = currentAppId.get() ?: return
                        val id = param.args[0] as Int
                        val data = param.args[1] as String
                        val api = apiMap[id] ?: "callback"

                        log("$label [RES] $appId #$id <- $api | $data | ${
                            param.args.drop(2).joinToString { it?.toString() ?: "null" }
                        }")

                        // Broadcast to WebSocket clients
                        broadcastLog(LogBroadcast("response", id, api, data))

                        // Update per-app counter to match the system's counter if provided
//                        if (id > 0) invokeAsyncRequestCounter.set(id)

                        // Check if this is a response for our active call
                        if (pendingRequests.containsKey(id)) {
//                            log("$label [RES] Match found for ID: $requestId")
                            pendingRequests[id]?.complete(data)
                        }
                    }
                })
            log("$label Hooked invokeCallbackHandler successfully.")
        } catch (e: Throwable) {
            log("$label Failed to hook Response: ${e.message}")
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
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "${cause.localizedMessage}\n${cause.stackTraceToString()}"
                    )
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
                    val clientId = UUID.randomUUID().toString().take(8)
                    log("$label [WebSocket] New connection: $clientId")
                    webSocketSessions.add(this)
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                frame.readText()
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        log("$label [WebSocket] Connection closed normally: $clientId")
                    } catch (e: Throwable) {
                        log("$label [WebSocket] Connection error ($clientId): ${e.message}")
                    } finally {
                        webSocketSessions.remove(this)
                        log("$label [WebSocket] Client disconnected and removed: $clientId")
                    }
                }
            }
        }.start(wait = false)
    }

    private fun broadcastLog(logBroadcast: LogBroadcast) {
        val json = gson.toJson(logBroadcast)
        // Take a snapshot of sessions to avoid holding the lock during async operations
        val sessionsCopy = synchronized(webSocketSessions) { webSocketSessions.toList() }
        sessionsCopy.forEach { session ->
            try {
                // Launch non-blocking coroutine for each session
                moduleScope.launch {
                    try {
                        session.send(Frame.Text(json))
                    } catch (e: Throwable) {
                        // Session might be closed, ignore
                    }
                }
            } catch (e: Throwable) {
                // Ignore errors launching coroutine
            }
        }
    }

    private suspend fun invoke(
        appId: String,
        api: String,
        data: String,
        extra: String = "{}",
        isSync: Boolean = false,
        arg7: Int = -1
    ): String {
        if (currentAppId.get() != appId) {
            val msg = "Unable to locate appId: $appId. Please open the Mini Program first."
            log("$label $msg")
            throw IllegalStateException(msg)
        }

        val instance = appBrandCommonBindingJniInstance.get()
        if (instance == null) {
            val msg =
                "AppBrandCommonBindingJniInstance is null for appId: $appId. Please open the Mini Program first."
            log("$label $msg")
            throw IllegalStateException(msg)
        }

        val requestId = Instant.now().toEpochMilli().toInt()
        val deferred = CompletableDeferred<String>()
        pendingRequests[requestId] = deferred

        log("$label Invoke $appId #$requestId | $api")

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
                instance, api, data, extra, requestId, isSync, 0, arg7
            )

            // Wait for response
            return withTimeout(10000L) {
                deferred.await()
            }

        } catch (e: Exception) {
            log("$label Exception in invoke for appId $appId:\n${e.stackTraceToString()}")
            throw e
        } finally {
            pendingRequests.remove(requestId)
        }
    }
}