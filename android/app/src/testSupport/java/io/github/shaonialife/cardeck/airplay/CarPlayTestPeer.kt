package io.github.shaonialife.cardeck.airplay

import java.math.BigInteger
import java.security.SecureRandom

internal class CarPlayTestPeer {
    val staticKey = AirPlayCrypto.ed25519Generate()
    val pairingId = "3e6f5739-b2bf-4ebf-bb87-5b6b57cc42e5"
    val pairings = HashMap<String, ByteArray>()
    var lastReadKey: ByteArray? = null
        private set
    var lastWriteKey: ByteArray? = null
        private set

    private val random = SecureRandom()

    // SRP 服务端状态
    private var salt = ByteArray(0)
    private var publicKeyB = BigInteger.ZERO
    private var verifier = BigInteger.ZERO
    private var privateB = BigInteger.ZERO
    private var sessionKey = ByteArray(0)

    // pair-verify 状态
    private var pvEphemeralPublic = ByteArray(0)
    var pvShared = ByteArray(0)
    private var pvEncryptionKey = ByteArray(0)

    fun handle(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val tlv = Tlv8.decode(body)
        val state = tlv[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        return when (path) {
            "/pair-setup" ->
                when (state) {
                    1 -> setupM2()
                    3 -> setupM4(tlv)
                    5 -> setupM6(tlv)
                    else -> error(state)
                }

            "/pair-verify" ->
                when (state) {
                    1 -> verifyM2(tlv)
                    3 -> verifyM4(tlv)
                    else -> error(state)
                }

            else -> error(state)
        }
    }

    private fun setupM2(): ByteArray {
        salt = ByteArray(16).also(random::nextBytes)
        val identifier = Srp6aClient.USERNAME.toByteArray()
        val x =
            Srp6aClient.toBigInt(
                AirPlayCrypto.sha512(
                    salt,
                    AirPlayCrypto.sha512(identifier, byteArrayOf(':'.code.toByte()), Srp6aClient.SETUP_CODE.toByteArray()),
                ),
            )
        verifier = Srp6aClient.generator().modPow(x, Srp6aClient.modulus())
        privateB = Srp6aClient.toBigInt(ByteArray(32).also(random::nextBytes))
        publicKeyB =
            (
                Srp6aClient.multiplier() * verifier +
                    Srp6aClient.generator().modPow(
                        privateB,
                        Srp6aClient.modulus(),
                    )
            ).mod(Srp6aClient.modulus())
        return Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(2)),
                Tlv8.Item(Tlv8.TYPE_SALT, salt),
                Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, Srp6aClient.pad(publicKeyB)),
            ),
        )
    }

    private fun setupM4(tlv: Map<Int, ByteArray>): ByteArray {
        val publicKeyA = tlv[Tlv8.TYPE_PUBLIC_KEY] ?: return error(4)
        val clientProof = tlv[Tlv8.TYPE_PROOF] ?: return error(4)
        val a = Srp6aClient.toBigInt(publicKeyA)
        val u = Srp6aClient.toBigInt(AirPlayCrypto.sha512(Srp6aClient.pad(a), Srp6aClient.pad(publicKeyB)))
        val s = (a * verifier.modPow(u, Srp6aClient.modulus())).mod(Srp6aClient.modulus()).modPow(privateB, Srp6aClient.modulus())
        sessionKey = AirPlayCrypto.sha512(Srp6aClient.toBytes(s))

        val hashN = AirPlayCrypto.sha512(Srp6aClient.toBytes(Srp6aClient.modulus()))
        val hashG = AirPlayCrypto.sha512(Srp6aClient.toBytes(Srp6aClient.generator()))
        val hashXor = ByteArray(hashN.size)
        for (index in hashN.indices) {
            hashXor[index] = (hashN[index].toInt() xor hashG[index].toInt()).toByte()
        }
        val identifier = Srp6aClient.USERNAME.toByteArray()
        val expected =
            AirPlayCrypto.sha512(
                hashXor,
                AirPlayCrypto.sha512(identifier),
                salt,
                Srp6aClient.pad(a),
                Srp6aClient.pad(publicKeyB),
                sessionKey,
            )
        if (!expected.contentEquals(clientProof)) return error(4)
        val serverProof = AirPlayCrypto.sha512(Srp6aClient.pad(a), clientProof, sessionKey)
        return Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(4)),
                Tlv8.Item(Tlv8.TYPE_PROOF, serverProof),
            ),
        )
    }

    private fun setupM6(tlv: Map<Int, ByteArray>): ByteArray {
        val encrypted = tlv[Tlv8.TYPE_ENCRYPTED_DATA] ?: return error(6)
        val encryptionKey = hkdf(sessionKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val sub = Tlv8.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg05"), encrypted))
        val controllerId = sub[Tlv8.TYPE_IDENTIFIER] ?: return error(6)
        val controllerLtpk = sub[Tlv8.TYPE_PUBLIC_KEY] ?: return error(6)
        val controllerSignature = sub[Tlv8.TYPE_SIGNATURE] ?: return error(6)

        val controllerSignKey = hkdf(sessionKey, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        if (!AirPlayCrypto.ed25519Verify(controllerLtpk, controllerSignKey + controllerId + controllerLtpk, controllerSignature)) {
            return error(6)
        }
        pairings[controllerId.toString(Charsets.UTF_8)] = controllerLtpk

        val accessoryId = pairingId.toByteArray(Charsets.UTF_8)
        val accessorySignKey = hkdf(sessionKey, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        val signature = AirPlayCrypto.ed25519Sign(staticKey.privateKey, accessorySignKey + accessoryId + staticKey.publicKey)
        val subResponse =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_IDENTIFIER, accessoryId),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, staticKey.publicKey),
                    Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                ),
            )
        return Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(6)),
                Tlv8.Item(
                    Tlv8.TYPE_ENCRYPTED_DATA,
                    AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg06"), subResponse),
                ),
            ),
        )
    }

    private fun verifyM2(tlv: Map<Int, ByteArray>): ByteArray {
        val clientEphemeral = tlv[Tlv8.TYPE_PUBLIC_KEY] ?: return error(2)
        pvClientEphemeral = clientEphemeral
        val pair = AirPlayCrypto.x25519Generate()
        pvEphemeralPublic = pair.publicKey
        pvShared = AirPlayCrypto.x25519Shared(pair.privateKey, clientEphemeral)
        pvEncryptionKey = hkdf(pvShared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")

        val accessoryId = pairingId.toByteArray(Charsets.UTF_8)
        val signature =
            AirPlayCrypto.ed25519Sign(
                staticKey.privateKey,
                pair.publicKey + accessoryId + clientEphemeral,
            )
        val sub =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_IDENTIFIER, accessoryId),
                    Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                ),
            )
        return Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(2)),
                Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, pair.publicKey),
                Tlv8.Item(
                    Tlv8.TYPE_ENCRYPTED_DATA,
                    AirPlayCrypto.chachaSeal(pvEncryptionKey, AirPlayCrypto.nonceLabel("PV-Msg02"), sub),
                ),
            ),
        )
    }

    private fun verifyM4(tlv: Map<Int, ByteArray>): ByteArray {
        val encrypted = tlv[Tlv8.TYPE_ENCRYPTED_DATA] ?: return error(4)
        val sub = Tlv8.decode(AirPlayCrypto.chachaOpen(pvEncryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), encrypted))
        val controllerId = sub[Tlv8.TYPE_IDENTIFIER] ?: return error(4)
        val controllerSignature = sub[Tlv8.TYPE_SIGNATURE] ?: return error(4)
        val controllerLtpk = pairings[controllerId.toString(Charsets.UTF_8)] ?: return error(4)

        // 客户端临时公钥需从 M1 找回：简化测试路径里客户端把它放在 identifier 前——
        // 实际按参考算法应为 clientEphemeral || controllerId || serverEphemeral。
        // 这里从 pair-verify 会话中重建（测试中我们保存了它）。
        val clientEphemeral = pvClientEphemeral ?: return error(4)
        if (!AirPlayCrypto.ed25519Verify(controllerLtpk, clientEphemeral + controllerId + pvEphemeralPublic, controllerSignature)) {
            return error(4)
        }
        lastReadKey = hkdf(pvShared, "Control-Salt", "Control-Write-Encryption-Key")
        lastWriteKey = hkdf(pvShared, "Control-Salt", "Control-Read-Encryption-Key")
        return Tlv8.encode(listOf(Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(4))))
    }

    private var pvClientEphemeral: ByteArray? = null

    private fun hkdf(
        material: ByteArray,
        salt: String,
        info: String,
    ): ByteArray {
        return AirPlayCrypto.hkdfSha512(material, salt.toByteArray(), info.toByteArray())
    }

    private fun error(state: Int): ByteArray =
        Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(state.toByte())),
                Tlv8.Item(Tlv8.TYPE_ERROR, byteArrayOf(2)),
            ),
        )
}
