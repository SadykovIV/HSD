package com.hsd.universal.hicar

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Network
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.os.Build
import com.hsd.universal.AppLog
import com.hsd.universal.ProjectionResources
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.R
import com.hsd.universal.ui.UsbAccessoryPolicy
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.wireless.JoinResult
import net.prototux.aare.aap.wireless.WifiCredentials
import net.prototux.aare.aap.wireless.WifiSecurity
import net.prototux.aare.transport.wireless.HeadUnitNetwork
import net.prototux.aare.transport.wireless.ProjectionNetworkAccess
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class HiCarBootstrap(private val context: Context, private val life: ProjectionResources,
                              private val status: (String) -> Unit, private val failed: (Exception) -> Unit) {
    class Endpoint(val identity: String, val key: ByteArray, val link: Link, val dataPort: Int)

    class Link(context: Context, val own: InetAddress, val peer: InetAddress,
               private val life: ProjectionResources, private val network: Network? = null) {
        private val access = ProjectionNetworkAccess(context)
        fun connect(port: Int): Socket = access.checked {
            Socket().also {
                life.own(it::close)
                network?.bindSocket(it)
                it.bind(InetSocketAddress(own, 0))
                it.connect(InetSocketAddress(peer, port), 10_000)
                it.tcpNoDelay = true
                it.soTimeout = 30_000
            }
        }
        fun bind(socket: DatagramSocket) { access.checked { network?.bindSocket(socket) } }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "HiCar timeout").apply { isDaemon = true } }
    init { life.own { timer.shutdownNow() } }
    private fun <T> bounded(seconds: Long, work: () -> T): T {
        val deadline = timer.schedule({ life.close() }, seconds, TimeUnit.SECONDS)
        try { return work() } finally { deadline.cancel(false) }
    }

    fun connect(wired: Boolean, onNcmReady: () -> Unit = {}): Endpoint {
        val identity = HiCarAuthentication.initialize(context)
        return if (wired) connectUsb(identity, onNcmReady) else connectWireless(identity)
    }

    private fun cableMessage(fallback: Int): String {
        val usb = context.getSystemService(UsbManager::class.java)
        val route = runCatching { usb?.accessoryList.orEmpty().firstNotNullOfOrNull {
            UsbAccessoryPolicy.route(it.manufacturer, it.model)
        } }.getOrNull() ?: return context.getString(fallback)
        return context.getString(R.string.hicar_usb_aoa, route.protocol)
    }

    private fun connectUsb(identity: String, onNcmReady: () -> Unit): Endpoint {
        val waiting = cableMessage(R.string.hicar_usb_wait_ncm)
        AppLog.i("HiCar USB · route=HICAR_USB · $waiting", false)
        status(waiting)
        val offer = ProjectionNetworkAccess(context).checked {
            HiCarNcmDiscovery.find(life, { cableMessage(R.string.hicar_usb_no_ncm) }) { name ->
                onNcmReady()
                AppLog.i("HiCar USB · NCM interface=$name · Nearby discovery", false)
                status("HiCar USB · поиск Nearby в сети NCM")
            }
        }
        val link = Link(context, offer.local, offer.host, life)
        val control = link.connect(offer.port)
        val nearby = HiCarNearby(control.getInputStream(), control.getOutputStream())
        val authPort = bounded(20) { nearby.negotiate(identity); nearby.usbAuthenticationPort() }
        status("HiCar USB · сопряжение по кабелю")
        val key = link.connect(authPort).use { socket ->
            bounded(30) {
                HiCarAuthentication(identity, wired = true).use { auth ->
                    auth.authenticate(socket.getInputStream(), socket.getOutputStream()) {
                        error("HiCar USB: ГУ не передало код сопряжения")
                    }
                }
            }
        }
        life.own { key.fill(0) }
        val dataPort = bounded(20) { nearby.usbDataPort() }
        AppLog.i("HiCar USB · NCM · HiChain verified · DMSDP endpoint negotiated", false)
        return Endpoint(identity, key, link, dataPort)
    }

    @SuppressLint("MissingPermission")
    private fun connectWireless(identity: String): Endpoint {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        check(adapter?.isEnabled == true) { "HiCar: включите Bluetooth" }
        val address = ProjectionRoute.prefs(context).getString("bluetoothPeer", null)
        check(!address.isNullOrEmpty()) { "HiCar: выберите магнитолу в настройках подключения" }
        val peer = adapter.bondedDevices.firstOrNull { it.address == address }
            ?: error("HiCar: сначала выполните сопряжение с магнитолой в настройках Bluetooth")
        fun bluetooth(uuid: UUID): BluetoothSocket = bounded(20) {
            peer.createInsecureRfcommSocketToServiceRecord(uuid).also { socket ->
                life.own(socket::close)
                if (Build.VERSION.SDK_INT < 31 ||
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    adapter.cancelDiscovery()
                }
                socket.connect()
            }
        }
        status("HiCar · подключение Bluetooth")
        val bt = bluetooth(HiCarNearby.controlUuid)
        val nearby = HiCarNearby(bt.inputStream, bt.outputStream)
        val service = bounded(20) { nearby.negotiate(identity); nearby.authenticationService() }
        val authSocket = bluetooth(service)
        val auth = HiCarAuthentication(identity).also { life.own(it::close) }
        status("HiCar · проверка сопряжения")
        val key = bounded(150) {
            auth.authenticate(authSocket.inputStream, authSocket.outputStream) {
                status(context.getString(R.string.hicar_pin_wait))
                HiCarPin.await(context, life)
            }
        }
        life.own { key.fill(0) }
        auth.close()
        status("HiCar · согласование Wi-Fi")
        val offer = bounded(30) { nearby.networkOffer(::localGroupClientAddress) }
        val wifi = HeadUnitNetwork(context, AapLog.Stdout, 60_000).also { life.own(it::release) }
        wifi.onLost = { failed(java.io.IOException("HiCar: соединение Wi-Fi потеряно")) }
        val result = wifi.join(WifiCredentials(offer.ssid, offer.password, offer.bssid, WifiSecurity.WPA2_PERSONAL, 3, null))
        check(result is JoinResult.Joined) { "HiCar: ${(result as? JoinResult.Failed)?.message ?: "Wi-Fi unavailable"}" }
        val network = checkNotNull(wifi.network)
        val local = InetAddress.getByName(checkNotNull(result.localAddress))
        // The transport must use Android's actual DHCP address, never the proposed address as a spoofed bind.
        val link = Link(context, local, offer.owner, life, network)
        val networkControl = link.connect(offer.controlPort)
        val tcp = HiCarNearby(networkControl.getInputStream(), networkControl.getOutputStream())
        val port = bounded(30) { tcp.negotiate(identity); tcp.dataPort() }
        AppLog.i("HiCar · HiChain verified · Wi-Fi joined · DMSDP endpoint negotiated", false)
        return Endpoint(identity, key, link, port)
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun localGroupClientAddress(): String {
        val manager = context.getSystemService(WifiP2pManager::class.java)
            ?: error("HiCar: Wi-Fi Direct недоступен")
        val answer = CompletableFuture<String?>()
        val channel = manager.initialize(context, Looper.getMainLooper()) { answer.completeExceptionally(IllegalStateException("Wi-Fi Direct closed")) }
        life.own { answer.cancel(false); channel.close() }
        manager.requestDeviceInfo(channel) { answer.complete(it?.deviceAddress) }
        val mac = answer.get(8, TimeUnit.SECONDS)
        check(HiCarNearby.validMac(mac)) {
            "HiCar: Android скрыл адрес Wi-Fi Direct. Включите Wi-Fi и выполните активацию HSD с поддержкой HiCar."
        }
        return checkNotNull(mac)
    }
}
