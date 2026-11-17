package com.hsd.universal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.carplay.AccessoryAuthentication
import com.hsd.universal.carplay.CarPlayBootstrap
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.asString
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
        val initiates = args.getString("rfcomm") == "fromHeadUnit"
        val listen = if (initiates) null else
            adapter.listenUsingRfcommWithServiceRecord(
                "QA CarPlay",
                UUID.fromString("00000000-deca-fade-deca-deafdecacaff"),
            )
        if (listen != null) life.own(listen::close)
        val addr = receiverWifiAddress()
        val ip =
            ServerSocket(0, 1, addr).also {
                life.own(it::close)
                it.soTimeout = 35_000
            }
        listening()
        val socket = listen?.accept(50_000) ?: adapter.getRemoteDevice(arg("peer"))
            .createRfcommSocketToServiceRecord(UUID.fromString("00000000-deca-fade-deca-deafdecacafe"))
            .also { outgoing -> life.own(outgoing::close); outgoing.connect() }
        life.own(socket::close)
        check(socket.remoteDevice.address == arg("peer"))
        val channel =
            Iap2LinkChannel(
                StreamDuplexStream(socket.inputStream, socket.outputStream, socket::close)
            )
        life.own(channel::close)
        check(channel.awaitReady(10_000)) { "Receiver iAP2 link timed out" }
        val parser = Iap2Csm.Parser()
        val wifiConfigOnly = args.getString("setup") == "wifiConfig"
        val request = if (wifiConfigOnly) 0x5702 else 0x4300
        val response = if (wifiConfigOnly) 0x5703 else 0x4301
        val sparse = args.getString("capabilities") == "incomplete"
        val reported = args.getString("capabilities")
        val serialFirst = args.getString("certificate") == "serial"
        val expectedDeviceUpdates = setOf(0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4e0d, 0x4e0e)
        val deviceUpdates = mutableSetOf<Int>()
        val signer = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(data("key")))
        fun send(id: Int, body: ByteArray = byteArrayOf()) =
            check(channel.sendControl(Iap2Csm.encode(id, body)))
        var accepted = false
        var receivedId = false
        var serialSent = false
        var firstConfigSeen = false
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (channel.isAlive && SystemClock.elapsedRealtime() < deadline) {
            val bytes = channel.recvControl(250) ?: continue
            for (frame in parser.offer(bytes)) when (frame.messageId) {
                0x1d00 -> {
                    val sends = mutableListOf<Int>()
                    if (!sparse) sends.add(response)
                    if (serialFirst) sends.add(0xaa06)
                    val declaredSends = when (reported) {
                        "reported-first" -> intArrayOf(0xaa06, 0x5703)
                        "reported-reconnect" -> intArrayOf(0x5000, 0x5002, 0x5003, 0x4c00, 0x4c02,
                            0x4c03, 0x4c05, 0x4c07, 0x4c08, 0x6800, 0x6802, 0x6803)
                        else -> sends.toIntArray()
                    }
                    val declaredReceives = if (reported == "reported-reconnect")
                        intArrayOf(0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4e0e, 0x5001, 0x4c01, 0x4c04, 0x6801, 0x4e0d)
                        else expectedDeviceUpdates.toIntArray() + if (reported == "reported-first") intArrayOf(0x5702) else intArrayOf()
                    send(0x1d01, Iap2Csm.u16List(6, declaredSends) + Iap2Csm.u16List(7, declaredReceives))
                }
                0xaa00 -> if (serialFirst && !serialSent) {
                    check(frame.body.isEmpty())
                    serialSent = true
                    repeat(2) { send(0xaa06, Iap2Csm.param(0, byteArrayOf(1, 2, 3))) }
                } else {
                    check(frame.body.isEmpty())
                    send(0xaa01, Iap2Csm.param(0, data("chain")))
                }
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
                in expectedDeviceUpdates -> {
                    check(accepted)
                    deviceUpdates.add(frame.messageId)
                    if (frame.messageId == 0x4e0e) receivedId = Iap2Csm.parseParams(frame.body).single { it.id == 0 }
                        .asString().equals(arg("peer"), ignoreCase = true)
                }
                0xaa04 -> error("Phone rejected accessory signature")
                request -> {
                    check(accepted && receivedId) { "Wi-Fi request arrived before the authenticated phone identifier" }
                    check(deviceUpdates == expectedDeviceUpdates)
                    if (sparse && !wifiConfigOnly) check(firstConfigSeen)
                    val useDiscovery = wifiConfigOnly || initiates
                    val network = if (wifiConfigOnly) {
                        Iap2Csm.string(1, arg("ssid")) + Iap2Csm.string(2, arg("pass")) + Iap2Csm.u8(3, 2)
                    } else {
                        val fields = Iap2Csm.group(
                            1,
                            Iap2Csm.string(0, arg("ssid")),
                            Iap2Csm.string(1, arg("pass")),
                            if (useDiscovery) byteArrayOf() else Iap2Csm.string(3, addr.hostAddress!!),
                            Iap2Csm.u8(4, 2),
                        )
                        if (useDiscovery) fields else fields + Iap2Csm.u32(2, ip.localPort.toLong())
                    }
                    send(response, network)
                    if (useDiscovery) {
                        // This head unit drops Bluetooth after delivering Wi-Fi credentials. Its
                        // endpoint is only published over Bonjour once the radio handoff is over.
                        SystemClock.sleep(300)
                        channel.close()
                        publishEndpoint(ip.localPort, life)
                    }
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
                            "Signed challenge, all requested device updates before Wi-Fi, replied over WPA2 (serialFirst=$serialFirst, incomplete=$sparse)",
                        )
                    }
                }
                0x5702 -> firstConfigSeen = true
            }
        }
        error("Receiver did not complete Bluetooth bootstrap")
    }

    @Suppress("DEPRECATION")
    private fun receiverWifiAddress(): Inet4Address {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            for (network in connectivity.allNetworks) {
                if (connectivity.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) continue
                val address = connectivity.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()
                if (address != null) return address
            }
            Thread.sleep(50)
        }
        error("Receiver fixture needs an assigned Wi-Fi IPv4 address before accepting iAP2")
    }

    @Suppress("DEPRECATION")
    private fun publishEndpoint(port: Int, life: ProjectionResources) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = connectivity.allNetworks.first {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val nsd = context.getSystemService(NsdManager::class.java)
        val registered = CompletableFuture<Unit>()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registered.complete(Unit) }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                registered.completeExceptionally(IllegalStateException("Bonjour registration failed ($code)"))
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        val service = NsdServiceInfo().also {
            it.serviceName = "HSD QA receiver"
            it.serviceType = "_airplay._tcp"
            it.network = wifi
            it.port = port
            it.setAttribute("features", "0x0,0x20")
        }
        nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
        life.own { nsd.unregisterService(listener) }
        registered.get(8, TimeUnit.SECONDS)
    }
}
