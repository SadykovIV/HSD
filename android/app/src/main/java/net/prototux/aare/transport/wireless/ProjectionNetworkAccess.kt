package net.prototux.aare.transport.wireless

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import com.hsd.universal.R
import java.io.IOException
import java.net.Socket

/** Keep projection on the head unit network and report the policy of the calling UID. */
internal class ProjectionNetworkAccess(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun socket(network: Network): Socket = checked { network.socketFactory.createSocket() }

    fun <T> checked(operation: () -> T): T = try {
        operation()
    } catch (error: Exception) {
        if (!policyDenial(error)) throw error
        val resource = when {
            Build.VERSION.SDK_INT >= 37 &&
                context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED ->
                R.string.projection_network_permission
            vpnActive() -> R.string.projection_network_vpn
            else -> R.string.projection_network_restricted
        }
        throw ProjectionNetworkDenied(context.getString(resource), error)
    }

    fun vpnActive(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    companion object {
        private val errnoName = Regex("""\b(?:EPERM|EACCES)\b""")
        fun policyDenial(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
            when (it) {
                is SecurityException -> true
                is ErrnoException -> it.errno == OsConstants.EPERM || it.errno == OsConstants.EACCES
                // Network.bindSocket() can return a SocketException without an ErrnoException cause.
                is IOException -> errnoName.containsMatchIn(it.message.orEmpty())
                else -> false
            }
        }
    }
}

internal class ProjectionNetworkDenied(message: String, cause: Throwable) : IOException(message, cause)
