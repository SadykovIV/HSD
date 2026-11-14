package com.hsd.universal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Surface
import com.hsd.universal.aa.AaTranscoder
import com.hsd.universal.aa.TranscodeOutput

/**
 * H.264-энкодер (Surface input) и рисовалка заглушки моста (текст, пока AA не подключился).
 * Кадры отдаются в Annex-B с 4-байтными стартовыми кодами; codec config приходит отдельно (onConfig).
 */
class VideoSource(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val listener: Listener,
    /** Видимая часть кадра (SPS-кроп, h529): надпись рисуется только в ней, вне её фон. null = весь кадр. */
    private val area: Rect? = null,
    /** Энкодер в формате Baidu (Mobis CCNC, h242): VBR, level 256, повтор кадра 100 мс. */
    private val baidu: Boolean = false,
    /** Shared read-only image supplied by the application; this encoder never recycles it. */
    private val brandArtwork: Bitmap? = null,
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, key: Boolean)
        fun onError(msg: String, t: Throwable?)
    }

    @Volatile var framesRendered = 0L
        private set

    /** Текст состояния под изображением приложения вместо ещё не готового рабочего стола. */
    @Volatile var banner: String? = null

    @Volatile private var encoder: AaTranscoder? = null
    private var renderThread: Thread? = null
    var encoderName: String = "?"
        private set

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    private val output = object : TranscodeOutput {
        override fun onEncoderConfig(csd: ByteArray) = listener.onConfig(csd)
        override fun onEncoderFrame(data: ByteArray, key: Boolean) = listener.onFrame(data, key)
        override fun onTranscodeError(msg: String) = listener.onError(msg, null)
        override fun onNeedAaKeyFrame() = Unit
    }

    @Synchronized
    fun start() {
        if (encoder != null) return
        val active = AaTranscoder(width, height, fps, bitrate, output, tuning = "cbr", baidu = baidu, directSurface = true)
        try {
            active.start()
            // Software Canvas draws to the relay's SurfaceTexture. The relay supplies EGL
            // or a negotiated YUV image to MediaCodec, just as it does for the desktop.
            val input = checkNotNull(active.surfaceInput).surface
            encoderName = active.encoderName
            encoder = active
            renderThread = Thread({ renderLoop(active, input) }, "video-render").also { it.start() }
        } catch (e: Exception) {
            encoder = null
            active.stop()
            throw e
        }
    }

    fun requestKeyFrame() { encoder?.requestSyncFrame() }

    fun setBitrate(bps: Int): Boolean {
        val active = encoder ?: return false
        active.setBitrate(bps)
        return active.currentBitrate == bps
    }

    @Synchronized
    fun stop() {
        val active = encoder ?: return
        encoder = null
        val worker = renderThread
        renderThread = null
        try {
            if (worker != null && worker !== Thread.currentThread()) {
                worker.interrupt()
                worker.join(1000)
            }
        } finally {
            active.stop()
        }
    }

    private fun renderLoop(active: AaTranscoder, input: Surface) {
        val frameMs = 1000L / fps
        var next = SystemClock.elapsedRealtime()
        try {
            while (encoder === active) {
                val canvas = input.lockCanvas(null)
                try {
                    drawBanner(canvas, banner.orEmpty())
                } finally {
                    input.unlockCanvasAndPost(canvas)
                }
                framesRendered++
                next += frameMs
                val delay = next - SystemClock.elapsedRealtime()
                if (delay > 0) Thread.sleep(delay) else next = SystemClock.elapsedRealtime()
            }
        } catch (e: Exception) {
            if (encoder === active) listener.onError("рендер заставки", e)
        }
    }

    /** Текущий кадр заглушки в Bitmap: с него транскодер начинает зум-растворение в картинку AA. Любая нить. */
    fun snapshot(): Bitmap? {
        val msg = banner ?: return null
        val w = area?.width() ?: width
        val h = area?.height() ?: height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawBanner(Canvas(bmp), w, h, msg, Paint(text), Paint(image))
        return bmp
    }

    private fun drawBanner(c: Canvas, msg: String) {
        val a = area ?: return drawBanner(c, width, height, msg, text, image)
        c.drawColor(BG)
        c.save()
        c.translate(a.left.toFloat(), a.top.toFloat())
        c.clipRect(0, 0, a.width(), a.height())
        drawBanner(c, a.width(), a.height(), msg, text, image)
        c.restore()
    }

    private fun drawBanner(c: Canvas, width: Int, height: Int, msg: String, text: Paint, image: Paint) {
        val w = width.toFloat()
        val h = height.toFloat()
        // плоские цвета: на CBR 4 Мбит/с градиенты слабого энкодера идут полосами
        c.drawColor(BG)
        brandArtwork?.let { artwork ->
            val side = minOf(h * 0.52f, w * 0.48f)
            val scale = side / maxOf(artwork.width, artwork.height)
            val aw = artwork.width * scale
            val ah = artwork.height * scale
            c.drawBitmap(artwork, null, RectF((w - aw) / 2, h * 0.32f - ah / 2,
                (w + aw) / 2, h * 0.32f + ah / 2), image)
        }

        text.textAlign = Paint.Align.CENTER
        text.typeface = WORDMARK
        text.textSize = h * 0.075f
        val title = BuildConfig.APP_TITLE
        if (text.measureText(title) > w * 0.88f) text.textSize *= w * 0.88f / text.measureText(title)
        text.color = Color.WHITE
        c.drawText(title, w / 2, h * 0.66f, text)

        // статус моста: по нему видно, чего ждём (сервер ГУ, разблокировка, кадр)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.CENTER
        text.color = STATUS
        text.textSize = h * 0.052f
        var lines = wrapBalanced(msg, w * 0.88f, text)
        while (lines.size * text.textSize * 1.25f > h * 0.23f && text.textSize > h * 0.032f) {
            text.textSize *= 0.9f
            lines = wrapBalanced(msg, w * 0.88f, text)
        }
        val lh = text.textSize * 1.25f
        var y = h * 0.73f + (h * 0.23f - lines.size * lh) / 2 - text.fontMetrics.ascent
        for (l in lines) {
            c.drawText(l, w / 2, y, text)
            y += lh
        }
    }

    /**
     * Перенос по словам на минимум строк, но строки примерно равной ширины:
     * на 4:3 «...перезапустите сервер ГУ» иначе оставляет «ГУ» одно на второй строке.
     */
    private fun wrapBalanced(msg: String, maxW: Float, p: Paint): List<String> {
        val words = msg.split(' ')
        fun greedy(limit: Float): List<String> {
            val lines = ArrayList<String>()
            var cur = ""
            for (word in words) {
                val t = if (cur.isEmpty()) word else "$cur $word"
                if (p.measureText(t) > limit && cur.isNotEmpty()) {
                    lines.add(cur)
                    cur = word
                } else {
                    cur = t
                }
            }
            if (cur.isNotEmpty()) lines.add(cur)
            return lines
        }
        val base = greedy(maxW)
        if (base.size < 2) return base
        // сужаем лимит, пока число строк не растёт: последняя строка добирает слова у предыдущих
        var best = base
        var limit = p.measureText(msg) / base.size
        while (limit < maxW) {
            val l = greedy(limit)
            if (l.size == base.size) {
                best = l
                break
            }
            limit += maxW * 0.02f
        }
        return best
    }

    companion object {
        const val MIME = AaTranscoder.MIME

        private val BG = Color.rgb(0x0E, 0x14, 0x1A) // фон иконки
        private val STATUS = Color.rgb(0xB8, 0xC4, 0xCC)
        private val WORDMARK: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    }
}
