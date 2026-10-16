package com.jishi.monitor.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.TimeZone
import org.junit.Test

/** 回放分片文件名解析（决定 AC-7/AC-8 的时间轴与段内定位精度）。 */
class HlsPlaylistTest {

    @Test
    fun parsesMp4SegmentNameInLocalTime() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val parsed = HlsPlaylist.parseMediaFileName("20261017015639-60000.mp4", tz)
        assertNotNull(parsed)
        assertEquals(60_000L, parsed!!.second)
        val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = parsed.first }
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
        assertEquals(9, cal.get(java.util.Calendar.MONTH))
        assertEquals(17, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(1, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(56, cal.get(java.util.Calendar.MINUTE))
        assertEquals(39, cal.get(java.util.Calendar.SECOND))
    }

    @Test
    fun parsesTsPlaylistWithExtinf() {
        val playlist = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:72\n" +
            "#EXTINF:36.000,\n/api/records/hls/X/01/20261016/20261016081954-36000.ts\n" +
            "#EXTINF:40.000,\n/api/records/hls/X/01/20261016/20261016082202-40000.ts\n"
        val segs = HlsPlaylist.parse(playlist, TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals(2, segs.size)
        assertEquals(36_000L, segs[0].durationMs)
        assertEquals(40_000L, segs[1].durationMs)
        // 真实播放列表的时间戳来自文件名，段间可能有空档（这里 08:19:54+36s → 08:22:02）
        assertTrue("第二段应晚于第一段", segs[1].startMs > segs[0].startMs)
    }

    @Test
    fun buildsPlaylistPath() {
        assertEquals(
            "/records/devices/X/channels/01/100/200/index.m3u8",
            HlsPlaylist.playlistPath("X", "01", 100, 200),
        )
    }
}
