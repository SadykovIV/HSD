package com.hsd.universal.dex

import com.hsd.universal.BuildConfig

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.hsd.universal.AppLog
import java.util.concurrent.Executors

/** Owned by one USB bridge session. Nothing here depends on which Activity is in front. */
internal class CarLifePhoneIdleTimer(
    private val context: Context,
    private val sessionReady: () -> Boolean,
    private val requestOff: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val prefs = DexPrefs(context)
    @Suppress("DEPRECATION")
    private val guard = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "${BuildConfig.BRAND}:PhoneIdle").apply {
        setReferenceCounted(false)
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val tick = Runnable { refresh() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { readTimeout(); refresh() }
    }
    private var active = false
    private var clockFailures = 0
    private var reading = false
    private var generation = 0L
    private var armedAt: Long? = null
    private var requestedForActivity: Long? = null
    private var nextProbe = 0L
    private var systemTimeout = 0L
    private var selectedTimeout: PhoneIdleTimeout? = null
    private var timeout = 0L
    private var owned = false

    fun start() {
        if (active) return
        active = true
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, observer)
        readTimeout()
        refresh()
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacks(tick)
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } finally {
            disarm()
            worker.shutdownNow()
        }
    }

    private fun readTimeout() {
        systemTimeout = Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0)
        timeout = prefs.phoneIdleTimeout.effectiveMs(systemTimeout)
        nextProbe = 0
    }

    private fun eligible(): Boolean = prefs.phoneIdleMode == PhoneIdleMode.PANEL && prefs.useDelegated &&
        prefs.desktopPanelControl && DelegatedDesktopAccess.isReady(context) && DelegatedPanelAccess.hasPermission(context) &&
        sessionReady() && power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked &&
        timeout in 1..Int.MAX_VALUE.toLong()

    @SuppressLint("WakelockTimeout") // Always timed. A dead handler cannot keep the device awake indefinitely.
    private fun refresh() {
        main.removeCallbacks(tick)
        if (!active) return
        val now = SystemClock.uptimeMillis()
        val selected = prefs.phoneIdleTimeout
        if (selected != selectedTimeout) {
            // Start a full interval without dropping the live session's guard.
            generation++
            requestedForActivity = null
            if (armedAt != null) armedAt = now
            clockFailures = 0
            selectedTimeout = selected
            timeout = selected.effectiveMs(systemTimeout)
            nextProbe = 0
        }
        if (eligible()) {
            // The panel controller owns its own timed lock after a successful handoff.
            // An unreadable clock or an unanswered request must allow Android to sleep.
            if (DexState.phoneScreenOff) {
                requestedForActivity = null
                releaseGuard()
                armedAt = now
                nextProbe = now + timeout
            } else if (!DexState.phoneScreenBusy) {
                if (owned && !reading && clockFailures == 0 && requestedForActivity == null) guard.acquire(5_000)
                if (armedAt == null) { armedAt = now; nextProbe = now }
                if (!reading && now >= nextProbe) probe()
            }
        } else {
            if (armedAt != null) AppLog.i("DESKTOP PANEL TIMER: disarmed; interactive=${power.isInteractive}, " +
                "keyguard=${keyguard.isKeyguardLocked}, deviceLocked=${keyguard.isDeviceLocked}", false)
            disarm()
        }
        main.postDelayed(tick, 750)
    }

    private fun probe() {
        reading = true
        val epoch = generation
        val before = SystemClock.uptimeMillis()
        worker.execute {
            val activity = runCatching { PhonePowerActivity.read(context) }.getOrNull()
            main.post {
                reading = false
                if (!active || epoch != generation || !eligible()) return@post
                if (selectedTimeout != prefs.phoneIdleTimeout) { refresh(); return@post }
                val now = SystemClock.uptimeMillis()
                if (activity == null) {
                    // A missing physical-activity clock must not turn a panel timer into an
                    // indefinite screen wake lock. Android resumes its own idle/keyguard policy.
                    releaseGuard()
                    if (clockFailures == 0) AppLog.i("DESKTOP PANEL TIMER: activity clock unavailable; will retry", false)
                    nextProbe = now + minOf(30_000L, 2_000L shl clockFailures.coerceAtMost(4))
                    clockFailures = (clockFailures + 1).coerceAtMost(4)
                    if (clockOwner === this) clockOwner = null
                    owned = false
                    return@post
                }
                clockFailures = 0
                if (!owned) {
                    owned = true
                    clockOwner = this
                    AppLog.i("DESKTOP PANEL TIMER: observing physical display idle during USB session", false)
                }
                if (requestedForActivity == activity) {
                    nextProbe = now + 1_000
                    return@post
                }
                requestedForActivity = null
                val remaining = PhoneIdleDeadline.remaining(now, armedAt ?: now, activity, timeout) ?: return@post
                if (remaining > 0 || now - before > 1_500) {
                    nextProbe = now + maxOf(remaining, 250)
                } else if (!DexState.phoneScreenOff && !DexState.phoneScreenBusy) {
                    // Recheck the USB session and lock above, then use the same token-scoped panel command as the UI.
                    requestedForActivity = activity
                    nextProbe = now + 1_000
                    guard.acquire(5_000)
                    if (!requestOff()) releaseGuard()
                }
                refresh()
            }
        }
    }

    private fun releaseGuard() { if (guard.isHeld) guard.release() }
    private fun disarm() {
        if (armedAt != null) generation++
        armedAt = null
        requestedForActivity = null
        clockFailures = 0
        if (clockOwner === this) clockOwner = null
        owned = false
        releaseGuard()
    }

    companion object {
        @Volatile private var clockOwner: CarLifePhoneIdleTimer? = null
        val ownsPhysicalTimer: Boolean get() = clockOwner != null
    }
}
