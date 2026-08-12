package io.github.erenche.syncclipboard.xposed.history.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.erenche.syncclipboard.common.model.HistorySyncStatus
import kotlinx.coroutines.flow.Flow

/**
 * 剪贴板历史 DAO — 覆盖 HistoryService 的所有数据访问需求。
 *
 * 设计原则：
 * - 纯 CRUD，无业务逻辑（冲突解决等保留在 Service 层）
 * - 搜索用 SQLite LIKE（默认 ASCII 大小写不敏感）
 * - 分页排序：置顶优先 → 时间倒序
 */
@Dao
interface HistoryItemDao {

    // ─── 观察响应式 ──────────────────────────────────────────────

    @Query("SELECT * FROM history_items")
    fun observeAll(): Flow<List<HistoryItemEntity>>

    // ─── 分页查询 ────────────────────────────────────────────────

    /**
     * 分页查询（支持搜索）。
     *
     * @param searchText 为 null 或空字符串时返回全部活跃记录
     */
    @Query(
        """
        SELECT * FROM history_items
        WHERE isDeleted = 0
          AND (:searchText IS NULL OR :searchText = ''
               OR text LIKE '%' || :searchText || '%'
               OR dataName LIKE '%' || :searchText || '%')
        ORDER BY pinned DESC, timestamp DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getPaged(
        offset: Int,
        limit: Int,
        searchText: String?
    ): List<HistoryItemEntity>

    /** 匹配搜索条件的活跃记录总数（用于 UI 计算总页数） */
    @Query(
        """
        SELECT COUNT(*) FROM history_items
        WHERE isDeleted = 0
          AND (:searchText IS NULL OR :searchText = ''
               OR text LIKE '%' || :searchText || '%'
               OR dataName LIKE '%' || :searchText || '%')
        """
    )
    fun count(searchText: String?): Int

    // ─── 全量 / 单条查询 ─────────────────────────────────────────

    @Query("SELECT * FROM history_items WHERE isDeleted = 0 ORDER BY pinned DESC, timestamp DESC")
    fun getAll(): List<HistoryItemEntity>

    /** 包含已软删除的全部记录（用于 mergeFromServerDtos 冲突解决） */
    @Query("SELECT * FROM history_items")
    fun getAllIncludingDeleted(): List<HistoryItemEntity>

    @Query("SELECT COUNT(*) FROM history_items WHERE isDeleted = 0")
    fun countActive(): Int

    @Query("SELECT * FROM history_items WHERE id = :id LIMIT 1")
    fun getById(id: String): HistoryItemEntity?

    @Query("SELECT * FROM history_items WHERE profileHash = :hash LIMIT 1")
    fun getByProfileHash(hash: String): HistoryItemEntity?

    /**
     * 批量按 profileHash 查询（大小写不敏感）。
     *
     * 用于 mergeFromServerDtos 一次性获取所有本地已存在的记录，避免 N 次定点查询。
     * 注意：LOWER(profileHash) 会导致索引失效，仅在同步场景使用。
     * hash 列表不应超过 SQLite 绑定变量上限（998）。
     */
    @Query("SELECT * FROM history_items WHERE LOWER(profileHash) IN (:hashes)")
    fun getByProfileHashesIgnoreCase(hashes: List<String>): List<HistoryItemEntity>

    /**
     * 查询孤儿候选记录：已同步或服务器来源（server-only）的活跃记录。
     *
     * 这两类记录在服务器缺失时应被处理（降级或物理删除）。
     * 调用方需在内存中按 serverHashes 集合过滤出真正的孤儿。
     *
     * 采用"查候选集 + Kotlin 过滤"而非 SQL `NOT IN (:hashes)`，原因：
     * - SQLite 绑定变量上限 998，serverHashes 可能超过此限
     * - 候选集（Synced + server-only）远小于全表，内存过滤开销可忽略
     */
    @Query(
        """
        SELECT * FROM history_items
        WHERE isDeleted = 0
          AND (syncStatus = 'Synced'
               OR (hasData = 1 AND (fileUri IS NULL OR fileUri = '')))
        """
    )
    fun findOrphanCandidates(): List<HistoryItemEntity>

    // ─── 同步状态查询 ────────────────────────────────────────────

    @Query("SELECT * FROM history_items WHERE syncStatus = 'NeedSync'")
    fun getNeedSyncItems(): List<HistoryItemEntity>

    /** LocalOnly 且未删除的记录（用于上传到服务器） */
    @Query("SELECT * FROM history_items WHERE syncStatus = 'LocalOnly' AND isDeleted = 0")
    fun getLocalOnlyActive(): List<HistoryItemEntity>

    /** 超量清理候选：仅 LocalOnly 且未置顶的记录（保护 Synced 记录，与 Windows 端一致） */
    @Query("SELECT id FROM history_items WHERE isDeleted = 0 AND pinned = 0 AND syncStatus = 'LocalOnly' ORDER BY timestamp ASC LIMIT :limit")
    fun getOldestActiveIds(limit: Int): List<String>

    // ─── 写入（upsert） ──────────────────────────────────────────

    @Upsert
    fun upsert(item: HistoryItemEntity)

    @Upsert
    fun upsertAll(items: List<HistoryItemEntity>)

    // ─── 软删除 ──────────────────────────────────────────────────

    @Query("UPDATE history_items SET isDeleted = 1 WHERE id = :id")
    fun softDeleteById(id: String)

    @Query("UPDATE history_items SET isDeleted = 1 WHERE id IN (:ids)")
    fun softDeleteByIds(ids: List<String>)

    @Query("UPDATE history_items SET isDeleted = 1 WHERE isDeleted = 0")
    fun softDeleteAll()

    // ─── 元数据更新 ──────────────────────────────────────────────

    @Query("UPDATE history_items SET starred = :starred, lastModified = :now WHERE id = :id")
    fun updateStarred(id: String, starred: Boolean, now: Long)

    @Query("UPDATE history_items SET pinned = :pinned, lastModified = :now WHERE id = :id")
    fun updatePinned(id: String, pinned: Boolean, now: Long)

    // ─── 同步状态更新 ────────────────────────────────────────────

    @Query("UPDATE history_items SET syncStatus = :status, lastModified = :now WHERE profileHash = :hash")
    fun updateSyncStatusByHash(hash: String, status: HistorySyncStatus, now: Long)

    @Query("UPDATE history_items SET version = :version, lastModified = :now WHERE profileHash = :hash")
    fun updateVersionByHash(hash: String, version: Int, now: Long)

    /** 全量同步前：把已软删除且 NeedSync 的记录改为 Synced（让 mergeFromServerDtos 的恢复逻辑能工作） */
    @Query("UPDATE history_items SET syncStatus = 'Synced' WHERE isDeleted = 1 AND syncStatus = 'NeedSync'")
    fun resetDeletedForFullSync()

    // ─── 物理删除 ────────────────────────────────────────────────

    @Query("DELETE FROM history_items WHERE id = :id")
    fun deleteById(id: String)

    /** 批量物理删除（用于孤儿记录清理） */
    @Query("DELETE FROM history_items WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    /** 删除所有已软删除的记录（清理磁盘空间） */
    @Query("DELETE FROM history_items WHERE isDeleted = 1")
    fun purgeDeleted()
}
