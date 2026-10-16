package com.jishi.monitor.player

import android.content.Context
import android.view.View

/**
 * 直播内核抽象（TD-002）。
 *
 * 两套实现：
 *  - [VlcLiveKernel]：VLC 内核（默认）。FLV + HEVC 走 FFmpeg 解复用（:demux=avformat），
 *    音视频共用同一主时钟，音频不会被视频排队饿死——这是解决"电流声/断续"的关键。
 *  - [LiveFlvHevcPlayer]：自研 FLV/HEVC 内核（备用，可用构建开关一键回滚）。
 *
 * 渲染面由内核自己提供，统一使用 TextureView：
 *  TextureView 是普通 View，能正常接受 Compose 的缩放/平移变换（SurfaceView 在部分设备上不行），
 *  这是"画面支持放大"的前提。
 */
interface LiveKernel {

    interface Listener {
        fun onFirstFrame(width: Int, height: Int)
        fun onError(message: String)
        fun onEnded()
    }

    /** 内核自己的渲染面（同一个内核实例重复调用应返回同一个 View）。 */
    fun createRenderView(ctx: Context): View

    fun start(url: String, token: String?, renderView: View)

    fun stop()

    fun togglePause()

    fun isPaused(): Boolean

    fun setMuted(muted: Boolean)

    fun isPlaying(): Boolean
}
