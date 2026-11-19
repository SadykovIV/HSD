package net.prototux.aare.transport.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.hsd.universal.R
import com.hsd.universal.ProjectionWifi
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.wireless.JoinResult
import net.prototux.aare.aap.wireless.WifiCredentials
import net.prototux.aare.aap.wireless.WifiSecurity
import net.prototux.aare.aap.wireless.WppStatus
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The head unit's Wi-Fi network for one session: joined with a [WifiNetworkSpecifier] request,
 * which gives an app-private, local-only network (no internet, never the default route) that
 * lasts as long as the request is registered. Sockets must be bound to [network].
 *
 * Android 10+ shows a system "connect to device" dialog for such a request, unless this app
 * already got approval for the same SSID *and* BSSID (hence passing the BSSID when known). The
 * request is only accepted from a foreground app (or, on newer versions, a foreground service).
 * On single-radio phones, joining drops the phone's normal Wi-Fi for the session.
 */
@SuppressLint("MissingPermission") // checked by WirelessConnector before use
internal class HeadUnitNetwork(
    context: Context,
    private val log: AapLog,
    /** How long the request may take, user approval included. */
    private val timeoutMs: Long,
    private val matchBssid: Boolean = true,
    private val requireIpv4: Boolean = true,
) {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val access = ProjectionNetworkAccess(this.context)

    /** The joined network; null until [join] succeeded. */
    @Volatile var network: Network? = null
        private set
    @Volatile var ssid: String? = null
        private set
    /** Called (on a ConnectivityManager thread) when the joined network goes away. */
    @Volatile var onLost: (() -> Unit)? = null

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var pending: CompletableFuture<*>? = null
    @Volatile private var released = false

    /** Blocks until the phone has an IPv4 address on the head unit's network, or fails. */
    fun join(credentials: WifiCredentials, directedScan: Boolean = false): JoinResult {
        if (released) return JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "cancelled")
        val radio = ProjectionWifi.read(context)
        if (radio.needsEnable) {
            return JoinResult.Failed(WppStatus.PHONE_WIFI_DISABLED, context.getString(radio.message))
        }
        ssid = credentials.ssid
        findExisting(credentials)?.let { return it }

        val specifier = try {
            specifier(credentials, directedScan)
        } catch (e: IllegalArgumentException) {
            return JoinResult.Failed(WppStatus.WIFI_INCORRECT_CREDENTIALS, "unusable credentials: ${e.message}")
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val result = CompletableFuture<Pair<Network, LinkProperties>?>()
        val attempt = nextAttempt.incrementAndGet()
        val failureReason = CompletableFuture<Int>()
        pending = result
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                log.i("wireless[$attempt]: Wi-Fi available; waiting for a local address")
                if (!released) network = n
            }

            // onAvailable can come before DHCP results are visible; wait for an IPv4 address.
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (n == network && localAddress(lp) != null && result.complete(n to lp))
                    log.i("wireless[$attempt]: local address acquired")
            }

            // Not found within the timeout, or the user declined the dialog.
            override fun onUnavailable() {
                log.w("wireless[$attempt]: Android marked the local Wi-Fi request unavailable")
                result.complete(null)
            }

            override fun onLost(n: Network) {
                if (n != network) return
                log.w("wireless[$attempt]: lost the head unit's Wi-Fi network")
                network = null
                if (!result.completeExceptionally(IOException(context.getString(R.string.projection_wifi_lost)))) onLost?.invoke()
            }
        }
        callback = cb
        val stopDiagnostics = if (Build.VERSION.SDK_INT >= 34) observeFailures(specifier) { reason ->
            log.w("wireless[$attempt]: ${failureDetail(reason)}")
            failureReason.complete(reason)
        } else null
        log.i("wireless[$attempt]: requesting exact SSID (${credentials.security}, directedScan=$directedScan, failureListener=${stopDiagnostics != null})")
        return try {
            connectivity.requestNetwork(request, cb, timeoutMs.toInt())
            if (released) {
                runCatching { connectivity.unregisterNetworkCallback(cb) }
                return JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "cancelled")
            }
            val link = result.get(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
            if (link == null) {
                // Connectivity and Wi-Fi report the same attempt on different callback queues.
                val detail = if (stopDiagnostics != null && !released) try {
                    failureDetail(failureReason.get(FAILURE_DETAIL_WAIT_MS, TimeUnit.MILLISECONDS))
                } catch (_: TimeoutException) { null } else null
                return JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE,
                    detail ?: context.getString(R.string.projection_wifi_unavailable))
            }
            val (target, properties) = link
            // The callback and DHCP do not grant this UID permission to bind. Verify before
            // telling the head unit SUCCESS, using the same socket operation as projection.
            access.socket(target).use { }
            if (network == target && !released) joined(properties)
            else JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, context.getString(R.string.projection_wifi_lost))
        } catch (error: IOException) {
            JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, error.message.orEmpty())
        } catch (error: java.util.concurrent.ExecutionException) {
            JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, error.cause?.message.orEmpty())
        } catch (_: CancellationException) {
            JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "cancelled")
        } catch (e: TimeoutException) {
            JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "no Wi-Fi address after ${timeoutMs / 1000} s")
        } finally { stopDiagnostics?.invoke() }
    }

    /** Drops the network request (Android then disconnects from the head unit). Idempotent. */
    fun release() {
        released = true
        pending?.cancel(false)
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }

    @android.annotation.TargetApi(34)
    private fun observeFailures(specifier: WifiNetworkSpecifier, report: (Int) -> Unit): (() -> Unit)? {
        val manager = wifi ?: return null
        val listener = WifiManager.LocalOnlyConnectionFailureListener { failed, reason ->
            if (failed == specifier && !released) report(reason)
        }
        try { manager.addLocalOnlyConnectionFailureListener(context.mainExecutor, listener) }
        catch (_: SecurityException) { return null }
        catch (_: UnsupportedOperationException) { return null }
        return { try { manager.removeLocalOnlyConnectionFailureListener(listener) } catch (_: RuntimeException) {} }
    }

    private fun failureDetail(reason: Int): String = context.getString(when (reason) {
        WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NOT_FOUND -> R.string.projection_wifi_not_found
        WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NO_RESPONSE -> R.string.projection_wifi_no_response
        WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_ASSOCIATION -> R.string.projection_wifi_association
        WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_AUTHENTICATION -> R.string.projection_wifi_authentication
        WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_IP_PROVISIONING -> R.string.projection_wifi_no_address
        WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_USER_REJECT -> R.string.projection_wifi_declined
        else -> R.string.projection_wifi_unavailable
    })

    private fun specifier(c: WifiCredentials, directedScan: Boolean): WifiNetworkSpecifier {
        val builder = WifiNetworkSpecifier.Builder().setSsid(c.ssid).setIsHiddenSsid(directedScan)
        val bssid = c.bssid
        if (matchBssid && bssid != null) builder.setBssid(MacAddress.fromString(bssid))
        when (c.security) {
            WifiSecurity.OPEN -> {}
            // A transition-mode (WPA2/WPA3) access point accepts a WPA2 PSK, also from phones without SAE.
            WifiSecurity.WPA2_PERSONAL, WifiSecurity.WPA2_WPA3_PERSONAL -> builder.setWpa2Passphrase(c.password)
            WifiSecurity.WPA3_PERSONAL -> builder.setWpa3Passphrase(c.password)
            WifiSecurity.UNSUPPORTED -> throw IllegalArgumentException("security mode ${c.securityMode} is not supported")
        }
        return builder.build()
    }

    /**
     * If the phone already is on that SSID (a saved network, or a previous session's request still
     * up), a specifier request could make Android tear that connection down and rebuild it; use
     * the existing network instead. Reading SSIDs needs the location permission; without it we
     * just make the request. When the head unit gave a BSSID the network must match it too, so a
     * look-alike access point with the same SSID is never reused.
     */
    private fun findExisting(c: WifiCredentials): JoinResult? {
        val ssid = c.ssid
        val wantBssid = if (matchBssid) c.bssid else null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val found = CompletableFuture<Network>()
        val listen = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Since Android 12 the SSID is only in the capabilities' WifiInfo, and only with this flag.
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) return
                    val info = caps.transportInfo as? WifiInfo ?: return
                    val securityMatches = when (c.security) {
                        WifiSecurity.WPA2_PERSONAL -> info.currentSecurityType == WifiInfo.SECURITY_TYPE_PSK
                        WifiSecurity.WPA3_PERSONAL -> info.currentSecurityType == WifiInfo.SECURITY_TYPE_SAE
                        WifiSecurity.WPA2_WPA3_PERSONAL -> info.currentSecurityType in setOf(WifiInfo.SECURITY_TYPE_PSK,WifiInfo.SECURITY_TYPE_SAE)
                        WifiSecurity.OPEN -> info.currentSecurityType == WifiInfo.SECURITY_TYPE_OPEN
                        else -> false
                    }
                    if (securityMatches && unquote(info.ssid) == ssid && bssidMatches(info.bssid, wantBssid)) found.complete(n)
                }
                override fun onLost(n: Network) = onExistingLost(n, found)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                @Suppress("DEPRECATION") // the only way to read the SSID on Android 10/11
                override fun onAvailable(n: Network) {
                    val info = wifi?.connectionInfo
                    if (unquote(info?.ssid) == ssid && bssidMatches(info?.bssid, wantBssid)) found.complete(n)
                }
                override fun onLost(n: Network) = onExistingLost(n, found)
            }
        }
        connectivity.registerNetworkCallback(listen, cb)
        // Callbacks for networks that are already up arrive right after registration.
        val n = runCatching { found.get(EXISTING_LOOKUP_MS, TimeUnit.MILLISECONDS) }.getOrNull()
        val lp = n?.let { connectivity.getLinkProperties(it) }
        if (n == null || lp == null || localAddress(lp) == null || released) {
            runCatching { connectivity.unregisterNetworkCallback(cb) }
            return null
        }
        // A passive callback can see a network that this UID cannot use. Ask Android
        // for our own specifier request in that case.
        try {
            access.socket(n).use { }
        } catch (error: IOException) {
            runCatching { connectivity.unregisterNetworkCallback(cb) }
            if (error is ProjectionNetworkDenied && access.vpnActive())
                return JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, error.message.orEmpty())
            log.i("wireless: existing Wi-Fi could not be bound (${error.javaClass.simpleName}); requesting a session network")
            return null
        }
        log.i("wireless: reusing matching Wi-Fi network")
        network = n
        callback = cb
        if (released || connectivity.getLinkProperties(n) == null) {
            runCatching { connectivity.unregisterNetworkCallback(cb) }
            network = null
            return null
        }
        return joined(lp)
    }

    private fun onExistingLost(n: Network, found: CompletableFuture<Network>) {
        if (n != network || !found.isDone) return
        log.w("wireless: lost the head unit's Wi-Fi network")
        network = null
        onLost?.invoke()
    }

    private fun joined(lp: LinkProperties): JoinResult.Joined {
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.dhcpServerAddress else null
        return JoinResult.Joined(localAddress = localAddress(lp)?.hostAddress, gateway = gateway?.hostAddress)
    }

    private fun localAddress(lp: LinkProperties): java.net.InetAddress? {
        val addresses = lp.linkAddresses.map { it.address }
        return addresses.firstOrNull { it is Inet4Address } ?: if (requireIpv4) null else
            addresses.firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress }
    }

    /** No BSSID wanted matches anything; otherwise the phone must know the current one and it must be equal. */
    private fun bssidMatches(actual: String?, wanted: String?) =
        wanted == null || actual.equals(wanted, ignoreCase = true)

    private fun unquote(s: String?) = s?.removeSurrounding("\"")

    private companion object {
        const val EXISTING_LOOKUP_MS = 700L
        const val FAILURE_DETAIL_WAIT_MS = 750L
        val nextAttempt = AtomicLong()
    }
}
