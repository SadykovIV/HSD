package com.hsd.universal.ui

import com.hsd.universal.dex.DexAutoConnectPolicy
import com.hsd.universal.R
import org.junit.Assert.*
import org.junit.Test

class UsbDetachPolicyTest {
    @Test fun samsungCanReturnAsConfiguredWithoutAnAccessoryFlag() {
        val disconnected = UsbBusState(false, false, null)
        val configured = UsbBusState(true, true, null)
        assertTrue(configured.mayEnumerateAfter(disconnected, awaitingReconnect = true))
        assertFalse(configured.mayEnumerateAfter(disconnected, awaitingReconnect = false))
        assertFalse(configured.mayEnumerateAfter(configured, awaitingReconnect = true))
        assertTrue(configured.copy(accessory = true).mayEnumerateAfter(configured, awaitingReconnect = false))
        assertFalse(disconnected.mayEnumerateAfter(configured, awaitingReconnect = true))
    }

    @Test fun waitingForAnAccessoryExplainsOnlyWhatTheSystemReported() {
        assertEquals(R.string.usb_wait_accessory, UsbBusState(null, null, null).waitingMessage())
        assertEquals(R.string.usb_wait_data, UsbBusState(false, false, true).waitingMessage())
        assertEquals(R.string.usb_wait_configuration, UsbBusState(true, false, null).waitingMessage())
        assertEquals(R.string.usb_wait_accessory_mode, UsbBusState(true, true, null).waitingMessage())
        assertEquals(R.string.usb_wait_enumeration, UsbBusState(true, true, true).waitingMessage())
    }

    @Test fun cableStateDistinguishesCameraSwitchesFromPhysicalUnplug() {
        assertFalse(UsbDetachPolicy.physicalDisconnect(cableConnected = true, accessoryPresent = false))
        assertTrue(UsbDetachPolicy.physicalDisconnect(cableConnected = false, accessoryPresent = false))
        assertFalse(UsbDetachPolicy.physicalDisconnect(cableConnected = false, accessoryPresent = true))
        assertTrue(UsbDetachPolicy.physicalDisconnect(cableConnected = null, accessoryPresent = false))
        assertFalse(UsbDetachPolicy.physicalDisconnect(cableConnected = null, accessoryPresent = null))
    }

    @Test fun onlyConfirmedPhysicalAbsenceOfOwnedUsbMarksDetached() {
        assertTrue(UsbDetachPolicy.confirmedDetached(true, false, null))
        assertFalse("camera can temporarily remove the accessory", UsbDetachPolicy.confirmedDetached(true, false, true))
        assertFalse("IO/protocol error with accessory present keeps reconnect grace", UsbDetachPolicy.confirmedDetached(true, true, null))
        assertFalse("failed UsbManager query is not proof of detach", UsbDetachPolicy.confirmedDetached(true, null, null))
        assertFalse("unrelated USB broadcast cannot stop TCP", UsbDetachPolicy.confirmedDetached(false, false, false))
        assertFalse(UsbDetachPolicy.confirmedDetached(false, null, null))
    }

    @Test fun staleQueuedDetachDoesNotOverrideAlreadyReconnectedAccessory() {
        assertFalse(UsbDetachPolicy.confirmedDetached(ownedUsbSession = true, compatibleAccessoryPresent = true, cableConnected = false))
    }

    @Test fun detachedIdleNeverResumesForOrdinaryPcAndExplicitConnectOnlyArmsWaiting() {
        val idle = UsbDetachedIdle()
        val gate = UsbScanGate { 0 }
        idle.detached()
        repeat(5) { idle.observeCompatibleAccessory(false); assertTrue(idle.idle) }
        idle.explicitConnect()
        assertFalse(idle.idle)
        assertEquals(UsbScanGate.Step.WAITING, gate.decide(null, false, false, false))
    }

    @Test fun newCarLifeAccessoryResumesWithoutRevokingAccessOrTreatingItAsUserStop() {
        val idle = UsbDetachedIdle()
        val auto = DexAutoConnectPolicy()
        auto.observeAccessory("car")
        idle.detached(); auto.observeAccessory(null)
        assertTrue(idle.idle)
        assertFalse("passive detach is not explicit Stop", auto.stopped)
        idle.observeCompatibleAccessory(true); auto.observeAccessory("car")
        assertFalse(idle.idle)
        assertEquals(DexAutoConnectPolicy.Decision.CONNECT, auto.decide(false, false, false))
    }

    @Test fun fastReplugDuringGraceMonitorsTheExistingHost() {
        val idle = UsbDetachedIdle()
        val auto = DexAutoConnectPolicy()
        auto.observeAccessory("car"); idle.detached(); auto.observeAccessory(null)
        idle.observeCompatibleAccessory(true); auto.observeAccessory("car")
        assertFalse(idle.idle)
        assertEquals(DexAutoConnectPolicy.Decision.MONITOR, auto.decide(false, true, false))
    }

    @Test fun absentAccessoryWinsOverStaleActiveStateAndOldPermissionCallback() {
        val gate = UsbScanGate { 0 }
        gate.decide("car", false, false, false)
        val token = gate.pendingToken!!
        assertEquals(UsbScanGate.Step.WAITING, gate.decide(null, false, true, false))
        assertFalse(gate.permissionResult("car", token, true))
        assertEquals(UsbScanGate.Step.START, gate.decide("car", true, false, false))
    }
}
