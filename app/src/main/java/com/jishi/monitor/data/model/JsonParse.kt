package com.jishi.monitor.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 容错解析器。
 * M0 实测已确认下列真实字段名（不再靠猜）：
 *  - /channels        {"items":[{id,name,custom_name,device_id,bid,status,enabled,playing,is_recording,protocol,talk,...}],"total":N}
 *                    在线状态字段是 **status**（bool），不是 online。
 *  - /devices?size=100 {"items":[{id,name,protocol,status,channel_count,ability{talk},...}],"total":N}
 *  - /records/timeline {"items":[{start,duration}]}  毫秒
 *  - /records/months   {"202610":"1111111111111111100000000000000"}  月 → 31 位位图
 *  - /channels/{id}/snapshot {"created_at":<秒>,"img":"<base64 JPEG/PNG>"}
 *  - /records          {"channel_id":..,"stream_id":..,"url":"/records/hls/index.m3u8?...","hls_url":"/records/devices/..","routes":null}
 */
object JsonParse {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject

    private fun arr(e: JsonElement?): JsonArray? = e as? JsonArray

    fun asString(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    fun asLong(e: JsonElement?): Long? = when (e) {
        is JsonPrimitive -> e.longOrNull ?: e.doubleOrNull?.toLong() ?: e.contentOrNull?.toLongOrNull()
        else -> null
    }

    fun asBool(e: JsonElement?): Boolean? = when (e) {
        is JsonPrimitive -> e.booleanOrNull ?: when (e.contentOrNull?.trim()?.lowercase()) {
            "1", "true", "online", "on", "yes" -> true
            "0", "false", "offline", "off", "no" -> false
            else -> null
        }
        else -> null
    }

    private fun items(root: JsonElement?): List<JsonObject> {
        val o = obj(root) ?: return emptyList()
        val a = arr(o["items"]) ?: arr(o["data"]) ?: arr(o["list"]) ?: return emptyList()
        return a.mapNotNull { it as? JsonObject }
    }

    // ---------------- 通道 ----------------
    fun parseChannels(text: String): ChannelsResult {
        val root = parse(text)
        val list = items(root)
        val channels = list.mapNotNull { o ->
            val id = asString(o["id"]) ?: asString(o["channel_id"]) ?: return@mapNotNull null
            Channel(
                id = id,
                rawName = asString(o["name"]).orEmpty(),
                customName = asString(o["custom_name"]).orEmpty(),
                deviceId = asString(o["device_id"]).orEmpty(),
                bid = asString(o["bid"]).orEmpty(),
                online = asBool(o["status"]) ?: false,
                enabled = asBool(o["enabled"]) ?: true,
                playing = asBool(o["playing"]) ?: false,
                isRecording = asBool(o["is_recording"]) ?: false,
                protocol = asString(o["protocol"]).orEmpty(),
                talk = asBool(o["talk"]) ?: false,
                deviceName = asString(o["device_name"]).orEmpty(),
            )
        }
        val total = asLong(obj(root)?.get("total"))
        return ChannelsResult(
            channels,
            "通道 " + channels.size + " 路（total=" + (total ?: -1) + "，在线 " + channels.count { it.online } + "）",
        )
    }

    // ---------------- 设备 ----------------
    fun parseDevices(text: String): DevicesResult {
        val root = parse(text)
        val list = items(root)
        val devices = list.mapNotNull { o ->
            val id = asString(o["id"]) ?: return@mapNotNull null
            val ability = obj(o["ability"])
            Device(
                id = id,
                name = asString(o["name"]).orEmpty(),
                protocol = asString(o["protocol"]).orEmpty(),
                online = asBool(o["status"]) ?: false,
                channelCount = (asLong(o["channel_count"]) ?: 0L).toInt(),
                talk = asBool(ability?.get("talk")) ?: false,
            )
        }
        return DevicesResult(devices, "设备 " + devices.size + " 台")
    }

    // ---------------- 时间轴 ----------------
    fun parseTimeline(text: String): TimelineResult {
        val root = parse(text)
        val list = items(root)
        val segs = list.mapNotNull { o ->
            val s = asLong(o["start"]) ?: asLong(o["start_time"]) ?: return@mapNotNull null
            val d = asLong(o["duration"]) ?: asLong(o["duration_ms"]) ?: run {
                val e = asLong(o["end"]) ?: asLong(o["end_time"]) ?: return@mapNotNull null
                e - s
            }
            // M0 实测：start 与 duration 均为毫秒（如 start=1792113566000, duration=27000）
            if (d <= 0) null else RecordSegment(normalizeMs(s), d)
        }
        return TimelineResult(segs.sortedBy { it.startMs }, "timeline 段 " + segs.size, "timeline")
    }

    private fun normalizeMs(v: Long): Long = if (v in 1..100_000_000_000L) v * 1000 else v

    // ---------------- 月份位图 ----------------
    /**
     * /records/months 返回 {"202610":"1111111111111111100000000000000"}：
     * 键为 yyyyMM，值为该月 1..31 日的位图（'1' = 当天有录像）。
     */
    fun parseMonths(text: String): MonthsResult {
        val root = obj(parse(text)) ?: return MonthsResult(emptyList(), "months 解析失败")
        val dates = ArrayList<String>()
        for ((k, v) in root) {
            val bitmap = asString(v) ?: continue
            val ym = k.filter { it.isDigit() }
            if (ym.length != 6) continue
            bitmap.forEachIndexed { idx, c ->
                if (c == '1' && idx < 31) {
                    val day = (idx + 1).toString().padStart(2, '0')
                    dates.add(ym + day)
                }
            }
        }
        return MonthsResult(dates.sorted(), "有录像日期 " + dates.size + " 天")
    }

    /** 把 20261016 或 2026-10-16 归一化为 yyyyMMdd。 */
    fun normalizeDate(raw: String): String? {
        val d = raw.filter { it.isDigit() }
        return when {
            d.length == 8 -> d
            raw.contains("-") && d.length == 8 -> d
            else -> null
        }
    }

    // ---------------- 回放入口 ----------------
    fun parsePlayback(text: String): RecordPlayback? {
        val o = obj(parse(text)) ?: return null
        val hls = asString(o["hls_url"]).orEmpty()
        val raw = asString(o["url"]).orEmpty()
        if (hls.isBlank() && raw.isBlank()) return null
        return RecordPlayback(
            channelId = asString(o["channel_id"]).orEmpty(),
            streamId = asString(o["stream_id"]).orEmpty(),
            hlsUrl = hls,
            rawUrl = raw,
        )
    }

    /** 两级 GET 的 items 列表（MP4/TS 分片路径）。 */
    fun parseRecordItems(text: String): List<String> {
        val o = obj(parse(text)) ?: return emptyList()
        val a = arr(o["items"]) ?: arr(o["data"]) ?: return emptyList()
        return a.mapNotNull { asString(it) }.filter { it.isNotBlank() }
    }

    /** 相对路径补全为绝对地址（不追加任何查询参数）。 */
    fun absolute(url: String, baseUrl: String): String {
        val u = url.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return baseUrl.trimEnd('/') + (if (u.startsWith("/")) u else "/" + u)
    }

    // ---------------- 快照 ----------------
    /** /channels/{id}/snapshot 返回 {"created_at":<秒>,"img":"<base64>"}。 */
    fun parseSnapshotBase64(text: String): String? {
        val o = obj(parse(text)) ?: return null
        val img = asString(o["img"]) ?: return null
        return img.takeIf { it.isNotBlank() }
    }
}
