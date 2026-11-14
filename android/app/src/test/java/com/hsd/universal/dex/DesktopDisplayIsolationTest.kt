package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopDisplayIsolationTest {
    @Test fun rejectedGroupRetriesOnlyWithoutOptionalIsolation() {
        val protected = DesktopModePolicy.displayFlags(DesktopModePolicy.DRIVEDECK, 31, trusted = true)
        val requests = mutableListOf<Int>()
        val accepted = DesktopDisplayIsolation.create(protected) { flags ->
            requests += flags
            if (requests.size == 1) throw SecurityException("group unavailable")
            flags
        }
        assertEquals(protected and DesktopModePolicy.VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP.inv(), accepted)
        assertEquals(listOf(protected, accepted), requests)
        val rejection = SecurityException("display unavailable")
        val failure = runCatching { DesktopDisplayIsolation.create(accepted) { throw rejection } }
        assertSame(rejection, failure.exceptionOrNull())
    }

    @Test fun legacyParentAndChildArePrivateAndOnlyUseSupportedPowerFlags() {
        for (sdk in 29..32) for (trusted in listOf(false, true)) {
            val flags = DesktopModePolicy.displayFlags(DesktopModePolicy.ANDROID_DESKTOP, sdk, trusted = trusted)
            assertEquals(flags, EmbeddedDisplayPolicy.displayFlags(sdk, trusted = trusted))
            assertEquals(0, flags and (1 or 16 or DesktopModePolicy.VIRTUAL_DISPLAY_ALWAYS_UNLOCKED))
            assertTrue(flags and 32 != 0)
            assertTrue(flags and 8 != 0)
            assertEquals(trusted, flags and 1024 != 0)
            assertEquals(trusted && sdk >= 31, flags and DesktopModePolicy.VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP != 0)
        }
    }

    @Test fun holdingOnlyTheCarGroupDoesNotRequireAnUnlockedKeyguard() {
        assertTrue(DesktopDisplayIsolation.hasSeparatePowerGroup(3, 1))
        for (group in listOf(null, -1, 0)) assertFalse(DesktopDisplayIsolation.hasSeparatePowerGroup(3, group))
        assertFalse(DesktopDisplayIsolation.hasSeparatePowerGroup(0, 1))
        assertFalse(DesktopDisplayIsolation.independent(DesktopDisplayIsolation.OWN_GROUP, 1))
    }

    @Test fun independentLockRequestsBothFlagsOnlyWhenPermissionWasConfirmed() {
        val extra = DesktopModePolicy.VIRTUAL_DISPLAY_OWN_DISPLAY_GROUP or DesktopModePolicy.VIRTUAL_DISPLAY_ALWAYS_UNLOCKED
        for (sdk in 33..37) for (mode in listOf(DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK)) {
            assertEquals(0, DesktopModePolicy.displayFlags(mode, sdk) and extra)
            assertEquals(extra, DesktopModePolicy.displayFlags(mode, sdk, true) and extra)
            assertEquals(DesktopModePolicy.displayFlags(mode, sdk), DesktopModePolicy.displayFlags(mode, sdk, true) and extra.inv())
        }
        assertEquals(DesktopModePolicy.displayFlags(DesktopModePolicy.SAMSUNG_DEX, 37),
            DesktopModePolicy.displayFlags(DesktopModePolicy.SAMSUNG_DEX, 37, true))
    }

    @Test fun returnedDefaultGroupOverridesMisleadingRequestedFlags() {
        val flags = DesktopDisplayIsolation.OWN_GROUP or DesktopDisplayIsolation.ALWAYS_UNLOCKED
        assertTrue(DesktopDisplayIsolation.independent(flags, 2))
        assertFalse(DesktopDisplayIsolation.independent(flags, 0))
        assertFalse(DesktopDisplayIsolation.independent(flags, -1))
        assertFalse(DesktopDisplayIsolation.independent(DesktopDisplayIsolation.OWN_GROUP, 2))
    }
}
