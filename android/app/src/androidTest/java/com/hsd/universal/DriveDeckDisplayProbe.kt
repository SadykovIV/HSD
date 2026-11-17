package com.hsd.universal

import android.content.*
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.*
import com.hsd.universal.dex.*
import com.hsd.universal.embed.EmbeddedSessionRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test the real desktop and its companion windows even on AVDs with a smaller AVC encoder. */
internal fun checkDriveDeckDisplay(context: Context, width: Int, height: Int, density: Int,
                                   tilesReady: (Int) -> Boolean, checkFullscreen: (Int, IDexUserService) -> Unit): Bundle {
    val input = DelegatedInputGateway(context)
    val token = Binder()
    input.beginGeneration(1, token, true)
    val ready = CountDownLatch(1)
    var producer: IDexUserService? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            producer = IDexUserService.Stub.asInterface(binder)
            ready.countDown()
        }
        override fun onServiceDisconnected(component: ComponentName) {}
    }
    val work = Executors.newSingleThreadExecutor()
    val thread = HandlerThread("qa-display-pixels").apply { start() }
    val intent = Intent(context, DelegatedDesktopService::class.java)
        .setAction(DelegatedDesktopService.BIND_ACTION_PREFIX + "size-" + SystemClock.uptimeMillis())
        .putExtras(Bundle().apply { putBinder(DelegatedInputGateway.EXTRA_BINDER, input) })
    val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    try {
        check(bound && ready.await(10, TimeUnit.SECONDS))
        val remote = checkNotNull(producer)
        val frames = AtomicInteger()
        val colors = AtomicInteger()
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).use { reader ->
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { img ->
                    if (img.width == width && img.height == height) {
                        val plane = img.planes[0]
                        val pixels = plane.buffer
                        val values = HashSet<Int>()
                        for (y in height/48 until height step height/24) for (x in width/48 until width step width/24)
                            values.add(pixels.getInt(plane.rowStride*y + plane.pixelStride*x))
                        colors.set(values.size)
                        frames.incrementAndGet()
                    }
                }
            }, Handler(thread.looper))
            try {
                val mode = DesktopModePolicy.DRIVEDECK
                val parent = remote.startDesktopSized(reader.surface, token, width, height, density, mode)
                EmbeddedSessionRegistry.activate(token, remote, parent, work, mode, density, true,
                    nativeLifetime = input.nativeProducerLifetime ?: remote.asBinder())
                val dm = context.getSystemService(DisplayManager::class.java)
                val deadline = SystemClock.elapsedRealtime() + 25000
                do {
                    val display = dm.getDisplay(parent)?.mode
                    if (display?.physicalWidth == width && display.physicalHeight == height &&
                        frames.get() >= 3 && colors.get() > 12 && tilesReady(parent)) {
                        val displayContext = context.createDisplayContext(dm.getDisplay(parent))
                        check(displayContext.resources.displayMetrics.densityDpi == density)
                        checkFullscreen(parent, remote)
                        return Bundle().apply {
                            putString("native_desktop", "${width}x${height}")
                            putInt("desktop_dpi", density)
                            putInt("composed_frames", frames.get())
                            putInt("sampled_colors", colors.get())
                        }
                    }
                    SystemClock.sleep(120)
                } while (SystemClock.elapsedRealtime() < deadline)
                error("Desktop ${width}x${height}: frames=${frames.get()} colors=${colors.get()} tiles=${tilesReady(parent)}")
            } finally {
                try {
                    EmbeddedSessionRegistry.deactivate(token)
                    val stopping = java.util.concurrent.FutureTask { remote.stop() }
                    Thread(stopping, "qa-display-stop").start()
                    try { stopping.get(3, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: java.util.concurrent.TimeoutException) {
                        context.cacheDir.resolve("qa-failure-threads.txt").writeText(
                            Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                                "${thread.name} ${thread.state}\n" + stack.joinToString("\n")
                            })
                        stopping.get(12, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    EmbeddedSessionRegistry.producerStopped(token)
                } finally {
                    reader.setOnImageAvailableListener(null, null)
                    // ImageReader.close invalidates even an image still being sampled by a callback.
                    thread.quitSafely()
                    thread.join()
                }
            }
        }
    } finally {
        if (bound) context.unbindService(connection)
        thread.quitSafely()
        thread.join(2000)
        work.shutdownNow()
        input.deactivate()
    }
}
