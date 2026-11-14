package com.hsd.universal.dex

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.usb.KadbUsb
import com.hsd.universal.R
import java.util.concurrent.Executors

class UsbActivationActivity : Activity() {
    private val manager by lazy { getSystemService(UsbManager::class.java) }
    private val ui by lazy { DesktopSetupViews(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var devices: LinearLayout
    private lateinit var status: TextView
    private lateinit var cancel: Button
    private var nextRequest = 0
    private class Attempt(val device: UsbDevice, val request: Int) {
        var permission: PendingIntent? = null
        var adb: Kadb? = null
        @Volatile var cancelled = false
    }
    private var attempt: Attempt? = null
    private val timeout = Runnable { finishAttempt(getString(R.string.usb_helper_timeout)) }
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = attempt
            if (current != null && !attached(current)) finishAttempt(getString(R.string.usb_helper_disconnected))
            else showDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = ui.column().apply { val pad = ui.dp(16); setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.background); addView(body) })
        val card = ui.section(body, getString(R.string.usb_helper_title))
        ui.text(card, getString(R.string.usb_helper_host_steps))
        status = ui.text(card, "", 15f, true)
        devices = ui.group(card)
        cancel = ui.button(card, getString(android.R.string.cancel)) { finishAttempt(getString(R.string.usb_helper_cancelled)) }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(changes, filter)
        showDevices()
    }

    override fun onResume() { super.onResume(); showDevices() }

    private fun showDevices() {
        val available = KadbUsb.devices(manager)
        devices.removeAllViews()
        cancel.visibility = if (attempt != null) android.view.View.VISIBLE else android.view.View.GONE
        if (attempt != null) return
        if (available.isEmpty()) ui.text(devices, getString(R.string.usb_helper_wait))
        for (device in available) {
            val name = runCatching { device.productName }.getOrNull()?.takeIf { it.isNotBlank() } ?: device.deviceName
            ui.button(devices, getString(R.string.usb_helper_activate, name)) { select(device) }
        }
    }

    private fun attached(current: Attempt) = KadbUsb.devices(manager).any { it == current.device }

    private fun select(device: UsbDevice) {
        if (attempt != null) return
        val current = Attempt(device, ++nextRequest)
        attempt = current
        showDevices()
        handler.postDelayed(timeout, 120_000)
        try {
            check(attached(current))
            if (manager.hasPermission(device)) start(current)
            else {
                status.setText(R.string.usb_helper_allow)
                // The request code and a fresh OS permission check identify our request. The
                // returned intent needs no device extras and can stay immutable.
                val response = requireNotNull(createPendingResult(current.request, Intent(),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE))
                current.permission = response
                manager.requestPermission(device, response)
            }
        } catch (_: RuntimeException) { finishAttempt(getString(R.string.usb_helper_disconnected)) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val current = attempt?.takeIf { it.request == requestCode } ?: return
        if (attached(current) && manager.hasPermission(current.device)) start(current)
        else finishAttempt(getString(R.string.usb_helper_denied))
    }

    private fun start(current: Attempt) {
        val adb = KadbUsb.create(applicationContext, current.device)
        current.adb = adb
        status.setText(R.string.usb_helper_confirm)
        executor.execute {
            val message = try {
                adb.use {
                    DexAccess.configure(applicationContext)
                    UsbPhoneActivation.activate(applicationContext, it) { current.cancelled }
                }
                getString(R.string.usb_helper_ready)
            } catch (e: Exception) {
                if (e is IllegalStateException && !e.message.isNullOrBlank()) e.message!!
                else getString(R.string.usb_helper_failed)
            }
            handler.post { if (attempt === current) finishAttempt(message) }
        }
    }

    private fun finishAttempt(message: String) {
        val current = attempt ?: return
        current.cancelled = true
        attempt = null
        current.permission?.cancel()
        handler.removeCallbacks(timeout)
        current.adb?.let { Thread({ runCatching { it.close() } }, "hsd-usb-close").start() }
        status.text = message
        showDevices()
    }

    override fun onDestroy() {
        unregisterReceiver(changes)
        finishAttempt("")
        executor.shutdownNow()
        super.onDestroy()
    }
}
