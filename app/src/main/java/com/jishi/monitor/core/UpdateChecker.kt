package com.jishi.monitor.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.jishi.monitor.BuildConfig
import com.jishi.monitor.data.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 应用内更新（FR-0.8 / AC-18③ / R-10）。
 * HTTP 无 TLS 下防投毒的最后一道闸：**必须校验 APK 包名 + 签名证书指纹（SHA-256）**，不匹配一律拒装。
 */
object UpdateChecker {

    sealed interface Result {
        data class Verified(val file: File) : Result
        data object NoUpdateUrl : Result
        data object DownloadFailed : Result
        data class Rejected(val reason: String) : Result
    }

    suspend fun downloadAndVerify(context: Context, api: ApiClient, updateUrl: String): Result = withContext(Dispatchers.IO) {
        if (updateUrl.isBlank()) return@withContext Result.NoUpdateUrl
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val apk = File(dir, "update.apk")
        if (apk.exists()) apk.delete()
        if (!api.downloadTo(updateUrl, apk)) return@withContext Result.DownloadFailed

        val pm = context.packageManager
        // API 28 起才有 GET_SIGNING_CERTIFICATES；低版本必须用 GET_SIGNATURES，
        // 否则 getPackageArchiveInfo 取不到信息，Android 8/9 上应用内更新会永久失败（lint InlinedApi）
        @Suppress("DEPRECATION")
        val flag = if (android.os.Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info: android.content.pm.PackageInfo = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flag)
        } catch (t: Throwable) {
            null
        } ?: return@withContext Result.Rejected("无法读取安装包信息")

        if (info.packageName != BuildConfig.APPLICATION_ID) {
            return@withContext Result.Rejected("包名不匹配：" + info.packageName)
        }
        // 防降级：新包版本号必须严格大于当前（避免被回滚到旧版本）
        @Suppress("DEPRECATION")
        val newVersionCode = info.versionCode
        if (newVersionCode <= BuildConfig.VERSION_CODE) {
            return@withContext Result.Rejected("版本号未提升（" + newVersionCode + " <= " + BuildConfig.VERSION_CODE + "），拒绝安装")
        }
        val certSha = signingCertSha256(info)
        val pinned = BuildConfig.PINNED_CERT_SHA256
        if (pinned.isBlank()) {
            return@withContext Result.Rejected("未配置签名指纹，拒绝安装（失败关闭）")
        }
        if (certSha == null || !certSha.equals(pinned, ignoreCase = true)) {
            return@withContext Result.Rejected("签名指纹不匹配")
        }
        Result.Verified(apk)
    }

    @Suppress("DEPRECATION")
    private fun signingCertSha256(info: android.content.pm.PackageInfo): String? {
        val signers = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return null
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            info.signatures
        }
        val first = signers?.firstOrNull() ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(first.toByteArray())
        val sb = StringBuilder()
        for (b in digest) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    /** 校验通过后调用系统安装器（用户仍需在系统界面确认）。 */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
