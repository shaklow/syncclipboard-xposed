package io.github.erenche.syncclipboard.xposed.history

import android.content.Context
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryRecordDto
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.model.HistorySyncStatus
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.erenche.syncclipboard.xposed.history.db.AppDatabase
import io.github.erenche.syncclipboard.xposed.history.db.HistoryItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/**
 * HistoryService — 剪贴板历史记录服务（Room 数据库后端）。
 *
 * - 本地剪贴板变化 → [addLocalContent]：syncStatus = LocalOnly，文件复制到持久化历史目录
 * - 从服务器下载 → [addRemoteContent]：syncStatus = Synced，使用已下载的文件路径
 * - 用 profileHash 去重，已存在时更新 syncStatus 和 lastAccessed
 *
 * 数据持久化由 Room 负责，每次写入自动增量更新，不再全量序列化 JSON。
 */
class HistoryService(context: Context) {

    private val ctx: Context = context.applicationContext

    companion object {
        private const val TAG = "HistoryService"
        private const val MAX_ITEMS = 1000
        /** 历史文件磁盘配额（字节）：超过后按最旧优先删除文件并标记对应记录 */
        private const val MAX_DISK_BYTES = 200L * 1024 * 1024
        /** 历史文件数量上限 */
        private const val MAX_DISK_FILES = 400
        /** enforceDiskLimit 最小执行间隔：避免高频写入时反复全目录扫描 */
        private const val DISK_LIMIT_INTERVAL_MS = 60_000L
    }

    /** 上次 enforceDiskLimit 执行时间（0 = 从未），批量写入时按间隔节流 */
    @Volatile
    private var lastDiskLimitCheck = 0L

    private val historyDir = File(context.filesDir, "history_files").apply { if (!exists()) mkdirs() }

    private val db = AppDatabase.getInstance(ctx)
    private val dao = db.historyItemDao()

    fun observeAll(): Flow<List<HistoryItem>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    /** 分页查询（置顶优先，按时间倒序），排除软删除 */
    fun getPaged(limit: Int = 50, offset: Int = 0): List<HistoryItem> {
        val result = dao.getPaged(offset, limit, null).map { it.toModel() }
        Logger.info(TAG, "getPaged: limit=$limit, offset=$offset, returned=${result.size}")
        return result
    }

    /**
     * 分页查询（支持搜索文本 + 类型筛选），置顶优先，按时间倒序，排除软删除。
     * 用于 UI 分页加载。
     *
     * @param typeFilter null = 全部；'starred' = 仅收藏；'Text'/'Image'/'File' = 按类型
     */
    fun getPaged(offset: Int, limit: Int, searchText: String?, typeFilter: String? = null): List<HistoryItem> {
        return dao.getPaged(offset, limit, searchText?.trim(), typeFilter).map { it.toModel() }
    }

    /** 符合搜索/筛选条件的活跃记录总数（用于 UI 计算总页数） */
    fun count(searchText: String?, typeFilter: String? = null): Int {
        return dao.count(searchText?.trim(), typeFilter)
    }

    /** 获取全部记录（置顶优先，按时间倒序），排除软删除 */
    fun getAll(): List<HistoryItem> {
        val result = dao.getAll().map { it.toModel() }
        Logger.info(TAG, "getAll: active=${result.size}")
        return result
    }

    fun getById(id: String): HistoryItem? =
        dao.getById(id)?.toModel()?.takeIf { !it.isDeleted }

    /**
     * 本地剪贴板变化时调用。
     * syncStatus = LocalOnly，如有文件复制到持久化历史目录。
     */
    fun addLocalContent(content: ClipboardContent) {
        val hash = (content.profileHash ?: computeHashForContent(content)).lowercase()
        Logger.info(TAG, "addLocalContent: type=${content.type}, hash=$hash, text=${content.text.take(50)}, hasData=${content.hasData}")
        db.runInTransaction {
            val existing = dao.getByProfileHash(hash)?.toModel()
            if (existing != null && !existing.isDeleted) {
                // 已存在，更新 syncStatus 和 lastAccessed
                dao.upsert(HistoryItemEntity.from(existing.copy(
                    syncStatus = HistorySyncStatus.LocalOnly,
                    lastAccessed = System.currentTimeMillis()
                )))
            } else {
                // 新增：如有文件，复制到持久化目录
                var fileUri: String? = null
                val srcUri = content.fileUri
                if (content.hasData && srcUri != null) {
                    fileUri = copyToHistoryDir(srcUri, content.fileName, hash)
                }
                dao.upsert(HistoryItemEntity.from(HistoryItem(
                    id = UUID.randomUUID().toString(),
                    type = content.type,
                    text = content.text,
                    profileHash = hash,
                    hasData = content.hasData,
                    dataName = content.fileName,
                    size = content.fileSize,
                    timestamp = content.timestamp,
                    syncStatus = HistorySyncStatus.LocalOnly,
                    fileUri = fileUri
                )))
                trimIfNeeded()
                // 仅新增了文件才需要检查磁盘配额（文本不占历史目录）
                if (fileUri != null) enforceDiskLimit()
            }
        }
    }

    /**
     * 从服务器下载时调用。
     * syncStatus = Synced。
     *
     * downloadPath 存在时**归档到持久化历史目录**（与 LocalOnly 统一存储，受磁盘 LRU 管理）；
     * 引擎 downloads/ 目录仅作为"当前文件"暂存（SyncEngine 下载新文件前清理旧文件）。
     */
    fun addRemoteContent(content: ClipboardContent, downloadPath: String? = null) {
        val hash = (content.profileHash ?: HashUtils.sha256(content.text)).lowercase()
        Logger.info(TAG, "addRemoteContent: type=${content.type}, hash=$hash, text=${content.text.take(50)}, hasData=${content.hasData}, downloadPath=$downloadPath")
        db.runInTransaction {
            val existing = dao.getByProfileHash(hash)?.toModel()
            if (existing != null && !existing.isDeleted) {
                // 已存在：已有本地归档副本则复用；否则用新下载的文件补一份归档
                val now = System.currentTimeMillis()
                if (existing.fileUri == null && downloadPath != null) {
                    val archived = copyToHistoryDir(downloadPath, content.fileName, hash)
                    dao.upsert(HistoryItemEntity.from(existing.copy(
                        syncStatus = HistorySyncStatus.Synced,
                        lastAccessed = now,
                        fileUri = archived ?: downloadPath
                    )))
                } else {
                    dao.upsert(HistoryItemEntity.from(existing.copy(
                        syncStatus = HistorySyncStatus.Synced,
                        lastAccessed = now
                    )))
                }
            } else {
                // 数据文件统一归档到 history_files（downloads/ 仅暂存当前文件）
                var fileUri: String? = downloadPath
                if (content.hasData && downloadPath != null) {
                    fileUri = copyToHistoryDir(downloadPath, content.fileName, hash) ?: downloadPath
                }
                dao.upsert(HistoryItemEntity.from(HistoryItem(
                    id = UUID.randomUUID().toString(),
                    type = content.type,
                    text = content.text,
                    profileHash = hash,
                    hasData = content.hasData,
                    dataName = content.fileName,
                    size = content.fileSize,
                    timestamp = content.timestamp,
                    syncStatus = HistorySyncStatus.Synced,
                    fileUri = fileUri
                )))
                trimIfNeeded()
                // 仅新增了文件才需要检查磁盘配额（文本不占历史目录）
                if (fileUri != null) enforceDiskLimit()
            }
        }
    }

    /**
     * 按服务器规则计算内容 hash（与 HashUtils.computeContentHash 对齐，额外支持 content:// URI）。
     * 分块流式计算，避免大文件整块读入内存。
     */
    private fun computeHashForContent(content: ClipboardContent): String {
        if (content.type == ClipboardContentType.Text || !content.hasData ||
            content.fileName.isNullOrBlank() || content.fileUri.isNullOrBlank()) {
            return HashUtils.sha256(content.text)
        }
        val fileUri = content.fileUri!!
        val fileName = content.fileName!!
        return try {
            if (fileUri.startsWith("content://")) {
                val uri = android.net.Uri.parse(fileUri)
                val input = ctx.contentResolver.openInputStream(uri)
                if (input != null) HashUtils.computeFileHash(fileName, input)
                else HashUtils.sha256(content.text)
            } else {
                val f = java.io.File(fileUri)
                if (f.exists()) HashUtils.computeFileHash(fileName, f)
                else HashUtils.sha256(content.text)
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "computeHashForContent: fallback to text hash: ${e.message}")
            HashUtils.sha256(content.text)
        }
    }

    fun delete(id: String) {
        val item = dao.getById(id)?.toModel() ?: return
        // 标记 NeedSync 以便下次同步推送删除事件到服务器
        val newStatus = if (item.syncStatus == HistorySyncStatus.Synced) HistorySyncStatus.NeedSync else item.syncStatus
        dao.upsert(HistoryItemEntity.from(item.copy(
            isDeleted = true,
            syncStatus = newStatus,
            lastModified = System.currentTimeMillis()
        )))
        // 清理持久化的物理文件
        deleteHistoryFile(item.fileUri)
    }

    /** 清空所有历史记录（软删除） */
    fun clearAll() {
        // 先收集待删除的文件路径，再软删除
        val activeItems = dao.getAll().map { it.toModel() }
        dao.softDeleteAll()
        activeItems.forEach { deleteHistoryFile(it.fileUri) }
        Logger.info(TAG, "clearAll: marked all active items as deleted (local only), files cleaned=${activeItems.size}")
    }

    /** 删除持久化目录中的历史文件（如果存在） */
    private fun deleteHistoryFile(fileUri: String?) {
        if (fileUri.isNullOrBlank()) return
        try {
            val file = File(fileUri)
            if (file.exists() && file.parentFile?.absolutePath == historyDir.absolutePath) {
                if (file.delete()) {
                    Logger.debug(TAG, "Deleted history file: $fileUri")
                }
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to delete history file: $fileUri, ${e.message}")
        }
    }

    fun toggleStar(id: String) {
        val item = dao.getById(id)?.toModel() ?: return
        val newStatus = if (item.syncStatus == HistorySyncStatus.Synced) HistorySyncStatus.NeedSync else item.syncStatus
        dao.upsert(HistoryItemEntity.from(item.copy(
            starred = !item.starred,
            syncStatus = newStatus,
            lastModified = System.currentTimeMillis()
        )))
    }

    fun togglePin(id: String) {
        val item = dao.getById(id)?.toModel() ?: return
        val newStatus = if (item.syncStatus == HistorySyncStatus.Synced) HistorySyncStatus.NeedSync else item.syncStatus
        dao.upsert(HistoryItemEntity.from(item.copy(
            pinned = !item.pinned,
            syncStatus = newStatus,
            lastModified = System.currentTimeMillis()
        )))
    }

    fun search(query: String): List<HistoryItem> =
        dao.getPaged(0, Int.MAX_VALUE, query).map { it.toModel() }

    fun count(): Int = dao.countActive()

    // ─── 服务器历史同步 ──────────────────────────────────────────

    /**
     * 将本地历史中未同步到服务器的项（syncStatus = LocalOnly）导出为 DTO 列表，
     * 用于上传到服务器。
     */
    fun getUnsyncedRecords(): List<Pair<HistoryItem, String?>> {
        return dao.getLocalOnlyActive().map { entity ->
            val model = entity.toModel()
            model to model.fileUri
        }
    }

    /**
     * 将单个 HistoryItem 转换为 HistoryRecordDto。
     */
    fun toDto(item: HistoryItem): HistoryRecordDto {
        return HistoryRecordDto(
            hash = item.profileHash,
            type = item.type,
            text = item.text,
            createTime = java.time.Instant.ofEpochMilli(item.timestamp).toString(),
            lastModified = java.time.Instant.ofEpochMilli(item.lastModified).toString(),
            lastAccessed = java.time.Instant.ofEpochMilli(item.lastAccessed).toString(),
            starred = item.starred,
            pinned = item.pinned,
            size = item.size,
            hasData = item.hasData,
            version = item.version,
            isDeleted = item.isDeleted
        )
    }

    /**
     * 从服务器历史 DTO 合并到本地。
     *
     * 冲突解决逻辑：
     * - 本地不存在：添加（服务器项）
     * - 本地存在：按 5 分钟时间阈值 + 版本号判断胜方
     * - 本地已软删除：根据 syncStatus 决定是否从服务器恢复
     *
     * 实现方式（对齐 Windows 端 EF Core 做法）：
     * - 不全量加载本地记录到内存，改为一次性批量查询所有 DTO 对应的本地记录（大小写不敏感）
     * - 仅收集实际发生变更/新增的记录，批量 upsert，避免无谓的全表写回
     */
    fun mergeFromServerDtos(serverDtos: List<HistoryRecordDto>) {
        if (serverDtos.isEmpty()) {
            Logger.info(TAG, "mergeFromServerDtos: empty input, skip")
            return
        }
        db.runInTransaction {
            val TIME_THRESHOLD_MS = 5 * 60 * 1000L
            var added = 0
            var remoteUpdated = 0
            var localNeedSync = 0
            // 仅收集实际变更的记录，避免全表 upsert
            val changedItems = mutableListOf<HistoryItem>()

            // 一次性批量查询所有本地已存在的记录（大小写不敏感）
            // 避免 N 次 getByProfileHash 查询；服务器 hash 通常为大写，本地可能存大写或小写
            val dtoHashesLower = serverDtos.map { it.hash.lowercase() }.distinct()
            val existingMap = dao.getByProfileHashesIgnoreCase(dtoHashesLower)
                .associateBy { it.profileHash.lowercase() }

            for (dto in serverDtos) {
                val key = dto.hash.lowercase()
                val existing = existingMap[key]?.toModel()

                if (existing == null) {
                    // 本地不存在：跳过已删除的，添加未删除的
                    if (dto.isDeleted != true) {
                        val ts = parseIsoTime(dto.createTime) ?: System.currentTimeMillis()
                        val modified = parseIsoTime(dto.lastModified) ?: ts
                        changedItems += HistoryItem(
                            id = UUID.randomUUID().toString(),
                            type = dto.type,
                            text = dto.text ?: "",
                            profileHash = key, // 统一存小写，避免大小写不一致导致后续查询失败
                            hasData = dto.hasData ?: (dto.type == ClipboardContentType.Image || dto.type == ClipboardContentType.File),
                            dataName = getDataNameFromDto(dto),
                            size = dto.size,
                            timestamp = ts,
                            syncStatus = HistorySyncStatus.Synced,
                            fileUri = null,
                            version = dto.version ?: 0,
                            lastModified = modified,
                            lastAccessed = System.currentTimeMillis(),
                            isDeleted = false,
                            starred = dto.starred ?: false,
                            pinned = dto.pinned ?: false
                        )
                        added++
                    }
                    continue
                }

                // 本地已存在：版本冲突解决
                val remoteVersion = dto.version ?: 0
                val localVersion = existing.version
                val remoteModified = parseIsoTime(dto.lastModified) ?: 0L
                val localModified = existing.lastModified
                val timeDiff = Math.abs(remoteModified - localModified)

                // 特殊情况处理：本地已软删除
                if (existing.isDeleted) {
                    if (existing.syncStatus == HistorySyncStatus.NeedSync) {
                        // 用户主动删除，等待 PATCH 推送，不恢复
                        continue
                    }
                    if (existing.syncStatus == HistorySyncStatus.LocalOnly) {
                        // PATCH 404 降级为 LocalOnly，不从服务器恢复
                        continue
                    }
                    // clearAll 批量清除（syncStatus=Synced）
                    if (dto.isDeleted != true) {
                        // 服务器活跃 → 从服务器恢复
                        changedItems += existing.copy(
                            text = dto.text ?: existing.text,
                            starred = dto.starred ?: existing.starred,
                            pinned = dto.pinned ?: existing.pinned,
                            version = remoteVersion,
                            lastModified = remoteModified,
                            syncStatus = HistorySyncStatus.Synced,
                            isDeleted = false,
                            fileUri = existing.fileUri,
                            hasData = existing.hasData || (dto.hasData ?: (dto.type == ClipboardContentType.Image || dto.type == ClipboardContentType.File)),
                            dataName = existing.dataName ?: getDataNameFromDto(dto)
                        )
                        remoteUpdated++
                    }
                    continue
                }

                val shouldUpdateFromRemote: Boolean
                val isLocalNewer: Boolean
                if (timeDiff > TIME_THRESHOLD_MS) {
                    shouldUpdateFromRemote = remoteModified > localModified
                    isLocalNewer = localModified > remoteModified
                } else {
                    shouldUpdateFromRemote = remoteVersion > localVersion
                    isLocalNewer = localVersion > remoteVersion
                }

                when {
                    shouldUpdateFromRemote -> {
                        // 服务器推送删除时，清理本地物理文件
                        if (dto.isDeleted == true) {
                            deleteHistoryFile(existing.fileUri)
                        }
                        changedItems += existing.copy(
                            text = dto.text ?: existing.text,
                            starred = dto.starred ?: existing.starred,
                            pinned = dto.pinned ?: existing.pinned,
                            version = remoteVersion,
                            lastModified = remoteModified,
                            syncStatus = HistorySyncStatus.Synced,
                            isDeleted = dto.isDeleted ?: false,
                            fileUri = if (dto.isDeleted == true) null else existing.fileUri,
                            hasData = existing.hasData || (dto.hasData ?: (dto.type == ClipboardContentType.Image || dto.type == ClipboardContentType.File)),
                            dataName = existing.dataName ?: getDataNameFromDto(dto)
                        )
                        remoteUpdated++
                    }
                    isLocalNewer -> {
                        if (existing.syncStatus != HistorySyncStatus.NeedSync) {
                            changedItems += existing.copy(syncStatus = HistorySyncStatus.NeedSync)
                            localNeedSync++
                        }
                    }
                    else -> {
                        // 版本相同：标记 Synced，补全 hasData/dataName
                        val correctHasData = dto.hasData ?: (dto.type == ClipboardContentType.Image || dto.type == ClipboardContentType.File)
                        val needFix = existing.dataName.isNullOrBlank() || !existing.hasData
                        if (existing.syncStatus != HistorySyncStatus.Synced || needFix) {
                            changedItems += existing.copy(
                                syncStatus = HistorySyncStatus.Synced,
                                hasData = existing.hasData || correctHasData,
                                dataName = existing.dataName ?: getDataNameFromDto(dto),
                                text = dto.text ?: existing.text
                            )
                        }
                    }
                }
            }

            // 仅写入实际变更的记录（增量 upsert）
            if (changedItems.isNotEmpty()) {
                dao.upsertAll(changedItems.map { HistoryItemEntity.from(it) })
            }
            trimIfNeeded()
            Logger.info(TAG, "mergeFromServerDtos: server=${serverDtos.size}, added=$added, remoteUpdated=$remoteUpdated, localNeedSync=$localNeedSync, changed=${changedItems.size}")
        }
    }

    /**
     * 孤儿记录检测：服务器缺失的记录处理。
     *
     * - Synced 记录：服务器缺失时，有本地数据→降级 LocalOnly；无本地数据→物理删除
     * - server-only 记录（hasData 但无 fileUri）：服务器缺失→物理删除
     *
     * 实现方式（对齐 Windows 端 EF Core 做法）：
     * - 不全量加载本地记录，只查"孤儿候选集"（Synced + server-only 的活跃记录）
     * - 在内存中按 serverHashes 过滤出真正的孤儿
     * - 候选集远小于全表，内存过滤开销可忽略
     */
    fun detectOrphanRecords(serverHashes: Set<String>): Int {
        var orphanCount = 0
        db.runInTransaction {
            // 仅查孤儿候选集，避免全表加载
            val candidates = dao.findOrphanCandidates().map { it.toModel() }
            val toPhysicalDelete = mutableListOf<String>()
            val toDowngrade = mutableListOf<HistoryItem>()

            for (item in candidates) {
                if (serverHashes.contains(item.profileHash.lowercase())) continue

                // 服务器不存在此记录
                val isServerOnly = item.hasData && item.fileUri.isNullOrBlank()
                if (isServerOnly) {
                    // server-only 记录（无本地文件副本）：物理删除
                    toPhysicalDelete.add(item.id)
                    orphanCount++
                } else {
                    // 本地有数据（文件或文本）：降级为 LocalOnly，与 Windows 端一致
                    // 文本记录 hasData=false, fileUri=null，本身即完整数据，不应物理删除
                    toDowngrade += item.copy(syncStatus = HistorySyncStatus.LocalOnly)
                    orphanCount++
                }
            }

            if (toDowngrade.isNotEmpty()) {
                dao.upsertAll(toDowngrade.map { HistoryItemEntity.from(it) })
            }
            if (toPhysicalDelete.isNotEmpty()) {
                dao.deleteByIds(toPhysicalDelete)
            }
            Logger.info(TAG, "detectOrphanRecords: candidates=${candidates.size}, orphans=$orphanCount")
        }
        return orphanCount
    }

    /** 获取所有 NeedSync 状态的记录（用于推送元数据变更到服务器） */
    fun getNeedSyncItems(): List<HistoryItem> =
        dao.getNeedSyncItems().map { it.toModel() }

    /** 按 profileHash 获取记录 */
    fun getItemByProfileHash(profileHash: String): HistoryItem? =
        dao.getByProfileHash(profileHash)?.toModel()

    /**
     * 全量同步前的重置：清除所有 isDeleted 记录的 NeedSync 标记。
     */
    fun resetDeletedForFullSync() {
        dao.resetDeletedForFullSync()
    }

    /** 标记某项为 NeedSync（本地变更后调用） */
    fun markAsNeedSync(profileHash: String) {
        val item = dao.getByProfileHash(profileHash)?.toModel() ?: return
        if (!item.isDeleted && item.syncStatus != HistorySyncStatus.NeedSync) {
            dao.upsert(HistoryItemEntity.from(item.copy(
                syncStatus = HistorySyncStatus.NeedSync,
                lastModified = System.currentTimeMillis()
            )))
        }
    }

    /** 标记某项已成功上传到服务器 */
    fun markAsSynced(profileHash: String) {
        val item = dao.getByProfileHash(profileHash)?.toModel() ?: return
        if (!item.isDeleted && item.syncStatus != HistorySyncStatus.Synced) {
            dao.upsert(HistoryItemEntity.from(item.copy(syncStatus = HistorySyncStatus.Synced)))
        }
    }

    /** 服务器返回 404 时，将记录降级为 LocalOnly（等待重新上传） */
    fun markAsLocalOnly(profileHash: String) {
        val item = dao.getByProfileHash(profileHash)?.toModel() ?: return
        // 包含已删除记录：否则用户删除的 NeedSync 项 PATCH 404 后无法降级
        if (item.syncStatus != HistorySyncStatus.LocalOnly) {
            dao.upsert(HistoryItemEntity.from(item.copy(syncStatus = HistorySyncStatus.LocalOnly)))
        }
    }

    /**
     * 应用服务器返回的更新到本地记录（PATCH 成功或冲突时）。
     * 保留本地 fileUri，更新元数据，标记 Synced。
     */
    fun applyServerUpdate(profileHash: String, server: HistoryRecordDto) {
        val item = dao.getByProfileHash(profileHash)?.toModel() ?: return
        val modified = parseIsoTime(server.lastModified) ?: System.currentTimeMillis()
        dao.upsert(HistoryItemEntity.from(item.copy(
            starred = server.starred ?: item.starred,
            pinned = server.pinned ?: item.pinned,
            version = server.version ?: item.version,
            lastModified = modified,
            syncStatus = HistorySyncStatus.Synced,
            isDeleted = server.isDeleted ?: item.isDeleted,
            fileUri = if (server.isDeleted == true) null else item.fileUri
        )))
    }

    /**
     * 409 冲突时仅更新本地 version（保持 NeedSync 和 isDeleted 等本地状态）。
     */
    fun updateVersionOnly(profileHash: String, server: HistoryRecordDto) {
        val item = dao.getByProfileHash(profileHash)?.toModel() ?: return
        val modified = parseIsoTime(server.lastModified) ?: item.lastModified
        dao.upsert(HistoryItemEntity.from(item.copy(
            version = server.version ?: item.version,
            lastModified = modified
        )))
    }

    /** 开启批量模式（Room 自动处理持久化，此方法保留为空实现以兼容调用方） */
    fun beginBatch() {}

    /** 结束批量模式（Room 自动处理持久化，此方法保留为空实现以兼容调用方） */
    fun endBatch() {}

    // ─── 内部方法 ────────────────────────────────────────────────

    /** 复制文件到持久化历史目录，返回新文件路径。
     *  支持本地路径与 content:// URI（如分享上传时 app FileProvider 授权的 URI）。 */
    private fun copyToHistoryDir(sourceUri: String, fileName: String?, hash: String): String? {
        return try {
            val name = fileName ?: "file_$hash"
            val dest = File(historyDir, "${hash}_${name}")
            if (sourceUri.startsWith("content://")) {
                val uri = android.net.Uri.parse(sourceUri)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    Logger.warn(TAG, "openInputStream returned null for $sourceUri")
                    return null
                }
            } else {
                val src = File(sourceUri)
                if (!src.exists()) {
                    Logger.warn(TAG, "Source file not exists: $sourceUri")
                    return null
                }
                src.copyTo(dest, overwrite = true)
            }
            Logger.debug(TAG, "Copied to history dir: $sourceUri -> ${dest.absolutePath}")
            dest.absolutePath
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to copy to history dir: $sourceUri", e)
            null
        }
    }

    /** 超过 MAX_ITEMS 时软删除最旧的非置顶记录 */
    private fun trimIfNeeded() {
        val activeCount = dao.countActive()
        if (activeCount > MAX_ITEMS) {
            val excess = activeCount - MAX_ITEMS
            val ids = dao.getOldestActiveIds(excess)
            if (ids.isNotEmpty()) {
                dao.softDeleteByIds(ids)
                Logger.info(TAG, "Trimmed ${ids.size} old items")
            }
        }
    }

    /**
     * 历史文件磁盘配额：总大小/数量超限时，按**最近访问时间（lastAccessed）从旧到新**淘汰
     * （严格 LRU；孤儿文件视为最旧，最先删）。60s 节流，避免高频写入时反复全目录扫描。
     *
     * 淘汰语义：
     * - 已同步记录（Synced）：仅删除本地文件副本并清空 fileUri，**保留记录与服务器数据**，
     *   需要时可重新下载；不标记 isDeleted，也不会把删除同步回服务器。
     * - 本地独有记录（LocalOnly）：文件是唯一副本，放弃数据并软删除记录（服务器本就没有）。
     * - 孤儿文件（DB 无对应记录）：直接删除。
     */
    fun enforceDiskLimit() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastDiskLimitCheck < DISK_LIMIT_INTERVAL_MS) return
            lastDiskLimitCheck = now
        }
        val files = historyDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        if (total <= MAX_DISK_BYTES && count <= MAX_DISK_FILES) return

        data class Entry(val file: File, val item: HistoryItem?, val len: Long)

        // 严格 LRU：按 lastAccessed 升序（孤儿文件 = 最早，排最前），同访问时间按文件 mtime 兜底
        val entries = files.map { f ->
            val hash = f.name.substringBefore('_')
            val item = runCatching { dao.getByProfileHash(hash)?.toModel() }.getOrNull()
            Entry(f, item, f.length())
        }.sortedWith(compareBy({ it.item?.lastAccessed ?: 0L }, { it.file.lastModified() }))

        var removed = 0
        for (e in entries) {
            if (total <= MAX_DISK_BYTES && count <= MAX_DISK_FILES) break
            val item = e.item
            if (item != null && !item.isDeleted) {
                try {
                    if (item.syncStatus == HistorySyncStatus.Synced) {
                        // 保留服务器数据：仅清本地文件副本
                        dao.upsert(HistoryItemEntity.from(item.copy(fileUri = null)))
                    } else {
                        // 本地独有：文件是唯一副本，连同记录一并放弃
                        dao.softDeleteById(item.id)
                    }
                } catch (ex: Exception) {
                    Logger.warn(TAG, "enforceDiskLimit: failed to update ${e.file.name}: ${ex.message}")
                }
            }
            // 删除前记录大小：file.delete() 后 length() 返回 0，total 将无法递减
            if (e.file.delete()) {
                total -= e.len
                count -= 1
                removed++
            }
        }
        if (removed > 0) {
            Logger.info(TAG, "enforceDiskLimit: removed $removed file(s), remaining bytes=$total")
        }
    }

    /** 解析 ISO 8601 时间字符串为毫秒时间戳 */
    private fun parseIsoTime(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.LocalDateTime.parse(iso)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e2: Exception) { null }
        }
    }

    /**
     * 从 DTO 推导数据文件名。
     * 服务器 history API 不返回 dataName，对 Image/File 类型用 text 字段作为文件名。
     */
    private fun getDataNameFromDto(dto: HistoryRecordDto): String? {
        return when (dto.type) {
            ClipboardContentType.File, ClipboardContentType.Image ->
                dto.text?.takeIf { it.isNotBlank() } ?: "${dto.type.name.lowercase()}_${dto.hash.take(8)}"
            ClipboardContentType.Text -> {
                if (dto.hasData != true) return null
                val ts = System.currentTimeMillis()
                val rand = (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                "Text_${ts}_$rand.txt"
            }
            ClipboardContentType.Group -> {
                if (dto.hasData != true) return null
                val ts = System.currentTimeMillis()
                val rand = (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
                "Group_${ts}_$rand.zip"
            }
        }
    }
}
