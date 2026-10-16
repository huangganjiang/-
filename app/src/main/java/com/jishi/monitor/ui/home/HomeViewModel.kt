package com.jishi.monitor.ui.home

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jishi.monitor.AppContainer
import com.jishi.monitor.core.Logx
import com.jishi.monitor.data.model.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val snapshots: Map<String, Bitmap> = emptyMap(),
    val error: String? = null,
    val note: String = "",
    /** 每路通道最近一次录像的结束时刻（毫秒），用于列表展示"最新录像时间"。 */
    val latestRecording: Map<String, Long> = emptyMap(),
)

/**
 * 首页（FR-2 / AC-3 / AC-19）。
 * 快照刷新策略按 M0 实测带宽结论调整：
 *   - 轮播当前项：3 秒一次
 *   - 列表可见行：6 秒一次，逐个错峰
 *   - 仅前台刷新（切后台立即停止）
 * 折算带宽 ≤1.5 Mbps（AC-19）。
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var carouselJob: Job? = null
    private var listJob: Job? = null

    init {
        viewModelScope.launch {
            val cached = container.channels.cachedChannels()
            if (cached.isNotEmpty()) _state.value = _state.value.copy(channels = cached)
            // 已激活则恢复会话并拉取；未激活由外层导航接管
            if (container.api.restoreSession()) refreshChannels()
        }
    }

    fun refreshChannels() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val list = container.channels.refresh()
            _state.value = _state.value.copy(
                loading = false,
                channels = list,
                note = container.channels.lastNote,
                error = if (list.isEmpty()) (container.channels.lastError ?: "暂时连不上，正在重试…") else null,
            )
            loadLatestRecordings(list)
        }
    }

    /** 拉取每路"最新录像时间"（失败静默，不影响首页其它内容）。 */
    private fun loadLatestRecordings(list: List<Channel>) {
        viewModelScope.launch {
            val result = HashMap<String, Long>()
            for (ch in list) {
                val end = runCatching { container.records.latestRecordingEndMs(ch.id) }.getOrNull()
                if (end != null) result[ch.id] = end
            }
            if (result.isNotEmpty()) {
                _state.value = _state.value.copy(latestRecording = _state.value.latestRecording + result)
            }
            Logx.i(TAG, "最新录像时间已更新 " + result.size + "/" + list.size + " 路")
        }
    }

    /** 只对可见项刷新（避免 7 路全量刷新导致带宽超标）。 */
    fun startSnapshotLoops(carouselId: () -> String?, visibleIds: () -> List<String>) {
        stopSnapshotLoops()
        carouselJob = viewModelScope.launch {
            while (true) {
                carouselId()?.let { fetchSnapshot(it) }
                delay(CAROUSEL_PERIOD_MS)
            }
        }
        listJob = viewModelScope.launch {
            while (true) {
                delay(LIST_PERIOD_MS)
                for (id in visibleIds()) {
                    fetchSnapshot(id)
                    delay(STAGGER_MS)
                }
            }
        }
    }

    fun stopSnapshotLoops() {
        carouselJob?.cancel(); carouselJob = null
        listJob?.cancel(); listJob = null
    }

    private val failedOnce = HashSet<String>()

    fun fetchSnapshot(channelId: String) {
        Logx.i(TAG, "调度快照 " + channelId)
        viewModelScope.launch {
            val bmp = container.snapshots.fetch(channelId)
            if (bmp == null) {
                if (failedOnce.add(channelId)) Logx.w(TAG, "快照获取失败: " + channelId)
                return@launch
            }
            failedOnce.remove(channelId)
            _state.value = _state.value.copy(snapshots = _state.value.snapshots + (channelId to bmp))
        }
    }

    fun offlineMessage(): String = "该通道当前离线"

    @Volatile private var autoTour = false

    fun autoTourEnabled(): Boolean = autoTour

    fun loadAutoTour() {
        viewModelScope.launch { autoTour = container.prefs.autoTour() }
    }

    // ---------------- 轮播当前项的真直播（只 1 路，避免 7 路并发解码/带宽） ----------------

    /** 轮播直播状态：null=未启用，true=直播中，false=已回退快照。 */
    private val _carouselLive = MutableStateFlow<Boolean?>(null)
    val carouselLive: StateFlow<Boolean?> = _carouselLive

    private val carouselPlayer: com.jishi.monitor.player.LiveKernel =
        container.newLiveKernel(withAudio = false, listener = object : com.jishi.monitor.player.LiveKernel.Listener {
        override fun onFirstFrame(width: Int, height: Int) {
            _carouselLive.value = true
            Logx.i(TAG, "轮播直播出画 " + width + "x" + height)
        }

        override fun onError(message: String) {
            // 首页不打断用户：失败即回退快照，不重连（省电省流量）
            Logx.w(TAG, "轮播直播失败，回退快照")
            _carouselLive.value = false
        }

        override fun onEnded() {
            _carouselLive.value = false
        }
    })

    private var carouselRenderViewCache: android.view.View? = null

    /** 轮播的渲染面（由内核提供，缓存复用；VLC 下是 VLCVideoLayout）。 */
    fun carouselRenderView(ctx: android.content.Context): android.view.View =
        carouselPlayer.createRenderView(ctx)

    private var carouselChannelId: String? = null
    private var carouselSurface: android.view.View? = null
    private var carouselSurfaceKey: String? = null

    /** 切换轮播当前通道的直播；同一 (通道, surface) 重复调用会被忽略。 */
    fun switchCarouselLive(channelId: String?, surface: android.view.View?) {
        if (channelId == null || surface == null) return
        val key = channelId + "@" + System.identityHashCode(surface)
        if (key == carouselSurfaceKey) return
        carouselSurfaceKey = key
        carouselSurface = surface
        carouselChannelId = channelId
        _carouselLive.value = null
        val url = container.api.currentBaseUrl() + com.jishi.monitor.data.net.LoginEndpoint.live(channelId)
        Logx.i(TAG, "轮播切到直播 " + channelId)
        carouselPlayer.start(url, container.api.currentToken(), surface)
        // 超时回退：真机上网络/解码不稳时不能一直停在"连接中"
        viewModelScope.launch {
            kotlinx.coroutines.delay(8000)
            if (carouselChannelId == channelId && _carouselLive.value != true) {
                Logx.w(TAG, "轮播直播超时，回退快照")
                _carouselLive.value = false
                carouselPlayer.stop()
            }
        }
    }

    fun stopCarouselLive() {
        carouselChannelId = null
        carouselSurfaceKey = null
        carouselSurface = null
        _carouselLive.value = null
        carouselPlayer.stop()
    }

    /** 回前台续播：SurfaceView 未重建时 surfaceCreated 不会再触发（评审 B2）。 */
    fun resumeCarouselLive() {
        val ch = carouselChannelId ?: return
        val surface = carouselSurface ?: return
        carouselSurfaceKey = null
        switchCarouselLive(ch, surface)
    }

    fun isCarouselLiveOn(channelId: String): Boolean =
        carouselChannelId == channelId && _carouselLive.value == true

    /** FR-0.4：上次观看通道（用于首页轮播预加载到该通道）。 */
    val lastChannelId = MutableStateFlow<String?>(null)

    fun loadLastChannel() {
        viewModelScope.launch {
            lastChannelId.value = container.prefs.lastChannelId()
        }
    }

    override fun onCleared() {
        stopSnapshotLoops()
        carouselPlayer.stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "Home"
        const val CAROUSEL_PERIOD_MS = 3000L
        const val LIST_PERIOD_MS = 6000L
        const val STAGGER_MS = 150L
    }
}
