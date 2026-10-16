package com.jishi.monitor.data.repo

import com.jishi.monitor.core.Logx
import com.jishi.monitor.data.Prefs
import com.jishi.monitor.data.model.Channel
import com.jishi.monitor.data.model.JsonParse
import com.jishi.monitor.data.net.ApiClient
import com.jishi.monitor.data.net.LoginEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 通道仓库。
 * 数量与在线状态一律以 /channels 返回为准（FR-2.1/2.2、AC-3、AC-22），绝不写死 7 路。
 * 家庭化名称用 /devices 的 name 与通道 device_id 关联后拼装（M0 实测 device_name 为空）。
 * 失败时回退到最近一次成功的缓存（FR-2.7）。
 */
class ChannelRepository(private val api: ApiClient, private val prefs: Prefs) {

    private val mutex = Mutex()
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels

    private val _devices = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceNames: StateFlow<Map<String, String>> = _devices

    /** 快照内存缓存：离线通道展示最后一张成功快照（FR-2.5）。 */
    private val snapshotCache = LinkedHashMap<String, ByteArray>()

    @Volatile var lastNote: String = ""
        private set

    @Volatile var lastError: String? = null
        private set

    suspend fun cachedChannels(): List<Channel> {
        val cached = prefs.lastChannelsJson() ?: return emptyList()
        return JsonParse.parseChannels(cached).channels
    }

    suspend fun refresh(): List<Channel> = mutex.withLock {
        val r = api.get(LoginEndpoint.CHANNELS)
        if (!r.ok) {
            lastError = if (r.code == 401) "鉴权失效" else "请求失败 " + r.code
            Logx.w(TAG, "/channels 失败 code=" + r.code)
            val fallback = cachedChannels()
            if (fallback.isNotEmpty()) _channels.value = fallback
            return@withLock fallback
        }
        val parsed = JsonParse.parseChannels(r.body)
        if (parsed.channels.isEmpty()) {
            Logx.w(TAG, "通道列表为空：" + parsed.note)
            return@withLock emptyList()
        }
        prefs.setLastChannelsJson(r.body)

        // 设备名关联（失败不阻断：退化为通道自带名）
        val names = HashMap<String, String>()
        val dr = api.get(LoginEndpoint.DEVICES)
        if (dr.ok) {
            val devices = JsonParse.parseDevices(dr.body)
            devices.devices.forEach { names[it.id] = it.name }
            Logx.i(TAG, devices.note)
        }

        val merged = parsed.channels.map { c ->
            c.copy(deviceName = names[c.deviceId] ?: c.deviceName)
        }
        _devices.value = names
        _channels.value = merged
        lastNote = parsed.note
        lastError = null
        Logx.i(TAG, parsed.note + "；设备名 " + names.size + " 个")
        merged
    }

    fun putSnapshot(channelId: String, bytes: ByteArray) {
        synchronized(snapshotCache) {
            snapshotCache[channelId] = bytes
            if (snapshotCache.size > 32) {
                val first = snapshotCache.keys.firstOrNull() ?: return
                snapshotCache.remove(first)
            }
        }
    }

    fun cachedSnapshot(channelId: String): ByteArray? = synchronized(snapshotCache) { snapshotCache[channelId] }

    companion object { private const val TAG = "ChannelRepo" }
}
