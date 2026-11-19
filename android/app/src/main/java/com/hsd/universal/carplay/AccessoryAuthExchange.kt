package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm

/** Both iAP2 transports require a full certificate and a fresh challenge on each connection. */
internal class AccessoryAuthExchange(
    private val verifier: AccessoryAuthentication,
    private val send: (Int, ByteArray) -> Unit,
    private val invalidSignature: String,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Stage { IDLE, IDENTIFICATION, SERIAL, CERTIFICATE, SIGNATURE, VERIFIED }
    private var enteredAt = 0L
    var stage = Stage.IDLE
        private set(value) {
            field = value
            enteredAt = clockMs()
        }
    private var serialReceived = false
    private var authenticated = false
    private var identificationAccepted = false

    /** The serial is an optional cache hint. A full certificate and a signed challenge are mandatory. */
    fun responseTimedOut(): Boolean {
        if (stage == Stage.IDLE || stage == Stage.VERIFIED) return false
        val waited = clockMs() - enteredAt
        if (stage == Stage.SERIAL && waited >= 5_000) {
            serialReceived = true // Ignore a late serial after the full request has been sent.
            requestCertificate(false)
            return false
        }
        return waited >= 30_000
    }

    fun start(controlVersion: Int) {
        check(stage == Stage.IDLE)
        require(controlVersion in 1..2) { "Unsupported iAP2 control session version $controlVersion" }
        // Control v1 requires authentication before all other control messages. Control v2
        // permits identification first, including negotiation of the certificate serial exchange.
        if (controlVersion == 1) requestCertificate(false) else requestIdentification()
    }

    private fun requestIdentification() {
        stage = Stage.IDENTIFICATION
        send(0x1d00, byteArrayOf())
    }

    fun identified(supportsCertificateSerial: Boolean = false) {
        check(stage == Stage.IDENTIFICATION)
        send(0x1d02, byteArrayOf())
        identificationAccepted = true
        if (authenticated) stage = Stage.VERIFIED else requestCertificate(supportsCertificateSerial)
    }

    private fun requestCertificate(supportsCertificateSerial: Boolean) {
        // Negotiate the advertised serial exchange, as well as the original full-certificate
        // path. A serial is only a cache lookup hint; with no cached chain we then request
        // the full certificate. Neither path can skip the fresh signature challenge.
        stage = if (supportsCertificateSerial) Stage.SERIAL else Stage.CERTIFICATE
        send(0xaa00, if (supportsCertificateSerial) Iap2Csm.param(0, byteArrayOf()) else byteArrayOf())
    }

    fun receive(id: Int, fields: List<Iap2Csm.Param>) {
        when (id) {
            0xaa06 -> {
                if (serialReceived || stage !in listOf(Stage.SERIAL, Stage.CERTIFICATE)) return
                require(fields.single { it.id == 0 }.value.isNotEmpty()) { "Empty accessory certificate serial" }
                serialReceived = true
                stage = Stage.CERTIFICATE
                // A serial only tells us to request the full certificate. Never use it as proof of identity.
                send(0xaa00, byteArrayOf())
            }
            0xaa01 -> {
                check(stage == Stage.CERTIFICATE || stage == Stage.SERIAL)
                val challenge = verifier.challenge(fields.single { it.id == 0 }.value)
                stage = Stage.SIGNATURE
                send(0xaa02, Iap2Csm.param(0, challenge))
            }
            0xaa03 -> {
                check(stage == Stage.SIGNATURE)
                if (!verifier.verify(fields.single { it.id == 0 }.value)) {
                    send(0xaa04, byteArrayOf())
                    throw java.security.SignatureException(invalidSignature)
                }
                send(0xaa05, byteArrayOf())
                authenticated = true
                if (identificationAccepted) stage = Stage.VERIFIED else requestIdentification()
            }
        }
    }
}
