package com.hsd.universal

import android.content.Context
import android.os.Bundle
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import net.prototux.aare.aap.*
import java.net.Socket
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android TLS and production DISCOVERY against an independent Python/OpenSSL wire implementation. */
internal fun checkAapDiscoveryInterop(context: Context, testContext: Context, port: Int): Bundle {
    val certificate = testContext.assets.open("aap-interop/receiver.pem").use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    val identity = ProjectionIdentity(context).phone("qa-openssl")
    val result = Bundle()
    for ((index, name) in listOf("GCM/plain-auth", "GCM/encrypted-auth", "CBC/fragmented-response").withIndex()) {
        val closed = CountDownLatch(1)
        var discovered = false
        var running = false
        var failure: Throwable? = null
        val pins = MemoryPinStore().apply { add("openssl", fingerprint(certificate)) }
        val socket = Socket("10.0.2.2", port).apply { soTimeout = 15_000 }
        // Fixture setup, before the first AAP byte. The production session never sees this selector.
        socket.getOutputStream().write(index)
        val session = AapSession(SocketTransport(socket, "independent OpenSSL receiver"), identity,
            ProjectionDescription.create(context), object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> {
                    check(response.displayName == "OpenSSL receiver")
                    discovered = true
                    return emptyList()
                }
                override fun onRunning(session: AapSession) { running = true }
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
                    failure = error
                    closed.countDown()
                }
            }, options = SessionOptions(sendPings = false, pins = pins, peerKey = "openssl"))
        try {
            session.start()
            check(closed.await(20, TimeUnit.SECONDS)) { "$name did not complete" }
            check(failure == null && discovered && running && session.discoveryRequestWritten) {
                "$name: $failure ${session.closedDuring} ${session.discoveryReadProgress}"
            }
            result.putString(name, "PASS ${session.negotiatedTls} ${session.discoveryRequestSummary}")
        } finally { session.stop(); socket.close() }
    }
    return result
}
