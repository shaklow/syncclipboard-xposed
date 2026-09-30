package io.github.erenche.syncclipboard.common.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafeFileNamesTest {
    @Test
    fun rejectsTraversalAndSeparators() {
        assertFalse(SafeFileNames.isSafeFileName("../token"))
        assertFalse(SafeFileNames.isSafeFileName("folder/file"))
        assertFalse(SafeFileNames.isSafeFileName("folder\\file"))
        assertFalse(SafeFileNames.isSafeFileName(".."))
    }

    @Test
    fun childCannotEscapeParent() {
        val parent = File(System.getProperty("java.io.tmpdir"), "safe-file-name-test")
        assertNull(SafeFileNames.childOrNull(parent, "../../outside"))
        assertTrue(SafeFileNames.childOrNull(parent, "clipboard.png")!!.canonicalPath
            .startsWith(parent.canonicalPath + File.separator))
    }
}
