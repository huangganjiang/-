package com.jishi.monitor.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 统一日志出口。
 * 硬要求（FR-10.10 / AC-25）：日志、崩溃上报、状态信息中不得出现口令、Token、账号明文。
 * 所有输出先经过 [redact] 脱敏。
 */
object Logx {
    private const val MAX_LINES = 300
    private val ring = ArrayDeque<String>(MAX_LINES)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    private val tokenPattern = Regex("(?i)(bearer\\s+)[A-Za-z0-9\\-._~+/=]+")
    private val pwdPattern = Regex("(?i)(\"?(password|passwd|pwd|viewPassword|token|secret|authorization)\"?\\s*[:=]\\s*\"?)([^\"\\s,}&]+)")
    private val serverUserPattern = Regex("(?i)(\"?username\"?\\s*[:=]\\s*\"?)([^\"\\s,}&]+)")
    /** IPv4 / host:port：地址本身属于可被利用的定位信息，统一打码。 */
    private val ipPattern = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b")
    private val urlHostPattern = Regex("(?i)(https?://)([^/\\s\"]+)")

    fun redact(input: String): String {
        var s = input
        s = tokenPattern.replace(s) { m -> m.groupValues[1] + "***" }
        s = pwdPattern.replace(s) { m -> m.groupValues[1] + "***" }
        s = serverUserPattern.replace(s) { m -> m.groupValues[1] + "***" }
        s = urlHostPattern.replace(s) { m -> m.groupValues[1] + "***" }
        s = ipPattern.replace(s) { "***" }
        return s
    }

    private fun record(level: String, tag: String, msg: String) {
        val line = fmt.format(Date()) + " [" + level + "] " + tag + ": " + redact(msg)
        synchronized(ring) {
            if (ring.size >= MAX_LINES) ring.removeFirst()
            ring.addLast(line)
        }
        when (level) {
            "E" -> Log.e(tag, redact(msg))
            "W" -> Log.w(tag, redact(msg))
            "I" -> Log.i(tag, redact(msg))
            else -> Log.d(tag, redact(msg))
        }
    }

    fun d(tag: String, msg: String) = record("D", tag, msg)
    fun i(tag: String, msg: String) = record("I", tag, msg)
    fun w(tag: String, msg: String) = record("W", tag, msg)
    fun e(tag: String, msg: String) = record("E", tag, msg)
    /**
     * 异常只记录类型，不记录 message——底层异常常携带完整 URL、路径甚至凭据（审计 M-5）。
     */
    fun e(tag: String, msg: String, t: Throwable) = record("E", tag, msg + " :: " + t.javaClass.simpleName)

    /** 供隐藏设置「状态信息」展示（已脱敏）。 */
    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }
}
