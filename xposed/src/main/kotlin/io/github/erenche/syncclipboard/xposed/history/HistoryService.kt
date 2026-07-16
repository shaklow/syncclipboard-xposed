package io.github.erenche.syncclipboard.xposed.history

import android.content.Context
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryRecordDto
import io.github.erenche.syncclipboard.common.model.HistoryItem
import io.github.erenche.syncclipboard.common.model.HistorySyncStatus
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * HistoryService — 剪贴板历史记录服务（参考原项目逻辑）。
 *
 * - 本地剪贴板变化 → [addLocalContent]：syncStatus = LocalOnly，文件复制到持久化历史目录
 * - 从服务器下载 → [addRemoteContent]：syncStatus = Synced，使用已下载的文件路径
 * - 用 profileHash 去重，已存在时更新 syncStatus 和 lastAccessed
 */
class HistoryService(context: Context) {

    companion object {
        private const val TAG = "HistoryService"
        private const val MAX_ITEMS = 1000
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val historyFile = File(context.filesDir, "clipboard_history.json")
    private val historyDir = File(context.filesDir, "history_files").apply { if (!exists()) mkdirs() }
    private val lock = Any()

    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: Flow<List<HistoryItem>> = _items.asStateFlow()

    init { loadFromDisk() }

    fun observeAll(): Flow<List<HistoryItem>> = items

    /** 分页查询（置顶优先，按时间倒序），排除软删除 */
    fun getPaged(limit: Int = 50, offset: Int = 0): List<HistoryItem> {
        val result = _items.value.asSequence()
            .filter { !it.isDeleted }
            .sortedWith(compareByDescending<HistoryItem> { if (it.pinned) 1 else 0 }
                .thenByDescending { it.timestamp })
            .drop(offset)
            .take(limit)
            .toList()
        Logger.info(TAG, "getPaged: limit=$limit, offset=$offset, total=${_items.value.size}, active=${_items.value.count { !it.isDeleted }}, returned=${result.size}")
        return result
    }

    /** 获取全部记录（置顶优先，按时间倒序），排除软删除 */
    fun getAll(): List<HistoryItem> {
        val result = _items.value.asSequence()
            .filter { !it.isDeleted }
            .sortedWith(compareByDescending<HistoryItem> { if (it.pinned) 1 else 0 }
                .thenByDescending { it.timestamp })
            .toList()
        Logger.info(TAG, "getAll: total=${_items.value.size}, active=${_items.value.count { !it.isDeleted }}, returned=${result.size}")
        return result
    }

    fun getById(id: String): HistoryItem? =
        _items.value.find { it.id == id && !it.isDeleted }

    /**
     * 本地剪贴板变化时调用（参考原项目 addLocalContent）。
     * syncStatus = LocalOnly，如有文件复制到持久化历史目录。
     */
    fun addLocalContent(content: ClipboardContent) {
        // 与服务器 hash 规则对齐：使用带类型前缀的 profileHash（text-<sha256>）
        val hash = content.profileHash ?: HashUtils.computeProfileHash(
            content.type.name.lowercase(),
            content.text
        )
        Logger.info(TAG, "addLocalContent: type=${content.type}, hash=$hash, text=${content.text.take(50)}, hasData=${content.hasData}")
        synchronized(lock) {
            val existing = _items.value.find { it.profileHash == hash && !it.isDeleted }
            if (existing != null) {
                // 已存在，更新 syncStatus 和 lastAccessed
                replaceItem(existing.copy(
                    syncStatus = HistorySyncStatus.LocalOnly,
                    lastAccessed = System.currentTimeMillis()
                ))
            } else {
                // 新增：如有文件，复制到持久化目录
                var fileUri: String? = null
                val srcUri = content.fileUri
                if (content.hasData && srcUri != null) {
                    fileUri = copyToHistoryDir(srcUri, content.fileName, hash)
                }
                addItem(HistoryItem(
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
                ))
                trimIfNeeded()
            }
        }
    }

    /**
     * 从服务器下载时调用（参考原项目 addRemoteContent）。
     * syncStatus = Synced，使用已下载的文件路径（downloadPath）。
     */
    fun addRemoteContent(content: ClipboardContent, downloadPath: String? = null) {
        val hash = content.profileHash ?: HashUtils.computeLocalHash(content.text)
        Logger.info(TAG, "addRemoteContent: type=${content.type}, hash=$hash, text=${content.text.take(50)}, hasData=${content.hasData}, downloadPath=$downloadPath")
        synchronized(lock) {
            val existing = _items.value.find { it.profileHash == hash && !it.isDeleted }
            if (existing != null) {
                // 已存在，更新为 Synced（远程同步过来的）
                replaceItem(existing.copy(
                    syncStatus = HistorySyncStatus.Synced,
                    lastAccessed = System.currentTimeMillis(),
                    fileUri = downloadPath ?: existing.fileUri
                ))
            } else {
                addItem(HistoryItem(
                    id = UUID.randomUUID().toString(),
                    type = content.type,
                    text = content.text,
                    profileHash = hash,
                    hasData = content.hasData,
                    dataName = content.fileName,
                    size = content.fileSize,
                    timestamp = content.timestamp,
                    syncStatus = HistorySyncStatus.Synced,
                    fileUri = downloadPath
                ))
                trimIfNeeded()
            }
        }
    }

    fun delete(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        // 标记 NeedSync 以便下次同步推送删除事件到服务器（与 toggleStar/togglePin 一致）
        val newStatus = if (item.syncStatus == HistorySyncStatus.Synced) HistorySyncStatus.NeedSync else item.syncStatus
        replaceItem(item.copy(isDeleted = true, syncStatus = newStatus, lastModified = System.currentTimeMillis()))
    }

    /** 清空所有历史记录（软删除） */
    fun clearAll() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            // 仅本地软删除，不标记 NeedSync（不推送删除到服务器）
            // 下次全量同步时，mergeFromServerDtos 会从服务器恢复活跃记录
            _items.value = _items.value.map {
                it.copy(
                    isDeleted = true,
                    lastModified = now
                )
            }
            saveToDisk()
            Logger.info(TAG, "clearAll: marked ${_items.value.size} items as deleted (local only)")
        }
    }

    fun toggleStar(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        // 已同步的记录本地变更后需要重新同步
        val newStatus = if (item.syncStatus == HistorySyncStatus.Synced) HistorySyncStatus.NeedSync else item.syncStatus
        replaceItem(item.copy(starred = !item.starred, syncStatus = newStatus, lastModified = System.currentTimeMillis()))
    }

    fun togglePin(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        val newStatus = if (item.syncStatus == HistorySyncStatus.Synced) HistorySyncStatus.NeedSync else item.syncStatus
        replaceItem(item.copy(pinned = !item.pinned, syncStatus = newStatus, lastModified = System.currentTimeMillis()))
    }

    fun search(query: String): List<HistoryItem> =
        _items.value.asSequence()
            .filter { !it.isDeleted && it.text.contains(query, ignoreCase = true) }
            .sortedByDescending { it.timestamp }
            .toList()

    fun count(): Int = _items.value.count { !it.isDeleted }

    // ─── 服务器历史同步（使用原项目 /api/history API）──────────────

    /**
     * 将本地历史中未同步到服务器的项（syncStatus = LocalOnly）导出为 DTO 列表，
     * 用于上传到服务器。
     */
    fun getUnsyncedRecords(): List<Pair<HistoryItem, String?>> {
        return _items.value
            .filter { !it.isDeleted && it.syncStatus == HistorySyncStatus.LocalOnly }
            .map { it to it.fileUri }
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
     * 从服务器历史 DTO 合并到本地（对齐原项目逻辑）。
     *
     * - 本地不存在：添加（服务器项）
     * - 本地存在：按 5 分钟时间阈值 + 版本号判断：
     *   - 时间差 > 5 分钟：较新的一方胜出
     *   - 时间差 ≤ 5 分钟：版本号大的一方胜出
     *   - 远程较新：更新本地，标记 Synced
     *   - 本地较新：标记 NeedSync，等待上传
     *   - 相同：标记 Synced
     */
    fun mergeFromServerDtos(serverDtos: List<HistoryRecordDto>) {
        synchronized(lock) {
            val localItems = _items.value.toMutableList()
            // 包含已软删除的项，用于匹配服务器删除事件
            val localHashes = localItems.associateBy { it.profileHash.lowercase() }.toMutableMap()

            val TIME_THRESHOLD_MS = 5 * 60 * 1000L
            var added = 0
            var remoteUpdated = 0
            var localNeedSync = 0

            for (dto in serverDtos) {
                val key = dto.hash.lowercase()
                val existing = localHashes[key]

                if (existing == null) {
                    // 本地不存在：跳过已删除的，添加未删除的
                    if (dto.isDeleted != true) {
                        val ts = parseIsoTime(dto.createTime) ?: System.currentTimeMillis()
                        val modified = parseIsoTime(dto.lastModified) ?: ts
                        val newItem = HistoryItem(
                            id = UUID.randomUUID().toString(),
                            type = dto.type,
                            text = dto.text ?: "",
                            profileHash = dto.hash,
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
                        localItems.add(0, newItem)
                        localHashes[key] = newItem
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
                        // 用户主动删除（deleteItem），等待 PATCH 推送到服务器，不恢复
                        continue
                    }
                    if (existing.syncStatus == HistorySyncStatus.LocalOnly) {
                        // 用户删除后 PATCH 404 降级为 LocalOnly（服务器不存在或不匹配）
                        // 不从服务器恢复，保留用户删除意图
                        continue
                    }
                    // clearAll 批量清除（syncStatus=Synced）
                    if (dto.isDeleted != true) {
                        // 服务器活跃 → 从服务器恢复
                        val idx = localItems.indexOfFirst { it.id == existing.id }
                        if (idx >= 0) {
                            localItems[idx] = existing.copy(
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
                    }
                    // 服务器也已删除（dto.isDeleted == true）：双方一致，无需操作
                    // 必须 continue，否则会落入版本冲突解决，本地 lastModified 较新时
                    // 会被错误标记为 NeedSync，触发无意义的 PATCH 404
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

                val idx = localItems.indexOfFirst { it.id == existing.id }
                if (idx < 0) continue

                when {
                    shouldUpdateFromRemote -> {
                        // 远程较新：更新本地
                        localItems[idx] = existing.copy(
                            text = dto.text ?: existing.text,
                            starred = dto.starred ?: existing.starred,
                            pinned = dto.pinned ?: existing.pinned,
                            version = remoteVersion,
                            lastModified = remoteModified,
                            syncStatus = HistorySyncStatus.Synced,
                            isDeleted = dto.isDeleted ?: false,
                            // 远程标记删除时清空 fileUri
                            fileUri = if (dto.isDeleted == true) null else existing.fileUri,
                            hasData = existing.hasData || (dto.hasData ?: (dto.type == ClipboardContentType.Image || dto.type == ClipboardContentType.File)),
                            dataName = existing.dataName ?: getDataNameFromDto(dto)
                        )
                        remoteUpdated++
                    }
                    isLocalNewer -> {
                        // 本地较新：标记 NeedSync
                        if (existing.syncStatus != HistorySyncStatus.NeedSync) {
                            localItems[idx] = existing.copy(syncStatus = HistorySyncStatus.NeedSync)
                            localNeedSync++
                        }
                    }
                    else -> {
                        // 版本相同：标记 Synced，补全 hasData/dataName（旧版同步可能为 null/false）
                        val correctHasData = dto.hasData ?: (dto.type == ClipboardContentType.Image || dto.type == ClipboardContentType.File)
                        val needFix = existing.dataName.isNullOrBlank() || !existing.hasData
                        if (existing.syncStatus != HistorySyncStatus.Synced || needFix) {
                            localItems[idx] = existing.copy(
                                syncStatus = HistorySyncStatus.Synced,
                                hasData = existing.hasData || correctHasData,
                                dataName = existing.dataName ?: getDataNameFromDto(dto),
                                text = dto.text ?: existing.text
                            )
                        }
                    }
                }
            }

            _items.value = localItems
            trimIfNeeded()
            saveToDisk()
            Logger.info(TAG, "mergeFromServerDtos: server=${serverDtos.size}, added=$added, remoteUpdated=$remoteUpdated, localNeedSync=$localNeedSync, total=${localItems.count { !it.isDeleted }}")
        }
    }

    /**
     * 孤儿记录检测（对齐原项目 detectOrphanData）。
     *
     * 检测范围（与 RN 一致）：
     * - Synced 记录：服务器缺失时，有本地数据→降级 LocalOnly；无本地数据→物理删除
     * - server-only 记录（hasData 但无 fileUri）：服务器缺失→物理删除
     *
     * 删除方式：物理删除（与 RN physicalDeleteItem 一致）
     *
     * @param serverHashes 服务器返回的所有 hash 集合（小写）
     * @return 被处理的孤儿记录数量
     */
    fun detectOrphanRecords(serverHashes: Set<String>): Int {
        synchronized(lock) {
            var orphanCount = 0
            val localItems = _items.value.toMutableList()
            val result = mutableListOf<HistoryItem>()
            var changed = false

            for (item in localItems) {
                if (item.isDeleted) {
                    // 跳过软删除的记录（保留在列表中，由过期清理处理）
                    result.add(item)
                    continue
                }

                val isSynced = item.syncStatus == HistorySyncStatus.Synced
                // isLocalFileReady = !hasData || !fileUri.isNullOrBlank()
                // isServerOnly = !isLocalFileReady = hasData && fileUri.isNullOrBlank()
                val isServerOnly = item.hasData && item.fileUri.isNullOrBlank()

                if (!isSynced && !isServerOnly) {
                    result.add(item)
                    continue
                }

                if (serverHashes.contains(item.profileHash.lowercase())) {
                    result.add(item)
                    continue
                }

                // 服务器不存在此记录
                if (isServerOnly) {
                    // 本地无数据：物理删除
                    orphanCount++
                    changed = true
                } else if (!item.fileUri.isNullOrBlank()) {
                    // 本地有数据：降级为 LocalOnly
                    result.add(item.copy(syncStatus = HistorySyncStatus.LocalOnly))
                    orphanCount++
                    changed = true
                } else {
                    // 本地无数据：物理删除
                    orphanCount++
                    changed = true
                }
            }

            if (changed) {
                _items.value = result
                saveToDisk()
            }
            Logger.info(TAG, "detectOrphanRecords: orphans=$orphanCount")
            return orphanCount
        }
    }

    /**
     * 获取所有 NeedSync 状态的记录（用于推送元数据变更到服务器）。
     */
    fun getNeedSyncItems(): List<HistoryItem> {
        // 包含已删除的记录（用于推送删除事件到服务器）
        return _items.value.filter { it.syncStatus == HistorySyncStatus.NeedSync }
    }

    /**
     * 按 profileHash 获取记录（用于冲突后重试读取最新状态）
     */
    fun getItemByProfileHash(profileHash: String): HistoryItem? {
        synchronized(lock) {
            return _items.value.find { it.profileHash == profileHash }
        }
    }

    /**
     * 全量同步前的重置：清除所有 isDeleted 记录的 NeedSync 标记。
     * 用于处理历史残留数据（如旧版本 clearAll 错误标记的 NeedSync），
     * 让 mergeFromServerDtos 的恢复逻辑能正常工作。
     */
    fun resetDeletedForFullSync() {
        synchronized(lock) {
            val needReset = _items.value.any { it.isDeleted && it.syncStatus == HistorySyncStatus.NeedSync }
            if (needReset) {
                _items.value = _items.value.map {
                    if (it.isDeleted && it.syncStatus == HistorySyncStatus.NeedSync) {
                        it.copy(syncStatus = HistorySyncStatus.Synced)
                    } else it
                }
                saveToDisk()
                Logger.info(TAG, "resetDeletedForFullSync: reset isDeleted NeedSync items to Synced")
            }
        }
    }

    /**
     * 标记某项为 NeedSync（本地变更后调用）。
     */
    fun markAsNeedSync(profileHash: String) {
        synchronized(lock) {
            val item = _items.value.find { it.profileHash == profileHash && !it.isDeleted }
            if (item != null && item.syncStatus != HistorySyncStatus.NeedSync) {
                replaceItem(item.copy(syncStatus = HistorySyncStatus.NeedSync, lastModified = System.currentTimeMillis()))
            }
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
     * 从 DTO 推导数据文件名（与 RN 项目 convert.ts getDataNameFromDto 一致）。
     * 服务器 history API 不返回 dataName，对 Image/File 类型用 text 字段作为文件名。
     * Image/File 类型天然有二进制数据，不依赖 hasData 字段。
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

    /** 标记某项已成功上传到服务器 */
    fun markAsSynced(profileHash: String) {
        synchronized(lock) {
            val item = _items.value.find { it.profileHash == profileHash && !it.isDeleted }
            if (item != null && item.syncStatus != HistorySyncStatus.Synced) {
                replaceItem(item.copy(syncStatus = HistorySyncStatus.Synced))
            }
        }
    }

    /** 服务器返回 404 时，将记录降级为 LocalOnly（等待重新上传） */
    fun markAsLocalOnly(profileHash: String) {
        synchronized(lock) {
            // 包含已删除记录：否则用户删除的 NeedSync 项 PATCH 404 后无法降级，
            // 导致每次同步都重试 PATCH，浪费时间
            val item = _items.value.find { it.profileHash == profileHash }
            if (item != null && item.syncStatus != HistorySyncStatus.LocalOnly) {
                replaceItem(item.copy(syncStatus = HistorySyncStatus.LocalOnly))
            }
        }
    }

    /**
     * 应用服务器返回的更新到本地记录（PATCH 成功或冲突时）。
     * 保留本地 fileUri，更新元数据，标记 Synced。
     */
    fun applyServerUpdate(profileHash: String, server: HistoryRecordDto) {
        synchronized(lock) {
            val item = _items.value.find { it.profileHash == profileHash } ?: return
            val modified = parseIsoTime(server.lastModified) ?: System.currentTimeMillis()
            replaceItem(item.copy(
                starred = server.starred ?: item.starred,
                pinned = server.pinned ?: item.pinned,
                version = server.version ?: item.version,
                lastModified = modified,
                syncStatus = HistorySyncStatus.Synced,
                isDeleted = server.isDeleted ?: item.isDeleted,
                fileUri = if (server.isDeleted == true) null else item.fileUri
            ))
        }
    }

    /**
     * 409 冲突时仅更新本地 version（保持 NeedSync 和 isDeleted 等本地状态）。
     * 这样下次 PATCH 会用新 version 重试，不会丢失用户的删除/修改意图。
     */
    fun updateVersionOnly(profileHash: String, server: HistoryRecordDto) {
        synchronized(lock) {
            val item = _items.value.find { it.profileHash == profileHash } ?: return
            val modified = parseIsoTime(server.lastModified) ?: item.lastModified
            replaceItem(item.copy(
                version = server.version ?: item.version,
                lastModified = modified
                // 保持 syncStatus=NeedSync, isDeleted 等本地状态不变
            ))
        }
    }

    // ─── 内部方法 ────────────────────────────────────────────────

    /** 复制文件到持久化历史目录，返回新文件路径 */
    private fun copyToHistoryDir(sourceUri: String, fileName: String?, hash: String): String? {
        return try {
            val src = File(sourceUri)
            if (!src.exists()) {
                // sourceUri 可能是 content:// URI，这里无法处理，返回 null
                Logger.warn(TAG, "Source file not exists (may be content:// URI): $sourceUri")
                return null
            }
            val name = fileName ?: "file_$hash"
            val dest = File(historyDir, "${hash}_${name}")
            src.copyTo(dest, overwrite = true)
            dest.setReadable(true, false)
            Logger.debug(TAG, "Copied to history dir: $sourceUri -> ${dest.absolutePath}")
            dest.absolutePath
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to copy to history dir: $sourceUri", e)
            null
        }
    }

    private fun addItem(item: HistoryItem) {
        _items.value = _items.value.toMutableList().apply { add(0, item) }
        saveToDisk()
    }

    private fun replaceItem(item: HistoryItem) {
        val current = _items.value.toMutableList()
        val idx = current.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            current[idx] = item
            _items.value = current
            saveToDisk()
        }
    }

    private fun trimIfNeeded() {
        val active = _items.value.filter { !it.isDeleted }
        if (active.size > MAX_ITEMS) {
            val toRemove = active.sortedBy { it.timestamp }
                .take(active.size - MAX_ITEMS).map { it.id }.toSet()
            _items.value = _items.value.map {
                if (it.id in toRemove) it.copy(isDeleted = true) else it
            }
            saveToDisk()
            Logger.info(TAG, "Trimmed ${toRemove.size} old items")
        }
    }

    private fun saveToDisk() {
        try {
            historyFile.writeText(
                json.encodeToString(ListSerializer(HistoryItem.serializer()), _items.value)
            )
        } catch (e: Exception) { Logger.error(TAG, "Failed to save history", e) }
    }

    private fun loadFromDisk() {
        try {
            if (historyFile.exists()) {
                val text = historyFile.readText()
                Logger.info(TAG, "loadFromDisk: file exists, size=${text.length} bytes")
                _items.value = json.decodeFromString(
                    ListSerializer(HistoryItem.serializer()),
                    text
                )
                Logger.info(TAG, "loadFromDisk: loaded ${_items.value.size} items")
            } else {
                Logger.info(TAG, "loadFromDisk: history file does not exist yet")
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to load history: ${e.message}", e)
            _items.value = emptyList()
        }
        // 迁移旧版无前缀 hash 到带前缀格式（与服务器对齐）
        migrateLegacyHashes()
        // 修复旧版同步遗留的 hasData=false / dataName=null（Image/File 类型）
        fixupHasDataAndDataName()
    }

    /**
     * 一次性修复：旧版同步把 Image/File 记录的 hasData 存为 false、dataName 存为 null，
     * 导致历史页面不显示下载按钮和图片预览。对 Image/File 类型强制设置 hasData=true 并推导 dataName。
     */
    private fun fixupHasDataAndDataName() {
        var changed = false
        val fixed = _items.value.map { item ->
            if ((item.type == ClipboardContentType.Image || item.type == ClipboardContentType.File)
                && (!item.hasData || item.dataName.isNullOrBlank())
            ) {
                changed = true
                item.copy(
                    hasData = true,
                    dataName = item.dataName ?: item.text?.takeIf { it.isNotBlank() }
                        ?: "${item.type.name.lowercase()}_${item.profileHash.take(8)}"
                )
            } else item
        }
        if (changed) {
            _items.value = fixed
            saveToDisk()
            Logger.info(TAG, "fixupHasDataAndDataName: fixed hasData/dataName for Image/File records")
        }
    }

    /**
     * 一次性迁移：把旧版无前缀的 profileHash（纯 SHA-256，64 位十六进制）转换为
     * 带类型前缀的格式（text-<sha256>），与服务器 hash 规则对齐。
     *
     * 修复 bug：旧版 addLocalContent 用 computeLocalHash 存储，导致 mergeFromServerDtos
     * 无法匹配服务器返回的带前缀 hash → 服务器记录被当作新记录入库 → 本地原 LocalOnly
     * 记录持续重复上传。
     *
     * - 重命名 history_files 目录下以旧 hash 为前缀的本地文件
     * - 若迁移后的 hash 与现有带前缀记录冲突，丢弃这条旧记录
     */
    private fun migrateLegacyHashes() {
        synchronized(lock) {
            val items = _items.value
            if (items.isEmpty()) return

            // 旧格式：纯 64 位十六进制（无 "-"）。服务器格式："<type>-<sha256>"
            val legacyPattern = Regex("^[0-9a-fA-F]{64}$")
            if (items.none { legacyPattern.matches(it.profileHash) }) return

            val newItems = mutableListOf<HistoryItem>()
            val existingHashes = items.mapTo(mutableSetOf()) { it.profileHash.lowercase() }
            var migrated = 0
            var dropped = 0
            var renamed = 0

            for (item in items) {
                if (!legacyPattern.matches(item.profileHash)) {
                    newItems.add(item)
                    continue
                }
                val newHash = HashUtils.computeProfileHash(
                    item.type.name.lowercase(),
                    item.text
                )
                // 冲突：已有带前缀记录，丢弃这条旧的无前缀记录
                if (newHash.lowercase() in existingHashes) {
                    dropped++
                    continue
                }
                // 重命名本地历史文件（如有）
                var newFileUri = item.fileUri
                if (!item.fileUri.isNullOrBlank()) {
                    val oldFile = File(item.fileUri)
                    if (oldFile.exists() && oldFile.name.startsWith("${item.profileHash}_")) {
                        val suffix = oldFile.name.substring(item.profileHash.length)
                        val newFile = File(oldFile.parentFile, "$newHash$suffix")
                        try {
                            if (oldFile.renameTo(newFile)) {
                                newFileUri = newFile.absolutePath
                                renamed++
                            }
                        } catch (e: Exception) {
                            Logger.warn(TAG, "migrateLegacyHashes: rename failed ${oldFile.name}")
                        }
                    }
                }
                existingHashes.add(newHash.lowercase())
                newItems.add(item.copy(profileHash = newHash, fileUri = newFileUri))
                migrated++
            }

            if (migrated > 0 || dropped > 0) {
                _items.value = newItems
                saveToDisk()
                Logger.info(TAG, "migrateLegacyHashes: migrated=$migrated, dropped=$dropped, renamed=$renamed")
            }
        }
    }
}
