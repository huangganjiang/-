package com.jishi.monitor.data.net

/**
 * /login 契约（M0 实测）：
 *   POST /login  Content-Type: application/json  body {"username":..,"password":SHA256(明文) 十六进制小写}
 *   失败示例：400 {"details":["账号不存在"],"msg":"用户名或密码错误","reason":"ErrNameOrPasswd"}
 * 该常量集中在此，避免各处硬编码路径。
 */
object LoginEndpoint {
    const val PATH = "/login"
    const val CONTENT_TYPE = "application/json; charset=utf-8"

    // 服务端路由（M0 实测存在，需 Token）
    const val CHANNELS = "/channels"
    const val DEVICES = "/devices?size=100"
    const val CONFIG_PARAMS = "/configs/params"
    const val CONFIG_WEB = "/configs/info/web"

    fun snapshot(channelId: String) = "/channels/" + channelId + "/snapshot"
    fun live(channelId: String) = "/live/" + channelId + ".flv"
    fun months(channelId: String, yyyyMM: String) =
        "/records/months?channel_id=" + channelId + "&source=CLOUD&dates=" + yyyyMM
    fun timeline(channelId: String, startMs: Long, endMs: Long) =
        "/records/timeline?channel_id=" + channelId + "&source=CLOUD&start=" + startMs + "&end=" + endMs
    fun records(channelId: String, startMs: Long, endMs: Long) =
        "/records?channel_id=" + channelId + "&source=CLOUD&start=" + startMs + "&end=" + endMs
}
