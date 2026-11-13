package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm

/** The accessory advertises what it sends (field 6) and receives (field 7) independently. */
internal class CarPlayWirelessSetup(private val sends: List<Int>, private val receives: List<Int>) {
    fun announce(local: String, send: (Int, ByteArray) -> Unit) {
        if (0x4e09 in receives) send(0x4e09, Iap2Csm.string(0, "HSD"))
        // A head unit may wait for the BT transport identifier before exposing the Wi-Fi session.
        if (0x4e0e in receives) send(0x4e0e, Iap2Csm.string(0, local))
        if (0x4e0d in receives) send(0x4e0d, Iap2Csm.u8(0, 1))

        val startSession = 0x4301 in sends || 0x4300 in receives
        val wifiConfiguration = 0x5703 in sends || 0x5702 in receives
        // If neither exchange was announced the lists are inconclusive. Offer both defined exchanges.
        if (startSession || !wifiConfiguration) send(0x4300,
            Iap2Csm.group(0, Iap2Csm.u8(0, 0)) +
                Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, local)))
        if (wifiConfiguration || !startSession) send(0x5702, byteArrayOf())
    }
}
