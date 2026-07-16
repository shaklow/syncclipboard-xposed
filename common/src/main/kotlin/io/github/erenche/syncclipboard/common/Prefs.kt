package io.github.erenche.syncclipboard.common

import android.content.Context
import android.content.SharedPreferences
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.model.DEFAULT_APP_CONFIG
import io.github.erenche.syncclipboard.common.model.ServerConfig
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * SharedPreferences 工具 — 用于 AppConfig 的持久化存储
 */
object Prefs {

    private const val PREFS_NAME = "syncclipboard_config"
    private const val KEY_CONFIG = "app_config"
    private const val KEY_SERVERS = "servers"
    private const val KEY_ACTIVE_SERVER = "active_server_index"
    private const val KEY_HISTORY_LAST_SYNC_TIME = "history_last_sync_time"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * 从 SharedPreferences 加载配置
     */
    fun loadConfig(context: Context): AppConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val configJson = prefs.getString(KEY_CONFIG, null)
        return if (configJson != null) {
            try {
                json.decodeFromString<AppConfig>(configJson)
            } catch (e: Exception) {
                Logger.warn("Prefs", "Failed to parse config, using default", e)
                DEFAULT_APP_CONFIG
            }
        } else {
            // 兼容旧格式：从独立 key 迁移
            migrateLegacyConfig(prefs)
        }
    }

    /**
     * 保存配置到 SharedPreferences
     */
    fun saveConfig(context: Context, config: AppConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(config)).apply()
    }

    /**
     * 从旧格式迁移配置
     */
    private fun migrateLegacyConfig(prefs: SharedPreferences): AppConfig {
        val serversJson = prefs.getString(KEY_SERVERS, null)
        val activeIndex = prefs.getInt(KEY_ACTIVE_SERVER, -1)

        val servers: List<ServerConfig> = if (serversJson != null) {
            try {
                json.decodeFromString<List<ServerConfig>>(serversJson)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val config = DEFAULT_APP_CONFIG.copy(
            servers = servers,
            activeServerIndex = activeIndex
        )

        // 保存为新格式
        prefs.edit()
            .putString(KEY_CONFIG, json.encodeToString(config))
            .remove(KEY_SERVERS)
            .remove(KEY_ACTIVE_SERVER)
            .apply()

        return config
    }

    /**
     * 获取 SharedPreferences 实例
     */
    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 加载历史同步游标（增量同步用，0 表示需要全量同步）
     */
    fun loadHistoryLastSyncTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_HISTORY_LAST_SYNC_TIME, 0L)
    }

    /**
     * 保存历史同步游标
     */
    fun saveHistoryLastSyncTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_HISTORY_LAST_SYNC_TIME, time).apply()
    }

    /**
     * 重置历史同步游标（切换服务器时调用，触发下次全量同步）
     */
    fun resetHistoryLastSyncTime(context: Context) {
        getPrefs(context).edit().putLong(KEY_HISTORY_LAST_SYNC_TIME, 0L).apply()
    }
}
