package com.jishi.monitor.data.model

import java.util.Calendar
import java.util.TimeZone

/**
 * EasyNVR 回放播放列表解析（M0 实测契约）。
 *
 * GET /records/devices/{device}/channels/{no}/{startMs}/{endMs}/index.m3u8 返回：
 *   #EXTM3U
 *   #EXT-X-VERSION:7
 *   #EXT-X-TARGETDURATION:72
 *   #EXTINF:36.000,
 *   /api/records/hls/{device}/{no}/{yyyyMMdd}/{yyyyMMddHHmmss}-{durationMs}.ts
 *
 * 分片文件名里带**本地时间戳**与**毫秒时长**，因此可以精确还原每段的起止时刻，
 * 这是时间轴色块与「段内 seek」的数据基础（AC-7 / AC-8 / AC-10）。
 */
object HlsPlaylist {

    private val extinf = Regex("#EXTINF:([0-9.]+)")


    fun parse(text: String, zone: TimeZone = TimeZone.getDefault()): List<RecordSegment> {
        val lines = text.split('\n').map { it.trim() }
        val out = ArrayList<RecordSegment>()
        var pendingDurationMs = -1L
        for (line in lines) {
            if (line.startsWith("#EXTINF:")) {
                val m = extinf.find(line)
                pendingDurationMs = m?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1L
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!line.endsWith(".ts") && !line.contains(".ts?")) continue
            val path = line.substringBefore('?')
            val file = path.substringAfterLast('/')
            val parsed = parseMediaFileName(file, zone)
            val start = parsed?.first ?: -1L
            val dur = parsed?.second ?: pendingDurationMs
            if (start > 0 && dur > 0) {
                out.add(RecordSegment(start, dur, line))
            } else if (dur > 0 && out.isNotEmpty()) {
                // 文件名不可解析时，用前一段末尾顺延（仍可用于播放，定位精度下降）
                val prev = out.last()
                out.add(RecordSegment(prev.endMs, dur, line))
            }
            pendingDurationMs = -1L
        }
        return out.sortedBy { it.startMs }
    }

    /** 解析 20261016081954-36000.ts / .mp4 → (起始毫秒, 时长毫秒)，按本机时区。 */
    fun parseMediaFileName(file: String, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Long>? {
        val name = file.substringBefore('?')
            .removeSuffix(".ts")
            .removeSuffix(".mp4")
            .substringAfterLast('/')
        val dash = name.lastIndexOf('-')
        if (dash <= 0) return null
        val stamp = name.substring(0, dash)
        val durStr = name.substring(dash + 1)
        val dur = durStr.toLongOrNull() ?: return null
        if (stamp.length < 14) return null
        return try {
            val y = stamp.substring(0, 4).toInt()
            val mo = stamp.substring(4, 6).toInt()
            val d = stamp.substring(6, 8).toInt()
            val h = stamp.substring(8, 10).toInt()
            val mi = stamp.substring(10, 12).toInt()
            val s = stamp.substring(12, 14).toInt()
            val cal = Calendar.getInstance(zone)
            cal.clear()
            cal.set(y, mo - 1, d, h, mi, s)
            cal.timeInMillis to dur
        } catch (t: Throwable) {
            null
        }
    }

    /** 把相对分片路径补全为绝对地址。 */
    fun absolute(url: String, baseUrl: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else baseUrl.trimEnd('/') + (if (url.startsWith("/")) url else "/" + url)

    /** 构造当天回放的播放列表地址（deviceId 不含 _01；start/end 为毫秒）。 */
    fun playlistPath(deviceId: String, channelNo: String, startMs: Long, endMs: Long): String =
        "/records/devices/" + deviceId + "/channels/" + channelNo + "/" + startMs + "/" + endMs + "/index.m3u8"

    val EMPTY_PLAYLIST_MARKER = "#EXT-X-ENDLIST"
}
