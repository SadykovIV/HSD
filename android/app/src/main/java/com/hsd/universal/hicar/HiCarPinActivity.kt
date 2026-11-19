package com.hsd.universal.hicar

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import com.hsd.universal.ProjectionResources
import com.hsd.universal.R
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object HiCarPin {
    data class Request(val token: String, val answer: CompletableFuture<String>)
    @Volatile var pending: Request? = null
        private set
    private const val ID = 7831
    private const val CHANNEL = "hicar-pairing"
    fun await(context: Context, life: ProjectionResources): String {
        val request = Request(UUID.randomUUID().toString(), CompletableFuture())
        synchronized(this) { check(pending == null); pending = request }
        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            life.own { request.answer.cancel(false) }
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "HiCar", NotificationManager.IMPORTANCE_HIGH))
            val intent = Intent(context, HiCarPinActivity::class.java).putExtra("token", request.token)
            val open = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notifications.notify(ID, Notification.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.hicar_pin_title)).setContentText(context.getString(R.string.hicar_pin_hint))
                .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(120_000).build())
            return request.answer.get(120, TimeUnit.SECONDS)
        } finally {
            synchronized(this) { if (pending === request) pending = null }
            notifications.cancel(ID)
        }
    }
    @Synchronized fun submit(token: String?, pin: String): Boolean {
        val request = pending ?: return false
        return token == request.token && pin.matches(Regex("[0-9]{6}")) && request.answer.complete(pin)
    }
}

/** Opened explicitly from the pairing notification or connection settings. */
class HiCarPinActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val pending = HiCarPin.pending
        val token = intent.getStringExtra("token") ?: pending?.token
        if (pending == null || token != pending.token) { finish(); return }
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(6))
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            hint = "000000"
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.hicar_pin_title).setMessage(R.string.hicar_pin_hint)
            .setView(field).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> pending.answer.cancel(false); finish() }
            .setOnCancelListener { pending.answer.cancel(false); finish() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (HiCarPin.submit(token, field.text.toString())) { field.text.clear(); dialog.dismiss(); finish() }
                else field.error = getString(R.string.hicar_pin_invalid)
            }
        }
        dialog.show()
    }
}
