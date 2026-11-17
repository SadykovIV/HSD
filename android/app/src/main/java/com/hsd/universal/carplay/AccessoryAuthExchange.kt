package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm

/** Both iAP2 transports require a full certificate and a fresh challenge on each connection. */
internal class AccessoryAuthExchange(
    private val verifier: AccessoryAuthentication,
    private val send: (Int, ByteArray) -> Unit,
    private val invalidSignature: String,
) {
    enum class Stage { IDENTIFICATION, CERTIFICATE, SIGNATURE, VERIFIED }
    var stage = Stage.IDENTIFICATION
        private set
    private var serialReceived = false

    fun identified() {
        check(stage == Stage.IDENTIFICATION)
        send(0x1d02, byteArrayOf())
        // Parameter 0 asks for the serial-only cache optimization. HSD has no accessory
        // certificate cache, so it always needs the complete certificate (an empty body).
        // Advertising 0xaa06 is a capability, not a requirement to use that optimization.
        stage = Stage.CERTIFICATE
        send(0xaa00, byteArrayOf())
    }

    fun receive(id: Int, fields: List<Iap2Csm.Param>) {
        when (id) {
            0xaa06 -> {
                if (serialReceived || stage != Stage.CERTIFICATE) return
                serialReceived = true
                stage = Stage.CERTIFICATE
                // A serial only tells us to request the full certificate. Never use it as proof of identity.
                send(0xaa00, byteArrayOf())
            }
            0xaa01 -> {
                check(stage == Stage.CERTIFICATE)
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
