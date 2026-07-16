package io.github.erenche.syncclipboard.common.util

import java.security.MessageDigest

/**
 * 哈希工具 — SHA-256 计算
 */
object HashUtils {

    /** 计算字符串的 SHA-256（UTF-8 编码，小写十六进制） */
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /** 计算字节数组的 SHA-256（小写十六进制） */
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
