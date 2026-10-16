package com.jishi.monitor.data.model

/** 一路通道。字段名以 /channels 实际返回为准（M0 已实测）。 */
data class Channel(
    val id: String,
    val rawName: String,
    val customName: String,
    val deviceId: String,
    val bid: String,
    val online: Boolean,
    val enabled: Boolean,
    val playing: Boolean,
    val isRecording: Boolean,
    val protocol: String,
    val talk: Boolean,
    val deviceName: String = "",
    val matchedKeys: Map<String, String> = emptyMap(),
) {
    /** 展示名：优先自定义名 → 设备名 + 通道序号（如「前院 01」）→ 通道 ID。 */
    val displayName: String
        get() {
            if (customName.isNotBlank()) return customName
            val base = deviceName.ifBlank { "" }
            val no = rawName.ifBlank { bid }
            return when {
                base.isNotBlank() && no.isNotBlank() -> base + " " + no
                base.isNotBlank() -> base
                no.isNotBlank() -> no
                else -> id
            }
        }

    /** 设备 ID（去掉 _01 后缀），用于构造回放 HLS 路由。 */
    val deviceKey: String get() = deviceId.ifBlank { id.substringBeforeLast('_') }

    /** 通道序号（01/02），用于构造回放 HLS 路由。 */
    val channelNo: String get() = bid.ifBlank { id.substringAfterLast('_') }.padStart(2, '0')

    val snapshotPath: String get() = "/channels/" + id + "/snapshot"
}

/** 设备（/devices），用于给通道拼出家庭化名称。 */
data class Device(
    val id: String,
    val name: String,
    val protocol: String,
    val online: Boolean,
    val channelCount: Int,
    val talk: Boolean,
)

/** 录像段（时间轴与播放的最小单位）。url 指向可直接播放的 .ts 分片。 */
data class RecordSegment(
    val startMs: Long,
    val durationMs: Long,
    val url: String = "",
) {
    val endMs: Long get() = startMs + durationMs
    val hasUrl: Boolean get() = url.isNotBlank()
}

/** /records 返回的播放入口（M0 实测：HLS 路由，不是 mp4 分段）。 */
data class RecordPlayback(
    val channelId: String,
    val streamId: String,
    val hlsUrl: String,
    val rawUrl: String,
)

data class ChannelsResult(
    val channels: List<Channel>,
    val note: String,
)

data class DevicesResult(
    val devices: List<Device>,
    val note: String,
)

data class TimelineResult(
    val segments: List<RecordSegment>,
    val note: String,
    val source: String = "",
)

data class MonthsResult(
    val dates: List<String>,
    val note: String,
)

/** 时间轴 5 档缩放（需求 7.2）。 */
enum class ZoomLevel(val label: String, val visibleMs: Long, val snapMs: Long) {
    T1("整天 24 小时（6 小时 / 1 小时刻度）", 24L * 60 * 60 * 1000, 5000),
    T2("6 小时（1 小时 / 10 分钟刻度）", 6L * 60 * 60 * 1000, 5000),
    T3("1 小时（10 分钟 / 1 分钟刻度）", 60L * 60 * 1000, 5000),
    T4("10 分钟（1 分钟 / 10 秒刻度）", 10L * 60 * 1000, 1000),
    T5("1 分钟（10 秒 / 1 秒刻度）", 60L * 1000, 1000);

    fun zoomIn(): ZoomLevel = entries.getOrElse(ordinal + 1) { this }
    fun zoomOut(): ZoomLevel = entries.getOrElse(ordinal - 1) { this }
}

// 到 8× 为止：16× 时 Sonic（有效区间 0.1~8）与 VLC 都无法保证音画同步，实际不可用
val SPEED_STEPS = listOf(0.5f, 1f, 1.5f, 2f, 4f, 8f)

fun nextSpeed(current: Float): Float {
    val idx = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return SPEED_STEPS[(idx + 1).mod(SPEED_STEPS.size)]
}

fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() + "×" else speed.toString() + "×"
