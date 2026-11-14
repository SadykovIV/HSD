package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm

/** Both iAP2 transports verify a fresh challenge, including when the accessory offers a serial first. */
internal class AccessoryAuthExchange(
    private val verifier: AccessoryAuthentication,
    private val send: (Int, ByteArray) -> Unit,
    private val invalidSignature: String,
) {
    enum class Stage { IDENTIFICATION, SERIAL_OR_CERTIFICATE, CERTIFICATE, SIGNATURE, VERIFIED }
    var stage = Stage.IDENTIFICATION
        private set
    private var serialReceived = false

    fun identified(accessorySends: List<Int>) {
        check(stage == Stage.IDENTIFICATION)
        send(0x1d02, byteArrayOf())
        val serial = 0xaa06 in accessorySends
        stage = if (serial) Stage.SERIAL_OR_CERTIFICATE else Stage.CERTIFICATE
        send(0xaa00, if (serial) Iap2Csm.param(0, byteArrayOf()) else byteArrayOf())
    }

    fun receive(id: Int, fields: List<Iap2Csm.Param>) {
        when (id) {
            0xaa06 -> {
                if (serialReceived || (stage != Stage.SERIAL_OR_CERTIFICATE && stage != Stage.CERTIFICATE)) return
                serialReceived = true
                stage = Stage.CERTIFICATE
                // A serial only tells us to request the full certificate. Never use it as proof of identity.
                send(0xaa00, byteArrayOf())
            }
            0xaa01 -> {
                check(stage == Stage.SERIAL_OR_CERTIFICATE || stage == Stage.CERTIFICATE)
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
                stage = Stage.VERIFIED
            }
        }
    }
}
