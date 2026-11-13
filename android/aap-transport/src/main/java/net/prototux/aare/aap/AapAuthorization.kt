package net.prototux.aare.aap

import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus

class AapAuthorization private constructor(val code: Int) {
    val status: MessageStatus? get() = MessageStatus.forNumber(code)
    val description: String get() = "$code (${status?.name ?: "UNKNOWN"})"
    val certificateError: Boolean get() = when (status) {
        MessageStatus.STATUS_CERTIFICATE_ERROR, MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID,
        MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED -> true
        else -> false
    }

    companion object {
        // Proto2 permits an absent status and supplies the schema default of zero.
        fun parse(body: ByteArray) = AapAuthorization(AuthResponse.parseFrom(body).status)
    }
}
