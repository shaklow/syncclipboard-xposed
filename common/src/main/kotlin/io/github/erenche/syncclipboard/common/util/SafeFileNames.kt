package io.github.erenche.syncclipboard.common.util

import java.io.File

/**
 * Validates names received from a clipboard backend before using them on disk.
 * Backend file names are identifiers, not paths: directory separators and traversal
 * segments are never valid here.
 */
object SafeFileNames {
    private const val MAX_NAME_LENGTH = 180

    fun isSafeFileName(name: String?): Boolean {
        if (name.isNullOrBlank() || name.length > MAX_NAME_LENGTH) return false
        if (name == "." || name == "..") return false
        return name.none { it == '/' || it == '\\' || it == '\u0000' || Character.isISOControl(it) }
    }

    /** Returns a child only when its canonical location remains below [parent]. */
    fun childOrNull(parent: File, name: String?): File? {
        if (!isSafeFileName(name)) return null
        return try {
            val parentPath = parent.canonicalFile
            val child = File(parentPath, name).canonicalFile
            if (child.parentFile == parentPath) child else null
        } catch (_: Exception) {
            null
        }
    }
}
