package com.jishi.monitor.core

import com.jishi.monitor.data.model.RecordSegment

/** 定位结果：命中段 → 从段内偏移播放；落空档 → 回到上一段末尾。 */
data class SeekPlan(
    val targetMs: Long,
    val segment: RecordSegment?,
    val offsetInSegmentMs: Long,
    val landedInGap: Boolean,
)

/**
 * 时间轴全部纯逻辑（可单元测试，不依赖 Android）。
 * 对应 FR-4.2~FR-4.8、AC-7~AC-10、AC-16。
 */
object TimelineMath {
    /** 相邻段间隔 <=10 秒视为连续，合并成一段（需求 7.1 规则 1）。 */
    const val MERGE_GAP_MS = 10_000L
    /** 拖动松手后的防抖（FR-4.4）。 */
    const val DRAG_DEBOUNCE_MS = 300L
    /** 频繁拖动防抖（需求 7.3）。 */
    const val FREQUENT_DRAG_DEBOUNCE_MS = 500L
    /** 长按进入第一档加速的阈值（FR-4.6 / AC-16）。 */
    const val LONG_PRESS_ACCEL_MS = 400L
    /** 长按进入第二档加速的阈值。 */
    const val LONG_PRESS_FAST_MS = 1200L
    /** 加速时的重复间隔。 */
    const val ACCEL_TICK_MS = 100L
    /** 第一档步长：每 100 毫秒跳 10 秒（约 1 分钟/秒）。 */
    const val STEP_MS = 10_000L
    /** 第二档步长：每 100 毫秒跳 50 秒（约 5 分钟/秒）。 */
    const val FAST_STEP_MS = 50_000L

    /** 合并相邻段（提高渲染与续播效率）。 */
    fun merge(segments: List<RecordSegment>, gapMs: Long = MERGE_GAP_MS): List<RecordSegment> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.filter { it.durationMs > 0 }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<RecordSegment>()
        var curStart = sorted[0].startMs
        var curEnd = sorted[0].endMs
        for (i in 1 until sorted.size) {
            val s = sorted[i]
            if (s.startMs - curEnd <= gapMs) {
                if (s.endMs > curEnd) curEnd = s.endMs
            } else {
                out.add(RecordSegment(curStart, curEnd - curStart))
                curStart = s.startMs
                curEnd = s.endMs
            }
        }
        out.add(RecordSegment(curStart, curEnd - curStart))
        return out
    }

    /** 命中某段（含端点）。 */
    fun segmentAt(segments: List<RecordSegment>, t: Long): RecordSegment? =
        segments.firstOrNull { t >= it.startMs && t <= it.endMs }

    /** 上一段（严格在 t 之前结束的最后一段）。 */
    fun previousSegment(segments: List<RecordSegment>, t: Long): RecordSegment? =
        segments.lastOrNull { it.endMs < t }

    /** 下一段（严格在 t 之后开始的最近一段）。 */
    fun nextSegment(segments: List<RecordSegment>, t: Long): RecordSegment? =
        segments.firstOrNull { it.startMs > t }

    /**
     * 计算 seek 目标：
     * - 命中段：段内偏移 = t - start
     * - 落空档：回到上一段末尾（无上一段则取下一段起点），并标记 landedInGap
     */
    fun planSeek(segments: List<RecordSegment>, t: Long): SeekPlan {
        if (segments.isEmpty()) return SeekPlan(t, null, 0L, landedInGap = true)
        val hit = segmentAt(segments, t)
        if (hit != null) {
            val offset = (t - hit.startMs).coerceIn(0L, (hit.durationMs - 1).coerceAtLeast(0L))
            return SeekPlan(hit.startMs + offset, hit, offset, landedInGap = false)
        }
        val prev = previousSegment(segments, t)
        if (prev != null) {
            val target = (prev.endMs - 1).coerceAtLeast(prev.startMs)
            return SeekPlan(target, prev, target - prev.startMs, landedInGap = true)
        }
        val next = nextSegment(segments, t)
        if (next != null) return SeekPlan(next.startMs, next, 0L, landedInGap = true)
        return SeekPlan(t, null, 0L, landedInGap = true)
    }

    /** 游标吸附（T1~T3 吸 5 秒，T4/T5 吸 1 秒）。 */
    fun snap(t: Long, snapMs: Long): Long = if (snapMs <= 0) t else Math.round(t.toDouble() / snapMs) * snapMs

    /**
     * 长按加速步长：
     * - heldMs < 400：普通点击，调用方按 STEP_MS 单次跳
     * - 400 <= heldMs < 1200：返回 STEP_MS
     * - heldMs >= 1200：返回 FAST_STEP_MS
     */
    fun stepFor(heldMs: Long): Long = if (heldMs >= LONG_PRESS_FAST_MS) FAST_STEP_MS else STEP_MS

    /** 长按期间的累计位移（按 100 毫秒节拍累加，分段计步）。 */
    fun accumulateHold(heldMs: Long, direction: Int = 1): Long {
        if (heldMs < LONG_PRESS_ACCEL_MS) return STEP_MS * direction
        var total = 0L
        var elapsed = LONG_PRESS_ACCEL_MS
        while (elapsed < heldMs) {
            val step = if (elapsed >= LONG_PRESS_FAST_MS) FAST_STEP_MS else STEP_MS
            total += step
            elapsed += ACCEL_TICK_MS
        }
        if (total == 0L) total = STEP_MS
        return total * direction
    }

    /** 把时刻夹在当天 00:00:00 到 23:59:59.999 之间（时间轴不自动跨天，FR-4.11）。 */
    fun clampToDay(t: Long, dayStartMs: Long): Long {
        val dayEnd = dayStartMs + 24L * 60 * 60 * 1000 - 1
        return t.coerceIn(dayStartMs, dayEnd)
    }

    /** 当天 00:00:00（本地时区）。 */
    fun dayStart(epochMs: Long, zoneOffsetMs: Long): Long =
        ((epochMs + zoneOffsetMs) / 86_400_000L) * 86_400_000L - zoneOffsetMs

    /** 时间轴可视窗口的起始位置（把 t 居中，越界则贴边）。 */
    fun windowStart(t: Long, dayStartMs: Long, visibleMs: Long): Long {
        val want = t - visibleMs / 2
        val min = dayStartMs
        val max = dayStartMs + 24L * 60 * 60 * 1000 - visibleMs
        return want.coerceIn(min, max.coerceAtLeast(min))
    }

    fun formatClock(epochMs: Long, zoneOffsetMs: Long): String {
        val local = epochMs + zoneOffsetMs
        val msOfDay = ((local % 86_400_000L) + 86_400_000L) % 86_400_000L
        val h = msOfDay / 3_600_000L
        val m = (msOfDay % 3_600_000L) / 60_000L
        val s = (msOfDay % 60_000L) / 1000L
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d 小时 %02d 分", h, m) else String.format("%d 分 %02d 秒", m, s)
    }
}
