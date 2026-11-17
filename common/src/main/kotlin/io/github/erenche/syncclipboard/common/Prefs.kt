package io.github.erenche.syncclipboard.common

import android.content.Context
import android.content.SharedPreferences
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.model.DEFAULT_APP_CONFIG
import io.github.erenche.syncclipboard.common.model.ProfileDto
import io.github.erenche.syncclipboard.common.model.ServerConfig
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.common.security.PreferenceEncryption
import io.github.erenche.syncclipboard.common.security.EncryptedValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * SharedPreferences 工具 — 用于 AppConfig 的持久化存储
 */
object Prefs {

    private const val PREFS_NAME = "syncclipboard_config"
    const val KEY_CONFIG = "app_config"
    private const val KEY_CONFIG_REVISION = "config_revision"
    private const val KEY_SERVERS = "servers"
    private const val KEY_ACTIVE_SERVER = "active_server_index"
    private const val KEY_HISTORY_LAST_SYNC_TIME = "history_last_sync_time"
    private const val KEY_LAST_REMOTE_HASH = "last_remote_hash"
    private const val KEY_LAST_REMOTE_FILE_PATH = "last_remote_file_path"
    private const val KEY_LAST_REMOTE_PROFILE = "last_remote_profile"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    @Volatile var configStorageError: Boolean = false
        private set

    /**
     * 从 SharedPreferences 加载配置
     */
    @Synchronized
    fun loadConfig(context: Context): AppConfig {
        return try {
            val prefs = getPrefs(context)
            val raw = prefs.getString(KEY_CONFIG, null)
            val config = if (raw != null) {
                var decoded: AppConfig? = null
                encryptedStore(prefs).load(KEY_CONFIG) { decoded = json.decodeFromString<AppConfig>(it) }
                requireNotNull(decoded)
            } else migrateLegacyConfig(prefs)
            configStorageError = false
            config
        } catch (e: Exception) {
            // 保留原配置；停止同步，避免错误默认值覆盖凭据。
            configStorageError = true
            Logger.warn("Prefs", "Config storage unavailable: ${e.javaClass.simpleName}")
            DEFAULT_APP_CONFIG.copy(enableAutoSync = false, enableBackgroundUpload = false,
                enableBackgroundDownload = false, enableSystemUiLocalNetworkAccess = false,
                enableSystemUiInternetAccess = false)
        }
    }

    /**
     * 保存配置到 SharedPreferences
     */
    @Synchronized
    fun saveConfig(context: Context, config: AppConfig) {
        loadConfig(context)
        check(!configStorageError) { "Config storage unavailable; original data retained" }
        try {
            persistConfig(getPrefs(context), config)
        } catch (e: Exception) {
            configStorageError = true
            throw e
        }
    }

    private fun persistConfig(prefs: SharedPreferences, config: AppConfig) {
        encryptedStore(prefs).save(KEY_CONFIG, json.encodeToString(config))
    }

    private fun encryptedStore(prefs: SharedPreferences) = EncryptedValueStore(
        PreferenceEncryption.codec, { key -> prefs.getString(key, null) }, { key, value ->
            val editor = prefs.edit()
            if (value == null) editor.remove(key) else editor.putString(key, value)
            if (key == KEY_CONFIG) {
                val revision = maxOf(System.currentTimeMillis(), prefs.getLong(KEY_CONFIG_REVISION, 0L) + 1)
                editor.putLong(KEY_CONFIG_REVISION, revision).remove(KEY_SERVERS).remove(KEY_ACTIVE_SERVER)
            }
            editor.commit()
        })

    fun configRevision(context: Context): Long = getPrefs(context).getLong(KEY_CONFIG_REVISION, 0L)

    @Synchronized
    fun configSnapshot(context: Context): Pair<AppConfig, Long> {
        val config = loadConfig(context)
        return config to if (configStorageError) 0L else configRevision(context)
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

        val servers: List<ServerConfig> = serversJson?.let {
            json.decodeFromString<List<ServerConfig>>(it)
        } ?: emptyList()

        val config = DEFAULT_APP_CONFIG.copy(
            servers = servers,
            activeServerIndex = activeIndex
        )

        persistConfig(prefs, config)

        return config
    }

    /**
     * 获取 SharedPreferences 实例
     */
    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 旧明文仅在加密落盘成功后替换。 */
    @Synchronized
    fun loadSensitiveString(context: Context, key: String): String? {
        return encryptedStore(getPrefs(context)).load(key)
    }

    @Synchronized
    fun saveSensitiveString(context: Context, key: String, value: String?) {
        encryptedStore(getPrefs(context)).save(key, value)
    }

    /** 同时迁移闲置服务器的队列，旧无归属队列仍不重放。 */
    fun migrateSensitiveValues(context: Context) {
        getPrefs(context).all.keys.filter {
            it == KEY_LAST_REMOTE_PROFILE || it == "upload_queue" || it.startsWith("upload_queue:")
        }.forEach { key ->
            runCatching { loadSensitiveString(context, key) }
                .onFailure { Logger.warn("Prefs", "Sensitive value migration pending: ${it.javaClass.simpleName}") }
        }
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

    /**
     * 加载缓存的最新 profile（引擎重启后恢复，app 无需等网络拉取即可显示上次内容）
     */
    fun loadLastRemoteProfile(context: Context): ProfileDto? {
        return try {
            val profileJson = loadSensitiveString(context, KEY_LAST_REMOTE_PROFILE) ?: return null
            json.decodeFromString(ProfileDto.serializer(), profileJson)
        } catch (e: Exception) {
            Logger.warn("Prefs", "Cached profile unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 保存缓存的最新 profile（null 清除）
     */
    fun saveLastRemoteProfile(context: Context, profile: ProfileDto?) {
        saveSensitiveString(context, KEY_LAST_REMOTE_PROFILE,
            profile?.let { json.encodeToString(ProfileDto.serializer(), it) })
    }
}
