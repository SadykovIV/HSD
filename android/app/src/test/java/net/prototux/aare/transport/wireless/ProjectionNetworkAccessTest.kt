package net.prototux.aare.transport.wireless

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class ProjectionNetworkAccessTest {
    @Test fun distinguishesSocketBindingPolicyFromLostNetworksAndTimeouts() {
        assertTrue(ProjectionNetworkAccess.policyDenial(SocketException("Binding socket to network 1618 failed: EPERM (Operation not permitted)")))
        assertTrue(ProjectionNetworkAccess.policyDenial(IOException("connect", SocketException("EACCES (Permission denied)"))))
        assertTrue(ProjectionNetworkAccess.policyDenial(SecurityException("network permission required")))
        assertFalse(ProjectionNetworkAccess.policyDenial(SocketException("Binding socket to network 1618 failed: ENONET")))
        assertFalse(ProjectionNetworkAccess.policyDenial(SocketTimeoutException("connection timed out")))
    }
}
