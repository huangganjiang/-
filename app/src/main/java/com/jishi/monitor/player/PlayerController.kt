package com.jishi.monitor.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.jishi.monitor.core.Logx
import com.jishi.monitor.data.net.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放器内核无关接口（需求第九章约束 3，便于将来替换内核）。 */
interface PlayerController {
    fun openLive(url: String)
    fun openSegment(url: String, startOffsetMs: Long, autoPlay: Boolean = true)

    /** 从当前段开始连续播放多段（无缝跨段）。 */
    fun openSegmentList(urls: List<String>, startOffsetMs: Long, autoPlay: Boolean = true) = Unit

    /** 打开 HLS 连续流（回放主路径）。 */
    fun openHls(url: String, startOffsetMs: Long, autoPlay: Boolean = true) = Unit
    fun play()
    fun pause()
    fun seekToMs(ms: Long)
    fun setSpeed(speed: Float)
    fun setMuted(muted: Boolean)
    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun durationMs(): Long
    fun release()
    val state: StateFlow<PlayerUiState>
}

data class PlayerUiState(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val bitrateBps: Long = 0L,
)

/**
 * Media3 ExoPlayer 实现。
 * - 直播：/live/{id}.flv（HTTP-FLV，渐进式），必须由 OkHttp 数据源注入 Bearer Token；
 * - 回放：按 MP4 分段逐个播放（M0 实测段内支持 Range + faststart，可直接 seekTo 段内偏移）。
 */
class Media3PlayerController(
    private val context: Context,
    private val api: ApiClient,
) : PlayerController {

    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var pendingUri: String? = null
    private var pendingSeekMs: Long = 0L
    private var muted = false
    private var speed = 1f

    private fun dataSourceFactory(): OkHttpDataSource.Factory {
        val f = OkHttpDataSource.Factory(api.client)
        val token = api.currentToken()
        if (!token.isNullOrEmpty()) {
            f.setDefaultRequestProperties(mapOf("Authorization" to "Bearer " + token))
        }
        // M0 实测：录像分片与直播流可匿名取，但统一带 Token 更稳妥
        f.setUserAgent("jishi-monitor/1.0")
        return f
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // 加大缓冲：1440p HEVC 在无硬解设备上会占满 CPU，缓冲太小会周期性欠载
        // （表现就是回放音频断续/电流）。回放是点播，缓冲大一点几乎不增加感知延迟。
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .build()
        val p = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    ready = playbackState == Player.STATE_READY || _state.value.ready,
                    ended = playbackState == Player.STATE_ENDED,
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                _state.value = _state.value.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                val code = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    // FLV 中的 HEVC（EasyNVR 直播现状）会走到这里：
                    // TagPayloadReader$UnsupportedFormatException: Video format not supported: 12
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "UNSUPPORTED_FORMAT"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "NETWORK"
                    else -> "OTHER"
                }
                Logx.w(TAG, "播放错误 code=" + error.errorCode + " name=" + error.errorCodeName)
                _state.value = _state.value.copy(
                    errorCode = code,
                    errorMessage = error.errorCodeName,
                    buffering = false,
                    playing = false,
                )
            }
        })
        player = p
        return p
    }

    /** 直播：自定义 FLV/HEVC 提取器在前，默认提取器兜底；回放：默认提取器（MP4）。 */
    private val liveExtractors = androidx.media3.extractor.ExtractorsFactory {
        arrayOf<androidx.media3.extractor.Extractor>(FlvHevcExtractor()) +
            androidx.media3.extractor.DefaultExtractorsFactory().createExtractors()
    }

    private fun open(uri: String, live: Boolean = false) {
        val p = ensurePlayer()
        p.stop()
        p.clearMediaItems()
        _state.value = PlayerUiState()
        val factory = if (live) {
            ProgressiveMediaSource.Factory(dataSourceFactory(), liveExtractors)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory())
        }
        val src = factory.createMediaSource(MediaItem.fromUri(Uri.parse(uri)))
        p.setMediaSource(src)
        p.prepare()
        p.setVolume(if (muted) 0f else 1f)
        p.setPlaybackSpeed(speed)
        p.playWhenReady = true
    }

    override fun openLive(url: String) {
        pendingUri = url
        pendingSeekMs = 0L
        open(url, live = true)
    }

    override fun openSegment(url: String, startOffsetMs: Long, autoPlay: Boolean) {
        pendingUri = url
        pendingSeekMs = startOffsetMs
        open(url)
        if (startOffsetMs > 0) {
            player?.seekTo(startOffsetMs)
        }
        if (!autoPlay) player?.pause()
    }

    /**
     * 打开"从当前段开始的一串分片"。Media3 会自动预缓冲下一段并无缝过渡，
     * 避免每 50 秒重建一次音频输出造成的周期性咔哒（甲方反馈"录像声音有电流"）。
     */
    override fun openSegmentList(urls: List<String>, startOffsetMs: Long, autoPlay: Boolean) {
        if (urls.isEmpty()) return
        pendingUri = urls.first()
        pendingSeekMs = startOffsetMs
        val p = ensurePlayer()
        val items = urls.map { androidx.media3.common.MediaItem.fromUri(it) }
        p.setMediaItems(items, 0, startOffsetMs.coerceAtLeast(0L))
        p.prepare()
        p.setPlaybackSpeed(speed)
        p.volume = if (muted) 0f else 1f
        if (autoPlay) p.play() else p.pause()
    }

    override fun openHls(url: String, startOffsetMs: Long, autoPlay: Boolean) {
        pendingUri = url
        pendingSeekMs = startOffsetMs
        val p = ensurePlayer()
        val mime = androidx.media3.common.MimeTypes.APPLICATION_M3U8
        val item = androidx.media3.common.MediaItem.Builder()
            .setUri(url)
            .setMimeType(mime)
            .build()
        p.setMediaItem(item)
        p.prepare()
        if (startOffsetMs > 0) p.seekTo(startOffsetMs)
        p.setPlaybackSpeed(speed)
        p.volume = if (muted) 0f else 1f
        if (autoPlay) p.play() else p.pause()
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekToMs(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0L))
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        player?.setPlaybackSpeed(speed)
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        player?.volume = if (muted) 0f else 1f
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun positionMs(): Long = player?.currentPosition ?: 0L

    override fun durationMs(): Long {
        val d = player?.duration ?: 0L
        return if (d <= 0) 0L else d
    }

    override fun release() {
        player?.release()
        player = null
        _state.value = PlayerUiState()
    }

    /** 绑定 Media3 的 PlayerView（渲染面）。 */
    fun bindView(view: androidx.media3.ui.PlayerView) {
        view.player = ensurePlayer()
        view.useController = false
        view.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }

    fun currentMediaUri(): String? = pendingUri

    /** 是否 HEVC/FLV 组合导致的不支持（用于 FR-3.9 的可读提示）。 */
    fun lastErrorWasUnsupported(): Boolean = _state.value.errorCode == "UNSUPPORTED_FORMAT"

    companion object { private const val TAG = "Player" }
}
