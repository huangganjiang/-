package com.jishi.monitor.player

/**
 * 直播断流重连策略（FR-3.5 / FR-8.1）——纯逻辑，可单元测试。
 * 指数退避 1/2/4/8 秒，最多 5 次；首帧 3 秒未出画即自动重试（不等 20 秒超时）。
 */
object ReconnectPolicy {
    val BACKOFF_MS = longArrayOf(1000L, 2000L, 4000L, 8000L)
    const val MAX_ATTEMPTS = 5
    const val FIRST_FRAME_TIMEOUT_MS = 3000L

    /** attempt 从 1 开始；返回下一次重试前的等待毫秒；超过上限返回 null（不再无限重连）。 */
    fun nextDelayMs(attempt: Int): Long? {
        if (attempt <= 0) return BACKOFF_MS[0]
        if (attempt > MAX_ATTEMPTS) return null
        return BACKOFF_MS[(attempt - 1).coerceAtMost(BACKOFF_MS.size - 1)]
    }

    fun shouldRetry(attempt: Int): Boolean = attempt <= MAX_ATTEMPTS

    /** 首帧超时判定。 */
    fun isFirstFrameTimeout(openedAtMs: Long, nowMs: Long): Boolean =
        nowMs - openedAtMs >= FIRST_FRAME_TIMEOUT_MS
}
