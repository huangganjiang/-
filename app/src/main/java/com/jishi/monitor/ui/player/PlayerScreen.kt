package com.jishi.monitor.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.jishi.monitor.R
import com.jishi.monitor.core.TimelineMath
import com.jishi.monitor.data.model.RecordSegment
import com.jishi.monitor.data.model.ZoomLevel
import com.jishi.monitor.data.model.formatSpeed
import com.jishi.monitor.ui.theme.JishiColors
import java.util.Calendar
import kotlin.coroutines.resume
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playerState by viewModel.player.state.collectAsStateWithLifecycle()
    var showMore by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val context = LocalContext.current
    // 直播的播放态来自自研内核（Media3 的 playing 在直播路径恒为 false）
    // 直播：按钮图标必须跟随"是否暂停"；回放：跟随 Media3 播放态
    // 直播：只有"已出画且未暂停"才显示暂停图标；连接中/失败一律显示播放三角（UI 详检 ③）
    val isPlayingNow = if (state.mode == PlayerMode.LIVE) {
        state.livePhase == LivePhase.PLAYING && !state.paused
    } else {
        playerState.playing
    }
    var fullscreen by remember { mutableStateOf(false) }
    var videoScale by remember { mutableStateOf(1f) }
    var videoOffset by remember { mutableStateOf(Offset.Zero) }

    // 缩放按模式隔离：切换直播/回放时复位，避免"在一个页面放大后另一个页面也被放大"
    LaunchedEffect(state.mode) {
        videoScale = 1f
        videoOffset = Offset.Zero
    }

    // 控制层自动隐藏：画面是主角，控制按钮不再长期压在画面上（甲方反馈"太遮挡内容"）
    LaunchedEffect(controlsVisible, state.mode) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }

    // 横屏全屏：切入横屏并隐藏系统栏；退出时恢复
    DisposableEffect(fullscreen) {
        val activity = context as? android.app.Activity
        if (activity != null) {
            activity.requestedOrientation = if (fullscreen) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (fullscreen) {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val act = context as? android.app.Activity
            if (fullscreen && act != null) {
                act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                androidx.core.view.WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 系统返回 / 全面屏返回手势：先回首页，而不是直接退出到桌面（甲方反馈）
    androidx.activity.compose.BackHandler(enabled = true) {
        if (showMore) {
            showMore = false
        } else if (showDate) {
            showDate = false
        } else if (fullscreen) {
            fullscreen = false      // 全屏时先退出全屏
        } else {
            onBack()
        }
    }

    // 离开播放页立即停止直播/回放，避免与首页轮播并发解码（评审 N1）
    DisposableEffect(Unit) {
        onDispose { viewModel.onBackground() }
    }

    LaunchedEffect(state.toast) {
        if (state.toast != null) { kotlinx.coroutines.delay(1600); viewModel.clearToast() }
    }

    // 方向自适应：横屏时画面铺满、控件浮在画面上；竖屏时画面按比例 + 控件在下方（不写死尺寸）
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(Modifier.fillMaxSize().background(JishiColors.backgroundGradient)) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top,
        ) {
            // ---------- 标题栏：摄像头名称（全屏时隐藏，把整屏让给画面） ----------
            if (!fullscreen) Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xF2FFFFFF))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val backShape = RoundedCornerShape(22.dp)
                Box(
                    Modifier
                        .size(48.dp)   // 热区下限 48dp
                        .clip(backShape)
                        .background(Color(0xFFF2F5FA))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "返回", tint = JishiColors.TextPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                // 长按通道名打开"更多"面板（隐藏入口：甲方要求不要多余的"更多"按钮）
                Column(
                    Modifier
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = { showMore = true })
                        },
                ) {
                    Text(
                        state.channel?.displayName ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            state.mode == PlayerMode.LIVE && state.livePhase == LivePhase.PLAYING ->
                                "在线 · " + (if (state.liveInfo.isBlank()) "实时" else state.liveInfo)
                            state.mode == PlayerMode.LIVE -> "连接中…"
                            else -> "回放 · " + PlayerViewModel.dayLabel(state.dayStartMs)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = JishiColors.TextMuted,
                    )
                }
                // 甲方要求：标题栏不再放"全屏/更多"（全屏移到画面内音量旁边，"更多"取消）
            }

            // ---------- 画面 ----------
            // 尺寸直接链式给出（不放 then()，避免 measure 约束被改写导致画面被拉伸）
            // 画面直接铺在该区域，不套圆角卡片（甲方要求：不要"框住"视频、不遮挡内容）
            val videoSize = when {
                // 全屏：画面占满整屏
                fullscreen -> Modifier.fillMaxSize()
                // 横屏：占剩余空间，给下方控件留位
                isLandscape -> Modifier.fillMaxWidth().weight(1f)
                // 竖屏回放：固定高度，给时间轴留空间
                state.mode == PlayerMode.REPLAY -> Modifier.fillMaxWidth().height(240.dp)
                // 竖屏直播：按视频真实宽高比
                else -> Modifier.fillMaxWidth().aspectRatio(videoAspectRatio(state.liveInfo))
            }
            Box(
                videoSize
                    .clipToBounds()
                    .background(Color.Black)
                    // 画面放大（甲方要求）：双指缩放 + 双击在 1x/2.5x 间切换 + 放大后拖动平移
                    .pointerInput(state.mode) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = {
                                if (videoScale > 1.01f) {
                                    videoScale = 1f; videoOffset = Offset.Zero
                                } else {
                                    videoScale = 2.5f
                                }
                            },
                        )
                    }
                    .pointerInput(state.mode) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            videoScale = (videoScale * zoom).coerceIn(1f, 6f)
                            videoOffset = if (videoScale <= 1.01f) Offset.Zero else videoOffset + pan
                        }
                    },
            ) {
                if (state.mode == PlayerMode.LIVE) {
                    // 直播：渲染面由内核提供（TD-002：VLC=VLCVideoLayout；回滚时=自研 TextureView）
                    AndroidView(
                        factory = { ctx -> viewModel.livePlayer.createRenderView(ctx) },
                        // update 在 View 已挂到窗口后调用：VLC 需要此时才能正确 attachViews
                        update = { v -> viewModel.attachLiveView(v) },
                        // 注意：SurfaceView 有自己的图层，缩放后不会被 Compose 自动裁掉，
                        // 会盖住下面的模式胶囊与信息卡——所以外层必须再套一层裁剪切边。
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = videoScale,
                                scaleY = videoScale,
                                translationX = videoOffset.x,
                                translationY = videoOffset.y,
                            ),
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                viewModel.player.bindView(this)
                            }
                        },
                        // 只在首次绑定：原来每次重组都解绑再绑定，会黑一帧（甲方实测"一秒一闪"）
                        update = { v -> if (v.player == null) viewModel.player.bindView(v) },
                        // 回放同样支持画面放大（甲方反馈：回放界面无法放大）
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = videoScale,
                                scaleY = videoScale,
                                translationX = videoOffset.x,
                                translationY = videoOffset.y,
                            ),
                    )
                }

                // 顶/底渐隐：保证白字在亮部也清晰（UI 详检 ①）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color(0xC3000000), Color(0x00000000)))),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xCD000000)))),
                )
                if (state.mode == PlayerMode.REPLAY && !playerState.ready && state.timelineLoading) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                        Spacer(Modifier.height(10.dp))
                        Text("正在加载回放…", color = Color.White, fontSize = 13.sp)
                    }
                }
                if (state.mode == PlayerMode.LIVE && state.livePhase != LivePhase.PLAYING) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    }
                }

                // 画面内：小米类型的白色细描边图标（默认隐藏，点画面才出现 3.5 秒 → 不遮挡内容）
                if (controlsVisible) {
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedOverlayIcon(
                            icon = if (isPlayingNow) R.drawable.ic_pause else R.drawable.ic_play,
                            label = if (isPlayingNow) "暂停" else "播放",
                        ) { viewModel.togglePlay() }
                        OutlinedOverlayIcon(
                            icon = if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on,
                            label = if (state.muted) "取消静音" else "静音",
                        ) { viewModel.toggleMute() }
                        // 全屏：紧挨音量（甲方要求），不再放标题栏
                        OutlinedOverlayIcon(
                            icon = R.drawable.ic_fit,
                            label = if (fullscreen) "退出全屏" else "全屏",
                        ) { fullscreen = !fullscreen }
                    }
                }

                // 按甲方要求：画面内不再放任何信息标签，保持画面干净无遮挡
            }

            // ---------- 模式胶囊（全屏时隐藏） ----------
            if (!fullscreen) Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isLandscape) {
                            Modifier
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .background(Color(0x99000000))
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModeTab("直播", state.mode == PlayerMode.LIVE, Modifier.weight(1f)) { viewModel.setMode(PlayerMode.LIVE) }
                ModeTab("回放", state.mode == PlayerMode.REPLAY, Modifier.weight(1f)) { viewModel.setMode(PlayerMode.REPLAY) }
            }

            if (state.mode == PlayerMode.LIVE && !fullscreen) {
                LiveBody(
                    state = state,
                    vw = playerState.videoWidth,
                    vh = playerState.videoHeight,
                    onRetry = {
                        viewModel.resetRetryBudget()
                        viewModel.startLive()
                    },
                    onTogglePlay = { viewModel.togglePlay() },
                    onToggleMute = { viewModel.toggleMute() },
                    onMore = { showMore = true },
                )
            } else if (!fullscreen) {
                ReplayBody(
                    modifier = Modifier.weight(1f),
                    state = state,
                    onZoom = { viewModel.setZoom(it) },
                    onPreview = { viewModel.previewCursor(it) },
                    onCommit = { viewModel.commitCursor() },
                    onDragStateChange = { viewModel.setDragging(it) },
                    onSeekTo = { viewModel.seekTo(it, forceOpen = true) },
                    onOpenDate = { showDate = true },
                    onSpeed = { viewModel.toggleSpeed() },
                    onStep = { d -> viewModel.step(d) },
                    onHoldPreview = { dir, held -> viewModel.holdPreview(dir, held) },
                    onReleaseHold = { viewModel.releaseHold() },
                    onPrev = { viewModel.prevSegment() },
                    onNext = { viewModel.nextSegment() },
                    onTogglePlay = { viewModel.togglePlay() },
                    isPlaying = if (state.mode == PlayerMode.LIVE) isPlayingNow else playerState.playing,
                    isReplay = state.mode == PlayerMode.REPLAY,
                )
            }
        }

        state.toast?.let {
            Surface(color = Color(0xCC0F172A), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 48.dp)) {
                Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 13.sp)
            }
        }
    }

    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }) {
            MorePanelContent(
                isReplay = state.mode == PlayerMode.REPLAY,
                speed = state.speed,
                autoTour = state.autoTour,
                onSpeed = { viewModel.setSpeed(it) },
                onAutoTour = { viewModel.setAutoTour(it) },
                onCheckUpdate = { viewModel.checkUpdate() },
            )
        }
    }

    if (showDate) {
        ModalBottomSheet(onDismissRequest = { showDate = false }) {
            DateSheetContent(
                currentDayStart = state.dayStartMs,
                recordDays = viewModel.recordDays.value,
                onMonthChanged = { viewModel.loadRecordDays(it) },
                onPick = { showDate = false; viewModel.selectDay(it) },
            )
        }
    }
}

/** 从 SurfaceView 捕获（直播内核使用）。 */

/** 从 PlayerView 捕获当前画面（SurfaceView 走 PixelCopy，TextureView 走 bitmap）。 */

/** "50 秒" / "1 分 12 秒" / "1 小时 2 分"。 */
private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return when {
        h > 0 -> h.toString() + " 小时 " + m + " 分"
        m > 0 -> m.toString() + " 分 " + sec + " 秒"
        else -> sec.toString() + " 秒"
    }
}

/** 从 "2560x1440" 解析出宽高比；解析失败退回 16:9。 */
private fun videoAspectRatio(liveInfo: String): Float {
    val parts = liveInfo.split("x")
    val w = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: 0f
    val h = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 0f
    return if (w > 0f && h > 0f && w / h in 0.5f..3.0f) w / h else 16f / 9f
}

/** 画面内的白色细描边图标（小米摄像头那种类型）：半透明描边、不抢画面主体。 */
@Composable
private fun OutlinedOverlayIcon(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(4.dp)
            .size(48.dp)   // 热区下限 48dp（FR-7.2）
            .clip(CircleShape)
            .background(Color(0x33000000))
            .border(1.5.dp, Color(0x99FFFFFF), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 圆形控制钮 + 下方文字（画面之外，不遮挡画面）。 */
@Composable
private fun ControlPill(icon: Int, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        com.jishi.monitor.ui.theme.Glass.CircleButton(
            modifier = Modifier.clickable { onClick() },
            size = 72.dp,
        ) {
            Icon(painterResource(icon), contentDescription = label, tint = JishiColors.TextPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = JishiColors.TextMuted)
    }
}

@Composable
private fun LiveBody(
    state: PlayerUiState,
    vw: Int,
    vh: Int,
    onRetry: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---------- 状态卡（毛玻璃） ----------
        com.jishi.monitor.ui.theme.Glass.Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoCell(
                    "画面",
                    when {
                        state.liveInfo.isNotBlank() -> state.liveInfo
                        vw > 0 -> vw.toString() + "x" + vh
                        else -> "--"
                    },
                    Modifier.weight(1f),
                )
                Divider()
                InfoCell(
                    "状态",
                    when (state.livePhase) {
                        LivePhase.PLAYING -> "正常"
                        LivePhase.CONNECTING -> "连接中"
                        LivePhase.FAILED -> "重试中"
                        else -> "空闲"
                    },
                    Modifier.weight(1f),
                )
                Divider()
                InfoCell("重连", state.liveRetryAttempt.toString() + " 次", Modifier.weight(1f))
            }
        }
        when (state.livePhase) {
            LivePhase.CONNECTING -> Surface(color = JishiColors.Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = JishiColors.AccentSolid)
                    Spacer(Modifier.width(12.dp))
                    Text("正在连接画面…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            LivePhase.FAILED -> state.liveError?.let { err ->
                Surface(color = Color(0xFFFFF3F3), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(err, color = JishiColors.Live, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            "重试",
                            color = JishiColors.AccentSolid,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onRetry() }
                                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                }
            }
            else -> Unit
        }
        Text(
            "提示：点击画面可隐藏控制条；单画面内可直接切回放。",
            style = MaterialTheme.typography.labelSmall, color = JishiColors.TextWeak,
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(28.dp).background(JishiColors.Divider))
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = JishiColors.TextWeak)
    }
}

@Composable
private fun ReplayBody(
    modifier: Modifier = Modifier,
    state: PlayerUiState,
    onZoom: (ZoomLevel) -> Unit,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenDate: () -> Unit,
    onSpeed: () -> Unit,
    onStep: (Long) -> Unit,
    onHoldPreview: (Int, Long) -> Unit,
    onReleaseHold: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    isPlaying: Boolean,
    isReplay: Boolean,
) {
    Column(
        modifier
            .fillMaxWidth()
            // 全面屏手势导航条避让：底部内容不被手势条压住
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 日期 + 倍速胶囊（仅回放显示：直播没有日期选择与倍速概念）
        if (isReplay) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = JishiColors.Card, shape = RoundedCornerShape(17.dp),
                modifier = Modifier.heightIn(min = 48.dp).clickable { onOpenDate() },
            ) {
                Row(
                    Modifier.height(48.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(PlayerViewModel.dayLabel(state.dayStartMs), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            // 直播没有倍速概念，仅回放提供（甲方指出的逻辑错误）
            if (isReplay) Surface(color = JishiColors.Card, shape = RoundedCornerShape(17.dp), modifier = Modifier.heightIn(min = 48.dp).clickable { onSpeed() }) {
                Box(Modifier.height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        formatSpeed(state.speed) + " 倍速",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // 甲方要求：放弃时间轴，改为"最近录像"列表——点一条播一条，逻辑直观、没有拖动/吸附的坑
        Surface(color = JishiColors.Card, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("最近录像", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        when {
                            state.timelineLoading -> "加载中…"
                            state.segments.isEmpty() -> "当日无录像"
                            else -> state.segments.size.toString() + " 段"
                        },
                        style = MaterialTheme.typography.labelSmall, color = JishiColors.TextWeak,
                    )
                }
                state.replayError?.let {
                    Text(
                        it,
                        color = JishiColors.Live,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                // 显示当天全部录像段（此前只取最近 40 条，导致更早的录像无法选取）
                val recent = state.segments.sortedByDescending { it.startMs }
                // 关键：列表用 weight(fill=false) 参与分配，而不是填满最大高度——
                // 否则下面的「当前播放 / 5 键主控 / 提示」会被挤成 0 高（UI 核查 U-01）
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp, max = 300.dp),
                ) {
                    itemsIndexed(recent) { _, seg ->
                        val isCurrent = state.segments.getOrNull(state.currentSegmentIndex)
                            ?.let { it.startMs == seg.startMs } == true
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSeekTo(seg.startMs) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(if (isCurrent) R.drawable.ic_pause else R.drawable.ic_play),
                                contentDescription = null,
                                tint = if (isCurrent) JishiColors.AccentSolid else JishiColors.TextWeak,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                TimelineMath.formatClock(seg.startMs, java.util.TimeZone.getDefault().rawOffset.toLong()),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) JishiColors.AccentSolid else JishiColors.TextPrimary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                formatDuration(seg.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = JishiColors.TextWeak,
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(start = 46.dp).height(1.dp).background(JishiColors.Divider))
                    }
                }
            }
        }
        // 当前播放位置（列表上方不再需要拖动交互）
        Text(
            "当前播放 " + TimelineMath.formatClock(
                if (state.playbackAbsMs > 0) state.playbackAbsMs else state.cursorMs,
                java.util.TimeZone.getDefault().rawOffset.toLong(),
            ),
            style = MaterialTheme.typography.labelSmall, color = JishiColors.TextSecondary,
        )

        // 主控 5 键
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ControlButton(R.drawable.ic_prev, "上一段", 48, onTap = { onPrev() })
            ControlButton(R.drawable.ic_back10, "-10 秒", 48, holdable = true,
                onTap = { onStep(-10_000L) },
                onHold = { held -> onHoldPreview(-1, held) },
                onRelease = { onReleaseHold() })
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(JishiColors.AccentSolid).clickable { onTogglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            ControlButton(R.drawable.ic_fwd10, "+10 秒", 48, holdable = true,
                onTap = { onStep(10_000L) },
                onHold = { held -> onHoldPreview(1, held) },
                onRelease = { onReleaseHold() })
            ControlButton(R.drawable.ic_next, "下一段", 48, onTap = { onNext() })
        }
        Text(
            "长按「10 秒」可加速：1 分钟/秒 → 5 分钟/秒；松手才定位。",
            style = MaterialTheme.typography.labelSmall, color = JishiColors.TextWeak,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TimelineTrack(
    segments: List<RecordSegment>,
    dayStartMs: Long,
    cursorMs: Long,
    zoom: ZoomLevel,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    onZoomIn: () -> Unit,
    onDragStateChange: (Boolean) -> Unit,
) {
    var widthPx by remember { mutableStateOf(1f) }
    val dayMs = PlayerViewModel.DAY_MS
    // 拖动期间冻结窗口起点：否则 windowStart 会随 cursorMs 一起移动，画布自己滑动，
    // 游标与手指位置对不上（甲方反馈"时间轴滑动逻辑有问题"）。
    var frozenStart by remember(zoom) { mutableStateOf<Long?>(null) }
    val liveStart = TimelineMath.windowStart(cursorMs, dayStartMs, zoom.visibleMs)
    val windowStart = frozenStart ?: liveStart
    fun timeAt(x: Float): Long = windowStart + ((x / widthPx) * zoom.visibleMs).toLong()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .pointerInput(zoom, segments.size) {
                detectTapGestures(
                    onTap = { offset -> onPreview(TimelineMath.snap(timeAt(offset.x), zoom.snapMs)); onCommit() },
                    onDoubleTap = { onZoomIn() },
                )
            }
            .pointerInput(zoom, segments.size) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        // 冻结当前窗口，拖动全程用同一映射
                        frozenStart = TimelineMath.windowStart(cursorMs, dayStartMs, zoom.visibleMs)
                        onDragStateChange(true)
                    },
                    onDragEnd = {
                        frozenStart = null
                        onDragStateChange(false)
                        onCommit()
                    },
                    onDragCancel = {
                        frozenStart = null
                        onDragStateChange(false)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()   // 自己消费拖动，避免被父级手势抢走
                        onPreview(TimelineMath.snap(timeAt(change.position.x), zoom.snapMs))
                    },
                )
            },
    ) {
        widthPx = size.width
        val h = size.height
        // 设计稿：上方刻度、中间连续录像带、下方大游标
        val trackH = 30f
        val trackTop = 30f

        // 1) 刻度线（按当前缩放档选一个合适的间隔）
        val tickStepMs = when {
            zoom.visibleMs >= 12L * 3600 * 1000 -> 4L * 3600 * 1000   // 整天：每 4 小时
            zoom.visibleMs >= 4L * 3600 * 1000 -> 3600 * 1000          // 6 小时：每 1 小时
            zoom.visibleMs >= 40L * 60 * 1000 -> 10L * 60 * 1000       // 1 小时：每 10 分钟
            else -> 60L * 1000                                          // 更细：每 1 分钟
        }
        val firstTick = ((windowStart + tickStepMs - 1) / tickStepMs) * tickStepMs
        var t = firstTick
        var guard = 0
        while (t <= windowStart + zoom.visibleMs && guard++ < 64) {
            val x = ((t - windowStart).toFloat() / zoom.visibleMs) * size.width
            drawLine(
                color = Color(0x330F172A),
                start = Offset(x, trackTop - 8f),
                end = Offset(x, trackTop),
                strokeWidth = 1.5f,
            )
            t += tickStepMs
        }

        // 2) 轨道底色
        drawRoundRect(
            color = JishiColors.TimelineTrack,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
        )

        // 3) 连续录像带：相邻段间隔 <= 90 秒视为同一段，合并后画一条，视觉上不再碎
        val merged = ArrayList<Pair<Long, Long>>()
        for (seg in segments.sortedBy { it.startMs }) {
            val last = merged.lastOrNull()
            if (last != null && seg.startMs - last.second <= 90_000L) {
                merged[merged.size - 1] = last.first to maxOf(last.second, seg.endMs)
            } else {
                merged.add(seg.startMs to seg.endMs)
            }
        }
        for ((startMs, endMs) in merged) {
            if (endMs < windowStart || startMs > windowStart + zoom.visibleMs) continue
            val x1 = ((startMs - windowStart).toFloat() / zoom.visibleMs) * size.width
            val x2 = ((endMs - windowStart).toFloat() / zoom.visibleMs) * size.width
            val left = x1.coerceIn(0f, size.width)
            val right = x2.coerceIn(0f, size.width)
            val minVisible = 3.5f
            val drawLeft = left.coerceAtMost(size.width - minVisible).coerceAtLeast(0f)
            val drawWidth = (right - left).coerceAtLeast(minVisible).coerceAtMost(size.width - drawLeft)
            // 有录像的时段用品牌蓝带标识
            drawRoundRect(
                color = JishiColors.AccentSolid,
                topLeft = Offset(drawLeft, trackTop),
                size = Size(drawWidth, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
            )
        }

        // 4) 游标：竖线 + 光晕 + 圆点
        val cursorX = (((cursorMs - windowStart).toFloat() / zoom.visibleMs) * size.width).coerceIn(0f, size.width)
        drawCircle(
            color = Color(0x261D4ED8),
            radius = 26f,
            center = Offset(cursorX, trackTop + trackH / 2f),
        )
        drawLine(
            color = Color(0xFF12161D),
            start = Offset(cursorX, trackTop - 14f),
            end = Offset(cursorX, trackTop + trackH + 14f),
            strokeWidth = 5f,
        )
        drawCircle(color = JishiColors.AccentSolid, radius = 16f, center = Offset(cursorX, trackTop + trackH / 2f))
        drawCircle(color = Color.White, radius = 7f, center = Offset(cursorX, trackTop + trackH / 2f))
    }
}

@Composable
private fun ModeTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) JishiColors.AccentSolid else JishiColors.Card,
        shape = RoundedCornerShape(17.dp),
        modifier = modifier.height(56.dp).clickable { onClick() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (selected) Color.White else JishiColors.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CircleButton(iconRes: Int, desc: String, onClick: () -> Unit) {
    Surface(color = Color(0xCCFFFFFF), shape = CircleShape, modifier = Modifier.size(48.dp).clickable { onClick() }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(iconRes), contentDescription = desc, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SmallIconButton(iconRes: Int, desc: String, onClick: () -> Unit) {
    // 热区下限 48dp（FR-7.2）；视觉上仍为小圆钮，用透明外框补足点击区域
    Box(Modifier.size(48.dp).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Surface(color = Color(0xFFF2F5FA), shape = CircleShape, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(iconRes), contentDescription = desc, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ControlButton(
    iconRes: Int,
    label: String,
    sizeDp: Int,
    holdable: Boolean = false,
    onTap: () -> Unit = {},
    onHold: (Long) -> Unit = {},
    onRelease: () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val mod = Modifier.size(sizeDp.dp).clip(CircleShape).background(JishiColors.Card)
        val gestureMod = if (!holdable) {
            mod.clickable { onTap() }
        } else {
            mod.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val started = System.currentTimeMillis()
                    var lastEmit = 0L
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        pressed = event.changes.any { it.pressed }
                        val held = System.currentTimeMillis() - started
                        if (held >= 400 && abs(held - lastEmit) >= 100) {
                            lastEmit = held
                            onHold(held)
                        }
                    }
                    val total = System.currentTimeMillis() - started
                    if (total < 400) onTap() else onRelease()
                }
            }
        }
        Box(gestureMod, contentAlignment = Alignment.Center) {
            Icon(painterResource(iconRes), contentDescription = label, modifier = Modifier.size((sizeDp / 2).dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = JishiColors.TextSecondary)
    }
}

@Composable
private fun MorePanelContent(
    isReplay: Boolean,
    speed: Float,
    autoTour: Boolean,
    onSpeed: (Float) -> Unit,
    onAutoTour: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("更多", style = MaterialTheme.typography.titleMedium)
        if (isReplay) Text("播放倍速", style = MaterialTheme.typography.bodySmall)
        if (isReplay) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0.5f, 1f, 2f, 4f).forEach { s ->
                Surface(
                    color = if (abs(s - speed) < 0.01f) JishiColors.AccentSolid else Color(0xFFF2F5FA),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.heightIn(min = 48.dp).clickable { onSpeed(s) },
                ) {
                    Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text(
                            formatSpeed(s),
                            color = if (abs(s - speed) < 0.01f) Color.White else JishiColors.TextSecondary,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动轮巡（仅单画面）", style = MaterialTheme.typography.bodyMedium)
                Text("间隔不小于 10 秒，默认关闭", style = MaterialTheme.typography.labelSmall, color = JishiColors.TextWeak)
            }
            Switch(checked = autoTour, onCheckedChange = onAutoTour)
        }
        Surface(
            color = Color(0xFFF2F5FA),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onCheckUpdate() },
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text("检查更新（发布包验签后安装）", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "版本 " + com.jishi.monitor.BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelSmall, color = JishiColors.TextWeak,
        )
    }
}

@Composable
private fun DateSheetContent(
    currentDayStart: Long,
    recordDays: Set<String>,
    onMonthChanged: (Long) -> Unit,
    onPick: (Long) -> Unit,
) {
    var monthAnchor by remember { mutableStateOf(currentDayStart) }
    LaunchedEffect(monthAnchor) { onMonthChanged(monthAnchor) }
    val cal = Calendar.getInstance().apply { timeInMillis = monthAnchor }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = Calendar.getInstance().apply {
        timeInMillis = monthAnchor
        set(Calendar.DAY_OF_MONTH, 1)
    }.get(Calendar.DAY_OF_WEEK) - 1

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((month + 1).toString() + " 月", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { monthAnchor -= 32L * 24 * 3600 * 1000 }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("‹ 上月", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { monthAnchor += 32L * 24 * 3600 * 1000 }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("下月 ›", style = MaterialTheme.typography.bodySmall) }
        }
        Text("有录像的日期以绿点标记；选择后切换到该天。", style = MaterialTheme.typography.labelSmall, color = JishiColors.TextWeak)
        val cellMod = Modifier.size(48.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var day = 1
            val rows = ((firstDayOfWeek + daysInMonth) + 6) / 7
            repeat(rows) { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(7) { c ->
                        val index = r * 7 + c
                        if (index < firstDayOfWeek || day > daysInMonth) {
                            Box(cellMod)
                        } else {
                            val d = day
                            val target = Calendar.getInstance().apply {
                                timeInMillis = monthAnchor
                                set(Calendar.YEAR, year)
                                set(Calendar.MONTH, month)
                                set(Calendar.DAY_OF_MONTH, d)
                                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            val key = String.format("%04d%02d%02d", year, month + 1, d)
                            val hasRecord = recordDays.contains(key)
                            Box(
                                cellMod.clip(CircleShape).background(if (target == currentDayStart) JishiColors.AccentSolid else Color(0xFFF2F5FA)).clickable { onPick(target) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    d.toString(),
                                    color = if (target == currentDayStart) Color.White else JishiColors.TextPrimary,
                                    fontSize = 13.sp,
                                )
                                if (hasRecord) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).size(7.dp)
                                            .clip(CircleShape).background(JishiColors.Online),
                                    )
                                }
                            }
                            day++
                        }
                    }
                }
            }
        }
    }
}
