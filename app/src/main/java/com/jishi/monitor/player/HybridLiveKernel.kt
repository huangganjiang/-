package com.jishi.monitor.player

import android.content.Context
import android.net.Uri
import android.view.View
import com.jishi.monitor.core.Logx
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * 混合直播内核（默认）：**视频走自研内核、音频走 VLC**。
 *
 * 为什么这样组合（实测定论）：
 *  - 自研内核（MediaCodec 直渲 Surface）在本环境画面稳定出图，但它的音频线程会被 1440p 软解抢占，
 *    表现为电流声/断续（真机硬解后虽有改善，但不可控）。
 *  - VLC 内核音频链路成熟（音视频共主时钟、AudioTrack 由 VLC 管理），实测电流声消失，
 *    但它的 vout 在本环境黑屏（EGL 与模拟器不兼容，与代码无关）。
 *  - 两者各取所长：画面用已验证的 MediaCodec 直渲路径，声音用 VLC 的音频输出，去掉 VLC 的视频输出。
 *
 * 代价：同一路直播会建立两条 HTTP 连接（约 2 倍带宽，单路约 3~4 Mbps）。
 * 若带宽紧张，可用构建开关切回纯内核（LIVE_KERNEL=vlc|self）。
 */
class HybridLiveKernel(
    appCtx: Context,
    private val libVlc: LibVLC?,
    private val listener: LiveKernel.Listener,
    /** 是否需要声音。首页轮播传 false：只拉视频，避免首页同时开两条连接（真机实测扛不住）。 */
    private val withAudio: Boolean = true,
) : LiveKernel {

    private var pendingAudioUrl: String? = null

    private val video = LiveFlvHevcPlayer(object : LiveKernel.Listener {
        override fun onFirstFrame(width: Int, height: Int) {
            listener.onFirstFrame(width, height)
            // 声画同步：等画面首帧上屏后再起音频，避免"声音先到、画面还在转圈"
            val url = pendingAudioUrl
            if (withAudio && url != null && audio?.isPlaying() != true) startVlcAudio(url)
        }

        override fun onError(message: String) = listener.onError(message)
        override fun onEnded() = listener.onEnded()
    })

    private val audio = libVlc?.let { VlcAudioPlayer(it) }
    private var muted = false

    override fun createRenderView(ctx: Context): View = video.createRenderView(ctx)

    override fun start(url: String, token: String?, renderView: View) {
        // 1) 视频：自研内核（画面已实测正常）
        // 先停掉上一个通道的 VLC 音频：否则新通道首帧出来前，旧通道的声音还在播（技术核查 P0-2）
        stopVlcAudio()
        video.setMuted(true)          // 视频内核不再出声，避免与 VLC 音频叠加
        video.start(url, token, renderView)
        if (!withAudio) return       // 轮播：只要画面
        // 音频不在这里起：记录 URL，等视频首帧回调后再启动，保证声画同步
        pendingAudioUrl = url
    }

    private fun startVlcAudio(url: String) {
        val a = audio
        if (a == null) {
            video.setMuted(muted)   // 没有 LibVLC 时退回自研内核自带音频
            Logx.w(TAG, "无 LibVLC，音频回退自研内核")
            return
        }
        a.play(url, 0L)
        Logx.i(TAG, "VLC 纯音频已启动（画面由自研内核提供）")
    }

    private fun stopVlcAudio() = audio?.stop()

    override fun stop() {
        pendingAudioUrl = null
        stopVlcAudio()
        video.stop()
    }

    override fun togglePause() {
        val paused = !video.isPaused()
        video.togglePause()
        if (paused) audio?.pause() else audio?.resume()
    }

    override fun isPaused(): Boolean = video.isPaused()

    override fun setMuted(value: Boolean) {
        muted = value
        val a = audio
        if (a != null) {
            a.setMuted(value)
        } else {
            // 没有 LibVLC（回滚路径）时声音由自研内核出，静音必须落到它身上
            video.setMuted(value)
        }
    }

    override fun isPlaying(): Boolean = video.isPlaying()

    companion object {
        private const val TAG = "HybridKernel"
    }
}
