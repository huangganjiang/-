package com.jishi.monitor.data.repo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.jishi.monitor.core.Logx
import com.jishi.monitor.data.model.JsonParse
import com.jishi.monitor.data.net.ApiClient
import com.jishi.monitor.data.net.LoginEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 快照仓库（M0 实测：/channels/{id}/snapshot 返回 JSON + base64 图片，不是图片直出）。
 * - 先 JSON 解析取 img，再 base64 解码为 Bitmap；
 * - 成功后写入内存缓存，离线通道展示最后一张（FR-2.5）；
 * - 离线通道会返回约 8.5 KB 的占位 PNG，业务上用 status 判断在线，不用图片体积判断。
 */
class SnapshotRepository(private val api: ApiClient, private val channels: ChannelRepository) {

    suspend fun fetch(channelId: String): Bitmap? = withContext(Dispatchers.IO) {
        Logx.i(TAG, "请求快照 " + channelId)
        val body = api.getBytes(LoginEndpoint.snapshot(channelId), readTimeoutSec = 20) ?: run {
            Logx.w(TAG, "快照请求失败: " + channelId)
            return@withContext null
        }
        val text = String(body, Charsets.UTF_8)
        val b64 = JsonParse.parseSnapshotBase64(text)
        if (b64.isNullOrBlank()) {
            Logx.w(TAG, "快照响应没有 img 字段: " + channelId + " body=" + text.take(80))
            return@withContext null
        }
        val bytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (t: Throwable) {
            Logx.w(TAG, "快照 base64 解码失败: " + channelId)
            return@withContext null
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) {
            channels.putSnapshot(channelId, bytes)
            Logx.i(TAG, "快照成功 " + channelId + " " + bmp.width + "x" + bmp.height)
        } else {
            Logx.w(TAG, "快照解码失败 " + channelId + " bytes=" + bytes.size)
        }
        bmp
    }

    fun cachedBytes(channelId: String): ByteArray? = channels.cachedSnapshot(channelId)

    companion object { private const val TAG = "SnapshotRepo" }
}
