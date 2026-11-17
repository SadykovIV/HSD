package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopUsbDebuggingPolicyTest {
    @Test fun filteredSettingIsNotEvidenceThatDebuggingWasDisabled() {
        for (unknown in listOf(null, "", "null", "-1", "unexpected"))
            assertEquals(DesktopUsbDebuggingPolicy.State.UNKNOWN, DesktopUsbDebuggingPolicy.state(unknown, false))
        assertEquals(DesktopUsbDebuggingPolicy.State.ENABLED, DesktopUsbDebuggingPolicy.state("0", true))
        assertEquals(DesktopUsbDebuggingPolicy.State.DISABLED, DesktopUsbDebuggingPolicy.state("0", null))
    }
    @Test fun onlyExactUsbFunctionCounts() {
        assertTrue(DesktopUsbDebuggingPolicy.hasAdbFunction("accessory,adb"))
        assertTrue(DesktopUsbDebuggingPolicy.hasAdbFunction("sec_charging,adb"))
        assertFalse(DesktopUsbDebuggingPolicy.hasAdbFunction("accessory"))
        assertFalse(DesktopUsbDebuggingPolicy.hasAdbFunction("not_adb"))
    }
    @Test fun verifiedSystemSettingWinsOverATransitioningUsbConfiguration() {
        assertEquals(DesktopUsbDebuggingPolicy.State.DISABLED, DesktopUsbDebuggingPolicy.verifiedState("0", "accessory,adb"))
        assertEquals(DesktopUsbDebuggingPolicy.State.ENABLED, DesktopUsbDebuggingPolicy.verifiedState("1", "none"))
        assertEquals(DesktopUsbDebuggingPolicy.State.ENABLED, DesktopUsbDebuggingPolicy.verifiedState(null, "accessory,adb"))
        assertEquals(DesktopUsbDebuggingPolicy.State.UNKNOWN, DesktopUsbDebuggingPolicy.verifiedState(null, "none"))
    }
}
