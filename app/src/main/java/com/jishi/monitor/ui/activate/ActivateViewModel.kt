package com.jishi.monitor.ui.activate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jishi.monitor.AppContainer
import com.jishi.monitor.core.Logx
import com.jishi.monitor.data.CredentialPayload
import com.jishi.monitor.data.net.LoginOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ActivateUiState(
    val checking: Boolean = false,
    val lockedUntilMs: Long = 0L,
    val error: String? = null,
    val failCount: Int = 0,
    val notConfigured: Boolean = false,
    val activated: Boolean = false,
)

/**
 * 登录页：填写「服务端地址 + 账号 + 密码」直接登录。
 *
 * 说明：该版本不内置任何服务端地址与账号口令（便于源码公开/上传仓库），
 * 由使用者自行填写；登录成功后凭据用 AndroidKeyStore 包裹保存，之后免输入。
 * 连续失败 5 次锁定 10 分钟，计数本地持久化（卸载才清零）。
 */
class ActivateViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ActivateUiState())
    val state: StateFlow<ActivateUiState> = _state

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                lockedUntilMs = container.prefs.lockUntilMs(),
                failCount = container.prefs.failCount(),
            )
        }
    }

    /** 服务端地址容错：允许只填 主机:端口，自动补 http://。 */
    private fun normalizeHost(raw: String): String {
        var h = raw.trim().removeSuffix("/")
        if (h.isEmpty()) return ""
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://" + h
        return h
    }

    fun submit(host: String, username: String, password: String) {
        val h = normalizeHost(host)
        val u = username.trim()
        if (h.isEmpty() || u.isEmpty() || password.isEmpty() || _state.value.checking) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val lockedUntil = container.prefs.lockUntilMs()
            if (now < lockedUntil) {
                _state.value = _state.value.copy(error = "尝试过于频繁，请稍后再试", lockedUntilMs = lockedUntil)
                return@launch
            }
            _state.value = _state.value.copy(checking = true, error = null)

            val payload = CredentialPayload(host = h, lanHost = "", username = u, password = password)
            when (val login = container.api.activateWith(payload)) {
                is LoginOutcome.Ok -> {
                    container.prefs.setFailCount(0)
                    container.prefs.setLockUntilMs(0L)
                    Logx.i(TAG, "登录成功，进入首页")
                    _state.value = _state.value.copy(checking = false, activated = true, error = null)
                }
                is LoginOutcome.Fail -> {
                    val next = container.prefs.failCount() + 1
                    container.prefs.setFailCount(next)
                    val msg = when (login.code) {
                        401 -> "账号或密码不正确"
                        0 -> "连不上服务器，请检查地址与网络"
                        else -> "登录失败（" + login.code + "）"
                    }
                    if (next >= MAX_FAILS) {
                        val until = System.currentTimeMillis() + LOCK_MS
                        container.prefs.setLockUntilMs(until)
                        _state.value = _state.value.copy(
                            checking = false, failCount = next, lockedUntilMs = until, error = msg,
                        )
                        Logx.w(TAG, "连续失败 " + next + " 次，锁定 10 分钟")
                    } else {
                        _state.value = _state.value.copy(checking = false, failCount = next, error = msg)
                    }
                    Logx.w(TAG, "登录失败 code=" + login.code)
                }
            }
        }
    }

    fun lockedRemainingMs(): Long = (_state.value.lockedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    companion object {
        private const val TAG = "Activate"
        const val MAX_FAILS = 5
        const val LOCK_MS = 10 * 60 * 1000L
    }
}
