package com.hsd.universal.hicar

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** A new peer uses PAKE. A previously bound peer must prove its saved identity with STS. */
internal class HiCarAuthentication(private val identity: String, private val wired: Boolean = false) : Closeable {
    private var handle = 0L
    private var closed = false

    fun authenticate(input: InputStream, output: OutputStream, pin: () -> String): ByteArray {
        val stream = BufferedInputStream(input)
        var dialect = if (wired) HiCarAuthEnvelope.Dialect.VW else HiCarAuthEnvelope.Dialect.LEGACY
        fun send(op: Int, data: JSONObject) {
            output.write(HiCarAuthEnvelope.encode(dialect, wired, op, data).toString().toByteArray(Charsets.UTF_8))
            output.flush()
        }
        send(1, JSONObject().put("deviceId", identity).put("devicename", "HSD")
            .put("has_pin", true).put("usb_connection", wired).put("CONNECT_ABILITY_KEY", "1"))
        val peer = HiCarAuthEnvelope.decode(HiCarJson.read(stream), wired, 1)
        dialect = peer.dialect
        val peerId = peer.data.opt("deviceId") as? String
        check(peerId != null && peerId.length in 1..64 && peerId.all { it.code in 33..126 }) {
            "HiCar: неверный идентификатор ГУ"
        }
        val known = synchronized(this) {
            check(!closed && handle == 0L)
            handle = HiCarNativeAuth.create(identity, peerId, "", 1)
            check(handle != 0L) { "HiCar: не удалось открыть хранилище сопряжения" }
            HiCarNativeAuth.trusted(handle)
        }
        if (!known) {
            val entered = if (wired) peer.pin.orEmpty() else pin()
            check(entered.matches(Regex("[0-9]{6}"))) { "HiCar: требуется шестизначный код с экрана ГУ" }
            synchronized(this) {
                check(!closed && handle != 0L) { "HiCar: сопряжение остановлено" }
                HiCarNativeAuth.close(handle)
                handle = HiCarNativeAuth.create(identity, peerId, entered, 1)
                check(handle != 0L) { "HiCar: не удалось начать проверку PIN" }
            }
        }
        var incoming: ByteArray? = null
        try {
            repeat(32) {
                val response = synchronized(this) {
                    check(handle != 0L) { "HiCar: сопряжение остановлено" }
                    checkNotNull(HiCarNativeAuth.step(handle, incoming, 32))
                }
                incoming?.fill(0)
                check(response.isNotEmpty() && response[0].size == 16) { "HiCar: неверный ответ HiChain" }
                val meta = ByteBuffer.wrap(response[0]).order(ByteOrder.nativeOrder()).asIntBuffer()
                check(meta[0] == 0 && meta[1] != 1 && meta[3] == 0) {
                    "HiCar: ГУ отклонило ${if (known) "повторное сопряжение" else "проверку PIN"} (${meta[0]})"
                }
                response.drop(1).forEach { bytes ->
                    try {
                        val data = HiCarJson.`object`(bytes.toString(Charsets.UTF_8))
                        normalize(data)
                        send(3, data)
                    } finally { bytes.fill(0) }
                }
                if (meta[1] == 0) return synchronized(this) {
                    checkNotNull(HiCarNativeAuth.sessionKey(handle)).also { check(it.size == 16 || it.size == 32) }
                }
                val next = HiCarAuthEnvelope.decode(HiCarJson.read(stream), wired, 3)
                check(next.dialect == dialect) { "HiCar: формат аутентификации изменился посреди сеанса" }
                normalize(next.data)
                incoming = next.data.toString().toByteArray(Charsets.UTF_8)
            }
            error("HiCar: превышено число этапов аутентификации")
        } finally { incoming?.fill(0) }
    }

    @Synchronized override fun close() { closed = true; if (handle != 0L) HiCarNativeAuth.close(handle); handle = 0 }

    companion object {
        private fun normalize(data: JSONObject) {
            data.optJSONObject("payload")?.let { payload ->
                for (key in arrayOf("peerUserType", "keyLength")) {
                    val value = payload.opt(key)
                    if (value is String && value in arrayOf("0", "1", "16", "32")) payload.put(key, value.toInt())
                }
            }
        }

        @Synchronized fun initialize(context: Context): String {
            val directory = File(context.noBackupFilesDir, "hicar-auth")
            check(directory.isDirectory || directory.mkdirs())
            Os.chmod(directory.absolutePath, 448) // 0700
            val identityFile = AtomicFile(File(directory, "identity"))
            val identity = if (identityFile.baseFile.exists()) identityFile.readFully().toString(Charsets.US_ASCII)
                else ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02X".format(it) }.also { id ->
                    val out = identityFile.startWrite()
                    try { out.write(id.toByteArray(Charsets.US_ASCII)); identityFile.finishWrite(out) }
                    catch (e: Exception) { identityFile.failWrite(out); throw e }
                }
            check(identity.matches(Regex("[0-9A-F]{64}"))) { "HiCar: повреждено хранилище сопряжения" }
            check(HiCarNativeAuth.init(directory.absolutePath, identity) == 0) { "HiCar: недоступно хранилище ключей" }
            return identity
        }
    }
}

/** VW's libauthagent sends an object in the identity response and a string in PAKE/STS. */
internal object HiCarAuthEnvelope {
    enum class Dialect(val version: String) { LEGACY("A_0"), VW("L_1.0") }

    class Packet(val dialect: Dialect, val data: JSONObject, val pin: String?)

    fun encode(dialect: Dialect, wired: Boolean, operation: Int, data: JSONObject): JSONObject = JSONObject()
        .put("version", dialect.version).put("discover_type", 1).put("oper_type", operation)
        .put("usb_connection", wired)
        .put("data", if (dialect == Dialect.VW && operation == 1) data else data.toString())

    fun decode(message: JSONObject, wired: Boolean, operation: Int): Packet {
        val dialect = Dialect.entries.firstOrNull { it.version == message.opt("version") }
            ?: error("HiCar: неизвестная версия аутентификации")
        check(message.opt("oper_type") == operation) { "HiCar: неверный этап аутентификации" }
        val data: JSONObject = when (val value = message.opt("data")) {
            is JSONObject -> value.also { check(dialect == Dialect.VW && operation == 1) }
            is String -> HiCarJson.`object`(value)
            else -> error("HiCar: отсутствуют данные аутентификации")
        }
        for (fields in listOf(message, data)) if (fields.has("usb_connection")) {
            val value = fields.opt("usb_connection")
            check(value == wired || value == if (wired) 1 else 0) { "HiCar: ГУ переключило тип соединения" }
        }
        val pin = if (wired && operation == 1) message.opt("pin_code") as? String else null
        return Packet(dialect, data, pin)
    }
}
