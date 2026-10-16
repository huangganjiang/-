package com.jishi.monitor.data

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 凭据包解密（FR-10.1 / FR-10.3）。
 * AES-256-GCM，密钥由「查看密码」经 PBKDF2-HMAC-SHA256 派生（迭代次数随包分发）。
 * 口令与派生密钥只在内存中存在，不落盘（FR-10.3 / AC-25）。
 */
object CredentialCrypto {
    const val GCM_TAG_BITS = 128

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int = 256): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyBits)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** 解密失败（口令错误）会抛 AEADBadTagException / GeneralSecurityException。 */
    fun decrypt(key: ByteArray, iv: ByteArray, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plain)
    }

    fun sha256Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
