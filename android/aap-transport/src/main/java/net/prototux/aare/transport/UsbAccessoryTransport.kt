package net.prototux.aare.transport

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import net.prototux.aare.aap.Transport
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** AAP over AOA bulk endpoints. FrameReader reads a whole USB transfer before parsing headers. */
class UsbAccessoryTransport private constructor(
    override val input: InputStream,
    private val rawOutput: OutputStream,
    override val name: String,
) : Transport {
    private constructor(streams: Pair<InputStream, OutputStream>, name: String) : this(streams.first, streams.second, name)

    constructor(fd: ParcelFileDescriptor, name: String) : this(descriptorStreams(fd), name)

    constructor(usb: UsbManager, accessory: UsbAccessory) : this(
        openAccessoryStreams(usb, accessory),
        "USB ${accessory.manufacturer} ${accessory.model}"
    )

    override val usbAccessory = true
    override val output: OutputStream = BufferedOutputStream(rawOutput, 64 * 1024)
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Abort a blocked writer without flushing or taking its BufferedOutputStream monitor.
        runCatching { rawOutput.close() }
        runCatching { input.close() }
    }

    companion object {
        private fun openAccessoryStreams(usb: UsbManager, accessory: UsbAccessory): Pair<InputStream, OutputStream> {
            fun fromDescriptor() = descriptorStreams(usb.openAccessory(accessory)
                ?: throw IOException("Android не предоставил дескриптор подключённого USB-аксессуара"))
            if (Build.VERSION.SDK_INT < 36) return fromDescriptor()
            return withDescriptorFallback({
                openStreams(usb.openAccessoryInputStream(accessory)) { usb.openAccessoryOutputStream(accessory) }
            }, ::fromDescriptor)
        }

        // Some API 36+ USB services dereference a missing ParcelFileDescriptor while opening a stream.
        internal fun withDescriptorFallback(streams: () -> Pair<InputStream, OutputStream>,
                                            descriptor: () -> Pair<InputStream, OutputStream>): Pair<InputStream, OutputStream> {
            try { return streams() } catch (missing: NullPointerException) {
                try { return descriptor() } catch (failure: Exception) {
                    failure.addSuppressed(missing)
                    throw failure
                }
            }
        }

        private fun descriptorStreams(fd: ParcelFileDescriptor) =
            openStreams(ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor))
            }

        internal fun openStreams(input: InputStream, openOutput: () -> OutputStream): Pair<InputStream, OutputStream> {
            try { return input to openOutput() }
            catch (failure: Throwable) {
                runCatching { input.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}
