package com.hsd.universal

import android.app.Activity
import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import com.hsd.universal.dex.DpiCorrectionActivity
import kotlin.random.Random

/** The right surface always draws blue. A receiver tap switches its protection and the
 * left reference colour, so a stale or entirely black frame cannot pass. */
internal fun openCarLifeSurfaceProbe(test: Instrumentation, display: Int, playClip: Boolean = false): Activity {
    val intent = Intent(test.targetContext, DpiCorrectionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    val page = test.startActivitySync(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
    test.runOnMainSync {
        check(page.window.decorView.display?.displayId == display)
        val row = LinearLayout(page)
        var secure = false
        fun render() {
            row.removeAllViews()
            for (left in listOf(true, false)) {
                val color = if (!left) Color.BLUE else if (secure) Color.RED else Color.GREEN
                val surface = SurfaceView(page)
                surface.setSecure(!left && secure)
                if (!left && playClip) surface.holder.addCallback(object : SurfaceHolder.Callback {
                    private var player: MediaPlayer? = null
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val movie = MediaPlayer()
                        player = movie
                        test.context.assets.openFd("projection-colours.mp4").use { clip ->
                            movie.setDataSource(clip.fileDescriptor, clip.startOffset, clip.length)
                        }
                        movie.setDisplay(holder)
                        // A playing video commonly asks its own window to remain lit.
                        movie.setScreenOnWhilePlaying(true)
                        movie.isLooping = true
                        movie.setOnPreparedListener { if (player === it) it.start() }
                        movie.prepareAsync()
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) { player?.release(); player = null }
                }) else surface.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        val canvas = holder.lockCanvas()
                        try { canvas.drawColor(color) } finally { holder.unlockCanvasAndPost(canvas) }
                    }
                })
                row.addView(surface, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        }
        page.setContentView(row)
        if (!playClip) row.setOnClickListener { secure = !secure; render() }
        render()
    }
    return page
}

/** A moving textured background and a uniquely coloured contact counter on the projected display. */
internal fun openCarLifeTouchMarker(test: Instrumentation, display: Int, port: Int): Activity {
    val intent = Intent(test.targetContext, DpiCorrectionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    val page = test.startActivitySync(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
    test.runOnMainSync {
        check(page.window.decorView.display?.displayId == display)
        page.setContentView(carLifeTouchMarkerView(page, port))
    }
    return page
}

internal fun carLifeTouchMarkerView(context: Context, port: Int): View = object : View(context) {
    val paint = Paint()
    val texture = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val random = Random(1234)
        canvas.drawColor(Color.LTGRAY)
        repeat(850) { i ->
            paint.color = Color.rgb(random.nextInt(35, 240), random.nextInt(35, 240), random.nextInt(35, 240))
            paint.strokeWidth = 3f
            val x = random.nextInt(width).toFloat()
            val y = random.nextInt(height).toFloat()
            canvas.drawLine(x, y, x + random.nextInt(-100, 100), y + random.nextInt(-100, 100), paint)
            if (i % 6 == 0) canvas.drawText(i.toString(), x, y, paint)
        }
    }
    var drawCount = 0
    var contacts = 0
    var downNs = 0L
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            contacts++
            downNs = System.nanoTime()
            invalidate()
        }
        return true
    }
    override fun onDraw(canvas: Canvas) {
        val offset = drawCount++ * 5 % texture.width
        for (x in -offset until width step texture.width)
            for (y in 0 until height step texture.height) canvas.drawBitmap(texture, x.toFloat(), y.toFloat(), null)
        paint.color = Color.rgb(32 + contacts % 3 * 96, 32 + contacts / 3 % 3 * 96, 88 + contacts / 9 % 2 * 136)
        canvas.drawRect(width * .36f, height * .36f, width * .64f, height * .64f, paint)
        if (downNs != 0L) {
            Log.i("HsdTouchProbe", "draw $port contact=$contacts downNs=$downNs drawNs=${System.nanoTime()}")
            downNs = 0L
        }
        if (drawCount == 1) Log.i("HsdTouchProbe", "ready $port")
        postInvalidateOnAnimation()
    }
}
