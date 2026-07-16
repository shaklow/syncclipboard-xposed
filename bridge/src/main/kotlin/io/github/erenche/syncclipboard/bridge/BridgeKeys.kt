package io.github.erenche.syncclipboard.bridge

/**
 * IPC 桥接协议常量定义
 *
 * Commands: 单向指令（fire-and-forget），不需要回复
 * Queries:  双向查询（request-reply），需要回复
 */
object BridgeKeys {
    // ─── 配置查询（app → xposed 进程）──────────────────────────

    /** 查询完整配置 */
    const val GET_CONFIG = "get_config"

    /** 推送配置更新 */
    const val PUSH_CONFIG = "push_config"

    // ─── 服务器管理（app → xposed 进程）─────────────────────────

    /** 添加服务器 */
    const val ADD_SERVER = "add_server"

    /** 更新服务器 */
    const val UPDATE_SERVER = "update_server"

    /** 删除服务器 */
    const val DELETE_SERVER = "delete_server"

    /** 设置激活服务器 */
    const val SET_ACTIVE_SERVER = "set_active_server"

    /** 测试服务器连接 */
    const val TEST_CONNECTION = "test_connection"

    // ─── 同步状态查询（app → xposed 进程）──────────────────────

    /** 获取同步状态 */
    const val GET_SYNC_STATUS = "get_sync_status"

    /** 获取当前剪贴板内容 */
    const val GET_CURRENT_CLIPBOARD = "get_current_clipboard"

    // ─── 同步控制指令（app → xposed 进程）──────────────────────

    /** 触发立即同步 */
    const val TRIGGER_SYNC = "trigger_sync"

    /** 触发立即上传 */
    const val UPLOAD_NOW = "upload_now"

    /** 触发立即下载 */
    const val DOWNLOAD_NOW = "download_now"

    /** 清除同步错误 */
    const val CLEAR_SYNC_ERROR = "clear_sync_error"

    // ─── 历史记录（app → xposed 进程）──────────────────────────

    /** 查询历史记录列表 */
    const val GET_HISTORY = "get_history"

    /** 强制同步历史记录（从服务器拉取完整历史） */
    const val FORCE_SYNC_HISTORY = "force_sync_history"

    /** 查询单条历史记录 */
    const val GET_HISTORY_ITEM = "get_history_item"

    /** 更新历史记录（star/pin） */
    const val UPDATE_HISTORY_ITEM = "update_history_item"

    /** 删除历史记录 */
    const val DELETE_HISTORY_ITEM = "delete_history_item"

    /** 清空所有历史记录 */
    const val CLEAR_HISTORY = "clear_history"

    // ─── 事件推送（xposed 进程 → app）──────────────────────────

    /** 剪贴板内容变化事件 */
    const val EVENT_CLIPBOARD_CHANGED = "event_clipboard_changed"

    /** 同步状态变化事件 */
    const val EVENT_SYNC_STATE_CHANGED = "event_sync_state_changed"

    /** 手动操作结果反馈事件（同步/上传） */
    const val EVENT_ACTION_RESULT = "event_action_result"

    /** 传输进度事件 */
    const val EVENT_TRANSFER_PROGRESS = "event_transfer_progress"

    // ─── 日志（app → xposed 进程）─────────────────────────────
    /** 查询日志（返回最近日志文本） */
    const val GET_LOGS = "get_logs"
    /** 清空日志缓冲区 */
    const val CLEAR_LOGS = "clear_logs"

    // ─── 文本直传（app → xposed 进程）────────────────────────
    /** 直接上传一段文本（如短信验证码）到服务器 */
    const val UPLOAD_TEXT = "upload_text"
}
