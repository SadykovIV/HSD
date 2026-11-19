package net.prototux.aare.aap

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads frames and reassembles them into [AapMessage]s.
 *
 * Fragments of different channels may interleave on the wire, so partial messages are kept per
 * channel. Encrypted payloads are decrypted frame by frame, in arrival order, as TLS requires.
 */
class FrameReader(input: InputStream, private val cryptor: () -> Cryptor?) {
    private val input = input.buffered(64 * 1024)
    private val header = ByteArray(4)
    private class Partial(val declaredTotal: Int, val buf: ByteArrayOutputStream, val encrypted: Boolean)

    private val partial = HashMap<Int, Partial>()

    /** Bytes received so far across all open partials; bounds memory across channels. */
    private var buffered = 0L
    private var payload = ByteArray(Frame.MAX_PAYLOAD + 1024)

    /** Only enabled for negotiation: normal media traffic creates no progress snapshots. */
    var trackProgress = false
        set(value) {
            if (value && !field) progress = ReadProgress()
            field = value
        }
    @Volatile var progress = ReadProgress()
        private set
    var onFirstRead: (() -> Unit)? = null

    data class ReadProgress(
        val bytes: Long = 0, val frames: Int = 0, val stage: String = "idle",
        val received: Int = 0, val expected: Int = 0,
        val channel: Int? = null, val flags: Int? = null, val payloadSize: Int? = null,
        val tlsType: Int? = null,
    ) {
        override fun toString() = "rxBytes=$bytes frames=$frames reading=$stage:$received/$expected " +
            "lastHeader=$channel/${flags?.let { "0x${it.toString(16)}" }}/$payloadSize tlsType=$tlsType"
    }

    private fun readFully(bytes: ByteArray, length: Int, stage: String) {
        var received = 0
        if (trackProgress) progress = progress.copy(stage = stage, received = 0, expected = length)
        while (received < length) {
            val count = input.read(bytes, received, length - received)
            if (count < 0) throw EOFException("AAP ended while reading $stage ($received/$length)")
            received += count
            if (trackProgress) {
                progress = progress.copy(bytes = progress.bytes + count, received = received)
                if (count > 0) onFirstRead?.let { first -> onFirstRead = null; first() }
            }
        }
    }

    @Volatile var oldUsbFrames = 0
        private set
    private var oldUsbBytes = 0

    /** Blocks until a complete message is available. Throws on end of stream or a framing error. */
    fun read(awaitUsbVersion: Boolean = false): AapMessage {
        while (true) {
            readFully(header, 4, "header")
            val channel = header[0].toInt() and 0xff
            val flags = header[1].toInt() and 0xff
            val size = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            if (trackProgress) progress = progress.copy(channel = channel, flags = flags, payloadSize = size, tlsType = null)
            val declaredTotal = if (Frame.hasTotalSize(flags)) {
                readFully(header, 4, "total-size")
                ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                    ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            } else 0
            if (payload.size < size) payload = ByteArray(size)
            readFully(payload, size, "payload")
            if (trackProgress) progress = progress.copy(frames = progress.frames + 1, stage = "decode",
                tlsType = if (Frame.isEncrypted(flags) && size > 0) payload[0].toInt() and 0xff else null)

            val first = Frame.isFirst(flags)
            // On a reopened USB pipe either ciphertext or the tail of an old fragmented message
            // can precede VERSION. Count whole frames only, preserving any partial VERSION on its channel.
            if (awaitUsbVersion && (Frame.isEncrypted(flags) || (!first && channel !in partial))) {
                oldUsbFrames++
                oldUsbBytes += size
                if (oldUsbFrames >= 32 || oldUsbBytes >= 128 * 1024)
                    throw AapException("USB is still carrying the previous session")
                continue
            }

            val plain: ByteArray
            val plainLen: Int
            if (Frame.isEncrypted(flags)) {
                val c = cryptor() ?: throw AapException("encrypted frame before TLS is up (ch=$channel)")
                plain = c.decrypt(payload, 0, size)
                plainLen = plain.size
            } else {
                plain = payload
                plainLen = size
            }

            val last = Frame.isLast(flags)
            if (first && last) {
                // Decryption already returns an owned array; plaintext uses the reusable receive buffer.
                val owned = if (plain === payload) plain.copyOf(plainLen) else plain
                return toMessage(channel, flags, Frame.isEncrypted(flags), owned, plainLen)
            }
            if (first) {
                partial.remove(channel)?.let { buffered -= it.buf.size() }
                // Early reject on the announced size; the real guard is the received-bytes check below.
                if (declaredTotal < 0 || declaredTotal > MAX_MESSAGE_SIZE) {
                    throw AapException("declared message size $declaredTotal out of range (ch=$channel)")
                }
                val buf = ByteArrayOutputStream(minOf(plainLen * 4, declaredTotal))
                val state = Partial(declaredTotal, buf, Frame.isEncrypted(flags))
                appendFragment(channel, state, plain, plainLen)
                partial[channel] = state
                continue
            }
            val state = partial[channel] ?: throw AapException("continuation frame without a first frame (ch=$channel)")
            if (Frame.isEncrypted(flags) != state.encrypted)
                throw AapException("encryption changed inside a fragmented message (ch=$channel)")
            appendFragment(channel, state, plain, plainLen)
            if (last) {
                partial.remove(channel)
                buffered -= state.buf.size()
                val encrypted = state.encrypted
                val all = state.buf.toByteArray()
                return toMessage(channel, flags, encrypted, all, all.size)
            }
        }
    }

    /**
     * Appends one fragment after checking the bytes actually received, so a head unit announcing a
     * wrong size cannot make us buffer more than the caps, nor more than it declared.
     */
    private fun appendFragment(channel: Int, state: Partial, data: ByteArray, len: Int) {
        val received = state.buf.size().toLong() + len
        val error = when {
            received > MAX_MESSAGE_SIZE -> "reassembled message exceeds $MAX_MESSAGE_SIZE bytes"
            received > state.declaredTotal -> "reassembled message exceeds declared size ${state.declaredTotal}"
            buffered + len > MAX_BUFFERED -> "too much partial data buffered"
            else -> null
        }
        if (error != null) {
            partial.clear()
            buffered = 0
            throw AapException("$error (ch=$channel)")
        }
        state.buf.write(data, 0, len)
        buffered += len
    }

    private fun toMessage(channel: Int, flags: Int, encrypted: Boolean, data: ByteArray, len: Int): AapMessage {
        if (len < 2) throw AapException("message too short on ch=$channel")
        val id = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        return AapMessage(channel, Frame.isControl(flags), encrypted, id, data, 2, len - 2)
    }

    private companion object {
        /** Upper bound on a reassembled message; a larger declared total is a protocol violation. */
        const val MAX_MESSAGE_SIZE = 8 * 1024 * 1024

        /** Upper bound on the bytes received for all partial messages open at once, across channels. */
        const val MAX_BUFFERED = 2L * MAX_MESSAGE_SIZE
    }
}

/**
 * Splits messages into frames, encrypting each chunk when needed, and writes them.
 * Fragments of one message are written back to back: some head units can't reassemble
 * interleaved fragments, so we never interleave on output.
 */
class FrameWriter(output: OutputStream, private val cryptor: () -> Cryptor?) {
    private val output = output
    private val frame = ByteArray(Frame.EXTENDED_SIZE + Frame.MAX_PAYLOAD + 1024)

    /**
     * Writes one message. [plain] holds id + body (see [AapMessage.plaintext]).
     * Must be called from a single thread (the session's writer), because TLS records must
     * leave in the order they were sealed.
     */
    fun write(channel: Int, control: Boolean, encrypted: Boolean, plain: ByteArray, length: Int = plain.size) {
        val c = if (encrypted) cryptor() ?: throw AapException("TLS not ready") else null
        var offset = 0
        val baseFlags = (if (control) Frame.FLAG_CONTROL else 0) or (if (encrypted) Frame.FLAG_ENCRYPTED else 0)
        do {
            val chunk = minOf(Frame.MAX_PAYLOAD, length - offset)
            val first = offset == 0
            val last = offset + chunk == length
            var flags = baseFlags
            if (first) flags = flags or Frame.FLAG_FIRST
            if (last) flags = flags or Frame.FLAG_LAST
            val extended = first && !last

            val body: ByteArray
            val bodyOff: Int
            val bodyLen: Int
            if (c != null) {
                body = c.encrypt(plain, offset, chunk); bodyOff = 0; bodyLen = body.size
            } else {
                body = plain; bodyOff = offset; bodyLen = chunk
            }
            if (bodyLen > 0xffff) throw AapException("frame too large: $bodyLen")

            var p = 0
            frame[p++] = channel.toByte()
            frame[p++] = flags.toByte()
            frame[p++] = (bodyLen shr 8).toByte()
            frame[p++] = bodyLen.toByte()
            if (extended) {
                frame[p++] = (length ushr 24).toByte()
                frame[p++] = (length ushr 16).toByte()
                frame[p++] = (length ushr 8).toByte()
                frame[p++] = length.toByte()
            }
            if (p + bodyLen <= frame.size) {
                System.arraycopy(body, bodyOff, frame, p, bodyLen)
                output.write(frame, 0, p + bodyLen)
            } else {
                output.write(frame, 0, p)
                output.write(body, bodyOff, bodyLen)
            }
            offset += chunk
        } while (offset < length)
        output.flush()
    }
}
