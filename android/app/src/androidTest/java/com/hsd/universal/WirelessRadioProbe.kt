package com.hsd.universal

import aap_protobuf.aaw.WifiConnectionStatusOuterClass.WifiConnectionStatus
import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest
import aap_protobuf.aaw.WifiVersionResponseOuterClass.WifiVersionResponse
import aap_protobuf.service.wifiprojection.message.AccessPointTypeOuterClass.AccessPointType
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.wireless.*
import net.prototux.aare.transport.wireless.WirelessConnector
import java.io.DataInputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID

/** Genuine SDP/RFCOMM, production WPP connector, WPA2 network binding and TCP on two disposable AVDs. */
@SuppressLint("MissingPermission")
internal class WirelessRadioProbe(private val context: Context, private val args: Bundle,
    private val listening: () -> Unit) {
    fun run(): Bundle = ProjectionResources().use { life ->
        ProjectionCpuLease.hold(context,life)
        if (args.getString("protocol") == "wpp-radio-sender") sender(life) else receiver(life)
    }

    private fun sender(life: ProjectionResources): Bundle {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val options = WirelessConnector.Options(bootstrap = WirelessBootstrap.Options(
            instanceId="hsd-disposable-radio", deviceName="HSD QA"))
        val attempt = WirelessConnector(context,AapLog.Stdout,options)
            .Attempt(adapter.getRemoteDevice(requireNotNull(args.getString("peer"))))
        life.own(attempt::abort)
        val transport = attempt.run()
        life.own(transport::close)
        val payload = ByteArray(32) { it.toByte() }
        transport.output.write(payload); transport.output.flush()
        val reply = ByteArray(32).also { DataInputStream(transport.input).readFully(it) }
        check(reply.contentEquals(MessageDigest.getInstance("SHA-256").digest(payload)))
        return Bundle().apply { putString("WPP_sender","Production connector: RFCOMM, WPA2 and TCP round trip") }
    }

    private fun receiver(life: ProjectionResources): Bundle {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val (_, address) = receiverFixtureWifi(context, life)
        val tcp = ServerSocket(0,1,address).also { it.soTimeout=45_000; life.own(it::close) }
        val listener = adapter.listenUsingRfcommWithServiceRecord("HSD WPP QA",UUID.fromString(AA_WIRELESS_UUID))
        life.own(listener::close); listening()
        val bt = listener.accept(45_000); life.own(bt::close)
        val timeout = java.util.Timer("WPP receiver timeout",true)
        timeout.schedule(object : java.util.TimerTask() { override fun run() { bt.close() } },60_000)
        life.own(timeout::cancel)
        val out = bt.outputStream
        fun send(id: Int, message: com.google.protobuf.MessageLite) = writeWppFrame(out,id,message.toByteArray())
        fun endpoint() = WifiStartRequest.newBuilder().setIpAddress(address.hostAddress).setPort(tcp.localPort).build()
        val mode = args.getString("start") ?: "phone"
        var phoneStarted = false
        var acknowledged = false
        var identified = false
        send(WppMessageId.VERSION_REQUEST,WifiVersionRequest.newBuilder().setMajor(4).setMinor(2).build())
        while (true) {
            val frame = readWppFrame(bt.inputStream) ?: error("RFCOMM closed before Wi-Fi handoff")
            when (frame.id) {
                WppMessageId.VERSION_RESPONSE -> {
                    val version = WifiVersionResponse.parseFrom(frame.payload)
                    check(version.deviceSerial.isNotBlank() && version.deviceInfo.deviceId.isNotBlank())
                    check(version.deviceInfo.connectivityLifetimeId.isNotBlank())
                    identified = true
                    if (mode == "receiver") send(WppMessageId.START_REQUEST,endpoint())
                }
                WppMessageId.START_REQUEST -> {
                    check(identified && mode != "receiver" && !phoneStarted)
                    val request = WifiStartRequest.parseFrom(frame.payload)
                    check(request.hasReason() && request.reason == 0 && !request.hasIpAddress() && !request.hasPort())
                    phoneStarted = true
                    if (mode == "crossed") send(WppMessageId.START_REQUEST,endpoint())
                    send(WppMessageId.START_RESPONSE,WifiStartResponse.newBuilder()
                        .setIpAddress(address.hostAddress).setPort(tcp.localPort).build())
                }
                WppMessageId.INFO_REQUEST -> send(WppMessageId.INFO_RESPONSE,WifiInfoResponse.newBuilder()
                    .setSsid(requireNotNull(args.getString("ssid"))).setPassword(requireNotNull(args.getString("pass")))
                    .setSecurityMode(WppSecurityMode.WPA2_PERSONAL).setAccessPointType(AccessPointType.DYNAMIC).build())
                WppMessageId.START_RESPONSE -> {
                    check(mode != "phone")
                    val response = WifiStartResponse.parseFrom(frame.payload)
                    check(response.status.number == WppStatus.SUCCESS && response.ipAddress.isNotBlank())
                    acknowledged = true
                }
                WppMessageId.PING_REQUEST -> writeWppFrame(out,WppMessageId.PING_RESPONSE,frame.payload)
                WppMessageId.CONNECTION_STATUS -> {
                    check(WifiConnectionStatus.parseFrom(frame.payload).status.number == WppStatus.SUCCESS)
                    check(identified && (if (mode == "phone") phoneStarted else acknowledged))
                    break
                }
            }
        }
        tcp.accept().use { connected ->
            connected.soTimeout=5000
            val payload = ByteArray(32).also { DataInputStream(connected.inputStream).readFully(it) }
            connected.outputStream.write(MessageDigest.getInstance("SHA-256").digest(payload))
        }
        return Bundle().apply { putString("WPP_receiver","$mode start; identity, endpoint, Wi-Fi status and TCP payload verified") }
    }
}
