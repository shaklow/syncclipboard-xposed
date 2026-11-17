package io.github.erenche.syncclipboard.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class InternetAccessStatusPolicyTest {
    private fun status(
        enabled: Boolean = true,
        hookInstalled: Boolean = true,
        storageFailed: Boolean = false
    ) = InternetAccessStatus(enabled, hookInstalled, storageFailed)

    @Test fun systemUiWithTheGroupNeedsNoSystemServerReply() {
        assertEquals(InternetAccessState.VERIFIED,
            InternetAccessStatusPolicy.classify(true, null, systemUiHasInetGroup = true))
    }

    @Test fun missingServerReplyRequiresRestartWhenTheGroupIsMissing() {
        assertEquals(InternetAccessState.RESTART_REQUIRED,
            InternetAccessStatusPolicy.classify(true, null, systemUiHasInetGroup = false))
        assertEquals(InternetAccessState.RESTART_REQUIRED,
            InternetAccessStatusPolicy.classify(true, null, systemUiHasInetGroup = null))
    }

    @Test fun missingServerReplyIsDisabledWhenTurnedOff() {
        assertEquals(InternetAccessState.DISABLED,
            InternetAccessStatusPolicy.classify(false, null, systemUiHasInetGroup = false))
    }

    @Test fun hooksAndSettingMustBeReadyBeforeRestartIsSuggested() {
        assertEquals(InternetAccessState.WAITING,
            InternetAccessStatusPolicy.classify(true, status(hookInstalled = false), false))
        assertEquals(InternetAccessState.WAITING,
            InternetAccessStatusPolicy.classify(true, status(enabled = false), false))
        assertEquals(InternetAccessState.RESTART_REQUIRED,
            InternetAccessStatusPolicy.classify(true, status(), false))
    }

    @Test fun storageFailureIsReportedWhenEnabledOrDisabling() {
        assertEquals(InternetAccessState.FAILED,
            InternetAccessStatusPolicy.classify(true, status(storageFailed = true), false))
        assertEquals(InternetAccessState.FAILED,
            InternetAccessStatusPolicy.classify(false, status(enabled = false, storageFailed = true), false))
    }

    @Test fun waitsUntilTheDisableReachesSystemServer() {
        assertEquals(InternetAccessState.WAITING,
            InternetAccessStatusPolicy.classify(false, status(enabled = true), false))
        assertEquals(InternetAccessState.DISABLED,
            InternetAccessStatusPolicy.classify(false, status(enabled = false), false))
    }
}
