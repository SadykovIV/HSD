package com.hsd.universal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import net.prototux.aare.aap.PhoneDescription
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Public phone presentation, independent of the TLS certificate and its renewal. */
internal object ProjectionDescription {
    private data class Icons(val small: ByteArray, val medium: ByteArray, val large: ByteArray)
    @Volatile private var icons: Icons? = null

    @Synchronized fun instanceId(context: Context): String {
        val settings = ProjectionRoute.prefs(context)
        return settings.getString("projectionInstanceId", null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                check(settings.edit().putString("projectionInstanceId", it).commit())
            }
    }

    fun create(context: Context): PhoneDescription {
        val images = icons ?: synchronized(this) {
            icons ?: Icons(icon(context, 32), icon(context, 64), icon(context, 128)).also { icons = it }
        }
        return PhoneDescription(android.os.Build.MODEL, "HSD", images.small, images.medium, images.large,
            instanceId(context))
    }

    private fun icon(context: Context, size: Int): ByteArray {
        // AAP expects PNGs at three fixed pixel sizes, not Android adaptive-icon XML or dp sizes.
        val drawable = requireNotNull(context.getDrawable(R.mipmap.ic_launcher)).mutate()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
