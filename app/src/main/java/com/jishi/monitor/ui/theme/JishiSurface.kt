package com.jishi.monitor.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一的"毛玻璃/玻璃卡"视觉组件（米家风格）。
 *
 * 为什么不用真实 backdrop-blur：Compose 没有原生背景模糊，第三方 Haze 需要额外依赖；
 * 而画面是 SurfaceView（独立图层）本身也无法被 Compose 模糊。
 * 这里用「高不透明度底色 + 1dp 高光描边 + 柔和投影 + 顶部渐变高光」做出玻璃质感，
 * 视觉接近且零依赖、零掉帧风险；API 31+ 的设备上，卡片底色会带轻微通透感。
 */
object Glass {

    /** 浅色底上的玻璃卡（信息卡、时间轴卡、列表卡）。 */
    @Composable
    fun Card(
        modifier: Modifier = Modifier,
        corner: Dp = 26.dp,
        elevation: Dp = 10.dp,
        tint: Color = Color(0xF2FFFFFF),
        contentPadding: PaddingValues = PaddingValues(0.dp),
        content: @Composable () -> Unit,
    ) {
        val shape = RoundedCornerShape(corner)
        Box(
            modifier
                .shadow(elevation, shape, clip = false, ambientColor = Color(0x1A0F172A), spotColor = Color(0x1F0F172A))
                .clip(shape)
                .background(tint)
                .border(1.dp, Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x33FFFFFF))), shape)
                .padding(contentPadding),
        ) { content() }
    }

    /** 压在有画面的深色底上的玻璃胶囊/浮层。 */
    @Composable
    fun Overlay(
        modifier: Modifier = Modifier,
        corner: Dp = 30.dp,
        tint: Color = Color(0x8C0F141C),
        content: @Composable () -> Unit,
    ) {
        val shape = RoundedCornerShape(corner)
        Box(
            modifier
                .clip(shape)
                .background(tint)
                .border(1.dp, Color(0x40FFFFFF), shape),
        ) { content() }
    }

    /** 圆形玻璃按钮（用于画面外的控制排）。 */
    @Composable
    fun CircleButton(
        modifier: Modifier = Modifier,
        size: Dp = 68.dp,
        tint: Color = Color(0xFAFFFFFF),
        content: @Composable () -> Unit,
    ) {
        val shape = RoundedCornerShape(size / 2)
        Box(
            modifier
                .shadow(8.dp, shape, clip = false, ambientColor = Color(0x140F172A), spotColor = Color(0x1A0F172A))
                .clip(shape)
                .background(tint)
                .border(1.dp, Brush.verticalGradient(listOf(Color(0xE6FFFFFF), Color(0x40FFFFFF))), shape),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { content() }
    }
}
