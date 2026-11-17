package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopAudioInterruptionTest {
    @Test fun ringingConversationAndIdleEndOneStreamWithoutTreatingHfpConnectionAsACall() {
        val calls = DesktopAudioInterruption()
        calls.updateMode(0)
        assertEquals(0L, calls.state.boundary)
        calls.updateMode(1)
        assertTrue(calls.state.blocked)
        val beginning = calls.state.boundary
        calls.updateMode(2)
        calls.updateMode(2)
        assertEquals(beginning, calls.state.boundary)
        calls.updateMode(0)
        assertFalse(calls.state.blocked)
        assertEquals(beginning + 1, calls.state.boundary)
    }

    @Test fun fastCallOrVoipRedirectionCannotLoseTheRestartBetweenRecorderReads() {
        val calls = DesktopAudioInterruption()
        for (mode in listOf(2, 3, 5, 6)) {
            val before = calls.state
            calls.updateMode(mode)
            assertTrue(calls.state.blocked)
            calls.updateMode(0)
            assertFalse(calls.state.blocked)
            assertEquals(before.boundary + 2, calls.state.boundary)
        }
    }

    @Test fun screeningAndAssistantModesDoNotInterruptUnrelatedPlayback() {
        val calls = DesktopAudioInterruption()
        for (mode in listOf(4, 7, 0, -1, -2)) {
            calls.updateMode(mode)
            assertFalse(calls.state.blocked)
            assertEquals(0L, calls.state.boundary)
        }
    }
}
