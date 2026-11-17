package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
import aap_protobuf.shared.PhoneInfoOuterClass.PhoneInfo
import com.google.protobuf.ByteString

/** Phone presentation sent after AUTH; it carries no TLS credentials. Icons are PNG, in pixels. */
class PhoneDescription(
    val deviceName: String,
    val label: String,
    val smallIcon: ByteArray? = null,
    val mediumIcon: ByteArray? = null,
    val largeIcon: ByteArray? = null,
    /** Stable per-install id, so head units remember this phone across certificate renewals. */
    val instanceId: String? = null,
) {
    internal fun discoveryRequest(includePhoneInfo: Boolean): ServiceDiscoveryRequest =
        ServiceDiscoveryRequest.newBuilder().setDeviceName(deviceName).setLabelText(label).apply {
            this@PhoneDescription.smallIcon?.let { setSmallIcon(ByteString.copyFrom(it)) }
            this@PhoneDescription.mediumIcon?.let { setMediumIcon(ByteString.copyFrom(it)) }
            this@PhoneDescription.largeIcon?.let { setLargeIcon(ByteString.copyFrom(it)) }
            if (includePhoneInfo) instanceId?.let { setPhoneInfo(PhoneInfo.newBuilder().setInstanceId(it)) }
        }.build()
}
