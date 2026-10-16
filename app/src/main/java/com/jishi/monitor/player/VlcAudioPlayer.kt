package com.jishi.monitor.player

import android.net.Uri
import com.jishi.monitor.core.Logx
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * VLC 纯音频播放器（直播与回放共用）。
 *
 * 为什么不让 MediaCodec/Media3 出声：1440p HEVC 在没有硬解的设备上会把 CPU 吃满，
 * 音频线程被饿 → 欠载 → 电流声与断续。VLC 的音频输出链路成熟稳定，把"出声"这一件事交给它，
 * 画面仍由我们自己的解码/播放路径负责。
 */
class VlcAudioPlayer(private val libVlc: LibVLC) {

    private var player: MediaPlayer? = null
    private var currentUrl: String? = null
    private var muted = false
    @Volatile private var stopped = false

    fun play(url: String, startMs: Long = 0L) {
        stop()
        stopped = false
        currentUrl = url
        try {
            val mp = MediaPlayer(libVlc)
            player = mp
            val media = Media(libVlc, Uri.parse(url)).apply {
                addOption(":demux=avformat")
                addOption(":no-video")          // 只要声音
                addOption(":network-caching=300")
                addOption(":live-caching=300")
                addOption(":clock-jitter=0")
                addOption(":http-user-agent=jishi-monitor/1.0")
                if (startMs > 0) addOption(":start-time=" + (startMs / 1000.0))
            }
            mp.media = media
            media.release()
            mp.volume = if (muted) 0 else 100
            mp.play()
            Logx.i(TAG, "VLC 音频播放 startMs=" + startMs)
        } catch (t: Throwable) {
            Logx.e(TAG, "VLC 音频启动失败", t)
            // 异常路径也要释放，否则 MediaPlayer 的 native 资源泄漏
            try { player?.release() } catch (e: Throwable) { }
            player = null
        }
    }


    /** 倍速：VLC 侧的播放速率，必须与画面同步，否则音画会持续分离。 */
    fun setRate(rate: Float) {
        try {
            player?.rate = rate.coerceIn(0.25f, 4f)
        } catch (t: Throwable) {
            Logx.w(TAG, "VLC 设置倍速失败: " + t.javaClass.simpleName)
        }
    }

    /** 回放定位时同步音频位置（尽力而为，失败不影响画面）。 */
    fun seekTo(ms: Long) {
        val mp = player ?: return
        try {
            mp.time = ms
        } catch (t: Throwable) {
            Logx.w(TAG, "VLC 音频 seek 失败: " + t.javaClass.simpleName)
        }
    }

    fun pause() = try { player?.pause() } catch (t: Throwable) { }

    fun resume() = try { player?.play() } catch (t: Throwable) { }

    fun setMuted(m: Boolean) {
        muted = m
        try { player?.volume = if (m) 0 else 100 } catch (t: Throwable) { }
    }

    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (t: Throwable) { false }

    fun stop() {
        stopped = true
        currentUrl = null
        val mp = player ?: return
        player = null
        try { mp.stop() } catch (t: Throwable) { }
        try { mp.release() } catch (t: Throwable) { }
    }

    companion object {
        private const val TAG = "VlcAudio"
    }
}
