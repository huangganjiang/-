package com.jishi.monitor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jishi.monitor.core.Logx
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地加密存储（FR-9.2 / FR-10.3~10.5 / AC-25）。
 *
 * 安全要点（按安全审计意见加固）：
 * - **每个逻辑别名使用独立密钥**（jishi_key_<alias>），避免共用一把密钥互相牵连；
 * - **以别名作为 AAD**，密文被搬到别的键名存放时解密必然失败；
 * - KeyStore 密钥不可导出，应用卸载即销毁 → 卸载重装必须重新输入查看密码（FR-10.5）。
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun keyName(alias: String) = KEY_PREFIX + alias

    private fun orCreateKey(alias: String): SecretKey {
        val name = keyName(alias)
        val ks = keyStore()
        (ks.getEntry(name, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    fun put(alias: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, orCreateKey(alias))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(alias, packed).apply()
    }

    fun get(alias: String): String? {
        val packed = prefs.getString(alias, null) ?: return null
        return try {
            val parts = packed.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, orCreateKey(alias), GCMParameterSpec(128, iv))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            Logx.w(TAG, "读取加密项失败，按未激活处理")
            null
        }
    }

    fun remove(alias: String) = prefs.edit().remove(alias).apply()

    fun clearAll() = prefs.edit().clear().apply()

    fun contains(alias: String): Boolean = prefs.contains(alias)

    companion object {
        private const val TAG = "SecureStore"
        private const val PREFS = "jishi_secure"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_PREFIX = "jishi_key_"
        const val ALIAS_CREDENTIALS = "credentials"
        const val ALIAS_TOKEN = "token"
    }
}
