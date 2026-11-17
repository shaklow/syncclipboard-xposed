package io.github.erenche.syncclipboard.xposed.hook

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * 原子保存 system_server 侧的兼容性开关；配置异常时一律回退为关闭，避免误授权。
 *
 * @param defaultEnabled 开关文件尚不存在时的默认值（例如 INTERNET 兼容默认开启）。
 */
internal class ToggleSettingStore(
    private val file: File,
    private val defaultEnabled: Boolean = false
) {
    @Volatile var enabled = defaultEnabled
        private set
    @Volatile var storageFailed = false
        private set
    private var persisted: Boolean? = null

    @Synchronized
    fun load() {
        enabled = defaultEnabled
        persisted = null
        try {
            val text = try {
                String(Files.readAllBytes(file.toPath()), Charsets.UTF_8).trim()
            } catch (_: NoSuchFileException) {
                null
            }
            persisted = when (text) {
                "1" -> true
                "0" -> false
                null -> null
                else -> throw IOException("Invalid toggle setting")
            }
            enabled = persisted ?: defaultEnabled
            storageFailed = false
        } catch (e: Exception) {
            // 文件损坏时宁可关闭补权，也不按默认值继续授权。
            enabled = false
            storageFailed = true
            throw e
        }
    }

    @Synchronized
    fun update(requested: Boolean) {
        // 关闭立即生效；启用须先成功落盘。
        if (!requested) enabled = false
        try {
            if (persisted != requested || storageFailed) save(requested)
            persisted = requested
            enabled = requested
            storageFailed = false
        } catch (e: Exception) {
            storageFailed = true
            throw e
        }
    }

    private fun save(value: Boolean) {
        val target = file.absoluteFile.toPath()
        val temporary = Files.createTempFile(target.parent, ".syncclipboard-toggle-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(if (value) '1'.code else '0'.code)
                stream.fd.sync()
            }
            // 同目录原子替换，失败时保留旧文件。
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
