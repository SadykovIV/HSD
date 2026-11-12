package com.hsd.universal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.carplay.AccessoryAuthentication
import com.hsd.universal.carplay.CarPlayBootstrap
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID

/** The two emulators use a per-run test CA. The release APK only trusts its accessory CA assets. */
@SuppressLint("MissingPermission")
internal class CarPlayRadioProbe(private val context: Context, private val args: Bundle,
    private val listening: () -> Unit) {
    private fun arg(key: String) = requireNotNull(args.getString(key))

    private fun data(key: String) = Base64.decode(arg(key), Base64.DEFAULT)

    fun run(): Bundle =
        ProjectionResources().use { life ->
            if (arg("protocol") == "carplay-radio-sender") sender(life) else receiver(life)
        }

    private fun sender(life: ProjectionResources): Bundle {
        val root =
            CertificateFactory.getInstance("X.509").generateCertificate(data("ca").inputStream())
                as X509Certificate
        val prefs = ProjectionRoute.prefs(context)
        val oldPeer = prefs.getString("bluetoothPeer", null)
        val oldLocal = prefs.getString("localBluetoothAddress", null)
        check(
            prefs
                .edit()
                .putString("bluetoothPeer", arg("peer"))
                .putString("localBluetoothAddress", arg("local"))
                .commit()
        )
        life.own {
            prefs
                .edit()
                .putString("bluetoothPeer", oldPeer)
                .putString("localBluetoothAddress", oldLocal)
                .commit()
        }
        val endpoint =
            CarPlayBootstrap(
                    context,
                    life,
                    { android.util.Log.i("CarPlayRadioQA", it) },
                    AccessoryAuthentication(listOf(root)),
                )
                .connect()
        val payload = ByteArray(32).also { SecureRandom().nextBytes(it) }
        endpoint.network.socketFactory.createSocket().use { socket ->
            socket.soTimeout = 8000
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 8000)
            socket.getOutputStream().write(payload)
            val reply =
                ByteArray(32).also { DataInputStream(socket.getInputStream()).readFully(it) }
            check(reply.contentEquals(MessageDigest.getInstance("SHA-256").digest(payload)))
        }
        return Bundle().apply {
            putString(
                "CarPlay_bootstrap",
                "Bluetooth iAP2, verified challenge, Wi-Fi, IP round trip",
            )
        }
    }

    private fun receiver(life: ProjectionResources): Bundle {
        val adapter = context.getSystemService(BluetoothManager::class.java)!!.adapter
        val listen =
            adapter.listenUsingRfcommWithServiceRecord(
                "QA CarPlay",
                UUID.fromString("00000000-deca-fade-deca-deafdecacaff"),
            )
        life.own(listen::close)
        val addr =
            NetworkInterface.getByName("wlan0")
                .inetAddresses
                .toList()
                .filterIsInstance<Inet4Address>()
                .single()
        val ip =
            ServerSocket(0, 1, addr).also {
                life.own(it::close)
                it.soTimeout = 35_000
            }
        listening()
        val socket = listen.accept(50_000)
        life.own(socket::close)
        check(socket.remoteDevice.address == arg("peer"))
        val channel =
            Iap2LinkChannel(
                StreamDuplexStream(socket.inputStream, socket.outputStream, socket::close)
            )
        life.own(channel::close)
        check(channel.awaitReady(10_000)) { "Receiver iAP2 link timed out" }
        val parser = Iap2Csm.Parser()
        val signer = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(data("key")))
        fun send(id: Int, body: ByteArray = byteArrayOf()) =
            check(channel.sendControl(Iap2Csm.encode(id, body)))
        var accepted = false
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (channel.isAlive && SystemClock.elapsedRealtime() < deadline) {
            val bytes = channel.recvControl(250) ?: continue
            for (frame in parser.offer(bytes)) when (frame.messageId) {
                0x1d00 -> send(0x1d01, Iap2Csm.u16List(7, intArrayOf(0x4300)))
                0xaa00 -> send(0xaa01, Iap2Csm.param(0, data("chain")))
                0xaa02 -> {
                    val digest = Iap2Csm.parseParams(frame.body).single { it.id == 0 }.value
                    val signature =
                        Signature.getInstance("NONEwithECDSA").run {
                            initSign(signer)
                            update(digest)
                            sign()
                        }
                    send(0xaa03, Iap2Csm.param(0, signature))
                }
                0xaa05 -> accepted = true
                0xaa04 -> error("Phone rejected accessory signature")
                0x4300 -> {
                    check(accepted)
                    val network =
                        Iap2Csm.group(
                            1,
                            Iap2Csm.string(0, arg("ssid")),
                            Iap2Csm.string(1, arg("pass")),
                            Iap2Csm.string(3, addr.hostAddress!!),
                            Iap2Csm.u8(4, 2),
                        )
                    send(0x4301, network + Iap2Csm.u16(2, ip.localPort))
                    ip.accept().use { client ->
                        client.soTimeout = 8000
                        val payload =
                            ByteArray(32).also {
                                DataInputStream(client.getInputStream()).readFully(it)
                            }
                        client
                            .getOutputStream()
                            .write(MessageDigest.getInstance("SHA-256").digest(payload))
                    }
                    return Bundle().apply {
                        putString(
                            "CarPlay_receiver",
                            "Signed challenge, accepted over Bluetooth, replied over WPA2",
                        )
                    }
                }
            }
        }
        error("Receiver did not complete Bluetooth bootstrap")
    }
}
