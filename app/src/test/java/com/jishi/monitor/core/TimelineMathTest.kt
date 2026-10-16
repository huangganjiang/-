package com.jishi.monitor.core

import com.jishi.monitor.data.model.RecordSegment
import com.jishi.monitor.player.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时间轴/定位/长按加速的纯逻辑（对应 FR-4.2~FR-4.8、AC-7~AC-10、AC-16）。 */
class TimelineMathTest {

    private fun seg(start: Long, dur: Long) = RecordSegment(start, dur)

    @Test
    fun mergesSegmentsWithGapWithinTenSeconds() {
        val merged = TimelineMath.merge(listOf(seg(0, 60_000), seg(65_000, 30_000), seg(200_000, 10_000)))
        assertEquals(2, merged.size)
        assertEquals(0L, merged[0].startMs)
        assertEquals(95_000L, merged[0].durationMs)
        assertEquals(200_000L, merged[1].startMs)
    }

    @Test
    fun keepsOneSecondSegmentsForRendering() {
        val merged = TimelineMath.merge(listOf(seg(1_000, 1_000)))
        assertEquals(1, merged.size)
        assertEquals(1_000L, merged[0].durationMs)
    }

    @Test
    fun seekPlanInsideSegmentKeepsOffset() {
        val plan = TimelineMath.planSeek(listOf(seg(10_000, 60_000)), 25_000)
        assertFalse(plan.landedInGap)
        assertEquals(15_000L, plan.offsetInSegmentMs)
    }

    @Test
    fun seekPlanInGapFallsBackToPreviousSegmentEnd() {
        val segments = listOf(seg(0, 60_000), seg(600_000, 30_000))
        val plan = TimelineMath.planSeek(segments, 300_000)
        assertTrue("落空档必须标记", plan.landedInGap)
        assertEquals(59_999L, plan.targetMs)
    }

    @Test
    fun previousAndNextSegmentBoundaries() {
        val segments = listOf(seg(0, 10_000), seg(100_000, 10_000), seg(200_000, 10_000))
        assertEquals(100_000L, TimelineMath.previousSegment(segments, 150_000)?.startMs)
        assertEquals(200_000L, TimelineMath.nextSegment(segments, 150_000)?.startMs)
        assertNull(TimelineMath.previousSegment(segments, 0))
    }

    @Test
    fun snappingUsesFiveOrOneSecond() {
        assertEquals(25_000L, TimelineMath.snap(27_400, 5_000))
        assertEquals(27_000L, TimelineMath.snap(27_400, 1_000))
    }

    @Test
    fun longPressAccelerationTwoStages() {
        // 单击：10 秒
        assertEquals(10_000L, TimelineMath.accumulateHold(0))
        assertEquals(10_000L, TimelineMath.accumulateHold(300))
        // 400ms 起：每 100ms 加 10 秒（约 1 分钟/秒）
        assertEquals(10_000L, TimelineMath.accumulateHold(500))
        assertEquals(80_000L, TimelineMath.accumulateHold(1_200))
        // 1200ms 起：每 100ms 加 50 秒（约 5 分钟/秒）
        assertTrue(TimelineMath.accumulateHold(1_400) >= 130_000L)
        assertEquals(TimelineMath.FAST_STEP_MS, TimelineMath.stepFor(1_200))
        assertEquals(TimelineMath.STEP_MS, TimelineMath.stepFor(500))
    }

    @Test
    fun reconnectPolicyBackoffAndCap() {
        assertEquals(1_000L, ReconnectPolicy.nextDelayMs(1))
        assertEquals(2_000L, ReconnectPolicy.nextDelayMs(2))
        assertEquals(4_000L, ReconnectPolicy.nextDelayMs(3))
        assertEquals(8_000L, ReconnectPolicy.nextDelayMs(4))
        assertEquals(8_000L, ReconnectPolicy.nextDelayMs(5))
        assertNull("超过 5 次必须停止重连", ReconnectPolicy.nextDelayMs(6))
        assertFalse(ReconnectPolicy.shouldRetry(6))
        assertTrue(ReconnectPolicy.isFirstFrameTimeout(0, 3_500))
    }

    @Test
    fun dayWindowClampingDoesNotCrossDay() {
        val dayStart = 1_792_195_200_000L
        assertEquals(dayStart + 86_400_000L - 1, TimelineMath.clampToDay(dayStart + 200_000_000L, dayStart))
        assertEquals(dayStart, TimelineMath.clampToDay(dayStart - 5, dayStart))
        assertEquals(dayStart, TimelineMath.windowStart(dayStart, dayStart, 3_600_000))
    }

    @Test
    fun formatsClockAndDuration() {
        assertEquals("01:02:03", TimelineMath.formatClock(3_723_000, 0))
        assertTrue(TimelineMath.formatDuration(90_000).contains("1"))
    }
}
