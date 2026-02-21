# WeFaaS

[Hook_WeChat_FaaS](https://github.com/FourTwooo/Hook_WeChat_FaaS) 的 Xposed 版本

## 用法

1. 自编译 apk 文件
2. 安装到有 LSPosed 的设备上，作用域勾选微信
3. 打开微信，进入任意一个小程序
4. 访问 Xposed 插件暴露的 API 接口，执行云函数和获取日志

## API 接口

### HTTP

|     接口名      |                        参数                        |   返回值    |           说明            |
|:------------:|:------------------------------------------------:|:--------:|:-----------------------:|
|    GET /     |                        无                         |   "ok"   | 用于测试 Xposed Server 是否开启 |
| POST /invoke | {"appId": string, "api": string, "data": string} |   JSON   |      调用云函数，返回执行结果       |
|  GET /logs   |                        无                         | string[] |      查询最近 1500 条日志      |

### WebSocket

|   接口名    | 参数 |                                      返回值                                       |    说明     |
|:--------:|:--:|:------------------------------------------------------------------------------:|:---------:|
| /ws/logs | 无  | {"api": string, "data": string, "id": number, "type": "request" \| "response"} | 实时收听请求与响应 |

## 注意事项

1. 第一次打开小程序执行自定义云函数后有可能会出现只有 request 但没有 response
   的情况，需要划掉小程序重新打开，再执行一次自定义云函数
2. 必须进入到小程序页面才能打开 Xposed Server，退出小程序后 Xposed Server 也会关闭
3. 本版本未经过充分测试，请勿在生产环境使用

## 效果图

wx.login 请求和响应
![jscode request](images/image-1.png)
![jscode response](images/image-2.png)

UserCryptoManager.getLatestUserKey 请求和响应
![getuserencryptkey request](images/image-3.png)
![getuserencryptkey response](images/image-4.png)

## 致谢

[Hook_WeChat_FaaS](https://github.com/FourTwooo/Hook_WeChat_FaaS)