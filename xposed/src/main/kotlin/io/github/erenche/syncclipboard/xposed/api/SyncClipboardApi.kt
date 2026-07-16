package io.github.erenche.syncclipboard.xposed.api

import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryRecordDto
import io.github.erenche.syncclipboard.common.model.HistoryRecordUpdateDto
import io.github.erenche.syncclipboard.common.model.HistoryStatisticsDto
import io.github.erenche.syncclipboard.common.model.ProfileDto

/**
 * SyncClipboard API 接口 — 对应 TypeScript ISyncClipboardAPI + IHistoryAPI。
 *
 * 所有方法均为 suspend 函数，用于协程环境中进行 HTTP 请求。
 *
 * 注意：历史记录 API（queryHistoryRecords / uploadHistoryRecord）仅
 * SyncClipboard HTTP 服务器支持，WebDAV/S3 客户端返回空结果或不执行操作。
 */
interface SyncClipboardApi {

    /** 获取远程剪贴板配置 */
    suspend fun getClipboard(): ProfileDto?

    /** 上传剪贴板配置 */
    suspend fun putClipboard(profile: ProfileDto)

    /** 下载文件到指定路径 */
    suspend fun downloadFile(fileName: String, destinationPath: String, onProgress: ((Float) -> Unit)? = null): String

    /** 上传文件 */
    suspend fun putFile(fileName: String, filePath: String, onProgress: ((Float) -> Unit)? = null)

    /**
     * 上传剪贴板内容（先上传数据文件，再上传配置）
     */
    suspend fun putContent(content: ClipboardContent)

    /**
     * 查询服务器历史记录（原项目 `POST /api/history/query`）。
     * 仅 SyncClipboard HTTP 服务器支持，WebDAV/S3 返回空列表。
     *
     * @param page 页码（从 1 开始）
     * @param modifiedAfter ISO 8601 时间字符串，仅返回此时间之后修改的记录（增量同步）
     * @param types 类型过滤位掩码（ProfileTypeFilter.All = 15）
     * @return 服务器返回的记录列表
     */
    suspend fun queryHistoryRecords(page: Int = 1, modifiedAfter: String? = null, types: Int = 15): List<HistoryRecordDto>

    /**
     * 上传历史记录到服务器（原项目 `POST /api/history`，multipart/form-data）。
     * 仅 SyncClipboard HTTP 服务器支持，WebDAV/S3 不执行操作。
     *
     * @param record 历史记录 DTO
     * @param filePath 可选的数据文件路径（有文件时一起上传）
     * @return 服务器返回的记录；409 冲突时返回服务器现有记录；失败返回 null
     */
    suspend fun uploadHistoryRecord(record: HistoryRecordDto, filePath: String? = null): HistoryRecordDto?

    /**
     * 更新历史记录元数据（原项目 `PATCH /api/history/{type}/{id}`）。
     * 仅 SyncClipboard HTTP 服务器支持。
     *
     * @return 服务器返回的更新后记录；404 返回 null；409 返回服务器记录
     */
    suspend fun updateHistoryRecord(type: ClipboardContentType, hash: String, update: HistoryRecordUpdateDto): HistoryRecordDto?

    /**
     * 获取单条历史记录（原项目 `GET /api/history/{id}`）。
     * 仅 SyncClipboard HTTP 服务器支持。
     *
     * @param profileId 记录 ID（格式：`{type}-{hash}`）
     * @return 服务器返回的记录；404 返回 null
     */
    suspend fun getHistoryRecord(profileId: String): HistoryRecordDto?

    /**
     * 获取历史记录统计信息（原项目 `GET /api/history/statistics`）。
     * 仅 SyncClipboard HTTP 服务器支持。
     */
    suspend fun getHistoryStatistics(): HistoryStatisticsDto?

    /**
     * 下载历史记录数据文件（原项目 `GET /api/history/{id}/data`）。
     * 仅 SyncClipboard HTTP 服务器支持。
     *
     * @param hash 记录 hash
     * @param destinationPath 本地保存路径
     * @return 下载成功返回路径，失败返回 null
     */
    suspend fun downloadHistoryData(hash: String, destinationPath: String): String?

    /**
     * 获取服务器时间（原项目 `GET /api/time`）。
     * 用于时间差校验，仅 SyncClipboard HTTP 服务器支持。
     *
     * @return 服务器时间戳（毫秒），失败返回 null
     */
    suspend fun getServerTime(): Long?

    /** 测试服务器连接 */
    suspend fun testConnection()
}
