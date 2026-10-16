package com.jishi.monitor

import android.content.Context
import com.jishi.monitor.data.CredentialBlob
import com.jishi.monitor.data.Prefs
import com.jishi.monitor.data.SecureStore
import com.jishi.monitor.data.net.ApiClient
import com.jishi.monitor.data.repo.ChannelRepository
import com.jishi.monitor.data.repo.ConfigRepository
import com.jishi.monitor.data.repo.RecordsRepository
import com.jishi.monitor.data.repo.SnapshotRepository
import com.jishi.monitor.player.Media3PlayerController

/** 轻量依赖容器（不引入 DI 框架，保持 APK 体积）。 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val prefs = Prefs(context)
    val secure = SecureStore(context)
    val blob = CredentialBlob(context)
    val api = ApiClient(secure, prefs)
    val channels = ChannelRepository(api, prefs)
    val records = RecordsRepository(api)
    val snapshots = SnapshotRepository(api, channels)
    val config = ConfigRepository(api, prefs)
    val player = Media3PlayerController(context, api)

    // ---------------- 直播内核（TD-002） ----------------
    // 默认走 VLC：其 FFmpeg 解复用能解 FLV 里的 HEVC，音频与视频共用主时钟，不会被视频饥饿。
    // 自研内核保留为备用实现，把 PLAYER_KERNEL_VLC_LIVE 置 false 即可回滚（零逻辑改动）。
    // by lazy：LibVLC 初始化是 native 重活，放到首次真正要播时再做，避免拖慢冷启动
    private val libVlc: org.videolan.libvlc.LibVLC? by lazy {
        if (!BuildConfig.PLAYER_KERNEL_VLC_LIVE) return@lazy null
        // 注意：必须传可变列表——LibVLC 构造内部会对 options 执行 add()，
        // 传 listOf(...)（不可变）会抛 UnsupportedOperationException 导致应用启动即崩。
        // 日志静默（审计要求：不把地址/流信息写进 logcat）。
        // 注意：曾用 --verbose=2 排障，其持续刷屏会周期性阻塞 VLC 主线程，导致音频每几秒一顿。
        org.videolan.libvlc.LibVLC(
            appContext,
            arrayListOf("--no-video-title-show", "--verbose=-1", "--vout=android_display"),
        )
    }

    /** LibVLC 实例由容器持有并复用（首页轮播与播放页共享，减少 native 初始化开销）。 */
    val sharedLibVlc: org.videolan.libvlc.LibVLC? get() = libVlc

    /** 回放用的 VLC 纯音频（与直播同一套思路：画面走 Media3，声音走 VLC）。 */
    fun newVlcAudio(): com.jishi.monitor.player.VlcAudioPlayer? =
        libVlc?.let { com.jishi.monitor.player.VlcAudioPlayer(it) }

    fun newLiveKernel(
        listener: com.jishi.monitor.player.LiveKernel.Listener,
        withAudio: Boolean = true,
    ): com.jishi.monitor.player.LiveKernel {
        val vlc = if (BuildConfig.PLAYER_KERNEL_VLC_LIVE) libVlc else null
        return when (BuildConfig.LIVE_KERNEL) {
            // 默认：视频自研（画面稳）+ 音频 VLC（无电流）
            "hybrid" -> com.jishi.monitor.player.HybridLiveKernel(appContext, vlc, listener, withAudio)
            "vlc" -> if (vlc != null) {
                com.jishi.monitor.player.VlcLiveKernel(appContext, vlc, listener)
            } else {
                com.jishi.monitor.player.LiveFlvHevcPlayer(listener)
            }
            else -> com.jishi.monitor.player.LiveFlvHevcPlayer(listener)
        }
    }
}
