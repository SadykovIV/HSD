package com.hsd.universal.dex

/** A saved port may still listen on loopback after Wi-Fi changes; stale remote IPs are never dialled. */
internal object VdmRecoveryEndpoints {
    /** Only services still advertised on an address currently assigned to the phone. */
    fun advertised(endpoint: DexEndpoint?, wifiEnabled: Boolean?, isOwnHost: (String) -> Boolean): List<DexEndpoint> =
        if (wifiEnabled == false || endpoint == null) emptyList()
        else candidates(endpoint, savedPairedPort = false, isOwnHost)

    /** Read-only system property; it names a TLS listener, never starts or reconfigures adbd. */
    fun tlsLoopback(property: String?): DexEndpoint? {
        val value = property?.trim()?.takeIf { it.length in 1..5 && it.all(Char::isDigit) } ?: return null
        val port = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return DexEndpoint("127.0.0.1", port)
    }

    fun candidates(endpoint: DexEndpoint, savedPairedPort: Boolean, isOwnHost: (String) -> Boolean): List<DexEndpoint> {
        if (endpoint.port !in 1..65535) return emptyList()
        val own = isOwnHost(endpoint.host)
        if (!own && !savedPairedPort) return emptyList()
        return buildList {
            add(DexEndpoint("127.0.0.1", endpoint.port))
            if (own && endpoint.host != "127.0.0.1") add(endpoint)
        }
    }
}
