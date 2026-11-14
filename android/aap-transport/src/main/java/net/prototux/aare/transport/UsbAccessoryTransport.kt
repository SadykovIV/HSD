package net.prototux.aare.transport

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import net.prototux.aare.aap.AapException
import net.prototux.aare.aap.Transport
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** AAP over AOA bulk endpoints. FrameReader buffers a whole USB transfer before parsing headers. */
class UsbAccessoryTransport(fd: ParcelFileDescriptor, override val name: String) : Transport {
    constructor(usb: UsbManager, accessory: UsbAccessory) : this(
        usb.openAccessory(accessory) ?: throw AapException("Android не открыл USB-аксессуар"),
        "USB ${accessory.manufacturer} ${accessory.model}"
    )

    override val usbAccessory = true
    override val input: InputStream
    override val output: OutputStream
    private val rawOutput: OutputStream
    private val closed = AtomicBoolean(false)

    init {
        // One kernel open per session. Each stream owns its descriptor and can only
        // close that descriptor, even after an identical accessory has reattached.
        input = ParcelFileDescriptor.AutoCloseInputStream(fd)
        try { rawOutput = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)) }
        catch (failure: Throwable) { input.close(); throw failure }
        output = BufferedOutputStream(rawOutput, 64 * 1024)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Abort a blocked writer without flushing or taking its BufferedOutputStream monitor.
        runCatching { rawOutput.close() }
        runCatching { input.close() }
    }
}
