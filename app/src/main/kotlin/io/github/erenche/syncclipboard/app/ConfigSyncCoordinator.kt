package io.github.erenche.syncclipboard.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.erenche.syncclipboard.app.util.AppLangUtils
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.ConfigRevisionPolicy
import io.github.erenche.syncclipboard.bridge.InternetAccessBridge
import io.github.erenche.syncclipboard.bridge.InternetAccessState
import io.github.erenche.syncclipboard.bridge.InternetAccessStatus
import io.github.erenche.syncclipboard.bridge.InternetAccessStatusPolicy
import io.github.erenche.syncclipboard.bridge.LocalNetworkAccessBridge
import io.github.erenche.syncclipboard.bridge.LocalNetworkState
import io.github.erenche.syncclipboard.bridge.LocalNetworkStatus
import io.github.erenche.syncclipboard.bridge.LocalNetworkStatusPolicy
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

enum class ConfigApplyState { SAVED, APPLYING, APPLIED, PENDING, STORAGE_ERROR, REMOTE_STORAGE_ERROR }
data class ConfigSyncState(
    val config: ConfigApplyState = ConfigApplyState.SAVED,
    val network: LocalNetworkState = LocalNetworkState.UNKNOWN,
    val internet: InternetAccessState = InternetAccessState.UNKNOWN
)

/** 串行同步最新配置，生效失败时保留本地设置并重试。 */
object ConfigSyncCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(ConfigSyncState())
    val state = mutableState.asStateFlow()
    private var started = false
    private var failureRevision: Long? = null
    private val reportedFailures = mutableSetOf<Int>()
    private var failureToast: Toast? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_CONFIG) retry()
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Prefs.registerConfigListener(app, listener)
        scope.launch {
            withContext(Dispatchers.IO) { Prefs.migrateSensitiveValues(app) }
            for (request in requests) {
                var backoff = 1000L
                while (true) {
                    val (config, revision) = withContext(Dispatchers.IO) { Prefs.configSnapshot(app) }
                    if (Prefs.configStorageError) {
                        mutableState.value = mutableState.value.copy(config = ConfigApplyState.STORAGE_ERROR)
                        notifyFailure(app, 0L, R.string.config_storage_error)
                        break
                    }
                    mutableState.value = mutableState.value.copy(config = ConfigApplyState.APPLYING)
                    val payload = Bundle().apply {
                        putString("config", Json.encodeToString(AppConfig.serializer(), config))
                        putLong("revision", revision)
                    }
                    val reply = runCatching {
                        SyncClipboardBridge.with(app).to(PackageNames.SYSTEM_UI)
                            .key(BridgeKeys.APPLY_CONFIG).payload(payload).await(4000)
                    }.getOrElse { if (it is CancellationException) throw it else Bundle.EMPTY }
                    if (Prefs.configRevision(app) != revision) continue
                    val applied = ConfigRevisionPolicy.acknowledged(revision,
                        reply.getLong("revision", 0), reply.getBoolean("applied"))
                    val configState = when {
                        applied -> ConfigApplyState.APPLIED
                        reply.getString("error") == "storage_unavailable" -> ConfigApplyState.REMOTE_STORAGE_ERROR
                        else -> ConfigApplyState.PENDING
                    }
                    mutableState.value = mutableState.value.copy(config = configState)
                    val network = if (!LocalNetworkStatusPolicy.supported(Build.VERSION.SDK_INT)) LocalNetworkState.UNSUPPORTED else {
                        val result = runCatching {
                            LocalNetworkAccessBridge.status(app, config.enableSystemUiLocalNetworkAccess)
                        }.getOrElse { if (it is CancellationException) throw it else Bundle.EMPTY }
                        LocalNetworkStatusPolicy.classify(Build.VERSION.SDK_INT,
                            config.enableSystemUiLocalNetworkAccess,
                            if (result.isEmpty) null else LocalNetworkStatus(result.getBoolean("enabled"),
                                result.getBoolean("backend"), result.getBoolean("writer"),
                                result.getBoolean("handler"), result.getBoolean("verified"),
                                result.getBoolean("readbackFailed"), result.getBoolean("storageFailed")))
                    }
                    if (Prefs.configRevision(app) != revision) continue
                    val systemUiHasInetGroup = if (reply.containsKey("inetGroup"))
                        reply.getBoolean("inetGroup") else null
                    val internet = if (config.enableSystemUiInternetAccess && systemUiHasInetGroup == true) {
                        // SystemUI 自带 INTERNET，无需等待系统侧回执；后台仍同步一次开关状态。
                        InternetAccessBridge.send(app, true)
                        InternetAccessState.VERIFIED
                    } else {
                        val result = runCatching {
                            InternetAccessBridge.status(app, config.enableSystemUiInternetAccess)
                        }.getOrElse { if (it is CancellationException) throw it else Bundle.EMPTY }
                        InternetAccessStatusPolicy.classify(config.enableSystemUiInternetAccess,
                            if (result.isEmpty) null else InternetAccessStatus(
                                enabled = result.getBoolean("enabled"),
                                hookInstalled = result.getBoolean("hooks"),
                                storageFailed = result.getBoolean("storageFailed")),
                            systemUiHasInetGroup)
                    }
                    mutableState.value = mutableState.value.copy(network = network, internet = internet)
                    val failureMessage = when {
                        configState == ConfigApplyState.REMOTE_STORAGE_ERROR -> R.string.config_remote_storage_error
                        configState == ConfigApplyState.PENDING -> R.string.config_pending
                        network == LocalNetworkState.FAILED -> R.string.local_network_failed
                        network == LocalNetworkState.RESTART_REQUIRED -> R.string.local_network_restart_required
                        internet == InternetAccessState.FAILED -> R.string.internet_access_failed
                        internet == InternetAccessState.RESTART_REQUIRED -> R.string.internet_access_restart_required
                        else -> null
                    }
                    failureMessage?.let { notifyFailure(app, revision, it) }
                    if (applied && network in setOf(LocalNetworkState.UNSUPPORTED,
                            LocalNetworkState.VERIFIED, LocalNetworkState.DISABLED) &&
                        internet in setOf(InternetAccessState.VERIFIED, InternetAccessState.DISABLED)) {
                        failureRevision = null
                        reportedFailures.clear()
                        break
                    }
                    withTimeoutOrNull(backoff) { requests.receive() }
                    backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
        retry()
    }

    fun retry() {
        mutableState.value = mutableState.value.copy(config = ConfigApplyState.SAVED)
        requests.trySend(Unit)
    }

    fun save(context: Context, config: AppConfig): Boolean = try {
        Prefs.saveConfig(context, config)
        retry()
        true
    } catch (e: Exception) {
        mutableState.value = mutableState.value.copy(config = ConfigApplyState.STORAGE_ERROR)
        Logger.warn("ConfigSync", "Config save failed: ${e.javaClass.simpleName}")
        scope.launch { notifyFailure(context.applicationContext, 0L, R.string.config_storage_error) }
        false
    }

    private fun notifyFailure(context: Context, revision: Long, @StringRes message: Int) {
        // 同一版设置的同类失败仅提示一次，重试期间保持静默。
        if (failureRevision != revision) {
            failureRevision = revision
            reportedFailures.clear()
        }
        if (!reportedFailures.add(message)) return
        failureToast?.cancel()
        failureToast = Toast.makeText(AppLangUtils.wrapContext(context), message, Toast.LENGTH_LONG)
            .also { it.show() }
    }
}
