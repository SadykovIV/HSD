package com.hsd.universal.aa

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.opengl.GLES20
import androidx.annotation.Keep
import java.nio.ByteBuffer

/** Used only when the encoder advertises Surface input but supplies the wrong buffer geometry. */
@Keep
internal class CodecImageInput(private val codec: MediaCodec, private val width: Int, private val height: Int) {
    private val rgba = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4))

    init {
        System.loadLibrary("hsd_surface")
    }

    private external fun convert(width: Int, height: Int, rgba: ByteBuffer,
        y: ByteBuffer, rowY: Int, pixelY: Int, u: ByteBuffer, rowU: Int, pixelU: Int,
        v: ByteBuffer, rowV: Int, pixelV: Int): Boolean

    fun submit(ptsNs: Long) {
        // A busy codec drops a whole unencoded frame. It never holds the next touch behind it.
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return
        var bytes = 0
        try {
            checkNotNull(codec.getInputImage(index)) { "Encoder did not supply a YUV input image" }.use { image ->
                check(image.format == ImageFormat.YUV_420_888 && image.width == width && image.height == height)
                val (y, u, v) = image.planes
                rgba.clear()
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Cannot read encoder framebuffer" }
                check(convert(width, height, rgba, y.buffer.slice(), y.rowStride, y.pixelStride,
                    u.buffer.slice(), u.rowStride, u.pixelStride, v.buffer.slice(), v.rowStride, v.pixelStride)) {
                    "Encoder returned an invalid YUV plane layout"
                }
                bytes = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)
            }
        } finally {
            codec.queueInputBuffer(index, 0, bytes, ptsNs / 1000, 0)
        }
    }
}
