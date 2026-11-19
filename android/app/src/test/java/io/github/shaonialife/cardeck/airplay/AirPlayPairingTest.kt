package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * 配对栈端到端回环：MockReceiver 严格按参考接收端（xcertplay PairSetup/PairVerify/Srp6a）
 * 的算法实现服务端，验证我们的客户端能完整走通 pair-setup + pair-verify，
 * 且双方派生的控制密钥方向一致。
 */
class AirPlayPairingTest {
    private val random = SecureRandom()

    @Test fun savedKeyVerifiesTwoIndependentSessionsWithoutRepeatingSetup() {
        val receiver = CarPlayTestPeer()
        val io = TlvTransport(receiver::handle)
        val keys = AirPlayCrypto.ed25519Generate()
        val id = ControllerIdentity("controller", keys.privateKey, keys.publicKey)
        val pinned = PairSetupClient(id, io) {}.run()!!.publicKey
        repeat(2) {
            val session = PairVerifyClient(id, io) {}.run(pinned)
            assertArrayEquals(receiver.lastReadKey, session.phoneWriteKey)
        }
    }

    @Test fun correctFinalStateWithAnErrorCannotAuthorizeAControlSession() {
        val receiver = CarPlayTestPeer()
        val keys = AirPlayCrypto.ed25519Generate()
        val id = ControllerIdentity("controller", keys.privateKey, keys.publicKey)
        val pinned = PairSetupClient(id, TlvTransport(receiver::handle)) {}.run()!!.publicKey
        val io = TlvTransport { path, request ->
            val result = receiver.handle(path, request)
            if (Tlv8.decode(result)[Tlv8.TYPE_STATE]!!.single().toInt() == 4)
                result + Tlv8.encode(listOf(Tlv8.Item(Tlv8.TYPE_ERROR, byteArrayOf(2))))
            else result
        }
        org.junit.Assert.assertThrows(java.net.ProtocolException::class.java) { PairVerifyClient(id, io) {}.run(pinned) }
    }

    @Test
    fun pairSetupThenPairVerifyAgainstCarPlayTestPeer() {
        val receiver = CarPlayTestPeer()
        val transport = TlvTransport { path, body -> receiver.handle(path, body) }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity("test-controller-id", keyPair.privateKey, keyPair.publicKey)

        val receiverIdentity = PairSetupClient(identity, transport) { }.run()
        assertNotNull(receiverIdentity)
        assertEquals(receiver.pairingId, receiverIdentity!!.pairingId)
        assertArrayEquals(receiver.staticKey.publicKey, receiverIdentity.publicKey)
        assertTrue("接收端应保存控制器公钥", receiver.pairings.containsKey(identity.pairingId))

        val keys = PairVerifyClient(identity, transport) { }.run(receiver.staticKey.publicKey)
        assertEquals(32, keys.phoneWriteKey.size)
        assertEquals(32, keys.phoneReadKey.size)

        // 方向交叉验证：接收端 readKey 解我们发出的方向，writeKey 是它发给我们的方向
        assertNotNull("接收端应完成 pair-verify", receiver.lastReadKey)
        assertArrayEquals(receiver.lastReadKey, keys.phoneWriteKey)
        assertArrayEquals(receiver.lastWriteKey, keys.phoneReadKey)
    }

    @Test
    fun tlv8FragmentationRoundTrip() {
        val big = ByteArray(600) { (it % 251).toByte() }
        val encoded =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, big),
                ),
            )
        val decoded = Tlv8.decode(encoded)
        assertEquals(1, decoded[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt())
        assertArrayEquals(big, decoded[Tlv8.TYPE_PUBLIC_KEY])
    }

    /** 按参考接收端算法实现的服务端。 */
    @Test fun mismatchedReceiverSignatureIsRejected() {
        val receiver = CarPlayTestPeer()
        val io = TlvTransport(receiver::handle)
        val keys = AirPlayCrypto.ed25519Generate()
        val id = ControllerIdentity("test-controller",keys.privateKey,keys.publicKey)
        PairSetupClient(id,io) {}.run()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            PairVerifyClient(id,io) {}.run(AirPlayCrypto.ed25519Generate().publicKey)
        }
    }
}
