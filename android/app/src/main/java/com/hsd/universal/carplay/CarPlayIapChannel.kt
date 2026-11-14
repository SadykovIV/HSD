package com.hsd.universal.carplay

import android.content.Context
import com.hsd.universal.ProjectionResources
import io.github.shaonialife.cardeck.airplay.ControlCipher
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.asU16List
import io.github.shaonialife.cardeck.transport.DuplexStream
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.concurrent.thread

internal fun accessoryVerifier(context: Context) =
    context.assets.open("carplay/apple-accessory-ca.pem").use {
        AccessoryAuthentication(
            CertificateFactory.getInstance("X.509").generateCertificates(it).map { c ->
                c as X509Certificate
            }
        )
    }

/** iAP2 events and DataStream transport carry the same link and CSM state machine. */
internal class CarPlayIapChannel(
    context: Context,
    life: ProjectionResources,
    device: CarPlayDeviceInformation,
    private val send: (ByteArray) -> Unit,
    private val handle: (Map<*, *>) -> Unit,
    private val failed: (Exception) -> Unit,
) {
    private val input = PipedInputStream(131072)
    private val feed = PipedOutputStream(input)
    private val auth = accessoryVerifier(context)
    private val channel =
        Iap2LinkChannel(
            object : DuplexStream {
                override fun read(buffer: ByteArray) = input.read(buffer)

                override fun write(bytes: ByteArray) = send(bytes)

                override fun close() {
                    feed.close()
                    input.close()
                }
            },
            "CarPlay IP iAP2",
        )

    init {
        life.own(channel::close)
        thread(name = "CarPlay IP iAP2 control", isDaemon = true) {
            try {
                check(channel.awaitReady(12_000)) { "Нет согласования iAP2 по сети" }
                fun tx(id: Int, body: ByteArray = byteArrayOf()) =
                    check(channel.sendControl(Iap2Csm.encode(id, body)))
                val parser = Iap2Csm.Parser()
                val exchange = AccessoryAuthExchange(auth, ::tx, context.getString(com.hsd.universal.R.string.carplay_invalid_accessory_signature))
                var receives = emptyList<Int>()
                val descriptors = mutableMapOf<String, ByteArray>()
                tx(0x1d00)
                while (channel.isAlive) {
                    val bytes = channel.recvControl(500) ?: continue
                    for (frame in parser.offer(bytes)) {
                        val fields = Iap2Csm.parseParams(frame.body)
                        fun param(id: Int) = fields.firstOrNull { it.id == id }?.value
                        when (frame.messageId) {
                            0x1d01 -> {
                                receives = fields.filter { it.id == 7 }.flatMap { it.asU16List() }
                                exchange.identified(fields.filter { it.id == 6 }.flatMap { it.asU16List() })
                            }
                            0xaa06, 0xaa01, 0xaa03 -> {
                                exchange.receive(frame.messageId, fields)
                                if (frame.messageId == 0xaa03) device.publish(receives, send = ::tx)
                            }
                            0x6800 ->
                                if (exchange.stage == AccessoryAuthExchange.Stage.VERIFIED) {
                                    val id = param(0)?.contentHashCode()?.toString() ?: "0"
                                    fields
                                        .firstOrNull { it.value.size > 8 }
                                        ?.let { descriptors[id] = it.value; logHidDescriptor(it.value) }
                                }
                            0x6802 ->
                                if (exchange.stage == AccessoryAuthExchange.Stage.VERIFIED) {
                                    val id = param(0)?.contentHashCode()?.toString() ?: "0"
                                    param(1)?.let { report ->
                                        handle(
                                            mapOf(
                                                "type" to "hidSendReport",
                                                "params" to
                                                    mapOf(
                                                        "uuid" to id,
                                                        "hidReport" to report,
                                                        "descriptor" to descriptors[id],
                                                    ),
                                            )
                                        )
                                    }
                                }
                        }
                    }
                }
            } catch (e: Exception) {
                if (channel.isAlive) failed(e)
            }
        }
    }

    fun accept(data: ByteArray) {
        if (channel.isAlive) {
            feed.write(data)
            feed.flush()
        }
    }
}

/**
 * Authenticated AirPlay DataStream records may split or coalesce a 32-byte APTransportPackage
 * header.
 */
internal class CarPlayDataStream(
    private val socket: Socket,
    readKey: ByteArray,
    writeKey: ByteArray,
) {
    private val cipher = ControlCipher(readKey, writeKey)

    @Synchronized
    fun send(bytes: ByteArray) {
        require(bytes.size <= 262144 - 32)
        val b =
            ByteBuffer.allocate(32 + bytes.size).apply {
                putInt(capacity())
                position(16)
                put("comm".toByteArray())
                position(32)
                put(bytes)
            }
        socket.getOutputStream().write(cipher.encrypt(b.array()))
    }

    fun read(receive: (ByteArray) -> Unit) {
        val raw = DataInputStream(socket.getInputStream())
        var pending = byteArrayOf()
        while (!socket.isClosed) {
            val a = raw.read()
            if (a < 0) {
                if (pending.isNotEmpty()) throw EOFException("Incomplete CarPlay DataStream packet")
                break
            }
            val b = raw.readUnsignedByte()
            val size = a or (b shl 8)
            require(size <= 16_384)
            val record = ByteArray(18 + size)
            record[0] = a.toByte()
            record[1] = b.toByte()
            raw.readFully(record, 2, record.size - 2)
            pending += cipher.decrypt(record).data
            while (pending.size >= 32) {
                val length = ByteBuffer.wrap(pending).int
                require(length in 32..262144)
                if (pending.size < length) break
                if (pending.copyOfRange(16, 20).decodeToString() == "comm")
                    receive(pending.copyOfRange(32, length))
                pending = pending.copyOfRange(length, pending.size)
            }
        }
    }
}
