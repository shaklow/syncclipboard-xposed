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
    const val KEY_CONFIG = "app_config"
    private const val KEY_SERVERS = "servers"
    private const val KEY_ACTIVE_SERVER = "active_server_index"
    private const val KEY_HISTORY_LAST_SYNC_TIME = "history_last_sync_time"
    private const val KEY_LAST_REMOTE_HASH = "last_remote_hash"
    private const val KEY_LAST_REMOTE_FILE_PATH = "last_remote_file_path"

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

    /** 注册配置变更监听（KEY_CONFIG 变化时回调） */
    fun registerConfigListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    /** 注销配置变更监听 */
    fun unregisterConfigListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(listener)
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

    /**
     * 加载最后远程内容 hash（持久化跨 SystemUI 重启，避免重启后
     * 把未变化的服务器内容误判为"新内容"，导致重复下载与重复历史）
     */
    fun loadLastRemoteHash(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_REMOTE_HASH, null)
    }

    /**
     * 保存最后远程内容 hash
     */
    fun saveLastRemoteHash(context: Context, hash: String?) {
        getPrefs(context).edit().putString(KEY_LAST_REMOTE_HASH, hash).apply()
    }

    /**
     * 加载最后下载的文件路径（SystemUI 重启后用于判断文件是否已存在）
     */
    fun loadLastRemoteFilePath(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_REMOTE_FILE_PATH, null)
    }

    /**
     * 保存最后下载的文件路径
     */
    fun saveLastRemoteFilePath(context: Context, path: String?) {
        getPrefs(context).edit().putString(KEY_LAST_REMOTE_FILE_PATH, path).apply()
    }
}
