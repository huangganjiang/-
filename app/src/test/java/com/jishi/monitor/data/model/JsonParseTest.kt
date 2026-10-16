package com.jishi.monitor.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用 M0 实测的真实响应做解析回归（字段名以实测为准）。 */
class JsonParseTest {

    @Test
    fun parsesChannelsFromRealShape() {
        val json = """{"items":[
          {"id":"<channel-id>","name":"01","custom_name":"","device_id":"<channel-id>","bid":"01",
           "status":true,"enabled":true,"playing":true,"is_recording":true,"protocol":"XIAOMI","talk":false},
          {"id":"<channel-id>","name":"01","custom_name":"","device_id":"<channel-id>","bid":"01",
           "status":false,"enabled":true,"playing":false,"is_recording":true,"protocol":"XIAOMI","talk":false}
        ],"total":2}"""
        val r = JsonParse.parseChannels(json)
        assertEquals(2, r.channels.size)
        assertTrue("status=true 应判定在线", r.channels[0].online)
        assertEquals("<channel-id>", r.channels[0].deviceId)
        assertEquals("01", r.channels[0].channelNo)
        assertEquals(false, r.channels[1].online)
        assertTrue(r.note.contains("total=2"))
    }

    @Test
    fun displayNameJoinsDeviceAndChannel() {
        val c = Channel(
            id = "X_01", rawName = "02", customName = "", deviceId = "X", bid = "02",
            online = true, enabled = true, playing = false, isRecording = false,
            protocol = "XIAOMI", talk = false, deviceName = "前院",
        )
        assertEquals("前院 02", c.displayName)
        assertEquals("大门口", c.copy(customName = "大门口").displayName)
    }

    @Test
    fun parsesMonthsBitmap() {
        val r = JsonParse.parseMonths("""{"202610":"1111111111111111100000000000000"}""")
        assertEquals(17, r.dates.size)
        assertEquals("20261001", r.dates.first())
        assertEquals("20261017", r.dates.last())
    }

    @Test
    fun parsesTimelineMilliseconds() {
        val r = JsonParse.parseTimeline("""{"items":[{"start":1792113566000,"duration":27000}]}""")
        assertEquals(1, r.segments.size)
        assertEquals(1792113566000L, r.segments[0].startMs)
        assertEquals(27_000L, r.segments[0].durationMs)
    }

    @Test
    fun parsesTwoLevelGetItems() {
        val l1 = """{"channel_id":"X_01","rtsp":"","stream_id":"",
          "url":"/records/hls/index.m3u8?channel_id=X_01\u0026start=1\u0026end=2\u0026source=CLOUD",
          "hls_url":"/records/devices/X/channels/01/1/2/index.m3u8","routes":null}"""
        val p = JsonParse.parsePlayback(l1)
        assertTrue(p!!.rawUrl.contains("start=1"))
        val l2 = """{"items":["/api/records/hls/X/01/20261017/20261017015639-60000.mp4"]}"""
        val items = JsonParse.parseRecordItems(l2)
        assertEquals(1, items.size)
        assertEquals("http://h/api/records/x", JsonParse.absolute("/api/records/x", "http://h"))
    }

    @Test
    fun parsesSnapshotBase64() {
        val b64 = JsonParse.parseSnapshotBase64("""{"created_at":1792172267,"img":"/9j/AAAA"}""")
        assertEquals("/9j/AAAA", b64)
        assertNull(JsonParse.parseSnapshotBase64("""{"msg":"x"}"""))
    }
}
