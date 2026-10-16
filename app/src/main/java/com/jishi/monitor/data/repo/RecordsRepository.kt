package com.jishi.monitor.data.repo

import com.jishi.monitor.core.Logx
import com.jishi.monitor.core.TimelineMath
import com.jishi.monitor.data.model.Channel
import com.jishi.monitor.data.model.HlsPlaylist
import com.jishi.monitor.data.model.JsonParse
import com.jishi.monitor.data.model.RecordSegment
import com.jishi.monitor.data.model.TimelineResult
import com.jishi.monitor.data.net.ApiClient
import com.jishi.monitor.data.net.LoginEndpoint

/**
 * 回放数据仓库（M0 实测契约）。
 *
 * 主路径（官方两级 GET）：
 *   GET /records?...                → { url, hls_url, ... }
 *   GET <url 原串>                   → { items:[ "/api/records/hls/{dev}/{ch}/{date}/{stamp}-{durMs}.mp4" ] }
 *   items 的文件名自带「本地时间起点 + 毫秒时长」，直接还原每段起止，供时间轴与段内 seek 使用。
 *
 * 降级路径：
 *   1) hls_url 播放列表（.ts，同一批时间戳）
 *   2) /records/timeline（只有 start/duration，没有可播 URL → 回放不可用，给可读提示）
 *
 * 结果按「通道 + 日期」缓存 5 分钟（需求 7.4）。
 */
class RecordsRepository(private val api: ApiClient) {

    private data class CacheEntry(val atMs: Long, val segs: List<RecordSegment>, val note: String, val source: String)

    private val cache = LinkedHashMap<String, CacheEntry>()
    private val monthsCache = LinkedHashMap<String, Pair<Long, List<String>>>()

    @Volatile var lastNote: String = ""
        private set

    @Volatile var lastError: String? = null
        private set

    suspend fun months(channelId: String, yyyyMM: String): List<String> {
        val key = channelId + "|" + yyyyMM
        val now = System.currentTimeMillis()
        synchronized(cache) {
            monthsCache[key]?.let { if (now - it.first < CACHE_MS) return it.second }
        }
        val r = api.get(LoginEndpoint.months(channelId, yyyyMM))
        if (!r.ok) {
            lastError = "months " + r.code
            return emptyList()
        }
        val parsed = JsonParse.parseMonths(r.body)
        synchronized(cache) { monthsCache[key] = now to parsed.dates }
        Logx.i(TAG, parsed.note)
        return parsed.dates
    }

    /** 取某天全部可播分段。 */
    suspend fun daySegments(channel: Channel, dayStartMs: Long, dayEndMs: Long, forceRefresh: Boolean = false): TimelineResult {
        val key = channel.id + "|" + dayStartMs
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            synchronized(cache) {
                cache[key]?.let { if (now - it.atMs < CACHE_MS) return TimelineResult(it.segs, it.note, it.source) }
            }
        }

        // 主路径：两级 GET → mp4 分段
        val fromItems = fetchMp4Segments(channel, dayStartMs, dayEndMs)
        if (fromItems.isNotEmpty()) {
            // 重要：分片列表**不做合并**——每个 mp4 就是一个可播单位（含 URL、起止精确），
            // 合并会丢弃 URL 并使段内 seek/跨段续播失效。时间轴渲染时相邻段自然相接。
            lastNote = "mp4 分段 " + fromItems.size + " 段"
            lastError = null
            synchronized(cache) { cache[key] = CacheEntry(now, fromItems, lastNote, "mp4") }
            return TimelineResult(fromItems, lastNote, "mp4")
        }

        // 降级 1：hls_url 播放列表（.ts）
        val fromHls = fetchHlsSegments(channel, dayStartMs, dayEndMs)
        if (fromHls.isNotEmpty()) {
            lastNote = "HLS 降级：.ts 分段 " + fromHls.size + " 段"
            Logx.w(TAG, lastNote)
            synchronized(cache) { cache[key] = CacheEntry(now, fromHls, lastNote, "hls") }
            return TimelineResult(fromHls, lastNote, "hls")
        }

        // 降级 2：/records/timeline（有形状无地址）
        val tl = api.get(LoginEndpoint.timeline(channel.id, dayStartMs, dayEndMs))
        if (tl.ok) {
            val parsed = JsonParse.parseTimeline(tl.body)
            val merged = TimelineMath.merge(parsed.segments)
            if (merged.isNotEmpty()) {
                lastNote = "仅 timeline（无播放地址）"
                lastError = "回放地址不可用"
                synchronized(cache) { cache[key] = CacheEntry(now, merged, lastNote, "timeline") }
                return TimelineResult(merged, lastNote, "timeline")
            }
        }
        lastNote = "当日无录像"
        lastError = if (tl.code == 401) "鉴权失效" else null
        synchronized(cache) { cache[key] = CacheEntry(now, emptyList(), lastNote, "none") }
        return TimelineResult(emptyList(), lastNote, "none")
    }

    /**
     * 取某天的 HLS 连续流地址（回放主路径）。
     *
     * 为什么优先 HLS：逐段 mp4 每 50 秒就要重建一次播放器/音频输出，跨段会有咔哒与断音；
     * HLS 是一条连续流，画面与声音都无缝。M0 实测 hls_url = /records/devices/{dev}/channels/{ch}/{start}/{end}/index.m3u8。
     */
    suspend fun hlsPlaylist(channelId: String, dayStartMs: Long, dayEndMs: Long): String? {
        val key = "hls|" + channelId + "|" + dayStartMs
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.let { if (now - it.atMs < CACHE_MS) return it.note.ifBlank { null } }
        }
        val r = api.get(LoginEndpoint.records(channelId, dayStartMs, dayEndMs), readTimeoutSec = 25)
        if (!r.ok) {
            Logx.w(TAG, "HLS: /records 失败 code=" + r.code)
            return null
        }
        val playback = JsonParse.parsePlayback(r.body)
        if (playback == null) {
            Logx.w(TAG, "HLS: /records 无播放信息 " + r.body.take(160))
            return null
        }
        val path = playback.hlsUrl
        if (path.isBlank()) {
            Logx.w(TAG, "HLS: 响应无 hls_url（rawUrl=" + playback.rawUrl + "）")
            return null
        }
        val full = JsonParse.absolute(path, api.currentBaseUrl())
        Logx.i(TAG, "HLS 播放列表 " + full)
        synchronized(cache) { cache[key] = CacheEntry(now, emptyList(), full, "hls-url") }
        return full
    }

    private suspend fun fetchMp4Segments(channel: Channel, dayStartMs: Long, dayEndMs: Long): List<RecordSegment> {
        val l1 = api.get(LoginEndpoint.records(channel.id, dayStartMs, dayEndMs), readTimeoutSec = 30)
        if (!l1.ok) {
            Logx.w(TAG, "/records 失败 code=" + l1.code)
            return emptyList()
        }
        val playback = JsonParse.parsePlayback(l1.body)
        if (playback == null) {
            Logx.w(TAG, "/records 无 url/hls_url")
            return emptyList()
        }
        // 若一级已直接给出 items（部分版本）
        val direct = JsonParse.parseRecordItems(l1.body)
        if (direct.isNotEmpty()) return toSegments(direct, api.currentBaseUrl())

        // 二级 GET：必须用响应里的 url 原串
        val l2Url = JsonParse.absolute(playback.rawUrl.ifBlank { playback.hlsUrl }, api.currentBaseUrl())
        val l2 = api.get(l2Url, readTimeoutSec = 30)
        if (!l2.ok) {
            Logx.w(TAG, "二级 GET 失败 code=" + l2.code + " url=" + l2Url)
            return emptyList()
        }
        val items = JsonParse.parseRecordItems(l2.body)
        if (items.isEmpty()) {
            Logx.w(TAG, "二级 GET 未返回 items：" + l2.body.take(120))
            return emptyList()
        }
        Logx.i(TAG, "二级 GET items=" + items.size)
        return toSegments(items, api.currentBaseUrl())
    }

    private fun toSegments(items: List<String>, baseUrl: String): List<RecordSegment> {
        val out = ArrayList<RecordSegment>()
        for (raw in items) {
            val name = raw.substringBefore('?').substringAfterLast('/')
            val parsed = HlsPlaylist.parseMediaFileName(name) ?: continue
            out.add(RecordSegment(parsed.first, parsed.second, JsonParse.absolute(raw, baseUrl)))
        }
        return out.sortedBy { it.startMs }
    }

    private suspend fun fetchHlsSegments(channel: Channel, dayStartMs: Long, dayEndMs: Long): List<RecordSegment> {
        val l1 = api.get(LoginEndpoint.records(channel.id, dayStartMs, dayEndMs), readTimeoutSec = 30)
        if (!l1.ok) return emptyList()
        val playback = JsonParse.parsePlayback(l1.body) ?: return emptyList()
        val url = JsonParse.absolute(playback.hlsUrl.ifBlank { playback.rawUrl }, api.currentBaseUrl())
        val pl = api.get(url, readTimeoutSec = 30)
        if (!pl.ok || !pl.body.contains("#EXTM3U")) return emptyList()
        return HlsPlaylist.parse(pl.body).map { it.copy(url = JsonParse.absolute(it.url, api.currentBaseUrl())) }
    }

    /**
     * 首页用：最近一次录像的结束时刻（毫秒）。
     * 一次 48 小时窗口的 timeline 查询即可覆盖「今天/昨天」，取最后一段的结束时刻。
     */
    suspend fun latestRecordingEndMs(channelId: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val key = "latest|" + channelId
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.let { if (now - it.atMs < LATEST_CACHE_MS) return it.segs.firstOrNull()?.startMs }
        }
        val r = api.get(LoginEndpoint.timeline(channelId, nowMs - 48L * 60 * 60 * 1000, nowMs), readTimeoutSec = 20)
        if (!r.ok) return null
        val latest = JsonParse.parseTimeline(r.body).segments.maxByOrNull { it.endMs }?.endMs
        synchronized(cache) {
            cache[key] = CacheEntry(now, latest?.let { listOf(RecordSegment(it, 0)) } ?: emptyList(), "", "latest")
        }
        return latest
    }

    companion object {
        private const val TAG = "RecordsRepo"
        const val CACHE_MS = 5 * 60 * 1000L
        /** 最新录像时间缓存：2 分钟（首页展示足够）。 */
        const val LATEST_CACHE_MS = 2 * 60 * 1000L
    }
}
