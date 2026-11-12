package com.hsd.universal

import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest
import aap_protobuf.service.wifiprojection.message.AccessPointTypeOuterClass.AccessPointType
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.net.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.sim.HeadUnitSimulator
import net.prototux.aare.aap.wireless.*

/**
 * The other AVD is the genuine running phone app; this receiver supplies its emulated AP over real
 * RFCOMM.
 */
@SuppressLint("MissingPermission")
internal class WirelessHeadUnitProbe(private val context: Context) {
    fun run(): Bundle {
        val bluetooth = context.getSystemService(BluetoothManager::class.java)!!.adapter!!
        val wifi =
            NetworkInterface.getByName("wlan0")
                .inetAddresses
                .toList()
                .filterIsInstance<Inet4Address>()
                .single()
        val result = Bundle()
        ServerSocket(0, 1, wifi).use { tcp ->
            tcp.soTimeout = 60_000
            bluetooth
                .listenUsingRfcommWithServiceRecord(
                    "Android Auto QA",
                    UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66"),
                )
                .use { service ->
                    android.util.Log.i("HsdRadioProbe", "Listening for RFCOMM")
                    service.accept(65_000).use { bt ->
                        val out = bt.outputStream
                        fun send(id: Int, payload: com.google.protobuf.MessageLite) =
                            writeWppFrame(out, id, payload.toByteArray())
                        send(
                            WppMessageId.VERSION_REQUEST,
                            WifiVersionRequest.newBuilder().setMajor(4).setMinor(2).build(),
                        )
                        send(
                            WppMessageId.START_REQUEST,
                            WifiStartRequest.newBuilder()
                                .setIpAddress(wifi.hostAddress)
                                .setPort(tcp.localPort)
                                .build(),
                        )
                        val deadline = SystemClock.elapsedRealtime() + 65000
                        while (SystemClock.elapsedRealtime() < deadline) {
                            val frame = readWppFrame(bt.inputStream) ?: error("RFCOMM closed")
                            android.util.Log.i("HsdRadioProbe", "WPP message ${frame.id}")
                            when (frame.id) {
                                WppMessageId.PING_REQUEST ->
                                    writeWppFrame(out, WppMessageId.PING_RESPONSE, frame.payload)
                                WppMessageId.INFO_REQUEST ->
                                    send(
                                        WppMessageId.INFO_RESPONSE,
                                        WifiInfoResponse.newBuilder()
                                            .setSsid("HSD-QA")
                                            .setPassword("hsd-avd-wireless")
                                            .setSecurityMode(WppSecurityMode.WPA2_PERSONAL)
                                            .setAccessPointType(AccessPointType.DYNAMIC)
                                            .build(),
                                    )
                                WppMessageId.START_RESPONSE -> {
                                    val reply = WifiStartResponse.parseFrom(frame.payload)
                                    check(reply.status.number == WppStatus.SUCCESS) {
                                        "WPP status ${reply.status}"
                                    }
                                    result.putString("WPP_phone", reply.ipAddress)
                                    break
                                }
                            }
                        }
                        val identity = ProjectionIdentity(context.createDeviceProtectedStorageContext()).phone("qa-radio")
                        val aa = HeadUnitSimulator(SocketTransport(tcp.accept(), "AVD Wi-Fi"), huCert = identity)
                        try {
                            aa.start()
                            check(aa.firstVideoFrame.await(50, TimeUnit.SECONDS)) {
                                "No DriveDeck frame · ${aa.error}"
                            }
                            SystemClock.sleep(1400)
                            check(aa.videoFrames.get() > 1)
                            result.putInt("wireless_AAP_frames", aa.videoFrames.get())
                            result.putString(
                                "wireless_AAP_channels",
                                aa.openedChannels.sorted().joinToString(),
                            )
                        } finally {
                            aa.stop()
                        }
                    }
                }
        }
        return result
    }
}
