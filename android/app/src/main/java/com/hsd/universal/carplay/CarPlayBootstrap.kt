package com.hsd.universal.carplay

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import com.hsd.universal.AppLog
import com.hsd.universal.R
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.ProjectionResources
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.asString
import io.github.shaonialife.cardeck.iap2.asU16List
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.net.InetAddress
import java.util.UUID
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.wireless.*
import net.prototux.aare.transport.wireless.HeadUnitNetwork

private val ACCESSORY_IAP2 = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

/** A socket accepted for hands-free or closed immediately by the HU must not win the iAP2 race. */
@SuppressLint("MissingPermission")
private class IapCandidate(private val socket: BluetoothSocket) : java.io.Closeable {
    @Volatile private var channel: Iap2LinkChannel? = null
    val link get() = checkNotNull(channel)

    fun negotiate(outgoing: Boolean) {
        if (outgoing) socket.connect()
        val candidate = Iap2LinkChannel(StreamDuplexStream(socket.inputStream, socket.outputStream, socket::close))
        channel = candidate
        if (!candidate.awaitReady(15_000)) throw java.io.IOException("Bluetooth connected but iAP2 did not synchronize")
    }

    override fun close() {
        // Closing the socket wakes a blocking connect/read/write, including a racing channel creation.
        runCatching(socket::close)
        channel?.close()
    }
}

internal data class CarPlayEndpoint(
    val network: Network,
    val host: InetAddress,
    val port: Int,
    val localId: String,
    val peer: String,
    val publicKey: ByteArray?,
)

@SuppressLint("MissingPermission", "HardwareIds")
internal class CarPlayBootstrap(
    private val context: Context,
    private val life: ProjectionResources,
    private val status: (String) -> Unit,
    private val auth: AccessoryAuthentication = accessoryVerifier(context),
) {
    fun connect(): CarPlayEndpoint {
        val prefs = ProjectionRoute.prefs(context)
        val remote =
            prefs.getString("bluetoothPeer", null) ?: error("Выберите сопряжённую магнитолу")
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("Bluetooth недоступен")
        check(adapter.isEnabled) { "Включите Bluetooth и повторите подключение" }
        val device = adapter.getRemoteDevice(remote)
        require(device.bondState == BluetoothDevice.BOND_BONDED) {
            "Требуется сопряжение с магнитолой"
        }
        val local = LocalBluetoothIdentity.resolve(context, life)
        status("CarPlay · выберите Apple CarPlay на магнитоле. Ожидаю соединение Bluetooth…")
        fun trace(message: String) = AppLog.i("CarPlay Bluetooth · $message", false)
        val cached = runCatching { device.uuids?.any { it.uuid == ACCESSORY_IAP2 } }.getOrNull()
        trace("bonded · accessory iAP2 in SDP cache=${cached ?: "unknown"}")
        if (android.os.Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
            runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }
        val candidate = connectCarPlayRfcomm(life, incoming = { resources ->
            val listener = CarPlayBluetoothService.claim(adapter)
            resources.own { CarPlayBluetoothService.release(listener) }
            try {
                trace("local iAP2 service registered · waiting for selected head unit")
                dialCarPlayRfcomm(resources, open = {
                    var socket = listener.accept()
                    while (socket.remoteDevice != device) {
                        socket.close()
                        socket = listener.accept()
                    }
                    IapCandidate(socket)
                }, connect = { it.negotiate(false) }, log = ::trace, initialRetryMs = 0, role = "incoming")
            } finally {
                CarPlayBluetoothService.release(listener)
            }
        }, outgoing = { resources ->
            dialCarPlayRfcomm(resources, open = {
                check(adapter.isEnabled && device.bondState == BluetoothDevice.BOND_BONDED) {
                    "Проверьте Bluetooth и сопряжение с магнитолой"
                }
                IapCandidate(device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2))
            }, connect = { it.negotiate(true) }, log = ::trace)
        }, log = ::trace)
        val link = candidate.link
        val wifi = HeadUnitNetwork(context, AapLog.Stdout, 60_000, requireIpv4 = false)
        life.own(wifi::release)
        var stage = R.string.carplay_stage_identification
        var bonjour: CarPlayDiscovery? = null
        var direct: Pair<String, Int>? = null
        var peerKey: ByteArray? = null
        fun join(fields: List<Iap2Csm.Param>, legacy: Boolean) {
            if (bonjour != null) return
            fun value(id: Int) = fields.firstOrNull { it.id == id }
            val base = if (legacy) 1 else 0
            val ssid = value(base)?.asString()?.takeIf(String::isNotBlank) ?: return
            val password = value(base + 1)?.asString().orEmpty()
            val sec =
                value(if (legacy) 3 else 4)?.value?.singleOrNull()?.toInt()
                    ?: if (password.isEmpty()) 0 else 2
            val security =
                when (sec) {
                    0 -> WifiSecurity.OPEN
                    2,
                    3 -> WifiSecurity.WPA2_PERSONAL
                    4 -> WifiSecurity.WPA3_PERSONAL
                    else -> error("Неподдерживаемая защита сети CarPlay ($sec)")
                }
            stage = R.string.carplay_stage_wifi
            status("CarPlay · подключаюсь к сети магнитолы")
            val result = wifi.join(WifiCredentials(ssid, password, null, security, sec, null))
            if (result is JoinResult.Failed) error("Не удалось подключиться к Wi-Fi магнитолы: ${result.message}")
            val network = checkNotNull(wifi.network) { "Соединение с Wi-Fi магнитолы завершено" }
            bonjour = CarPlayDiscovery(context, network, local, life, ::trace).also { it.start() }
            stage = R.string.carplay_stage_discovery
            trace("Wi-Fi joined · waiting for CarPlay endpoint")
        }
        var authenticated = false
        var identified = false
        var setup: CarPlayWirelessSetup? = null
        val parser = Iap2Csm.Parser()
        fun send(id: Int, body: ByteArray = byteArrayOf()) =
            check(link.sendControl(Iap2Csm.encode(id, body)))
        trace("iAP2 link ready")
        send(0x1d00)
        val deadline = android.os.SystemClock.elapsedRealtime() + 110_000
        while (!life.isClosed && android.os.SystemClock.elapsedRealtime() < deadline) {
            // Some receivers release Bluetooth as soon as they hand off to Wi-Fi. Drain the
            // last iAP2 reply and let discovery on the authenticated Wi-Fi network finish.
            val chunk = link.recvControl(250)
            if (chunk == null && !link.isAlive && bonjour == null) break
            if (chunk != null)
                for (frame in parser.offer(chunk)) {
                    val fields = Iap2Csm.parseParams(frame.body)
                    fun value(id: Int) = fields.firstOrNull { it.id == id }?.value
                    when (frame.messageId) {
                        0x1d01 -> {
                            check(!identified)
                            identified = true
                            val sends = fields.filter { it.id == 6 }.flatMap { it.asU16List() }
                            val receives = fields.filter { it.id == 7 }.flatMap { it.asU16List() }
                            setup = CarPlayWirelessSetup(sends, receives)
                            trace("identified · advertises start=${0x4300 in receives || 0x4301 in sends}, Wi-Fi config=${0x5702 in receives || 0x5703 in sends}")
                            send(0x1d02)
                            stage = R.string.carplay_stage_certificate
                            send(0xaa00)
                        }
                        0xaa01 -> {
                            check(identified && !authenticated)
                            val nonce = auth.challenge(requireNotNull(value(0)))
                            stage = R.string.carplay_stage_signature
                            send(0xaa02, Iap2Csm.param(0, nonce))
                        }
                        0xaa03 -> {
                            check(!authenticated)
                            if (!auth.verify(requireNotNull(value(0)))) {
                                send(0xaa04)
                                error("Магнитола не подтвердила подпись iAP2")
                            }
                            authenticated = true
                            send(0xaa05)
                            trace("accessory verified · requesting Wi-Fi session")
                            stage = R.string.carplay_stage_credentials
                            checkNotNull(setup).announce(local, ::send)
                        }
                        0x5703 -> {
                            check(authenticated)
                            join(fields, true)
                        }
                        0x4301 -> {
                            check(authenticated)
                            val networkFields = value(1)?.let(Iap2Csm::parseParams).orEmpty()
                            join(networkFields, false)
                            val address = networkFields.firstOrNull { it.id == 3 }?.asString()
                            val port =
                                value(2)?.fold(0) { n, b -> (n shl 8) or (b.toInt() and 255) }
                            if (address != null && port != null && port in 1..65535)
                                direct = address to port
                            val key = value(4)?.toString(Charsets.US_ASCII)?.trimEnd('\u0000')
                            if (key != null && key.matches(Regex("[0-9a-fA-F]{64}")))
                                peerKey = key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        }
                    }
                }
            val net = wifi.network ?: continue
            val target =
                direct?.let { (host, port) -> bonjour?.address(host)?.let { it to port } }
                    ?: bonjour?.endpoint()
            if (authenticated && target != null)
                return CarPlayEndpoint(net, target.first, target.second, local, remote, peerKey)
        }
        val phase = context.getString(stage)
        error(context.getString(if (link.isAlive || bonjour != null) R.string.carplay_setup_timeout else R.string.carplay_setup_closed, phase))
    }
}
