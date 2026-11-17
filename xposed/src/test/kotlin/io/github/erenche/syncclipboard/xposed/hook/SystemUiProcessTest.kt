package io.github.erenche.syncclipboard.xposed.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiProcessTest {
    @Test fun parsesSupplementaryGroups() {
        val status = """
            Name:	com.android.systemui
            Uid:	10338	10338	10338	10338
            Gid:	10338	10338	10338	10338
            Groups:	1065 1077 3001 3002 3006 9997 20338 50338
        """.trimIndent()
        assertArrayEquals(
            intArrayOf(1065, 1077, 3001, 3002, 3006, 9997, 20338, 50338),
            SystemUiProcess.parseGroups(status)
        )
    }

    @Test fun detectsTheInetGroup() {
        assertTrue(SystemUiProcess.parseGroups("Groups:\t3003 9997\n")!!.contains(SystemUiProcess.AID_INET))
        assertFalse(SystemUiProcess.parseGroups("Groups:\t1065 9997\n")!!.contains(SystemUiProcess.AID_INET))
    }

    @Test fun missingGroupsLineIsNotGranted() {
        assertNull(SystemUiProcess.parseGroups("Uid:\t10338\n"))
    }
}
