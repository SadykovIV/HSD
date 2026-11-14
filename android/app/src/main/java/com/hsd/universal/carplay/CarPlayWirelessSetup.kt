package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm

/** The accessory advertises what it sends (field 6) and receives (field 7) independently. */
internal class CarPlayWirelessSetup(private val sends: List<Int>, private val receives: List<Int>) {
    private var availabilityDue: Long? = null

    fun announce(local: String, device: CarPlayDeviceInformation, nowMs: Long, send: (Int, ByteArray) -> Unit) {
        device.publish(receives, send = send)
        // A head unit may wait for the BT transport identifier before exposing the Wi-Fi session.
        if (receives.isEmpty() || 0x4e0e in receives) send(0x4e0e, Iap2Csm.string(0, local))
        if (receives.isEmpty() || 0x4e0d in receives) send(0x4e0d, Iap2Csm.u8(0, 1))

        val startSession = 0x4301 in sends || 0x4300 in receives
        val wifiConfiguration = 0x5703 in sends || 0x5702 in receives
        if (startSession) sendAvailability(local, send)
        if (wifiConfiguration || !startSession) send(0x5702, byteArrayOf())
        // With no advertised exchange, allow a configuration reply before offering session startup.
        // Sending both at once can interrupt an older receiver while it is preparing its reply.
        if (!startSession && !wifiConfiguration) availabilityDue = nowMs + 1_500
    }

    fun whileWaitingForWifi(local: String, nowMs: Long, send: (Int, ByteArray) -> Unit) {
        val due = availabilityDue ?: return
        if (nowMs < due) return
        availabilityDue = null
        sendAvailability(local, send)
    }

    private fun sendAvailability(local: String, send: (Int, ByteArray) -> Unit) {
        send(0x4300, Iap2Csm.group(0, Iap2Csm.u8(0, 0)) +
            Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, local)))
    }
}
