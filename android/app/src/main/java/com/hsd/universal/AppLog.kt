package com.hsd.universal

import android.content.Context
import android.os.Build
import android.util.Log
import com.hsd.universal.update.ComponentPackages
import com.hsd.universal.update.DesktopComponent
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Лог в три места: logcat (тег сборки), файл с тегом и датой <timestamp>.log в getExternalFilesDir(null)
 * и кольцо последних строк для экрана. Подробности (hexdump) идут только в logcat и файл.
 */
object AppLog {
    const val TAG = BuildConfig.LOG_TAG
    private const val RING = 200

    private val ring = ArrayDeque<String>()
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var out: AsyncLogWriter? = null
    var file: File? = null
        private set

    @Synchronized
    fun init(ctx: Context) {
        DesktopTransport.init(ctx)
        if (out != null) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "$TAG-$stamp.log")
        try {
            out = AsyncLogWriter(FileOutputStream(f, true)) { error -> Log.e(TAG, "не записать файл лога", error) }
            file = f
        } catch (e: Exception) {
            Log.e(TAG, "не открыть файл лога $f", e)
        }
        i("лог: ${f.absolutePath}")
        i("$TAG ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}), " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        val driveDeck = runCatching { ComponentPackages.installed(ctx, DesktopComponent.DRIVEDECK) }.getOrNull()
        if (driveDeck != null) i("DriveDeck ${driveDeck.versionName} (code ${driveDeck.longVersionCode})", false)
        CrashLog.install(ctx)
    }

    /** Строка для всех приёмников (экран тоже, если ui=true). */
    @Synchronized
    fun i(line: String, ui: Boolean = true) {
        val text = DiagnosticNames.format(line)
        Log.i(TAG, text)
        record(text, ui)
    }

    @Synchronized
    fun detail(text: String) {
        val formatted = DiagnosticNames.format(text)
        for (line in formatted.lineSequence()) Log.d(TAG, line)
        record(formatted, false)
    }

    @Synchronized
    fun e(line: String, t: Throwable? = null) {
        val text = DiagnosticNames.format(line)
        if (t == null) Log.e(TAG, text)
        else Log.e(TAG, text + "\n" + DiagnosticNames.format(t.stackTraceToString()))
        val full = if (t == null) text else "$text: ${t.javaClass.simpleName}: ${DiagnosticNames.format(t.message.orEmpty())}"
        record("ERROR $full", true)
    }

    @Synchronized
    fun lastLines(): List<String> = ring.toList()

    /** Очистить строки на экране (файл лога не трогаем). */
    @Synchronized
    fun clearRing() = ring.clear()

    private fun record(text: String, ui: Boolean) {
        val entry = tsFmt.format(Date()) + " " + text
        if (ui) {
            if (ring.size == RING) ring.removeFirst()
            ring.addLast(entry)
        }
        out?.append(entry)
    }

    /** Never hold the producer monitor while waiting for storage. */
    fun flush(timeoutMs: Long = 500): Boolean = synchronized(this) { out }?.flush(timeoutMs) ?: true

    /** Audio/unknown packets log headers only; known valid controls retain safe decoded metadata. */
    fun msg(dir: String, chan: Int, outerLen: Int, type: Int, len: Int, decoded: String?, inner: ByteArray, ui: Boolean = true, note: String = "") {
        val entry = ProtocolLogPolicy.message(dir, chan, outerLen, type, len, decoded, inner, note)
        i(entry.header, ui)
        entry.detail?.let(::detail)
    }
}
