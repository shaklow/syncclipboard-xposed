package io.github.erenche.syncclipboard.xposed.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiPermissionPolicyTest {
    @Test fun appendsTheInternetBitOnlyWhenEnabled() {
        assertEquals(4, SystemUiPermissionPolicy.supplementMask(0, true))
        assertEquals(12, SystemUiPermissionPolicy.supplementMask(8, true))
        assertEquals(4, SystemUiPermissionPolicy.supplementMask(4, true))
        assertEquals(0, SystemUiPermissionPolicy.supplementMask(0, false))
        assertEquals(8, SystemUiPermissionPolicy.supplementMask(8, false))
    }

    @Test fun keepsUninstalledMasks() {
        assertEquals(-1, SystemUiPermissionPolicy.supplementMask(-1, true))
    }

    @Test fun matchesBothAppIdAndFullUidKeys() {
        assertTrue(SystemUiPermissionPolicy.isTargetUid(338, 338))
        // user 1 的完整 uid 形式
        assertTrue(SystemUiPermissionPolicy.isTargetUid(100338, 338))
        // 10338 是另一个 appId（SystemUI 自己），不能匹配 appId 338
        assertFalse(SystemUiPermissionPolicy.isTargetUid(10338, 338))
        assertFalse(SystemUiPermissionPolicy.isTargetUid(339, 338))
        assertFalse(SystemUiPermissionPolicy.isTargetUid(-1, 338))
        assertFalse(SystemUiPermissionPolicy.isTargetUid(338, -1))
    }
}
