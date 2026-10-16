package com.jishi.monitor.data.net

import com.jishi.monitor.core.Logx
import com.jishi.monitor.data.CredentialPayload
import com.jishi.monitor.data.CredentialCrypto
import com.jishi.monitor.data.Prefs
import com.jishi.monitor.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HttpResult(
    val code: Int,
    val body: String,
    val note: String = "",
) {
    val ok: Boolean get() = code in 200..299
}

@Serializable
data class LoginResult(
    val token: String = "",
    val expireAtMs: Long = 0L,
    val note: String = "",
)

sealed interface LoginOutcome {
    data class Ok(val token: String, val expireAtMs: Long) : LoginOutcome
    data class Fail(val code: Int, val reason: String) : LoginOutcome
}

/**
 * EasyNVR HTTP 客户端。
 * - 只使用明文 HTTP（服务端无 TLS，V-11 已实测）；
 * - Bearer Token 由 [configure] 注入，401 时静默重登一次（FR-1.4 / FR-8.x，不跳登录页）；
 * - 超时：连接 10 秒 / 读取 20 秒（回放取址放宽到 30 秒，需求 8.1）。
 */
class ApiClient(private val secure: SecureStore, private val prefs: Prefs) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val authMutex = Mutex()

    @Volatile private var payload: CredentialPayload? = null
    @Volatile private var baseUrl: String = ""
    @Volatile private var token: String? = null
    @Volatile private var tokenExpireAtMs: Long = 0L

    /** 仅当请求主机与服务主机一致时才附带 Token，避免 Token 被诱导发往任意主机。 */
    /** 判断给定 URL/主机是否与当前基址同源（同源才允许携带 Token）。 */
    fun isSameOrigin(host: String?): Boolean {
        val base = baseUrl
        if (base.isBlank() || host.isNullOrBlank()) return false
        val baseHost = runCatching { java.net.URI(base).host }.getOrNull() ?: return false
        return baseHost.equals(host, ignoreCase = true)
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            if (isSameOrigin(req.url.host)) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer " + (token ?: "")).build())
            } else {
                chain.proceed(req.newBuilder().removeHeader("Authorization").build())
            }
        }
        // 网络拦截器对每一次真实请求（含重定向后的请求）生效：跨主机时剥离 Token
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            if (isSameOrigin(req.url.host)) chain.proceed(req)
            else chain.proceed(req.newBuilder().removeHeader("Authorization").build())
        }
        .build()

    /** 秒级超时的探测客户端（内网/公网择优，FR-0.3）。 */
    private val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    fun currentBaseUrl(): String = baseUrl

    fun currentToken(): String? = token

    fun tokenExpireAtMs(): Long = tokenExpireAtMs

    fun hasCredentials(): Boolean = payload != null

    /** 载入已激活凭据（首启激活后由 [activateWith] 落库）。 */
    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) {
        val stored = secure.get(SecureStore.ALIAS_CREDENTIALS) ?: return@withContext false
        val p = runCatching { json.decodeFromString(CredentialPayload.serializer(), stored) }.getOrNull()
            ?: return@withContext false
        payload = p
        baseUrl = resolveBaseUrl(p)
        val t = secure.get(SecureStore.ALIAS_TOKEN)
        if (t != null) {
            val obj = runCatching { json.parseToJsonElement(t) as JsonObject }.getOrNull()
            token = obj?.get("token")?.let { (it as? JsonPrimitive)?.contentOrNull }
            tokenExpireAtMs = obj?.get("expireAtMs")?.let { (it as? JsonPrimitive)?.longOrNull } ?: 0L
        }
        Logx.i(TAG, "已恢复会话：baseUrl=" + baseUrl + " token=" + (if (token.isNullOrEmpty()) "无" else "有"))
        true
    }

    /** 首次激活：写入凭据并换取 Token。 */
    suspend fun activateWith(p: CredentialPayload): LoginOutcome = withContext(Dispatchers.IO) {
        payload = p
        baseUrl = resolveBaseUrl(p)
        secure.put(SecureStore.ALIAS_CREDENTIALS, json.encodeToString(CredentialPayload.serializer(), p))
        login()
    }

    /** 隐藏设置里手动改地址（FR-1.2）：更新加密凭据并重新登录。 */
    suspend fun overrideHost(newHost: String): Boolean = withContext(Dispatchers.IO) {
        val p = payload ?: return@withContext false
        val normalized = normalize(newHost)
        if (normalized.isEmpty()) return@withContext false
        val updated = p.copy(host = normalized)
        payload = updated
        baseUrl = normalized
        secure.put(SecureStore.ALIAS_CREDENTIALS, json.encodeToString(CredentialPayload.serializer(), updated))
        when (val r = login()) {
            is LoginOutcome.Ok -> true
            is LoginOutcome.Fail -> false
        }
    }

    /** 内网优先探测；失败回退公网（FR-0.3）。 */
    suspend fun resolveBaseUrl(p: CredentialPayload): String {
        val lan = normalize(p.lanHost)
        val wan = normalize(p.host)
        if (lan.isNotEmpty() && canReach(lan)) {
            prefs.setLanPreferred(true)
            return lan
        }
        if (wan.isNotEmpty() && canReach(wan)) {
            prefs.setLanPreferred(false)
            return wan
        }
        return if (wan.isNotEmpty()) wan else lan
    }

    private fun normalize(url: String): String {
        val t = url.trim().trimEnd('/')
        if (t.isEmpty()) return ""
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://" + t
    }

    private fun canReach(base: String): Boolean = try {
        val req = Request.Builder().url(base + "/configs/info/web").get().build()
        probeClient.newCall(req).execute().use { it.code in 200..499 }
    } catch (t: Throwable) {
        false
    }

    /** 静默重登（Token 有效期 6 小时，过期前 15 分钟续期，FR-1.4）。 */
    suspend fun ensureToken(force: Boolean = false): Boolean = authMutex.withLock {
        val now = System.currentTimeMillis()
        val valid = !token.isNullOrEmpty() && now < tokenExpireAtMs - REFRESH_AHEAD_MS
        if (valid && !force) return@withLock true
        if (payload == null && !restoreSession()) return@withLock false
        when (val r = login()) {
            is LoginOutcome.Ok -> true
            is LoginOutcome.Fail -> {
                Logx.w(TAG, "静默续期失败 code=" + r.code + " reason=" + r.reason)
                false
            }
        }
    }

    private suspend fun login(): LoginOutcome {
        val p = payload ?: return LoginOutcome.Fail(0, "无凭据")
        if (baseUrl.isBlank()) baseUrl = resolveBaseUrl(p)
        val body = buildString {
            append("{\"username\":\"").append(escape(p.username)).append("\",")
            append("\"password\":\"").append(CredentialCrypto.sha256Hex(p.password)).append("\"}")
        }
        val req = Request.Builder()
            .url(baseUrl + "/login")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        if (!isSameOrigin(req.url.host)) return LoginOutcome.Fail(0, "地址与配置的服务主机不一致，已拒绝发送凭据")
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code in 200..299) {
                    val parsed = parseLogin(text)
                    if (parsed.token.isEmpty()) {
                        LoginOutcome.Fail(resp.code, "响应中未找到 token")
                    } else {
                        token = parsed.token
                        tokenExpireAtMs = parsed.expireAtMs
                        secure.put(
                            SecureStore.ALIAS_TOKEN,
                            "{\"token\":\"" + escape(parsed.token) + "\",\"expireAtMs\":" + parsed.expireAtMs + "}"
                        )
                        Logx.i(TAG, "登录成功 token=" + mask(parsed.token))
                        LoginOutcome.Ok(parsed.token, parsed.expireAtMs)
                    }
                } else {
                    LoginOutcome.Fail(resp.code, extractReason(text))
                }
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "登录请求异常: " + t.javaClass.simpleName)
            LoginOutcome.Fail(-1, "网络不可达")
        }
    }

    /** /login 响应字段名容错（token / access_token / data.token；过期时间取自 expired_at 或 timeout_s）。 */
    private fun parseLogin(text: String): LoginResult {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return LoginResult()
        fun dig(e: JsonElement?, key: String): JsonElement? = (e as? JsonObject)?.get(key)
        var token = (dig(root, "token") as? JsonPrimitive)?.contentOrNull
        if (token.isNullOrEmpty()) token = (dig(dig(root, "data"), "token") as? JsonPrimitive)?.contentOrNull
        if (token.isNullOrEmpty()) token = (dig(root, "access_token") as? JsonPrimitive)?.contentOrNull
        var expireAt = 0L
        val expiredAt = (dig(root, "expired_at") as? JsonPrimitive)?.contentOrNull
            ?: (dig(dig(root, "data"), "expired_at") as? JsonPrimitive)?.contentOrNull
        val timeoutS = (dig(root, "timeout_s") as? JsonPrimitive)?.longOrNull
            ?: (dig(dig(root, "data"), "timeout_s") as? JsonPrimitive)?.longOrNull
        val now = System.currentTimeMillis()
        val timeoutSeconds = timeoutS
        when {
            timeoutSeconds != null && timeoutSeconds != 0L -> expireAt = now + timeoutSeconds * 1000L
            !expiredAt.isNullOrEmpty() -> {
                val num = expiredAt.toLongOrNull()
                expireAt = when {
                    num == null -> now + 6 * 60 * 60 * 1000L
                    num > 1_000_000_000_000L -> num
                    else -> num * 1000L
                }
            }
            else -> expireAt = now + 6 * 60 * 60 * 1000L
        }
        return LoginResult(token.orEmpty(), expireAt)
    }

    private fun extractReason(text: String): String {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return "请求失败"
        val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull ?: ""
        val reason = (root["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
        return (msg + " " + reason).trim().ifEmpty { "请求失败" }
    }

    /** 带 Token 的 GET；401 时静默重登一次（不弹窗、不跳登录页）。 */
    suspend fun get(path: String, readTimeoutSec: Long = 20, retryAuth: Boolean = true): HttpResult =
        withContext(Dispatchers.IO) {
            if (!ensureToken()) return@withContext HttpResult(401, "", "未激活")
            val url = if (path.startsWith("http")) path else baseUrl + path
            val first = rawGet(url, readTimeoutSec)
            if (first.code == 401 && retryAuth) {
                if (ensureToken(force = true)) {
                    val again = rawGet(url, readTimeoutSec)
                    return@withContext again
                }
            }
            first
        }

    private fun rawGet(url: String, readTimeoutSec: Long): HttpResult = try {
        val req = Request.Builder()
            .url(url)
            .get()
            .build()
        val c = client.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
        c.newCall(req).execute().use { resp: Response ->
            HttpResult(resp.code, resp.body?.string().orEmpty(), resp.header("Content-Type").orEmpty())
        }
    } catch (e: IOException) {
        Logx.w(TAG, "GET 失败 " + url + " :: " + e.javaClass.simpleName)
        HttpResult(-1, "", "网络不可达")
    }

    /** 取二进制（快照）。 */
    suspend fun getBytes(path: String, readTimeoutSec: Long = 20): ByteArray? = withContext(Dispatchers.IO) {
        if (!ensureToken()) return@withContext null
        val url = if (path.startsWith("http")) path else baseUrl + path
        try {
            val req = Request.Builder()
                .url(url)
                .get()
                .build()
            val c = client.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
            c.newCall(req).execute().use { resp ->
                if (resp.code in 200..299) resp.body?.bytes() else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 下载到文件（更新包）。 */
    suspend fun downloadTo(url: String, target: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return@withContext false
                resp.body?.byteStream()?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                true
            }
        } catch (t: Throwable) {
            Logx.w(TAG, "下载失败: " + t.javaClass.simpleName)
            false
        }
    }

    /** 供 UI 展示（不泄密）：服务地址、Token 剩余时间。 */
    fun statusLine(): String {
        val left = (tokenExpireAtMs - System.currentTimeMillis()) / 1000
        return "地址 " + baseUrl + " · Token " + (if (token.isNullOrEmpty()) "无" else "剩余 " + (left.coerceAtLeast(0)) + " 秒")
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun mask(t: String): String = if (t.length <= 8) "***" else t.take(4) + "***" + t.takeLast(2)

    companion object {
        private const val TAG = "ApiClient"
        private const val REFRESH_AHEAD_MS = 15 * 60 * 1000L
    }
}
