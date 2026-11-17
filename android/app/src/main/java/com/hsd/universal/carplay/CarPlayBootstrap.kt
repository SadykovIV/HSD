package com.hsd.universal.carplay

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.wireless.*
import net.prototux.aare.transport.wireless.HeadUnitNetwork

private val ACCESSORY_IAP2 = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
private val connectedDeviceUuids = CarPlayDeviceUuids(android.os.SystemClock::elapsedRealtime)

/** A socket accepted for hands-free or closed immediately by the HU must not win the iAP2 race. */
@SuppressLint("MissingPermission")
private class IapCandidate(private val socket: BluetoothSocket, private val trace: (String) -> Unit) : java.io.Closeable {
    @Volatile private var channel: Iap2LinkChannel? = null
    val link get() = checkNotNull(channel)

    fun negotiate(outgoing: Boolean) {
        if (outgoing) socket.connect()
        val candidate = Iap2LinkChannel(StreamDuplexStream(socket.inputStream, socket.outputStream, socket::close),
            log = { trace("${if (outgoing) "outgoing" else "incoming"} · $it") })
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
    val device: CarPlayDeviceInformation,
)

@SuppressLint("MissingPermission", "HardwareIds")
internal class CarPlayBootstrap(
    private val context: Context,
    private val life: ProjectionResources,
    private val status: (String) -> Unit,
    private val auth: AccessoryAuthentication = accessoryVerifier(context),
) {
    private fun awaitBond(device: BluetoothDevice, adapter: android.bluetooth.BluetoothAdapter) {
        val initial = device.bondState
        if (initial == BluetoothDevice.BOND_BONDED) return
        check(initial == BluetoothDevice.BOND_BONDING) { context.getString(R.string.carplay_bond_needed) }
        status(context.getString(R.string.carplay_bond_confirm))
        val bonded = CompletableFuture<Unit>()
        fun observeBond() {
            if (device.bondState == BluetoothDevice.BOND_BONDED) bonded.complete(Unit)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION") val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed == device) observeBond()
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        try {
            life.own { bonded.cancel(false) }
            // A receiver can inspect our SDP record before the user confirms the pairing codes.
            if (device.bondState == BluetoothDevice.BOND_BONDING) CarPlayBluetoothService.prepare(adapter)
            // During confirmation Android may briefly report NONE while moving between
            // Bluetooth transports. Only the system's positive bond opens the secure session.
            observeBond()
            bonded.get(CarPlayBluetoothService.PAIRING_SECONDS.toLong(), TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            error(context.getString(R.string.carplay_bond_timeout))
        } finally { context.unregisterReceiver(receiver) }
    }

    fun connect(): CarPlayEndpoint {
        val prefs = ProjectionRoute.prefs(context)
        val remote =
            prefs.getString("bluetoothPeer", null) ?: error(context.getString(R.string.desktop_select_a_paired_head_unit))
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("Bluetooth недоступен")
        check(adapter.isEnabled) { "Включите Bluetooth и повторите подключение" }
        val device = adapter.getRemoteDevice(remote)
        awaitBond(device, adapter)
        val name = device.name?.takeIf(String::isNotBlank) ?: "CarPlay"
        status(context.getString(R.string.carplay_wait_service, name))
        // Resolve our identity alongside SDP so neither Bluetooth role misses the peer's handshake.
        val identity = FutureTask { LocalBluetoothIdentity.resolve(context, life) }
        life.own { identity.cancel(true) }
        kotlin.concurrent.thread(name = "CarPlay local identity", isDaemon = true) { identity.run() }
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
                    IapCandidate(socket, ::trace)
                }, connect = { it.negotiate(false) }, log = ::trace, initialRetryMs = 0, role = "incoming")
            } finally {
                CarPlayBluetoothService.release(listener)
            }
        }, outgoing = { resources ->
            if (cached != true) refreshAccessoryServices(device, resources, ::trace)
            dialCarPlayRfcomm(resources, open = {
                check(adapter.isEnabled && device.bondState == BluetoothDevice.BOND_BONDED) {
                    "Проверьте Bluetooth и сопряжение с магнитолой"
                }
                IapCandidate(device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2), ::trace)
            }, connect = { it.negotiate(true) }, log = ::trace)
        }, log = ::trace, timeoutMessage = context.getString(R.string.carplay_service_timeout))
        val local by lazy {
            try { identity.get() } catch (error: ExecutionException) { throw error.cause ?: error }
        }
        val link = candidate.link
        val deviceInfo = CarPlayDeviceInformation(connectedDeviceUuids.acquire(life))
        val wifi = HeadUnitNetwork(context, AapLog.Stdout, 60_000, requireIpv4 = false)
        life.own(wifi::release)
        var stage = 0
        fun advance(next: Int) {
            if (stage == next) return
            stage = next
            status("CarPlay · ${context.getString(next)}")
        }
        advance(R.string.carplay_stage_identification)
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
            advance(R.string.carplay_stage_wifi)
            val result = wifi.join(WifiCredentials(ssid, password, null, security, sec, null))
            if (result is JoinResult.Failed) error(if (result.status == WppStatus.PHONE_WIFI_DISABLED) result.message
                else context.getString(R.string.projection_wifi_join_failed, result.message))
            val network = checkNotNull(wifi.network) { "Соединение с Wi-Fi магнитолы завершено" }
            bonjour = CarPlayDiscovery(context, network, local, life, ::trace).also { it.start() }
            advance(R.string.carplay_stage_discovery)
            trace("Wi-Fi joined · waiting for CarPlay endpoint")
        }
        var setup: CarPlayWirelessSetup? = null
        val parser = Iap2Csm.Parser()
        // IDs suffice to reconstruct the exchange. Control bodies contain private network credentials.
        val observed = mutableSetOf<Int>()
        fun send(id: Int, body: ByteArray = byteArrayOf()) {
            check(link.sendControl(Iap2Csm.encode(id, body)))
            trace("iAP2 tx=0x${id.toString(16)}")
        }
        val exchange = AccessoryAuthExchange(auth, ::send, context.getString(R.string.carplay_invalid_accessory_signature))
        trace("iAP2 link ready")
        send(0x1d00)
        val deadline = android.os.SystemClock.elapsedRealtime() + 110_000
        var nextProgress = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!life.isClosed && android.os.SystemClock.elapsedRealtime() < deadline) {
            // Some receivers release Bluetooth as soon as they hand off to Wi-Fi. Drain the
            // last iAP2 reply and let discovery on the authenticated Wi-Fi network finish.
            val chunk = link.recvControl(250)
            if (chunk == null && !link.isAlive && bonjour == null) break
            if (chunk != null)
                for (frame in parser.offer(chunk)) {
                    if (observed.size < 48 && observed.add(frame.messageId))
                        trace("iAP2 rx=0x${frame.messageId.toString(16)}")
                    val fields = Iap2Csm.parseParams(frame.body)
                    fun value(id: Int) = fields.firstOrNull { it.id == id }?.value
                    when (frame.messageId) {
                        0x1d01 -> {
                            val sends = fields.filter { it.id == 6 }.flatMap { it.asU16List() }
                            val receives = fields.filter { it.id == 7 }.flatMap { it.asU16List() }
                            setup = CarPlayWirelessSetup(sends, receives)
                            fun ids(list: List<Int>) = list.distinct().take(64).joinToString(",") { it.toString(16) }.ifEmpty { "none" }
                            trace("identified · sends=${ids(sends)} · receives=${ids(receives)}")
                            exchange.identified()
                            advance(R.string.carplay_stage_certificate)
                        }
                        0xaa06, 0xaa01, 0xaa03 -> {
                            exchange.receive(frame.messageId, fields)
                            when (frame.messageId) {
                                0xaa06 -> if (exchange.stage == AccessoryAuthExchange.Stage.CERTIFICATE)
                                    advance(R.string.carplay_stage_certificate)
                                0xaa01 -> advance(R.string.carplay_stage_signature)
                                0xaa03 -> {
                                    trace("accessory verified · requesting Wi-Fi session")
                                    advance(R.string.carplay_stage_credentials)
                                    checkNotNull(setup).announce(local, deviceInfo, android.os.SystemClock.elapsedRealtime(), ::send)
                                }
                            }
                        }
                        0x5703 -> {
                            check(exchange.stage == AccessoryAuthExchange.Stage.VERIFIED)
                            join(fields, true)
                        }
                        0x4301 -> {
                            check(exchange.stage == AccessoryAuthExchange.Stage.VERIFIED)
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
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= nextProgress && exchange.stage != AccessoryAuthExchange.Stage.VERIFIED) {
                trace("waiting · auth=${exchange.stage} · ${link.progressSummary()}")
                nextProgress = now + 10_000
            }
            if (stage == R.string.carplay_stage_credentials && link.isAlive)
                setup?.whileWaitingForWifi(local, android.os.SystemClock.elapsedRealtime(), ::send)
            val net = wifi.network ?: continue
            val target =
                direct?.let { (host, port) -> bonjour?.address(host)?.let { it to port } }
                    ?: bonjour?.endpoint()
            if (exchange.stage == AccessoryAuthExchange.Stage.VERIFIED && target != null)
                return CarPlayEndpoint(net, target.first, target.second, local, remote, peerKey, deviceInfo)
        }
        val phase = context.getString(stage)
        error(context.getString(if (link.isAlive || bonjour != null) R.string.carplay_setup_timeout else R.string.carplay_setup_closed, phase))
    }

    /** Only the outgoing role waits for SDP. Incoming RFCOMM remains available during the lookup. */
    private fun refreshAccessoryServices(device: BluetoothDevice, resources: ProjectionResources, trace: (String) -> Unit) {
        val finished = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION") val peer = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (peer == device) finished.countDown()
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        resources.own(finished::countDown)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
            try {
                if (resources.isClosed) return
                val requested = device.fetchUuidsWithSdp()
                val replied = requested && finished.await(6, TimeUnit.SECONDS)
                if (!resources.isClosed) {
                    val uuids = device.uuids
                    trace("SDP refresh · requested=$requested reply=$replied accessory iAP2=${uuids?.any { it.uuid == ACCESSORY_IAP2 } ?: "unknown"}")
                }
            } finally { context.unregisterReceiver(receiver) }
        } catch (error: SecurityException) {
            trace("SDP refresh unavailable · ${error.javaClass.simpleName}")
        }
    }
}
