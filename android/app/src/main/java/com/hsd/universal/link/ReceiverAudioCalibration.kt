package com.hsd.universal.link

import android.content.Context
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.R
import java.security.MessageDigest
import java.util.Locale

/** A session keeps one audio offset. Edits made during playback apply on the next connection. */
class ReceiverAudioCalibration internal constructor(private val context: Context, private val route: ProjectionRoute) {
    private val store = prefs(context)
    private val initial = store.all
    @Volatile private var offset = (initial[DEFAULT] as? Int ?: 0).coerceIn(-MAX_MS, MAX_MS)

    fun identify(manufacturer: String?, model: String?) {
        fun clean(value: String?) = value.orEmpty().filterNot(Char::isISOControl).trim().take(64)
        val maker = clean(manufacturer)
        val name = clean(model)
        val title = listOf(maker, name).filter(String::isNotBlank).distinct().joinToString(" ")
        // The original store used 'USB' for every Android Auto route, including wireless.
        val legacyRoute = if (route == ProjectionRoute.CARLIFE_WIFI) "Wi-Fi" else "USB"
        fun key(prefix: String): String {
            val parts = listOf(prefix, maker, name).joinToString("\u0000").lowercase(Locale.ROOT)
            return "delay." + MessageDigest.getInstance("SHA-256").digest(parts.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        val legacy = initial[key(legacyRoute)] as? Int
        if (title.isEmpty() && legacy == null) {
            offset = (initial[DEFAULT] as? Int ?: 0).coerceIn(-MAX_MS, MAX_MS)
            store.edit().remove("selected.${route.name}").remove("name.${route.name}").apply()
            return
        }
        val key = key(route.name)
        val identified = title.ifEmpty { context.getString(R.string.audio_timing_unnamed_receiver) } + " · " + route.label(context)
        offset = ((initial[key] as? Int) ?: legacy ?: (initial[DEFAULT] as? Int) ?: 0).coerceIn(-MAX_MS, MAX_MS)
        store.edit().apply {
            if (legacy != null && !store.contains(key)) putInt(key, legacy.coerceIn(0, MAX_MS))
            putString("selected.${route.name}", key)
            putString("name.${route.name}", identified)
        }.apply()
    }

    fun milliseconds(): Int = offset
    data class Selection(val key: String, val name: String, val ms: Int)

    companion object {
        const val MAX_MS = 2000
        private const val DEFAULT = "delay.default"
        private fun prefs(c: Context) = c.getSharedPreferences("desktop_receiver_audio_v1", Context.MODE_PRIVATE)

        internal fun selected(c: Context, route: ProjectionRoute): Selection {
            val store = prefs(c)
            val key = store.getString("selected.${route.name}", null) ?: DEFAULT
            val name = store.getString("name.${route.name}", null) ?: c.getString(R.string.audio_timing_defaults)
            val fallback = store.getInt(DEFAULT, 0)
            return Selection(key, name, store.getInt(key, fallback).coerceIn(-MAX_MS, MAX_MS))
        }

        fun set(c: Context, target: Selection, value: Int) {
            require(value in -MAX_MS..MAX_MS)
            prefs(c).edit().putInt(target.key, value).apply()
        }

        fun audioDelayUs(automaticUs: Long, offsetMs: Int): Long =
            (automaticUs + offsetMs.coerceIn(-MAX_MS, MAX_MS) * 1000L).coerceAtLeast(0)
    }
}
