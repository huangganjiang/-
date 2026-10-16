package com.jishi.monitor.ui.home

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jishi.monitor.core.Logx
import com.jishi.monitor.data.model.Channel
import com.jishi.monitor.ui.theme.JishiColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 首页：轮播 + 列表（FR-2、AC-3、AC-22 数量以接口为准）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    active: Boolean,
    onOpenChannel: (Channel) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels = state.channels
    val scope = rememberCoroutineScope()
    // 循环分页：page 取模映射到通道，7→1 是"继续向前"而不是原路缩回。
    // 注意：曾用 Int.MAX_VALUE 做"真无限"，但它在初始仅 1 页时会让 Pager 状态不一致，
    // 导致手势拖拽失效（甲方实测"滑不动"）——改为有界循环（足够长，且不再溢出）。
    val pagerState = rememberPagerState(initialPage = PAGER_START) {
        if (channels.isEmpty()) 1 else channels.size * PAGER_CYCLES
    }
    val pageCount = channels.size
    val currentIndex = if (pageCount == 0) 0 else pagerState.currentPage % pageCount
    var visibleIds by remember { mutableStateOf<List<String>>(emptyList()) }

    var toast by remember { mutableStateOf<String?>(null) }

    // 前台恢复即刷新通道（激活完成、从后台回来、从播放页返回都会走到这里）
    LaunchedEffect(active) {
        if (active) {
            viewModel.loadLastChannel()
            viewModel.loadAutoTour()
            viewModel.refreshChannels()
        } else {
            viewModel.stopCarouselLive()
        }
    }

    // FR-0.4：回到上次观看的通道（轮播定位 + 预取快照）
    LaunchedEffect(channels.size, viewModel.lastChannelId.value) {
        if (channels.isEmpty()) return@LaunchedEffect
        val last = viewModel.lastChannelId.value
        val idx = if (last == null) 0 else channels.indexOfFirst { it.id == last }.coerceAtLeast(0)
        // 对齐到无限空间里最近的、模数等于 idx 的页
        val base = PAGER_START - (PAGER_START % channels.size)
        val target = base + idx
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }

    LaunchedEffect(active, channels.size) {
        if (!active || channels.isEmpty()) {
            viewModel.stopSnapshotLoops()
            return@LaunchedEffect
        }
        viewModel.startSnapshotLoops(
            carouselId = { channels.getOrNull(pagerState.currentPage % channels.size)?.id },
            visibleIds = { visibleIds },
        )
    }
    // 自动切换的计时由下面循环里的 isScrollInProgress 直接判断（用户拖动时不抢）
    // 首页轮播自动切换（甲方要求）：默认每 10 秒切一路，用户手动操作后重新计时
    LaunchedEffect(active, channels.size) {
        if (!active || channels.size <= 1) return@LaunchedEffect
        Logx.i("Home", "自动轮播启动，间隔 " + (CAROUSEL_AUTO_MS / 1000) + " 秒")
        while (true) {
            kotlinx.coroutines.delay(CAROUSEL_AUTO_MS)
            // 用户正在拖动时不抢，等下一轮
            if (pagerState.isScrollInProgress) continue
            val from = pagerState.currentPage
            try {
                pagerState.animateScrollToPage(from + 1)
                Logx.i("Home", "自动轮播 " + from + " → " + (from + 1))
            } catch (t: Throwable) {
                Logx.w("Home", "自动轮播失败: " + t.javaClass.simpleName)
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) { kotlinx.coroutines.delay(1800); toast = null }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(JishiColors.backgroundGradient)
            // 全面屏/刘海/手势导航适配：内容避开状态栏与手势条（已开启 setDecorFitsSystemWindows(false)）
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))

            // 标题行：长按标题 5 秒进入隐藏设置（FR-1.1）
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("即视", style = MaterialTheme.typography.headlineMedium)
                        // 录制红点：品牌里的"直播"意象，垂直居中
                        Box(
                            Modifier
                                .padding(horizontal = 6.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(JishiColors.Live),
                        )
                        Text(
                            "Live",
                            style = MaterialTheme.typography.headlineMedium,
                            color = JishiColors.AccentSolid,
                        )
                    }
                    Text(
                        subtitleText(channels),
                        style = MaterialTheme.typography.labelSmall,
                        color = JishiColors.TextSecondary,
                    )
                }
                StatusPill(channels)
                IconButton(onClick = { viewModel.refreshChannels() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = JishiColors.TextSecondary)
                }
            }

            Spacer(Modifier.height(10.dp))

            if (channels.isEmpty()) {
                EmptyHint(state.loading, state.error)
            } else {
                // 轮播
                Surface(color = JishiColors.Card, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .shadow(6.dp, RoundedCornerShape(22.dp)),
            ) {
                        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                            val ch = channels.getOrNull(if (pageCount == 0) 0 else page % pageCount)
                            val isCurrent = page == pagerState.currentPage
                            CarouselPage(
                                channel = ch,
                                bitmap = ch?.let { state.snapshots[it.id] },
                                latestRecordingMs = ch?.let { state.latestRecording[it.id] },
                                // 甲方要求：首页轮播回归"快照 + 自动切换"，不再拉真直播
                                // （首页同时开流会长时间停在"连接中"，且与播放页争抢带宽/解码器）
                                attachLive = CAROUSEL_LIVE_ENABLED && isCurrent && ch?.online == true,
                                liveOn = CAROUSEL_LIVE_ENABLED && ch != null && viewModel.isCarouselLiveOn(ch.id),
                                onNeedRenderView = { ctx -> viewModel.carouselRenderView(ctx) },
                                onSurfaceReady = { view -> viewModel.switchCarouselLive(ch?.id, view) },
                                onOpen = {
                                    viewModel.stopCarouselLive()
                                    ch?.let { if (it.online) onOpenChannel(it) else toast = "该通道当前离线" }
                                },
                            )
                        }
                        // 甲方要求：不放左右箭头按钮，轮播自动切换 + 手动滑动即可
                    }
                }

                Spacer(Modifier.height(8.dp))
                PageIndicator(channels.size, currentIndex)

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("全部摄像头", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("共 " + channels.size + " 路", style = MaterialTheme.typography.labelSmall, color = JishiColors.TextWeak)
                }
                Spacer(Modifier.height(6.dp))

                // 两列设备卡片流（设计稿：米家风格卡片）
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        count = channels.size,
                        key = { i -> channels[i].id },
                    ) { i ->
                        val ch = channels[i]
                        ChannelCard(
                            channel = ch,
                            bitmap = state.snapshots[ch.id],
                            latestRecordingMs = state.latestRecording[ch.id],
                            playing = ch.id == channels.getOrNull(currentIndex)?.id,
                            onClick = {
                                if (ch.online) onOpenChannel(ch) else toast = "该通道当前离线"
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        toast?.let {
            Surface(
                color = Color(0xCC0F172A),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            ) {
                Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 13.sp)
            }
        }
    }

    // 列表缩略图：轮转刷新全部通道（每 700ms 一路 → 全量一轮 ≈ 5 秒，带宽仍 ≤1.5 Mbps），
    // 避免固定只刷前 5 行导致第 6/7 路永久停在"加载中"（产品评审 Z-谎报③）。
    // 轮播直播：仅在首页前台时运行；切后台立即停，回前台续播（NFR-8 / 评审 B2）
    LaunchedEffect(active) {
        if (active) viewModel.resumeCarouselLive() else viewModel.stopCarouselLive()
    }

    // 离开首页（进单画面/回放）立即停轮播直播，避免两路 HEVC 并发解码（评审 N1）
    DisposableEffect(Unit) {
        onDispose { viewModel.stopCarouselLive() }
    }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var cursor = 0
        while (true) {
            // 必须读实时状态：LaunchedEffect 捕获的 state 是启动时的快照，会永远判空并重复抓取
            val snap = viewModel.state.value
            val list = snap.channels
            if (list.isNotEmpty()) {
                val ch = list[cursor % list.size]
                if (snap.snapshots[ch.id] == null) viewModel.fetchSnapshot(ch.id)
                cursor++
            }
            kotlinx.coroutines.delay(600)
        }
    }

    LaunchedEffect(channels) {
        visibleIds = channels.map { it.id }
    }
}

private fun subtitleText(channels: List<Channel>): String {
    // 在线数已由右上胶囊展示，这里避免重复（UI 详检 P1）
    return "共 " + channels.size + " 路画面 · 点击查看实时"
}

@Composable
private fun StatusPill(channels: List<Channel>) {
    val online = channels.count { it.online }
    Surface(color = JishiColors.Card, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (online > 0) JishiColors.Online else JishiColors.Offline, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(online.toString() + "/" + channels.size + " 在线", fontSize = 12.sp, color = JishiColors.TextSecondary)
        }
    }
}

@Composable
private fun CarouselPage(
    channel: Channel?,
    bitmap: android.graphics.Bitmap?,
    latestRecordingMs: Long?,
    attachLive: Boolean,
    liveOn: Boolean,
    onNeedRenderView: (android.content.Context) -> android.view.View,
    onSurfaceReady: (android.view.View) -> Unit,
    onOpen: () -> Unit,
) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).clickable { onOpen() }) {
        // 底层：当前页挂直播 Surface（首屏即可出真流）
        if (attachLive) {
            AndroidView(
                factory = { ctx -> onNeedRenderView(ctx) },
                update = { v -> onSurfaceReady(v) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 直播未出画（或非当前页/离线）时用快照盖住，避免黑屏
        if (!liveOn) {
            SnapshotImage(bitmap, channel?.online ?: true, Modifier.fillMaxSize())
        }
        // 正在连接直播：给加载动画，而不是静默停在快照上（甲方要求）
        if (attachLive && !liveOn && channel?.online == true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("正在连接直播…", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        // 顶部/底部渐隐，保证白字在亮部也清晰（UI 详检 ①）
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
        // 角标：只有真直播时才标"直播"；离线通道不标（避免误导，UI 详检 ②）
        if (liveOn) {
            // 红点呼吸：只在真直播时闪，提示"这是活动画面"
            val blink = rememberInfiniteTransition(label = "liveDot")
            val dotAlpha by blink.animateFloat(
                initialValue = 1f,
                targetValue = 0.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "liveDotAlpha",
            )
            Surface(color = Color(0xCC000000), shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(10.dp).align(Alignment.TopStart)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(JishiColors.Live.copy(alpha = dotAlpha), CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text("直播", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        // 左下名称
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(channel?.displayName ?: "", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(if (channel?.online == true) "在线" else "离线")
                    append("   ·   最近录像 ")
                    append(latestRecordingMs?.let { formatLatestRecording(it) } ?: "--")
                },
                color = Color(0xFFEDF3FF), fontSize = 12.sp,
            )
        }
    }
}

/** 最新录像时间：今天/昨天 HH:mm，更早显示 MM-dd HH:mm。 */
private fun formatLatestRecording(endMs: Long): String {
    val zone = java.util.TimeZone.getDefault()
    val todayStart = com.jishi.monitor.ui.player.PlayerViewModel.dayStartOf(System.currentTimeMillis(), zone)
    val dayPart = when {
        endMs >= todayStart -> "今天"
        endMs >= todayStart - 86_400_000L -> "昨天"
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA).format(java.util.Date(endMs))
    }
    val clock = com.jishi.monitor.core.TimelineMath.formatClock(endMs, zone.rawOffset.toLong())
    return dayPart + " " + clock.substring(0, 5)
}

/** 设备卡片（两列网格）：快照 + 名称 + 状态 + 最近录像。 */
@Composable
private fun ChannelCard(
    channel: Channel,
    bitmap: android.graphics.Bitmap?,
    latestRecordingMs: Long?,
    playing: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(JishiColors.Card)
            .clickable { onClick() }
            .padding(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFEDF2F9)),
            contentAlignment = Alignment.Center,
        ) {
            SnapshotImage(bitmap, channel.online, Modifier.fillMaxSize())
            if (playing) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xB30F141C))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("轮播中", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (channel.displayName.isBlank()) channel.id else channel.displayName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (channel.online) JishiColors.Online else JishiColors.TextWeak),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (channel.online) "在线" else "离线",
                style = MaterialTheme.typography.labelSmall,
                color = if (channel.online) JishiColors.OnlineText else JishiColors.TextMuted,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                latestRecordingMs?.let { formatLatestRecording(it) } ?: "--",
                style = MaterialTheme.typography.labelSmall,
                color = JishiColors.TextWeak,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    bitmap: android.graphics.Bitmap?,
    latestRecordingMs: Long?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEDF2F9)),
            contentAlignment = Alignment.Center,
        ) {
            SnapshotImage(bitmap, channel.online, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (channel.displayName.isBlank()) channel.id else channel.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            // 固定两段，避免四段信息过长把行撑爆（UI 详检 ⑤）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (channel.online) "在线" else "离线",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (channel.online) JishiColors.OnlineText else JishiColors.TextMuted,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "最近录像 " + (latestRecordingMs?.let { formatLatestRecording(it) } ?: "--"),
                    style = MaterialTheme.typography.labelSmall,
                    color = JishiColors.TextWeak,
                )
            }
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = JishiColors.TextWeak)
    }
}

@Composable
private fun SnapshotImage(bitmap: android.graphics.Bitmap?, online: Boolean, modifier: Modifier) {
    if (bitmap == null) {
        Box(modifier.background(Color(0xFFEDF2F9)), contentAlignment = Alignment.Center) {
            Text("加载中", fontSize = 12.sp, color = JishiColors.TextWeak)
        }
        return
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        colorFilter = if (online) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
    )
}

@Composable
private fun RoundArrow(modifier: Modifier, left: Boolean, onClick: () -> Unit) {
    Surface(color = Color(0xE6FFFFFF), shape = CircleShape, modifier = modifier.size(48.dp).clickable { onClick() }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (left) Icons.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowRight,
                contentDescription = if (left) "上一个" else "下一个",
                tint = JishiColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun PageIndicator(total: Int, current: Int) {
    if (total <= 1) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (total > 9) {
            Text((current + 1).toString() + " / " + total, style = MaterialTheme.typography.labelSmall, color = JishiColors.TextSecondary)
        } else {
            repeat(total) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(5.dp)
                        .width(if (i == current) 16.dp else 5.dp)
                        .clip(CircleShape)
                        .background(if (i == current) JishiColors.AccentStart else Color(0xFFD3DCEA)),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(loading: Boolean, error: String?) {
    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
        Text(
            when {
                loading -> "正在加载…"
                error != null -> "暂时连不上，正在重试…"
                else -> "还没有摄像头"
            },
            color = JishiColors.TextSecondary,
        )
    }
}

/**
 * 长按 5 秒触发（FR-1.1 隐藏入口）。
 * 必须在 5 秒内持续按住；提前抬手一律不触发（此前实现"抬手即触发"导致单击就进设置，已修）。
 */

/** 首页轮播是否拉真直播。按甲方要求当前为 false：轮播用快照 + 自动切换。 */
private const val CAROUSEL_LIVE_ENABLED = false

/** 轮播自动切换间隔（毫秒）。 */
private const val CAROUSEL_AUTO_MS = 10_000L

/** 循环分页：总页数 = 通道数 × 该倍数（够用很久，又不会像 Int.MAX_VALUE 那样拖垮手势）。 */
private const val PAGER_CYCLES = 200

/** 分页起点（对应中间那一段，左右都留出充足空间）。 */
private const val PAGER_START = 700
