package io.github.erenche.syncclipboard.app.tile

import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.github.erenche.syncclipboard.bridge.BridgeKeys
import io.github.erenche.syncclipboard.bridge.SyncClipboardBridge
import io.github.erenche.syncclipboard.common.Prefs
import io.github.erenche.syncclipboard.common.model.AppConfig
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.serialization.json.Json

/**
 * 快捷设置磁贴 — 切换"自动同步"总开关。
 *
 * 行为对齐 [io.github.erenche.syncclipboard.app.activity.SettingsActivity] 中的 toggleAutoSync：
 * - 关闭→打开：若后台上传/下载都关则默认都开
 * - 打开→关闭：后台上传/下载一并关闭
 *
 * 配置变更通过 bridge 推送到 SystemUI 进程的 SyncEngine。
 *
 * 交互：
 * - 单击：切换自动同步开关
 * - 长按：由系统启动声明了 QS_TILE_PREFERENCES 的 MainActivity（见 AndroidManifest）
 */
@RequiresApi(Build.VERSION_CODES.N)
class AutoSyncTileService : TileService() {

    companion object {
        private const val TAG = "AutoSyncTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        toggleAutoSync()
    }

    /** 刷新磁贴状态以反映当前配置 */
    private fun refreshTile() {
        val config = Prefs.loadConfig(this)
        val tile = qsTile ?: return
        tile.state = if (config.enableAutoSync) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(io.github.erenche.syncclipboard.app.R.string.tile_auto_sync)
        tile.updateTile()
    }

    /** 切换自动同步总开关（联动后台上传/下载子开关） */
    private fun toggleAutoSync() {
        val config = Prefs.loadConfig(this)
        val newConfig = if (config.enableAutoSync) {
            // 关闭：子开关一并关闭
            config.copy(
                enableAutoSync = false,
                enableBackgroundUpload = false,
                enableBackgroundDownload = false
            )
        } else {
            // 打开：若两个子开关都关则默认都开
            val restoreUpload = config.enableBackgroundUpload || config.enableBackgroundDownload
            config.copy(
                enableAutoSync = true,
                enableBackgroundUpload = if (restoreUpload) config.enableBackgroundUpload else true,
                enableBackgroundDownload = if (restoreUpload) config.enableBackgroundDownload else true
            )
        }

        try {
            Prefs.saveConfig(this, newConfig)
            pushConfigToSystemUI(newConfig)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to apply config: ${e.message}")
        }

        val tile = qsTile
        if (tile != null) {
            tile.state = if (newConfig.enableAutoSync) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    /** 通过 bridge 推送配置到 SystemUI 进程 */
    private fun pushConfigToSystemUI(config: AppConfig) {
        try {
            val configJson = Json.encodeToString(AppConfig.serializer(), config)
            val payload = Bundle().apply { putString("config", configJson) }
            SyncClipboardBridge.with(this)
                .to("com.android.systemui")
                .key(BridgeKeys.PUSH_CONFIG)
                .payload(payload)
                .send()
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to push config via bridge: ${e.message}")
        }
    }
}
