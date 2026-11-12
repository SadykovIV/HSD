package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.airplay.ControlCipher
import java.io.*
import java.net.Socket

/**
 * Persistent RTSP framing. Decrypt a single complete record at a time to preserve surplus pipelined
 * bytes.
 */
internal class AirPlayWire(socket: Socket) {
    private val raw = BufferedInputStream(socket.getInputStream())
    private val out = socket.getOutputStream()
    var cipher: ControlCipher? = null
    private var record = ByteArrayInputStream(byteArrayOf())
    private val input =
        object : InputStream() {
            override fun read(): Int {
                val crypto = cipher ?: return raw.read()
                while (record.available() == 0) {
                    val a = raw.read()
                    if (a < 0) return -1
                    val b = raw.read()
                    if (b < 0) throw EOFException()
                    val len = a or (b shl 8)
                    require(len <= 16_384)
                    val bytes = ByteArray(len + 18)
                    bytes[0] = a.toByte()
                    bytes[1] = b.toByte()
                    DataInputStream(raw).readFully(bytes, 2, len + 16)
                    record = ByteArrayInputStream(crypto.decrypt(bytes).data)
                }
                return record.read()
            }

            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (cipher == null) return raw.read(buf, off, len)
                if (record.available() > 0) return record.read(buf, off, len)
                val first = read()
                if (first < 0) return -1
                buf[off] = first.toByte()
                val more = minOf(len - 1, record.available())
                if (more > 0) record.read(buf, off + 1, more)
                return 1 + more
            }
        }

    data class Message(val line: String, val headers: Map<String, String>, val data: ByteArray)

    private var sequence = 0
    private var sessionToken: String? = null

    @Synchronized
    fun request(
        method: String,
        path: String,
        body: ByteArray = byteArrayOf(),
        type: String? = if (body.isEmpty()) null else "application/x-apple-binary-plist",
    ): ByteArray {
        sequence++
        val contentType = type?.let { "Content-Type: $it\r\n" }.orEmpty()
        val session = sessionToken?.let { "Session: $it\r\n" }.orEmpty()
        val extra =
            if (path.startsWith("/pair-"))
                "X-Apple-HKP: 2\r\n" + (if (path == "/pair-verify") "X-Apple-PD: 1\r\n" else "")
            else ""
        write(
            "$method $path RTSP/1.0\r\nCSeq: $sequence\r\nUser-Agent: AirPlay/566.25.21\r\n${extra}${session}${contentType}Content-Length: ${body.size}\r\n\r\n",
            body,
        )
        val response = read()
        check((response.headers["cseq"]?.toIntOrNull() ?: sequence) == sequence) { "CarPlay: неожиданный CSeq" }
        val status = response.line.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Некорректный ответ RTSP")
        if (status !in 200..299) throw RtspError(status, method, path)
        response.headers["session"]?.substringBefore(';')?.let { sessionToken = it }
        return response.data
    }

    fun read(): Message {
        val (line, headers) = head(input) ?: throw EOFException("Магнитола закрыла RTSP")
        val len = headers["content-length"]?.toInt() ?: 0
        require(len in 0..2097152)
        return Message(line, headers, ByteArray(len).also { DataInputStream(input).readFully(it) })
    }

    fun respond(message: Message) {
        val number = message.headers["cseq"]?.toIntOrNull() ?: 0
        write("RTSP/1.0 200 OK\r\nCSeq: $number\r\nContent-Length: 0\r\n\r\n", byteArrayOf())
    }

    @Synchronized
    fun command(body: ByteArray) {
        sequence++
        write(
            "POST /command RTSP/1.0\r\nCSeq: $sequence\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.size}\r\n\r\n",
            body,
        )
    }

    @Synchronized
    private fun write(head: String, body: ByteArray) {
        val data = head.toByteArray(Charsets.US_ASCII) + body
        out.write(cipher?.encrypt(data) ?: data)
        out.flush()
    }

    companion object {
        fun head(input: InputStream): Pair<String, Map<String, String>>? {
            val bytes = ByteArrayOutputStream()
            var end = 0
            while (bytes.size() < 16_384) {
                val b = input.read()
                if (b < 0) {
                    if (bytes.size() == 0) return null
                    throw EOFException()
                }
                bytes.write(b)
                end = (end shl 8) or b
                if (end == 0x0d0a0d0a) {
                    val lines = bytes.toString("ISO-8859-1").split("\r\n")
                    return lines.first() to
                        lines
                            .drop(1)
                            .filter { ':' in it }
                            .associate {
                                it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                            }
                }
            }
            throw IOException("Слишком большой заголовок CarPlay")
        }
    }
}

internal class RtspError(val status: Int, method: String, path: String) : java.io.IOException("CarPlay $method $path · RTSP $status")
