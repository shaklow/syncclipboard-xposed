package io.github.erenche.syncclipboard.common.util

import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import java.io.File
import java.security.MessageDigest

/**
 * 哈希工具 — SHA-256 计算，算法与服务器对齐（参考 docs/Hash.md）
 */
object HashUtils {

    /** 计算字符串的 SHA-256（UTF-8 编码，小写十六进制） */
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /** 流式计算 InputStream 的 SHA-256（64KB 分块，不整块读入内存），小写十六进制 */
    fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 计算字节数组的 SHA-256（小写十六进制） */
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算文件/图片的 profileHash（与服务器 FileProfile/ImageProfile 对齐）。
     *
     * 服务器规则（Hash.md）：
     *   ContentHash = SHA256(文件字节内容)
     *   Combined    = "文件名|" + UPPERCASE(ContentHash)
     *   Hash        = SHA256(UTF-8(Combined))
     */
    fun computeFileHash(fileName: String, fileBytes: ByteArray): String =
        computeFileHash(fileName, fileBytes.inputStream())

    /** 文件版本（分块流式计算，大文件不整块读入内存） */
    fun computeFileHash(fileName: String, file: File): String =
        computeFileHash(fileName, file.inputStream())

    /** 输入流版本（分块流式计算，调用方负责流的生命周期） */
    fun computeFileHash(fileName: String, input: java.io.InputStream): String {
        val contentHash = sha256(input).uppercase()
        val combined = "$fileName|$contentHash"
        return sha256(combined)
    }

    /**
     * 根据 ClipboardContent 计算 profileHash（文件路径必须可读）。
     *
     * - Text → SHA256(text)
     * - Image/File（绝对路径）→ computeFileHash(fileName, file)（分块流式，无内存尖峰）
     * - Image/File（无法读取文件）→ 降级为 SHA256(text)（通常为空字符串，仅作占位）
     *
     * 注意：content:// URI 需要 ContentResolver，无法在此处直接读取；
     * 调用方（HistoryService）负责预先将 URI 转换为输入流再调用 computeFileHash。
     */
    fun computeContentHash(content: ClipboardContent): String {
        if (content.type != ClipboardContentType.Text &&
            content.hasData &&
            !content.fileName.isNullOrBlank() &&
            !content.fileUri.isNullOrBlank()
        ) {
            return try {
                val file = File(content.fileUri!!)
                if (file.exists()) computeFileHash(content.fileName!!, file)
                else sha256(content.text)
            } catch (_: Exception) {
                sha256(content.text)
            }
        }
        return sha256(content.text)
    }
}
