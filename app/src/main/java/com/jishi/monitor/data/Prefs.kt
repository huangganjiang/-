package com.jishi.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "jishi_prefs")

/**
 * 非敏感偏好与计数（FR-10.6 / FR-2.x / FR-4.7）。
 * 口令、凭据、Token 一律走 [SecureStore]，不进这里。
 */
class Prefs(private val context: Context) {

    private object K {
        val failCount = intPreferencesKey("fail_count")
        val lockUntilMs = longPreferencesKey("lock_until_ms")
        val lastChannelId = stringPreferencesKey("last_channel_id")
        val speed = stringPreferencesKey("playback_speed")
        val autoTour = booleanPreferencesKey("auto_tour")
        val guideDone = booleanPreferencesKey("guide_done")
        val lastChannelsJson = stringPreferencesKey("last_channels_json")
        val lanPreferred = booleanPreferencesKey("lan_preferred")
        val configSource = stringPreferencesKey("config_source")
        val lastTickAtMs = longPreferencesKey("last_tick_at")
    }

    suspend fun failCount(): Int = context.dataStore.data.first()[K.failCount] ?: 0

    suspend fun setFailCount(v: Int) = context.dataStore.edit { it[K.failCount] = v }

    suspend fun lockUntilMs(): Long = context.dataStore.data.first()[K.lockUntilMs] ?: 0L

    suspend fun setLockUntilMs(v: Long) = context.dataStore.edit { it[K.lockUntilMs] = v }

    suspend fun lastChannelId(): String? = context.dataStore.data.first()[K.lastChannelId]

    suspend fun setLastChannelId(v: String?) = context.dataStore.edit { p ->
        if (v == null) p.remove(K.lastChannelId) else p[K.lastChannelId] = v
    }

    suspend fun speed(): Float = (context.dataStore.data.first()[K.speed] ?: "1").toFloatOrNull() ?: 1f

    suspend fun setSpeed(v: Float) = context.dataStore.edit { it[K.speed] = v.toString() }

    suspend fun autoTour(): Boolean = context.dataStore.data.first()[K.autoTour] ?: false

    suspend fun setAutoTour(v: Boolean) = context.dataStore.edit { it[K.autoTour] = v }

    // ---- 通道缓存（离线时首页仍可展示上次结果，FR-2.6） ----
    suspend fun lastChannelsJson(): String? = context.dataStore.data.first()[K.lastChannelsJson]

    suspend fun setLastChannelsJson(v: String) = context.dataStore.edit { it[K.lastChannelsJson] = v }

    // ---- 内外网择优结果（FR-0.3） ----
    suspend fun lanPreferred(): Boolean = context.dataStore.data.first()[K.lanPreferred] ?: true

    suspend fun setLanPreferred(v: Boolean) = context.dataStore.edit { it[K.lanPreferred] = v }

    // ---- 配置来源（FR-0.8） ----
    suspend fun configSource(): String? = context.dataStore.data.first()[K.configSource]

    suspend fun setConfigSource(v: String) = context.dataStore.edit { it[K.configSource] = v }

    // ---- 启动自检时间戳 ----
    suspend fun lastTickAtMs(): Long = context.dataStore.data.first()[K.lastTickAtMs] ?: 0L

    suspend fun setLastTickAtMs(v: Long) = context.dataStore.edit { it[K.lastTickAtMs] = v }
}

