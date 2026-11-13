package net.prototux.aare.aap

import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import com.google.protobuf.InvalidProtocolBufferException
import org.junit.Assert.*
import org.junit.Test

class AapAuthorizationTest {
    @Test fun preservesStatusAndDistinguishesCertificateErrors() {
        for (code in listOf(0, -2, -3, -23, -24, -254, -999)) {
            val result = AapAuthorization.parse(AuthResponse.newBuilder().setStatus(code).build().toByteArray())
            assertEquals(code, result.code)
            assertEquals(code in setOf(-2, -23, -24), result.certificateError)
            assertTrue(result.description.contains(if (code == -999) "UNKNOWN" else "STATUS_"))
        }
        assertEquals(0, AapAuthorization.parse(byteArrayOf()).code)
    }

    @Test(expected = InvalidProtocolBufferException::class)
    fun truncatedStatusCannotAuthorize() {
        AapAuthorization.parse(byteArrayOf(8, 0x80.toByte()))
    }
}
