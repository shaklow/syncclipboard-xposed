package io.github.erenche.syncclipboard.xposed.hook

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToggleSettingStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun settingFile() = File(temporary.root, "syncclipboard_local_network")

    @Test fun missingFileDoesNotEnableTheOverride() {
        val store = ToggleSettingStore(settingFile())
        store.load()
        assertFalse(store.enabled)
        assertFalse(store.storageFailed)
    }

    @Test fun missingFileUsesTheDefaultValue() {
        val on = ToggleSettingStore(settingFile(), defaultEnabled = true)
        on.load()
        assertTrue(on.enabled)
        assertFalse(on.storageFailed)

        val off = ToggleSettingStore(File(temporary.root, "other"), defaultEnabled = true)
        off.load()
        assertTrue(off.enabled)
    }

    @Test fun persistedValueWinsOverTheDefault() {
        val file = File(temporary.root, "syncclipboard_internet_access").apply { writeText("0") }
        val store = ToggleSettingStore(file, defaultEnabled = true)
        store.load()
        assertFalse(store.enabled)

        store.update(true)
        val reopened = ToggleSettingStore(file, defaultEnabled = true)
        reopened.load()
        assertTrue(reopened.enabled)
    }

    @Test fun readsBothLegacyValues() {
        val file = settingFile()
        for (enabled in listOf(true, false)) {
            file.writeText(if (enabled) "1\n" else "0\n")
            val store = ToggleSettingStore(file)
            store.load()
            assertEquals(enabled, store.enabled)
            assertFalse(store.storageFailed)
        }
    }

    @Test fun emptyAndCorruptFilesNeverEnableTheOverride() {
        val file = settingFile()
        for (text in listOf("", " ", "true", "01", "1junk")) {
            file.writeText(text)
            val store = ToggleSettingStore(file, defaultEnabled = true)
            assertThrows(IOException::class.java) { store.load() }
            assertFalse(store.enabled)
            assertTrue(store.storageFailed)
        }
    }

    @Test fun closingAndReopeningPreservesTheLastSuccessfulSetting() {
        val file = settingFile()
        val store = ToggleSettingStore(file)
        store.load()
        for (enabled in listOf(true, false, true, false)) {
            store.update(enabled)
            val reopened = ToggleSettingStore(file)
            reopened.load()
            assertEquals(enabled, reopened.enabled)
            assertEquals(if (enabled) "1" else "0", file.readText())
            assertFalse(reopened.storageFailed)
        }
        assertEquals(listOf(file.name), temporary.root.list()!!.toList())
    }

    @Test fun failedEnableDoesNotGrantPermissionOrLeaveTemporaryFiles() {
        val file = settingFile()
        assertTrue(file.mkdir())
        val marker = File(file, "existing")
        marker.writeText("keep")
        val store = ToggleSettingStore(file)

        assertThrows(IOException::class.java) { store.update(true) }

        assertFalse(store.enabled)
        assertTrue(store.storageFailed)
        assertEquals("keep", marker.readText())
        assertEquals(listOf(file.name), temporary.root.list()!!.toList())
    }

    @Test fun failedDisableRevokesImmediatelyAndReportsFailureUntilRetrySucceeds() {
        val file = settingFile()
        val store = ToggleSettingStore(file)
        store.update(true)
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        val marker = File(file, "existing")
        marker.writeText("keep")

        assertThrows(IOException::class.java) { store.update(false) }
        assertFalse(store.enabled)
        assertTrue(store.storageFailed)
        assertEquals("keep", marker.readText())
        assertEquals(listOf(file.name), temporary.root.list()!!.toList())

        assertTrue(marker.delete())
        assertTrue(file.delete())
        store.update(false)
        val reopened = ToggleSettingStore(file)
        reopened.load()
        assertFalse(reopened.enabled)
        assertFalse(store.storageFailed)
        assertFalse(reopened.storageFailed)
    }

    @Test fun unreadableSettingDisablesAPreviouslyEnabledStore() {
        val file = settingFile()
        val store = ToggleSettingStore(file)
        store.update(true)
        file.writeText("corrupt")

        assertThrows(IOException::class.java) { store.load() }

        assertFalse(store.enabled)
        assertTrue(store.storageFailed)
    }

    @Test fun interruptedFirstWriteCannotEnableTheOverride() {
        File(temporary.root, ".syncclipboard-toggle-interrupted.tmp").writeText("1")
        val store = ToggleSettingStore(settingFile())
        store.load()
        assertFalse(store.enabled)
    }
}
