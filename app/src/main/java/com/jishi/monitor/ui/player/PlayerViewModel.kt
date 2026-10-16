package com.jishi.monitor.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jishi.monitor.AppContainer
import com.jishi.monitor.core.Logx
import com.jishi.monitor.core.TimelineMath
import com.jishi.monitor.data.model.Channel
import com.jishi.monitor.data.model.RecordSegment
import com.jishi.monitor.data.model.ZoomLevel
import com.jishi.monitor.data.model.formatSpeed
import com.jishi.monitor.data.model.nextSpeed
import com.jishi.monitor.data.net.LoginEndpoint
import com.jishi.monitor.player.LiveFlvHevcPlayer
import com.jishi.monitor.player.ReconnectPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

enum class PlayerMode { LIVE, REPLAY }

/** 直播连接阶段：只有 FAILED 才向用户展示错误文案，其余一律显示加载动画。 */
enum class LivePhase { IDLE, CONNECTING, PLAYING, FAILED }

data class PlayerUiState(
    val channel: Channel? = null,
    val mode: PlayerMode = PlayerMode.LIVE,
    val dayStartMs: Long = 0L,
    val segments: List<RecordSegment> = emptyList(),
    val zoom: ZoomLevel = ZoomLevel.T1,
    val cursorMs: Long = 0L,
    val currentSegmentIndex: Int = -1,
    val playbackOffsetMs: Long = 0L,
    val speed: Float = 1f,
    val muted: Boolean = false,
    val autoTour: Boolean = false,
    val timelineLoading: Boolean = false,
    val playbackAbsMs: Long = 0L,
    val replayError: String? = null,
    val liveError: String? = null,
    val liveInfo: String = "",
    val playing: Boolean = false,
    /** 直播是否被用户暂停（用于按钮图标：暂停中显示"播放"三角）。 */
    val paused: Boolean = false,
    val toast: String? = null,
    val segSource: String = "",
    /** 当天 HLS 连续流地址（有则回放走它：画面与声音都无缝，避免跨段断音）。 */
    val hlsUrl: String? = null,
    val liveRetryAttempt: Int = 0,
    val livePhase: LivePhase = LivePhase.IDLE,
)

/**
 * 单画面：直播与回放同页（FR-3 / FR-4）。
 * 回放定位：M0 实测分片支持 Range 与 faststart，因此定位 = 换到目标分片 + 段内 seekTo(偏移)。
 */
class PlayerViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private var ticker: Job? = null
    private var openedAtMs = 0L
    private var retryAttempt = 0
    private var retryScheduled = false
    private var advanceLock = false

    val player get() = container.player

    /** 直播播放器：直接操作 MediaCodec 渲染 Surface（TD-001）。回放仍走 Media3（已验证可用）。 */
    val livePlayer: com.jishi.monitor.player.LiveKernel =
        container.newLiveKernel(object : com.jishi.monitor.player.LiveKernel.Listener {
        override fun onFirstFrame(width: Int, height: Int) {
            _state.value = _state.value.copy(
                liveInfo = width.toString() + "x" + height,
                liveError = null,
                livePhase = LivePhase.PLAYING,
                playing = true,
            )
            openedAtMs = System.currentTimeMillis()
            retryAttempt = 0
            Logx.i(TAG, "直播首帧 " + width + "x" + height)
        }

        override fun onError(message: String) {
            val unsupported = message.contains("size", true) || message.contains("codec", true) || message.contains("format", true)
            if (unsupported) {
                _state.value = _state.value.copy(
                    liveError = "当前画面格式设备不支持，请在服务端调整后重试",
                    livePhase = LivePhase.FAILED,
                )
            } else if (_state.value.livePhase != LivePhase.PLAYING) {
                _state.value = _state.value.copy(livePhase = LivePhase.CONNECTING)
            }
        }

        override fun onEnded() {
            if (_state.value.mode == PlayerMode.LIVE) retryLive()
        }
    })

    private var liveRenderView: android.view.View? = null

    /** 已经用哪个渲染面启动过：同一实例再次回调直接忽略，避免重组触发重连。 */
    private var startedRenderView: android.view.View? = null

    /**
     * 渲染面就绪/销毁（VLC 用 VLCVideoLayout，自研内核用 TextureView）。
     *
     * 注意必须按 View 实例幂等：Compose 的 AndroidView.update 会在每次重组时回调，
     * 而控制条 3.5 秒自动隐藏就会触发重组——没有这道守卫会变成"直播约 3.5 秒被强制重连"。
     */
    fun attachLiveView(view: android.view.View?) {
        liveRenderView = view
        if (view == null) {
            startedRenderView = null
            return
        }
        if (startedRenderView === view) return
        startedRenderView = view
        if (_state.value.mode == PlayerMode.LIVE && _state.value.channel != null) {
            startLiveInternal()
        }
    }

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                speed = container.prefs.speed(),
                autoTour = container.prefs.autoTour(),
            )
        }
        startTicker()
    }

    fun open(channel: Channel) {
        val today = dayStartOf(System.currentTimeMillis())
        _state.value = _state.value.copy(
            channel = channel,
            mode = PlayerMode.LIVE,
            dayStartMs = today,
            cursorMs = System.currentTimeMillis(),
            segments = emptyList(),
            currentSegmentIndex = -1,
            liveError = null,
            replayError = null,
        )
        viewModelScope.launch { container.prefs.setLastChannelId(channel.id) }
        startLive()
    }

    // ---------------- 直播 ----------------

    fun startLive() {
        // 从回放切回直播：先停掉 Media3，否则回放音频会与直播音频叠加（产品核查 D2）
        try { player.pause() } catch (t: Throwable) { }
        vlcAudio?.stop()
        _state.value = _state.value.copy(mode = PlayerMode.LIVE, liveError = null)
        startLiveInternal()
    }

    // 回放音频交给 VLC：无硬解设备上 Media3 出声会被视频解码饿出电流/断续
    private val vlcAudio: com.jishi.monitor.player.VlcAudioPlayer? by lazy { container.newVlcAudio() }

    private var liveStartedAtMs = 0L

    private fun startLiveInternal() {
        val ch = _state.value.channel ?: return
        val view = liveRenderView
        if (view == null) {
            Logx.i(TAG, "等待渲染面就绪")
            _state.value = _state.value.copy(livePhase = LivePhase.CONNECTING)
            return
        }
        val now = System.currentTimeMillis()
        if (now - liveStartedAtMs < 1000) return
        liveStartedAtMs = now
        val url = container.api.currentBaseUrl() + LoginEndpoint.live(ch.id)
        // 同源断言：只有与当前基址同源时才携带 Token（跨主机一律匿名），
        // 与 ApiClient 的同源剥离策略保持一致（审计终版 P1）
        val sameOrigin = container.api.isSameOrigin(url)
        if (!sameOrigin && !container.api.currentToken().isNullOrEmpty()) {
            Logx.w(TAG, "直播地址非同源，按匿名拉流")
        }
        Logx.i(TAG, "打开直播(直连内核) " + ch.id)
        openedAtMs = now
        retryScheduled = false
        _state.value = _state.value.copy(livePhase = LivePhase.CONNECTING, liveError = null, paused = false)
        livePlayer.setMuted(_state.value.muted)
        livePlayer.start(url, if (sameOrigin) container.api.currentToken() else null, view)
    }

    private fun retryLive() {
        if (retryScheduled) return
        retryScheduled = true
        val attempt = retryAttempt + 1
        val delayMs = ReconnectPolicy.nextDelayMs(attempt)
        if (delayMs == null) {
            _state.value = _state.value.copy(
                liveError = "暂时连不上，正在重试…",
                liveRetryAttempt = attempt,
            )
            Logx.w(TAG, "重连次数已达上限，停止重连")
            return
        }
        retryAttempt = attempt
        _state.value = _state.value.copy(liveRetryAttempt = attempt)
        viewModelScope.launch {
            delay(delayMs)
            retryScheduled = false
            if (_state.value.mode == PlayerMode.LIVE) startLiveInternal()
        }
    }

    // ---------------- 回放 ----------------

    fun setMode(mode: PlayerMode) {
        if (mode == _state.value.mode) return
        if (mode == PlayerMode.LIVE) {
            startLive()
        } else {
            livePlayer.stop()
            player.pause()
            _state.value = _state.value.copy(mode = PlayerMode.REPLAY)
            loadDay(_state.value.dayStartMs, force = false)
        }
    }

    fun shiftDay(deltaDays: Int) {
        val newStart = _state.value.dayStartMs + deltaDays.toLong() * 24 * 60 * 60 * 1000
        loadDay(newStart, force = true)
    }

    fun selectDay(dayStartMs: Long) = loadDay(dayStartMs, force = true)

    fun loadDay(dayStartMs: Long, force: Boolean) {
        val ch = _state.value.channel ?: return
        _state.value = _state.value.copy(dayStartMs = dayStartMs, timelineLoading = true, replayError = null)
        viewModelScope.launch {
            // 先取 HLS 连续流地址（回放主路径），失败再退回逐段 mp4
            val hls = runCatching {
                container.records.hlsPlaylist(ch.id, dayStartMs, dayStartMs + DAY_MS - 1)
            }.getOrNull()
            val r = container.records.daySegments(ch, dayStartMs, dayStartMs + DAY_MS - 1, force)
            val segs = r.segments
            val now = System.currentTimeMillis()
            val initial = if (dayStartMs <= now && now < dayStartMs + DAY_MS) now else dayStartMs
            _state.value = _state.value.copy(
                segments = segs,
                timelineLoading = false,
                segSource = r.source,
                hlsUrl = hls,
                replayError = when {
                    segs.isEmpty() -> "当日无录像"
                    segs.none { it.hasUrl } -> "回放暂不可用"
                    else -> null
                },
                cursorMs = TimelineMath.clampToDay(initial, dayStartMs),
            )
            // 自动对焦到有录像的区间（整天视图下窄段几乎不可见，甲方实测问题）
            autoFocusRecords(segs, dayStartMs)
            if (segs.isNotEmpty()) {
                seekTo(_state.value.cursorMs, forceOpen = true)
            }
        }
    }

    /** 用户是否正在拖动时间轴：拖动期间游标由手指控制，不被播放头覆盖。 */
    @Volatile
    private var dragging = false

    fun setDragging(value: Boolean) {
        dragging = value
    }

    /** 拖动预览（不立即 seek）。 */
    fun previewCursor(t: Long) {
        _state.value = _state.value.copy(cursorMs = TimelineMath.clampToDay(t, _state.value.dayStartMs))
    }

    /** 供 UI 判断是否已自动对焦过（换天/首次加载时复位）。 */
    private var autoFocusedDay = Long.MIN_VALUE

    /**
     * 自动对焦到"有录像的区间"：
     * 整天视图下 50 秒的录像几乎不可见，因此按录像跨度自动选一档缩放，并把游标放到最近一段。
     * 用户手动缩放后不再自动干预，直到切换日期。
     */
    fun autoFocusRecords(segments: List<com.jishi.monitor.data.model.RecordSegment>, dayStartMs: Long) {
        if (segments.isEmpty()) return
        if (autoFocusedDay == dayStartMs) return
        autoFocusedDay = dayStartMs
        val first = segments.minOf { it.startMs }
        val last = segments.maxOf { it.endMs }
        val span = (last - first).coerceAtLeast(1L)
        // 选一档"刚好能装下录像跨度"的缩放（T5 最细 → T1 整天）
        // 取"刚好装下录像跨度"的最细一档（lastOrNull：T5→T1 方向找最大的可行细化）
        val level = ZoomLevel.entries.lastOrNull { it.visibleMs >= span } ?: ZoomLevel.T1
        val cursor = segments.maxByOrNull { it.endMs }?.startMs ?: first
        Logx.i(TAG, "时间轴自动对焦：跨度 " + (span / 1000) + " 秒 → " + level.name)
        _state.value = _state.value.copy(zoom = level, cursorMs = cursor)
    }

    fun setZoom(level: ZoomLevel) {
        _state.value = _state.value.copy(zoom = level)
    }

    /** 松手后 300 毫秒防抖再 seek（FR-4.4）。 */
    fun commitCursor() {
        viewModelScope.launch {
            delay(TimelineMath.DRAG_DEBOUNCE_MS)
            seekTo(_state.value.cursorMs, forceOpen = false)
        }
    }

    /** 定位到指定时刻：命中段 → 段内偏移播放；落空档 → 回到上一段末尾并提示。 */
    fun seekTo(targetMs: Long, forceOpen: Boolean) {
        val s = _state.value
        var plan = TimelineMath.planSeek(s.segments, targetMs)
        // 落在两段之间的空隙时：不再只提示"无录像"，而是自动跳到最近的一段继续播
        if (plan.landedInGap && s.segments.isNotEmpty()) {
            val nearest = s.segments.minByOrNull {
                minOf(kotlin.math.abs(it.startMs - targetMs), kotlin.math.abs(it.endMs - targetMs))
            }
            if (nearest != null) {
                val inSeg = targetMs.coerceIn(nearest.startMs, nearest.endMs)
                plan = TimelineMath.planSeek(s.segments, inSeg)
                _state.value = _state.value.copy(toast = "已跳到最近的录像")
            }
        }
        if (plan.landedInGap) {
            _state.value = _state.value.copy(toast = "该时段无录像", cursorMs = plan.targetMs)
        } else {
            _state.value = _state.value.copy(cursorMs = plan.targetMs)
        }
        val seg = plan.segment
        if (seg == null) {
            _state.value = _state.value.copy(replayError = "回放暂不可用")
            return
        }
        if (!seg.hasUrl) {
            _state.value = _state.value.copy(replayError = "回放暂不可用")
            return
        }
        val index = s.segments.indexOfFirst { it.startMs == seg.startMs && it.durationMs == seg.durationMs }
        val sameSegment = index == s.currentSegmentIndex && !forceOpen
        val hls = s.hlsUrl
        if (hls != null) {
            // HLS 主路径：绝对时刻 → 连续流内的相对偏移
            // （HLS 第 1 个分片就是当天第一段录像的开头，实测分片名与段起始时间一致）
            val base = s.segments.firstOrNull()?.startMs ?: s.dayStartMs
            val relative = (plan.targetMs - base).coerceAtLeast(0L)
            val samePos = index == s.currentSegmentIndex && !forceOpen &&
                kotlin.math.abs(relative - s.playbackOffsetMs) < 1500
            if (samePos) {
                player.seekToMs(relative)
                vlcAudio?.seekTo(relative)
            } else {
                Logx.i(TAG, "HLS 定位 relative=" + relative + "ms（绝对 " + plan.targetMs + "）")
                player.openHls(hls, relative)
                player.setSpeed(s.speed)
                player.setMuted(true)                 // 画面静音，声音由 VLC 出
                vlcAudio?.play(hls, relative)         // 音频也走同一条 HLS 连续流 → 跨段无重建
                vlcAudio?.setMuted(s.muted)
            }
            _state.value = _state.value.copy(
                currentSegmentIndex = index.coerceAtLeast(0),
                playbackOffsetMs = relative,
                replayError = null,
            )
            return
        }
        if (sameSegment) {
            player.seekToMs(plan.offsetInSegmentMs)
            // 音频跟随定位（VLC 与 Media3 各自维护时钟，这里做一次同步）
            vlcAudio?.seekTo(plan.targetMs - seg.startMs)
            _state.value = _state.value.copy(playbackOffsetMs = plan.offsetInSegmentMs)
        } else {
            Logx.i(TAG, "打开分片 #" + index + " offset=" + plan.offsetInSegmentMs + "ms")
            // 用"从当前段开始的一串分片"播放：两端都会自动衔接下一段，
            // 避免每 50 秒重建一次音频输出造成的周期性咔哒/电流
            val urls = s.segments.drop(index.coerceAtLeast(0))
                .filter { it.hasUrl }
                .take(SEGMENT_CHAIN)
                .map { it.url }
            // 视频用列表（Media3 自动无缝衔接下一段）；音频先按当前段播放，
            // 段末由 ticker 触发下一段（VLC 4.0 的 MediaList API 与 3.x 不同，暂不链式）
            player.openSegmentList(urls, plan.offsetInSegmentMs)
            vlcAudio?.play(seg.url, plan.offsetInSegmentMs)
            player.setSpeed(s.speed)
            // 画面静音（由 VLC 出声）
            player.setMuted(true)
            _state.value = _state.value.copy(
                currentSegmentIndex = index.coerceAtLeast(0),
                playbackOffsetMs = plan.offsetInSegmentMs,
                replayError = null,
            )
        }
    }

    fun step(deltaMs: Long) {
        val s = _state.value
        val target = TimelineMath.clampToDay(s.cursorMs + deltaMs, s.dayStartMs)
        _state.value = _state.value.copy(cursorMs = target)
        seekTo(target, forceOpen = false)
    }

    /** 长按加速：只改预览时刻，松手才 seek（FR-4.6 / AC-16）。 */
    fun holdPreview(direction: Int, heldMs: Long) {
        val s = _state.value
        val delta = TimelineMath.accumulateHold(heldMs, direction)
        val target = TimelineMath.clampToDay(s.cursorMs + delta, s.dayStartMs)
        _state.value = _state.value.copy(cursorMs = target)
    }

    fun releaseHold() = seekTo(_state.value.cursorMs, forceOpen = false)

    fun prevSegment() {
        val s = _state.value
        val prev = TimelineMath.previousSegment(s.segments, s.cursorMs)
        if (prev == null) {
            _state.value = _state.value.copy(toast = "已经是第一段")
            return
        }
        _state.value = _state.value.copy(cursorMs = prev.startMs)
        seekTo(prev.startMs, forceOpen = true)
    }

    fun nextSegment() {
        val s = _state.value
        val next = TimelineMath.nextSegment(s.segments, s.cursorMs)
        if (next == null) {
            _state.value = _state.value.copy(toast = "已经是最后一段")
            return
        }
        _state.value = _state.value.copy(cursorMs = next.startMs)
        seekTo(next.startMs, forceOpen = true)
    }

    // ---------------- 播放控制 ----------------

    fun togglePlay() {
        if (_state.value.mode == PlayerMode.LIVE) {
            livePlayer.togglePause()
            val nowPaused = livePlayer.isPaused()
            Logx.i(TAG, "直播" + (if (nowPaused) "暂停" else "继续播放"))
            _state.value = _state.value.copy(paused = nowPaused, playing = !nowPaused)
        } else {
            // 回放：画面走 Media3、声音走 VLC，二者必须一起暂停/恢复
            if (player.isPlaying()) {
                player.pause()
                vlcAudio?.pause()
            } else {
                player.play()
                vlcAudio?.resume()
            }
        }
    }

    fun toggleSpeed() {
        val next = nextSpeed(_state.value.speed)
        _state.value = _state.value.copy(speed = next)
        player.setSpeed(next)
        vlcAudio?.setRate(next)   // 声音同速，避免音画分离
        viewModelScope.launch { container.prefs.setSpeed(next) }
        Logx.i(TAG, "倍速切换为 " + formatSpeed(next))
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        player.setSpeed(speed)
        vlcAudio?.setRate(speed)
        viewModelScope.launch { container.prefs.setSpeed(speed) }
    }

    fun toggleMute() {
        val m = !_state.value.muted
        _state.value = _state.value.copy(muted = m)
        livePlayer.setMuted(m)   // 直播：混合内核里会转发给 VLC 音频
        // 回放：声音只在 VLC，Media3 侧必须**恒定静音**。
        // 曾经这里写成 player.setMuted(m)，取消静音会让 Media3 与 VLC 同时出声 → 回声/梳状失真
        // （被听成"电流声"）。所以回放永远把 Media3 静音，只调 VLC 音量。
        if (_state.value.mode == PlayerMode.REPLAY) {
            player.setMuted(true)
        } else {
            player.setMuted(m)
        }
        vlcAudio?.setMuted(m)
    }

    /** 更多面板里的"检查更新"：走 UpdateChecker（包名 + 版本号 + 签名指纹三重校验）。 */
    fun checkUpdate() {
        viewModelScope.launch {
            _state.value = _state.value.copy(toast = "正在检查更新…")
            val url = container.config.current.updateUrl
            val r = com.jishi.monitor.core.UpdateChecker.downloadAndVerify(
                container.appContext,
                container.api,
                url,
            )
            _state.value = _state.value.copy(
                toast = when (r) {
                    is com.jishi.monitor.core.UpdateChecker.Result.Verified -> "已下载并验签通过，准备安装"
                    is com.jishi.monitor.core.UpdateChecker.Result.Rejected -> "更新被拒绝：" + r.reason
                    com.jishi.monitor.core.UpdateChecker.Result.NoUpdateUrl -> "当前未配置更新地址"
                    com.jishi.monitor.core.UpdateChecker.Result.DownloadFailed -> "更新包下载失败"
                },
            )
        }
    }

    fun setAutoTour(on: Boolean) {
        _state.value = _state.value.copy(autoTour = on)
        viewModelScope.launch { container.prefs.setAutoTour(on) }
    }

    /** 切后台：直播立即停止拉流（NFR-8 后台不耗流量/电量）。 */
    fun onBackground() {
        if (_state.value.mode == PlayerMode.LIVE) {
            startedRenderView = null   // 允许回前台后重新挂载启动
            livePlayer.stop()
            _state.value = _state.value.copy(livePhase = LivePhase.CONNECTING, playing = false)
        } else {
            player.pause()
            // 回放的"声音"由 VLC 负责，必须一起停，否则切后台画面停了声音还在放（NFR-8）
            vlcAudio?.pause()
        }
    }

    fun onForeground() {
        if (_state.value.mode == PlayerMode.LIVE && _state.value.channel != null) {
            startLive()
        } else if (_state.value.mode == PlayerMode.REPLAY) {
            // 画面与声音都要恢复：只 resume 音频会让画面永久停在暂停帧（技术核查 P0-1）
            runCatching { player.play() }
            vlcAudio?.resume()
        }
    }

    /** 用户手动重试：重置重试预算并立即重连（配合加载动画，不先报错）。 */
    fun resetRetryBudget() {
        retryAttempt = 0
        retryScheduled = false
        liveStartedAtMs = 0L
        _state.value = _state.value.copy(livePhase = LivePhase.CONNECTING, liveError = null)
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null)
    }

    /** 日期弹层：加载某月「有录像的日期」位图（FR-4.1 绿点）。 */
    val recordDays = androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())

    fun loadRecordDays(monthAnchorMs: Long) {
        val ch = _state.value.channel ?: return
        val cal = Calendar.getInstance()
        cal.timeInMillis = monthAnchorMs
        val yyyyMM = String.format("%04d%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        viewModelScope.launch {
            val days = container.records.months(ch.id, yyyyMM)
            recordDays.value = days.toSet()
            Logx.i(TAG, "有录像日期 " + days.size + " 天（" + yyyyMM + "）")
        }
    }

    fun absolutePositionMs(): Long {
        val s = _state.value
        // HLS 是"当天第一条录像起的连续流"，播放位置是相对它的偏移，
        // 不能用当前分片的 startMs 去加（技术核查 P1：错值还会被写进 cursorMs）
        val hls = s.hlsUrl
        if (hls != null) {
            val base = s.segments.firstOrNull()?.startMs ?: s.dayStartMs
            return base + player.positionMs()
        }
        val seg = s.segments.getOrNull(s.currentSegmentIndex) ?: return s.cursorMs
        return seg.startMs + player.positionMs()
    }

    // ---------------- 轮询：重连 / 首帧超时 / 跨段续播 ----------------

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(500)
                val s = _state.value
                val ps = player.state.value
                val ch = s.channel ?: continue

                if (s.mode == PlayerMode.LIVE) {
                    // 直播内核为 LiveFlvHevcPlayer：以「是否已出帧」判定
                    if (livePlayer.isPlaying()) {
                        if (s.liveError != null || s.livePhase != LivePhase.PLAYING) {
                            _state.value = s.copy(liveError = null, livePhase = LivePhase.PLAYING, playing = true)
                        }
                    } else if (!retryScheduled && System.currentTimeMillis() - openedAtMs > FIRST_FRAME_WINDOW_MS) {
                        if (retryAttempt < FIRST_FRAME_MAX_RETRY) {
                            Logx.w(TAG, "直播首帧超时，重试 #" + (retryAttempt + 1))
                            if (s.livePhase != LivePhase.CONNECTING) {
                                _state.value = s.copy(livePhase = LivePhase.CONNECTING)
                            }
                            retryLive()
                        } else if (s.livePhase != LivePhase.FAILED) {
                            _state.value = s.copy(liveError = "暂时连不上，正在重试…", livePhase = LivePhase.FAILED)
                        }
                    }
                    continue
                }
                if (s.mode == PlayerMode.LIVE) {
                    if (ps.errorCode != null) {
                        val msg = if (ps.errorCode == "UNSUPPORTED_FORMAT") {
                            // FR-3.9：可读提示，不暴露 SDK 术语
                            "当前画面格式设备不支持，请在服务端调整后重试"
                        } else {
                            "暂时连不上，正在重试…"
                        }
                        if (s.liveError != msg) _state.value = s.copy(liveError = msg)
                        // 编码/封装不支持时重连无意义：按 FR-3.9 给出提示并停止，不做无限重连
                        if (ps.errorCode != "UNSUPPORTED_FORMAT" && ReconnectPolicy.shouldRetry(retryAttempt)) {
                            retryLive()
                        }
                    } else if (!ps.ready && !retryScheduled && !ps.buffering &&
                        System.currentTimeMillis() - openedAtMs > FIRST_FRAME_WINDOW_MS
                    ) {
                        // FR-8.1：首帧超时即重试，但只在「既未就绪、也不在缓冲」时判定，
                        // 避免解码器初始化/网络抖动期间被反复打断（否则永远出不了画）。
                        if (retryAttempt < FIRST_FRAME_MAX_RETRY) {
                            Logx.w(TAG, "首帧超时未出画，自动重试 #" + (retryAttempt + 1))
                            retryLive()
                        } else {
                            if (s.liveError == null) _state.value = s.copy(liveError = "暂时连不上，正在重试…")
                        }
                    } else if (ps.ready && ps.videoWidth > 0) {
                        val info = ps.videoWidth.toString() + "x" + ps.videoHeight
                        if (s.liveInfo != info) _state.value = s.copy(liveInfo = info, liveError = null)
                    }
                    if (ps.playing) openedAtMs = System.currentTimeMillis()
                } else {
                    // 回放：显示当前播放时刻（FR-4.9），并让时间轴游标跟随播放头
                    val abs = absolutePositionMs()
                    if (abs > 0 && abs != s.playbackAbsMs) {
                        // 游标跟随播放头：回放播放中时间轴游标要一直在走，
                        // 只有用户正在拖动时才让位给拖动预览（否则"定位/播放"两个时间永远分叉）
                        _state.value = _state.value.copy(
                            playbackAbsMs = abs,
                            cursorMs = if (dragging) s.cursorMs else abs,
                        )
                    }
                    // 回放：跨段自动续播（FR-4.8 / AC-10）
                    if (ps.ended && !advanceLock && s.segments.isNotEmpty() && s.hlsUrl == null) {
                        advanceLock = true
                        val next = s.segments.getOrNull(s.currentSegmentIndex + 1)
                        if (next != null) {
                            Logx.i(TAG, "分片播完，自动续播下一段")
                            _state.value = s.copy(cursorMs = next.startMs)
                            val cur = s.segments.getOrNull(s.currentSegmentIndex)
                            if (cur != null) {
                                player.openSegment(next.url, 0L)
                                player.setSpeed(s.speed)
                                player.setMuted(s.muted)
                                // 声音由 VLC 负责：换段时音频必须一起切到新分片，否则第一段之后就无声
                                vlcAudio?.play(next.url, 0L)
                                vlcAudio?.setMuted(s.muted)
                                _state.value = _state.value.copy(currentSegmentIndex = s.currentSegmentIndex + 1, playbackOffsetMs = 0L)
                            }
                        }
                        advanceLock = false
                    }
                }
            }
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        livePlayer.stop()
        vlcAudio?.stop()   // VLC 音频必须显式释放，否则 native 资源泄漏
        player.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "PlayerVM"
        const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * 一次交给播放器的连续分片数。
         * 必须为 1：回放的"声音"由 VLC 逐段播放，若画面侧一次吃 8 段，
         * 段结束事件只在整链播完才触发 → 第一段之后无声、播放进度也停在段首（代码核查 P0-3）。
         */
        private const val SEGMENT_CHAIN = 1
        /** FR-8.1：首帧 3 秒未出画即自动重试（不等 20 秒超时）。 */
        private const val FIRST_FRAME_WINDOW_MS = 3_000L
        private const val FIRST_FRAME_MAX_RETRY = 5

        fun dayStartOf(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
            val cal = Calendar.getInstance(zone)
            cal.timeInMillis = epochMs
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun dayLabel(dayStartMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
            val cal = Calendar.getInstance(zone)
            cal.timeInMillis = dayStartMs
            val today = dayStartOf(System.currentTimeMillis(), zone)
            return when (dayStartMs) {
                today -> "今天"
                today - DAY_MS -> "昨天"
                else -> (cal.get(Calendar.MONTH) + 1).toString() + " 月 " + cal.get(Calendar.DAY_OF_MONTH) + " 日"
            }
        }
    }
}
