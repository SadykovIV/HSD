package net.prototux.aare.transport

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import net.prototux.aare.aap.AapException
import net.prototux.aare.aap.Transport
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** AAP over AOA bulk endpoints. FrameReader buffers a whole USB transfer before parsing headers. */
class UsbAccessoryTransport(usb: UsbManager, accessory: UsbAccessory) : Transport {
    override val usbAccessory = true
    override val name = "USB ${accessory.manufacturer} ${accessory.model}"
    override val input: InputStream
    override val output: OutputStream
    private val rawOutput: OutputStream
    private val closed = AtomicBoolean(false)

    init {
        if (Build.VERSION.SDK_INT >= 36) {
            input = usb.openAccessoryInputStream(accessory)
            try { rawOutput = usb.openAccessoryOutputStream(accessory) }
            catch (failure: Throwable) { input.close(); throw failure }
        } else {
            val fd = usb.openAccessory(accessory) ?: throw AapException("Android не открыл USB-аксессуар")
            input = ParcelFileDescriptor.AutoCloseInputStream(fd)
            try { rawOutput = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)) }
            catch (failure: Throwable) { input.close(); throw failure }
        }
        output = BufferedOutputStream(rawOutput, 64 * 1024)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Abort a blocked writer without flushing or taking its BufferedOutputStream monitor.
        runCatching { rawOutput.close() }
        runCatching { input.close() }
    }
}
