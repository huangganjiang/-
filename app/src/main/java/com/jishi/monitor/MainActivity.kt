package com.jishi.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jishi.monitor.data.model.Channel
import com.jishi.monitor.core.Logx
import com.jishi.monitor.ui.activate.ActivateScreen
import com.jishi.monitor.ui.activate.ActivateViewModel
import com.jishi.monitor.ui.home.HomeScreen
import com.jishi.monitor.ui.home.HomeViewModel
import com.jishi.monitor.ui.player.PlayerScreen
import com.jishi.monitor.ui.player.PlayerViewModel
import com.jishi.monitor.ui.theme.JishiColors
import com.jishi.monitor.ui.theme.JishiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as JishiApp).container
        // 全面屏/刘海/灵动岛适配：内容自行避开安全区，同时允许画面延伸到挖孔区
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            JishiTheme { AppRoot(container) }
        }
    }
}

private sealed interface Route {
    data object Loading : Route
    data object Activate : Route
    data object Home : Route
    data class Play(val channel: Channel) : Route
}

@Composable
private fun AppRoot(container: AppContainer) {
    var route by remember { mutableStateOf<Route>(Route.Loading) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val restored = container.api.restoreSession()
        // 加载远端配置：应用内更新地址（updateUrl）等来自这里。
        // 此前从未调用 load()，导致 updateUrl 恒为空 → 检查更新永远"未配置地址"（产品核查 P0-2）
        if (restored) {
            runCatching { container.config.load() }
                .onFailure { Logx.w("Main", "远端配置加载失败: " + it.javaClass.simpleName) }
        }
        // 用户明确：这是"随看随走"的工具 App，不需要首装引导/后台保活提示页。
        route = if (!restored) Route.Activate else Route.Home
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val lifeState by lifecycleOwner.lifecycle.currentStateAsState()
    val active = lifeState.isAtLeast(Lifecycle.State.RESUMED)

    val homeVm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val activateVm: ActivateViewModel = viewModel(factory = viewModelFactory { initializer { ActivateViewModel(container) } })
    val playerVm: PlayerViewModel = viewModel(factory = viewModelFactory { initializer { PlayerViewModel(container) } })

    // 播放页前后台：切后台停拉流，回前台自动重连
    LaunchedEffect(active, route) {
        if (route is Route.Play) {
            if (active) playerVm.onForeground() else playerVm.onBackground()
        }
    }

    when (val r = route) {
        Route.Loading -> Box(
            Modifier.fillMaxSize().background(JishiColors.backgroundGradient),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        Route.Activate -> {
            val st by activateVm.state.collectAsStateWithLifecycle()
            ActivateScreen(activateVm)
            LaunchedEffect(st.activated) {
                if (st.activated) route = Route.Home
            }
        }

        Route.Home -> HomeScreen(
            viewModel = homeVm,
            active = active,
            onOpenChannel = { ch ->
                playerVm.open(ch)
                route = Route.Play(ch)
            },
        )

        is Route.Play -> PlayerScreen(
            viewModel = playerVm,
            onBack = { route = Route.Home },
        )
    }
}
