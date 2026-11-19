package com.hsd.universal.hicar

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Locale

/** Bounded DMSDP/HiSight records. Control and media have separate sockets and locks. */
internal class HiCarControl(socket: Socket, key: ByteArray, private val rtsp: Boolean) : Closeable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val cipher = SessionCipher(key, if (rtsp) 65001 else 1498)
    private var buffered = ByteArray(0)
    @Volatile var encrypted = false
    private var sequence = 0
    val protocol = if (rtsp) "RTSP/1.0" else "DMSDP/1.0"

    data class Message(val start: String, val headers: Map<String, String>, val body: ByteArray,
                       val module: Int? = null, val type: Int = -1) {
        val method get() = start.substringBefore(' ')
        val cseq get() = headers["cseq"] ?: throw IOException("HiCar: missing CSeq")
        val success get() = start.substringAfter(' ').substringBefore(' ') == "200"
        fun parameters(): Map<String, String> = body.toString(Charsets.US_ASCII).split("\r\n")
            .filter { it.isNotBlank() }.associate { row ->
                require(':' in row) { "HiCar: invalid parameter" }
                row.substringBefore(':').trim() to row.substringAfter(':').trim()
            }
    }

    @Synchronized fun request(method: String, headers: Map<String, String> = emptyMap(), body: String = "", target: String = "*"): String {
        val seq = (++sequence).toString()
        send(encode("$method $target $protocol", headers + ("CSeq" to seq), body.toByteArray(Charsets.UTF_8)))
        return seq
    }

    fun reply(message: Message, headers: Map<String, String> = emptyMap(), body: String = "", code: Int = 200) {
        send(encode("$protocol $code ${if (code == 200) "OK" else "Not Supported"}",
            headers + ("CSeq" to message.cseq), body.toByteArray(Charsets.UTF_8)))
    }

    @Synchronized fun send(plain: ByteArray) {
        require(plain.isNotEmpty() && plain.size <= 65536)
        val maximum = if (rtsp) 64000 else 1400
        require(!rtsp || plain.size <= maximum)
        var at = 0
        while (at < plain.size) {
            val piece = plain.copyOfRange(at, minOf(at + maximum, plain.size))
            val bytes = if (encrypted) cipher.encrypt(piece) else piece
            if (rtsp) output.writeInt(bytes.size)
            else { output.writeByte(if (encrypted) 171 else 0); output.writeShort(bytes.size) }
            output.write(bytes)
            at += piece.size
        }
        output.flush()
    }

    fun read(): Message {
        while (true) {
            parse(buffered, protocol)?.let { (message, consumed) ->
                buffered = buffered.copyOfRange(consumed, buffered.size)
                if (message.module != null) check(encrypted) { "HiCar: business before authentication" }
                return message
            }
            val marker = if (rtsp) if (encrypted) 171 else 0 else input.readUnsignedByte()
            val length = if (rtsp) input.readInt() else input.readUnsignedShort()
            check(length in 1..(if (rtsp) 65000 else 1497)) { "HiCar: invalid control record size" }
            check(marker == if (encrypted) 171 else 0) { "HiCar: unexpected encryption state" }
            val packet = ByteArray(length).also(input::readFully)
            val plain = if (encrypted) cipher.decrypt(packet) else packet
            check(buffered.size + plain.size <= 73732) { "HiCar: control message limit" }
            buffered += plain
            plain.fill(0)
        }
    }

    override fun close() { cipher.close(); buffered.fill(0) }

    companion object {
        fun business(module: Int, command: Int, data: ByteArray) : ByteArray {
            val body = NearbyWire.uint(command) + data
            require(body.size <= 1400)
            return ByteBuffer.allocate(11 + body.size).put(36).putInt(module).putInt(-1).putShort(body.size.toShort()).put(body).array()
        }

        fun encode(start: String, headers: Map<String, String>, body: ByteArray = byteArrayOf()): ByteArray {
            require(start.matches(Regex("[A-Z_]+ \\S+ (RTSP|DMSDP)/1\\.0|(RTSP|DMSDP)/1\\.0 [1-5][0-9]{2} [ -~]+")))
            val head = buildString {
                append(start).append("\r\n")
                headers.forEach { (key, value) ->
                    require(key.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,63}")) && value.all { it.code in 32..126 })
                    append(key).append(": ").append(value).append("\r\n")
                }
                if (body.isNotEmpty()) append("Content-Type: text/parameters\r\nContent-Length: ${body.size}\r\n")
                append("\r\n")
            }
            return head.toByteArray(Charsets.US_ASCII) + body
        }

        fun parse(bytes: ByteArray, protocol: String): Pair<Message, Int>? {
            if (bytes.isEmpty()) return null
            if (bytes[0] == 36.toByte()) {
                if (bytes.size < 11) return null
                val b = ByteBuffer.wrap(bytes).apply { get() }
                val module = b.int; val type = b.int; val length = b.short.toInt() and 65535
                check(length <= 1400) { "HiCar: business limit" }
                if (bytes.size < length + 11) return null
                return Message("", emptyMap(), bytes.copyOfRange(11, 11 + length), module, type) to (11 + length)
            }
            val end = (0 until (bytes.size - 3).coerceAtLeast(0)).firstOrNull {
                bytes[it] == 13.toByte() && bytes[it+1] == 10.toByte() && bytes[it+2] == 13.toByte() && bytes[it+3] == 10.toByte()
            }
            if (end == null) { check(bytes.size <= 8192) { "HiCar: header limit" }; return null }
            check(end <= 8192 && bytes.take(end).all { it.toInt() in 1..127 }) { "HiCar: invalid header" }
            val rows = bytes.copyOfRange(0, end).toString(Charsets.US_ASCII).split("\r\n")
            check(rows[0].matches(Regex("[A-Z_]+ \\S+ ${Regex.escape(protocol)}|${Regex.escape(protocol)} [1-5][0-9]{2}( [ -~]+)?")))
            val headers = linkedMapOf<String, String>()
            rows.drop(1).forEach { row ->
                val name = row.substringBefore(':').lowercase(Locale.ROOT)
                check(':' in row && name.matches(Regex("[a-z][a-z0-9-]{0,63}")) &&
                    headers.put(name, row.substringAfter(':').trim()) == null && headers.size <= 64) { "HiCar: duplicate or invalid header" }
            }
            check(headers["cseq"]?.matches(Regex("[0-9]{1,9}")) == true) { "HiCar: invalid CSeq" }
            val size = headers["content-length"]?.let {
                check(it.matches(Regex("[0-9]{1,5}"))); it.toInt().also { n -> check(n <= 65536) }
            } ?: 0
            if (bytes.size < end + 4 + size) return null
            return Message(rows[0], headers, bytes.copyOfRange(end+4, end+4+size)) to (end+4+size)
        }
    }
}
