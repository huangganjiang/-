package com.jishi.monitor.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.jishi.monitor.core.Logx
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * VLC 直播内核（TD-002 默认实现）。
 *
 * 为什么用它：参考包（甲方认可音频/时间轴正常）就是 VLC；VLC 3.7.2 内置 FFmpeg ≥6.1，
 * 其 ff_flv_demuxer 能解出 FLV 里的 HEVC（codecId=12），而 Media3 的 FLV 提取器只认 AVC。
 * 音频由 VLC 自身的输出链路驱动，与视频共用主时钟，不会像自研实现那样被视频饥饿出欠载。
 *
 * 渲染方式：**不使用 VLCVideoLayout**，而是自己提供 SurfaceView，通过 IVLCVout.setVideoSurface
 * 把 Surface 交给 VLC。原因：VLCVideoLayout 依赖自身 onLayoutChange 上报窗口尺寸，
 * 在 Compose 的 AndroidView 里实测只报 videoTrack 却不出画面；自持 Surface 的路径在模拟器上稳定。
 */
class VlcLiveKernel(
    private val appCtx: Context,
    private val libVlc: LibVLC,
    private val listener: LiveKernel.Listener,
) : LiveKernel {

    private var player: MediaPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var pendingUrl: String? = null
    private var pendingToken: String? = null
    private var attached = false
    private var muted = false

    @Volatile private var firstFrameSent = false

    override fun createRenderView(ctx: Context): View {
        val sv = SurfaceView(ctx)
        surfaceView = sv
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                attachSurface(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // 尺寸变化要同步给 VLC，否则画面按旧尺寸渲染（表现为黑屏/错位）
                try { player?.vlcVout?.setWindowSize(width, height) } catch (t: Throwable) { }
                if (!attached) attachSurface(holder)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                attached = false
                try { player?.vlcVout?.detachViews() } catch (t: Throwable) { }
            }
        })
        return sv
    }

    override fun start(url: String, token: String?, renderView: View) {
        pendingUrl = url
        pendingToken = token
        (renderView as? SurfaceView)?.let { sv ->
            surfaceView = sv
            if (sv.holder.surface != null && sv.holder.surface.isValid) attachSurface(sv.holder)
        }
    }

    /** Surface 就绪后创建 MediaPlayer、绑定 Surface 并起播。 */
    private fun attachSurface(holder: SurfaceHolder) {
        val url = pendingUrl ?: return
        if (attached && player != null) return
        stop()
        attached = true
        firstFrameSent = false
        try {
            val mp = MediaPlayer(libVlc)
            player = mp
            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Vout -> notifyFirstFrameWhenReady(mp)
                    MediaPlayer.Event.EncounteredError -> listener.onError("VLC 播放失败")
                    MediaPlayer.Event.EndReached -> listener.onEnded()
                    else -> Unit
                }
            }
            val vout = mp.vlcVout
            // 顺序：先告知窗口尺寸，再绑定 Surface，最后 attachViews
            val w = surfaceView?.width ?: 0
            val h = surfaceView?.height ?: 0
            if (w > 0 && h > 0) vout.setWindowSize(w, h)
            vout.setVideoSurface(holder.surface, holder)
            vout.attachViews()

            val media = Media(libVlc, Uri.parse(url)).apply {
                // FLV 里的 HEVC（codecId=12）必须交给 FFmpeg 解复用，VLC 原生 flv demux 只走 AVC
                addOption(":demux=avformat")
                addOption(":network-caching=300")
                addOption(":live-caching=300")
                addOption(":clock-jitter=0")
                addOption(":http-user-agent=jishi-monitor/1.0")
                setHWDecoderEnabled(true, false)
            }
            mp.media = media
            media.release()
            mp.volume = if (muted) 0 else 100
            mp.play()
            Logx.i(TAG, "VLC 起播 demux=avformat caching=300")
        } catch (t: Throwable) {
            Logx.e(TAG, "VLC 起播失败", t)
            listener.onError("播放内核初始化失败")
            try { player?.release() } catch (e: Throwable) { }
            player = null
            attached = false
        }
    }

    private fun notifyFirstFrameWhenReady(mp: MediaPlayer) {
        if (firstFrameSent) return
        Thread {
            var tries = 0
            while (!firstFrameSent && tries++ < 50) {
                try {
                    val t = mp.currentVideoTrack
                    if (t != null && t.width > 0 && t.height > 0) {
                        firstFrameSent = true
                        Logx.i(TAG, "VLC 出画 " + t.width + "x" + t.height)
                        listener.onFirstFrame(t.width, t.height)
                        return@Thread
                    }
                } catch (e: Throwable) { }
                try { Thread.sleep(100) } catch (e: InterruptedException) { return@Thread }
            }
        }.start()
    }

    override fun stop() {
        attached = false
        val mp = player ?: return
        player = null
        try { mp.setEventListener(null) } catch (t: Throwable) { }
        try { mp.stop() } catch (t: Throwable) { }
        try { mp.vlcVout.detachViews() } catch (t: Throwable) { }
        try { mp.release() } catch (t: Throwable) { }
    }

    override fun togglePause() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause() else mp.play()
    }

    override fun isPaused(): Boolean = player?.isPlaying == false

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        try { player?.volume = if (muted) 0 else 100 } catch (t: Throwable) { }
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    companion object {
        private const val TAG = "VlcKernel"
    }
}
