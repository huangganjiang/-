package com.jishi.monitor.data.repo

import com.jishi.monitor.BuildConfig
import com.jishi.monitor.core.Logx
import com.jishi.monitor.data.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import okhttp3.Request

@Serializable
data class AppConfig(
    val version: Int = 1,
    val source: String = SRC_BUILTIN,
    val updateUrl: String = "",
    val tourIntervalSec: Int = 10,
) {
    companion object {
        const val SRC_BUILTIN = "内置兜底配置"
        const val SRC_REMOTE = "外置配置"
        const val SRC_MANUAL = "手动输入"
    }
}

/**
 * 外置配置（FR-0.5）。
 *
 * 约定：远端 /app/config.json 形如 {"payload":{...},"sig":"<Base64>"}，
 * sig = SHA256withRSA(payload 的原始字节)。校验失败或未配置公钥时
 * **一律回退安装包内置配置，不得采信**（HTTP 下防投毒）。
 */
class ConfigRepository(
    private val api: com.jishi.monitor.data.net.ApiClient,
    private val prefs: Prefs,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun load(): AppConfig {
        val builtin = AppConfig(version = 1, source = AppConfig.SRC_REMOTE, updateUrl = "", tourIntervalSec = 10)
        val pubKey = BuildConfig.CONFIG_PUBKEY
        if (pubKey.isBlank()) {
            Logx.i(TAG, "未配置配置签名公钥：外置配置一律不采信，使用内置兜底（FR-0.5）")
            return fallback("未配置签名公钥")
        }
        // 地址来自运行时（由解密凭据的主机推导），产物中不含明文地址（AC-25）
        val base = api.currentBaseUrl()
        if (base.isBlank()) return fallback("尚无可用服务地址")
        val url = base.trimEnd('/') + "/app/config.json"
        val text = try {
            val req = Request.Builder().url(url).get().build()
            api.client.newCall(req).execute().use { if (it.code in 200..299) it.body?.string() else null }
        } catch (t: Throwable) {
            Logx.i(TAG, "外置配置不可用: " + t.javaClass.simpleName)
            null
        }
        if (text.isNullOrBlank()) return fallback("外置配置不可用")
        val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return fallback("外置配置不是 JSON 对象")
        val sig = (obj["sig"] as? JsonPrimitive)?.contentOrNull
        val payloadEl = obj["payload"]
        if (sig.isNullOrBlank() || payloadEl == null) return fallback("外置配置缺少 payload/sig")
        val payloadBytes = payloadEl.toString().toByteArray(Charsets.UTF_8)
        if (!verify(pubKey, payloadBytes, sig)) {
            Logx.e(TAG, "外置配置验签失败：拒绝采信，回退内置（AC-18③）")
            return fallback("验签失败")
        }
        val payload = runCatching { json.decodeFromString(RemotePayload.serializer(), payloadEl.toString()) }.getOrNull()
            ?: return fallback("payload 解析失败")
        val cfg = AppConfig(
            version = payload.version,
            source = AppConfig.SRC_REMOTE,
            updateUrl = payload.updateUrl,
            tourIntervalSec = payload.tourIntervalSec.coerceAtLeast(10),
        )
        prefs.setConfigSource(cfg.source)
        Logx.i(TAG, "外置配置验签通过 version=" + cfg.version)
        _current = cfg
        return cfg
    }

    @Volatile private var _current: AppConfig? = null
    val current: AppConfig get() = _current ?: AppConfig(source = prefs.let { "内置兜底配置" })

    private suspend fun fallback(reason: String): AppConfig {
        Logx.i(TAG, "使用内置兜底配置（" + reason + "）")
        val c = AppConfig(version = 1, source = AppConfig.SRC_BUILTIN, updateUrl = "", tourIntervalSec = 10)
        prefs.setConfigSource(c.source)
        _current = c
        return c
    }

    companion object {
        private const val TAG = "ConfigRepo"

        /** 供单元测试直接调用。 */
        fun verify(publicKeyBase64: String, payload: ByteArray, signatureBase64: String): Boolean = try {
            val der = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(key)
            sig.update(payload)
            sig.verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        } catch (t: Throwable) {
            false
        }
    }
}

@Serializable
private data class RemotePayload(
    val version: Int = 1,
    val updateUrl: String = "",
    val tourIntervalSec: Int = 10,
)
