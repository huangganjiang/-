package com.jishi.monitor.data

import android.content.Context
import android.util.Base64
import com.jishi.monitor.core.Logx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CredentialPayload(
    val host: String = "",
    val lanHost: String = "",
    val username: String = "",
    val password: String = "",
)

sealed interface UnlockResult {
    data class Success(val payload: CredentialPayload, val host: String, val lanHost: String) : UnlockResult
    /** 口令错误（AEAD 校验失败）。 */
    data object WrongPassword : UnlockResult
    /** 本安装包未内置凭据包（开发构建）。 */
    data object NotConfigured : UnlockResult
    data class Error(val reason: String) : UnlockResult
}

@Serializable
data class BlobJson(
    val v: Int = 0,
    val configured: Boolean = false,
    val kdf: String = "",
    val iter: Int = 0,
    val salt: String = "",
    val iv: String = "",
    val ct: String = "",
)

/**
 * 内嵌加密凭据包（assets/credential_blob.json，构建期由 generateCredentialBlob 生成）。
 * 包内只有 KDF 参数与密文，没有明文地址/账号/口令（AC-25）。
 */
class CredentialBlob(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    fun blob(): BlobJson? = try {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString(BlobJson.serializer(), text)
    } catch (t: Throwable) {
        Logx.w(TAG, "凭据包读取失败: " + t.javaClass.simpleName)
        null
    }

    fun isConfigured(): Boolean = blob()?.configured == true

    /** 用查看密码解密凭据包。口令不进日志、不落盘。 */
    fun unlock(viewPassword: String): UnlockResult {
        val b = blob() ?: return UnlockResult.Error("凭据包缺失")
        if (!b.configured) return UnlockResult.NotConfigured
        if (b.iter < 200_000) {
            // FR-10.1 要求迭代不少于 20 万次
            Logx.e(TAG, "凭据包 KDF 迭代次数不足: " + b.iter)
            return UnlockResult.Error("凭据包参数不合规")
        }
        return try {
            val salt = Base64.decode(b.salt, Base64.NO_WRAP)
            val iv = Base64.decode(b.iv, Base64.NO_WRAP)
            val ct = Base64.decode(b.ct, Base64.NO_WRAP)
            val key = CredentialCrypto.deriveKey(viewPassword.toCharArray(), salt, b.iter)
            val plain = CredentialCrypto.decrypt(key, iv, ct)
            val payload = json.decodeFromString(CredentialPayload.serializer(), String(plain, Charsets.UTF_8))
            UnlockResult.Success(payload, payload.host, payload.lanHost)
        } catch (t: Throwable) {
            // javax.crypto.AEADBadTagException 表示口令错误
            UnlockResult.WrongPassword
        }
    }

    companion object {
        private const val TAG = "CredentialBlob"
        const val ASSET = "credential_blob.json"
    }
}
