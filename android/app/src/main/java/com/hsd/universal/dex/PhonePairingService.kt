package com.hsd.universal.dex

import com.hsd.universal.DesktopTransport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.hsd.universal.AppLog
import com.hsd.universal.BridgeService
import com.hsd.universal.BrandArtwork
import com.hsd.universal.R
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.Executors

/** The code is transient. A notification reply never carries an address, command or requested permission. */
internal object PairingReplyPolicy {
    fun code(value: CharSequence?): String? = value?.toString()?.filterNot(Char::isWhitespace)
        ?.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
    fun accepts(expected: String?, actual: String?, waiting: Boolean): Boolean =
        waiting && !expected.isNullOrEmpty() && expected == actual
}

internal enum class PairingNotificationBlock { APP, PROGRESS_CHANNEL, CODE_CHANNEL }

internal object PairingNotificationPolicy {
    /** A missing channel will be created by the service; an existing user-blocked channel stays blocked. */
    fun block(appAllowed: Boolean, progressImportance: Int?, codeImportance: Int?, needsCode: Boolean): PairingNotificationBlock? = when {
        !appAllowed -> PairingNotificationBlock.APP
        progressImportance == NotificationManager.IMPORTANCE_NONE -> PairingNotificationBlock.PROGRESS_CHANNEL
        needsCode && codeImportance == NotificationManager.IMPORTANCE_NONE -> PairingNotificationBlock.CODE_CHANNEL
        else -> null
    }
}

/** User-started foreground activation continues while Android's one-time pairing sheet stays open. */
class PhonePairingService : Service() {
    enum class Phase { IDLE, CHECKING, WAITING_CODE, PAIRING, PREPARING_RECOVERY, RECOVERING_DESKTOP, DISPATCHED, READY, FAILED, CANCELLED }
    data class Snapshot(val phase: Phase, val message: String, val attempt: String?, val inAppOnly: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val attemptLock = Any()
    // Closing a TLS/ADB stream may flush or wait for I/O. Never do it on the
    // notification/UI thread; the attempt gate invalidates work synchronously.
    private val cancellation = Executors.newSingleThreadExecutor { task ->
        Thread(task, "${com.hsd.universal.BuildConfig.BRAND}-pairing-cancel").apply { isDaemon = true }
    }
    private var discovery: DexDiscovery? = null
    private val savedRetry = SavedAdbRetryGate()
    private val pairingWindow = ActivePairingWindow()
    @Volatile private var pairedThisAttempt = false
    @Volatile private var connection: DexEndpoint? = null
    @Volatile private var cancelled = false
    @Volatile private var waitingCode = false
    @Volatile private var dispatched = false
    @Volatile private var destroyed = false
    @Volatile private var finished = false
    private var worker: Thread? = null
    private var replyToken: String? = null
    private var codeGate: PhonePairingAttemptGate? = null
    @Volatile private var pairingRequest: PairingRequest? = null
    @Volatile private var coordinator: DesktopActivationCoordinator.Request? = null
    private var inAppOnly = false
    private var resumingCommitted = false
    private var completionReceiptStarted = Long.MIN_VALUE
    private var attemptStarted = 0L
    private var postedPrompt: String? = null
    private var postedPromptMessage: String? = null
    private lateinit var notifications: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        notifications = getSystemService(NotificationManager::class.java)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                // START is sent with startForegroundService. A settings change can race the
                // caller's gate: acknowledge its FGS obligation even when rejecting it below.
                // REPLY/CANCEL use ordinary service PendingIntents and impose no such obligation.
                if (replyToken == null) {
                    PairingContinuationStore.clear(this)
                    attemptStarted = SystemClock.elapsedRealtime()
                    replyToken = UUID.randomUUID().toString()
                    codeGate = PhonePairingAttemptGate(checkNotNull(replyToken))
                }
                startForeground(PROGRESS_ID, progress(status.ifBlank { getString(R.string.desktop_preparing_activation) }), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                if (finished || destroyed) {
                    // A queued START may still reach this instance between finish and onDestroy.
                    // It needs an FGS acknowledgement but cannot reopen a terminal attempt.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (worker != null) return START_NOT_STICKY
                val prefs = DexPrefs(this)
                val forcePair = intent.getBooleanExtra(FORCE_PAIR, false)
                resumingCommitted = intent.getBooleanExtra(RESUME_COMMITTED, false)
                if (resumingCommitted && !DelegatedDesktopAccess.isReady(this)) {
                    finish(getString(R.string.desktop_access_click_activate_to_give_it_back, DesktopTransport.shortName))
                    return START_NOT_STICKY
                }
                val step = DesktopActivationCoordinator.currentStep(this)
                if (!prefs.useDelegated || ((BridgeService.alive || DexState.running) &&
                    (forcePair || step in setOf(DesktopReadinessPolicy.Step.ACQUIRE_GRANTS,
                        DesktopReadinessPolicy.Step.APPLY_MODE, DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED)))) {
                    finish(getString(R.string.desktop_stop_the_connection_before_changing_the_activation))
                    return START_NOT_STICKY
                }
                inAppOnly = intent.getBooleanExtra(IN_APP_ONLY, false)
                if (!inAppOnly && !DesktopActivationCoordinator.isReady(this) && notificationBlock(this, forcePair || !prefs.hasPaired) != null) {
                    finish(getString(R.string.desktop_activation_notifications_are_offline_and_select_the_input_in, DesktopTransport.shortName))
                    return START_NOT_STICKY
                }
                cancelled = false
                active = true
                completionReceiptStarted = PhoneActivationStatus.read(this).let {
                    if (it.activationBinding == PhoneCommittedActivationPolicy.Binding.CURRENT) it.startedElapsedMs else Long.MIN_VALUE
                }
                PhoneActivationStatus.clearFinished(this)
                publish(Phase.CHECKING, getString(R.string.desktop_checking_readiness, DesktopTransport.shortName))
                if (PhonePairingPromptPolicy.offer(DesktopActivationCoordinator.isReady(this), forcePair,
                        step == DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED, prefs.hasPaired)) {
                    showCode(null, optional = true)
                }
                worker = Thread({ activate(forcePair) }, "${com.hsd.universal.BuildConfig.BRAND}-desk-activation").also { it.start() }
            }
            REPLY -> {
                if (getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) return START_NOT_STICKY
                val token = intent.getStringExtra(TOKEN)
                val value = PairingReplyPolicy.code(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE))
                // Never log the code/token. Distinguish delivery from a later pairing failure.
                AppLog.i("PHONE notification reply received: validCode=${value != null} activeWorker=${worker != null}", false)
                when {
                    token != null && value != null && acceptCode(token, value) -> Unit
                    token != null && value != null && resumeReply(token, value) -> Unit
                    token != null && token == codeGate?.token() && value == null ->
                        showCode(getString(R.string.desktop_enter_the_6_digits_from_the_open_pairing_dialog), reopen = false)
                    worker == null || finished || cancelled || destroyed -> {
                        AppLog.i("PHONE notification reply expired: activation attempt no longer active", false)
                        notifications.cancel(CODE_ID)
                        notifyResult(this, if (DesktopActivationCoordinator.isReady(this))
                            getString(R.string.desktop_it_s_already_activated, DesktopTransport.shortName, DesktopTransport.name)
                        else getString(R.string.desktop_attempt_to_activate_interrupted_or_expired_and_press_activate, DesktopTransport.shortName))
                    }
                    else -> {
                        AppLog.i("PHONE notification reply ignored: stale prompt or code already in progress", false)
                        // A delayed old reply cannot alter the current attempt. Reposting its
                        // current notification acknowledges SystemUI's send spinner only.
                        notifications.activeNotifications.firstOrNull { it.id == CODE_ID }?.notification?.let {
                            notifications.notify(CODE_ID, it)
                        }
                    }
                }
                if (worker == null) stopSelf()
            }
            CANCEL -> {
                if (worker == null) stopSelf()
                else cancelAttempt(intent.getStringExtra(TOKEN))
            }
            else -> if (worker == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Only a fresh user reply can resume a process lost by Android; nothing restarts in the background. */
    private fun resumeReply(token: String, value: String): Boolean {
        if (worker != null || finished || cancelled || destroyed || !DexPrefs(this).useDelegated ||
            BridgeService.alive || DexState.running || PhoneActivationStatus.read(this).phase == PhoneActivationStatus.Phase.PENDING)
            return false
        val saved = PairingContinuationStore.consume(this, token) ?: return false
        if (DesktopActivationCoordinator.isReady(this)) return false
        replyToken = saved.attempt
        attemptStarted = saved.started
        codeGate = PhonePairingAttemptGate(saved.attempt, saved.prompt)
        check(codeGate?.offer(token, value) == true)
        try {
            startForeground(PROGRESS_ID, progress(getString(R.string.desktop_resuming_activation)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: RuntimeException) {
            codeGate?.cancel()
            notifyResult(this, getString(R.string.desktop_android_has_stopped_service_open_up_and_re_activate, DesktopTransport.shortName))
            return false
        }
        active = true
        inAppOnly = false
        waitingCode = false
        completionReceiptStarted = PhoneActivationStatus.read(this).let {
            if (it.activationBinding == PhoneCommittedActivationPolicy.Binding.CURRENT) it.startedElapsedMs else Long.MIN_VALUE
        }
        PhoneActivationStatus.clearFinished(this)
        publish(Phase.PAIRING, getString(R.string.desktop_code_accepted_continuing_pairing))
        notifications.notify(CODE_ID, codeNotification(status, allowReply = false))
        AppLog.i("PHONE notification reply resumed interrupted input", false)
        worker = Thread({ activate(false) }, "${com.hsd.universal.BuildConfig.BRAND}-desk-activation").also { it.start() }
        return true
    }

    private fun cancelAttempt(token: String?): Boolean {
        val (cancelledWorker, requests) = synchronized(attemptLock) {
            if (token == null || token != replyToken || dispatched || cancelled || finished || destroyed) return false
            cancelled = true
            codeGate?.cancel()
            PairingContinuationStore.clear(this)
            worker to arrayOf(pairingRequest, coordinator)
        }
        cancelledWorker?.interrupt()
        closeOffMain(*requests)
        return true
    }

    private fun closeOffMain(vararg requests: AutoCloseable?) {
        val captured = requests.filterNotNull().distinct()
        if (captured.isEmpty()) return
        synchronized(attemptLock) {
            // Destruction already queued the currently owned requests. A late
            // in-app reply must not submit work to an executor being shut down;
            // the worker's use/finally remains the final owner of each request.
            if (cancellation.isShutdown) return
            cancellation.execute { captured.forEach { runCatching { it.close() } } }
        }
    }

    private class PairingInputSubmitted : Exception()

    private fun activate(forcePair: Boolean) {
        var failure: String? = null
        try {
            val prefs = DexPrefs(this)
            val needs = DesktopReadinessPolicy.Requirements(prefs.usbPlaybackAudio, prefs.desktopPanelControl)
            if (forcePair) pairWithNotification()
            while (true) {
                checkActive()
                codeGate?.takeCode()?.let { pairWithNotification(initialCode = it) }
                try {
                    val result = DesktopActivationCoordinator.request(applicationContext, needs, explicit = true,
                        allowAcquireGrants = !resumingCommitted) {
                        !cancelled && !destroyed && !finished && codeGate?.hasCode() != true
                    }.use { request ->
                        coordinator = request
                        try {
                            request.ensureReady({ connection }, ::ensureDiscovery, { endpoint ->
                                savedRetry.markTried(listOf(endpoint), PhoneAdbEndpointRequest.environment(this))
                            }, { stage ->
                                when (stage) {
                                    DesktopActivationCoordinator.Stage.CHECKING -> update(getString(if (pairedThisAttempt)
                                        R.string.desktop_pairing_saved_connecting else R.string.desktop_checking_the_saved_access), Phase.CHECKING)
                                    DesktopActivationCoordinator.Stage.PREPARING_RECOVERY -> update(getString(R.string.desktop_preparing_recovery_without_wi_fi), Phase.PREPARING_RECOVERY)
                                    DesktopActivationCoordinator.Stage.RECOVERING_DESKTOP -> update(getString(R.string.desktop_preparing_the_desk), Phase.RECOVERING_DESKTOP)
                                    DesktopActivationCoordinator.Stage.DISPATCHED -> update(getString(R.string.desktop_enable_access_it_s_about_to_restart, DesktopTransport.shortName), Phase.DISPATCHED)
                                }
                            }, {
                                synchronized(attemptLock) {
                                    checkActive()
                                    if (codeGate?.complete() != true) throw PairingInputSubmitted()
                                    waitingCode = false
                                    dispatched = true
                                    PairingContinuationStore.clear(this)
                                }
                                postAttempt { notifications.cancel(CODE_ID) }
                            })
                        } finally { coordinator = null }
                    }
                    if (result == DesktopActivationCoordinator.Result.READY) {
                        val completed = synchronized(attemptLock) {
                            checkActive()
                            if (codeGate?.complete() != true) false else {
                                check(DesktopActivationCoordinator.isReady(this)) { getString(R.string.desktop_desktop_readiness_has_changed_reactivate) }
                                completeCommittedActivation(this, completionReceiptStarted)
                                true
                            }
                        }
                        if (!completed) continue
                        waitingCode = false
                        update(getString(R.string.desktop_ready_to_connect_0a85, DesktopTransport.shortName), Phase.READY)
                        return
                    }
                    val deadline = SystemClock.elapsedRealtime() + PhoneActivationPolicy.DEADLINE_MS
                    while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
                        when (val state = PhoneActivationStatus.read(this)) {
                            is PhoneActivationStatus.Snapshot -> when (state.phase) {
                                PhoneActivationStatus.Phase.READY -> {
                                    if (DesktopActivationCoordinator.isReady(this)) {
                                        synchronized(attemptLock) {
                                            checkActive()
                                            completeCommittedActivation(this, completionReceiptStarted)
                                        }
                                        update(getString(R.string.desktop_ready_to_connect_0a85, DesktopTransport.shortName), Phase.READY)
                                        return
                                    }
                                    throw DesktopPreparationException(getString(R.string.desktop_access_is_available_but_the_desktop_is_not_ready))
                                }
                                PhoneActivationStatus.Phase.FAILED -> throw DesktopPreparationException(state.message)
                                else -> Thread.sleep(250)
                            }
                        }
                    }
                    throw DesktopPreparationException(getString(R.string.desktop_the_launch_result_has_not_yet_been_confirmed, DesktopTransport.shortName))
                } catch (error: Exception) {
                    checkActive()
                    // Closing the current request only interrupts its probe. The same worker then pairs.
                    if (!dispatched && codeGate?.hasCode() == true) continue
                    when {
                        error is DesktopActivationCoordinator.PairingRequired -> pairWithNotification()
                        error is DexAccessException && !dispatched && PhonePairingPromptPolicy.canRetryWithCode(error.reason) ->
                            if (error.reason in setOf(DexAccessReason.AUTHORIZATION_REJECTED, DexAccessReason.PAIR_REJECTED))
                                pairWithNotification(getString(R.string.desktop_the_saved_key_is_not_accepted_open_a_new))
                            else waitForSavedConnectionOrCode()
                        error is DesktopPreparationException && error.canPair && !dispatched ->
                            waitForSavedConnectionOrCode()
                        else -> throw error
                    }
                }
            }
        } catch (_: InterruptedException) {
            synchronized(attemptLock) { cancelled = true }
        } catch (error: Exception) {
            AppLog.i("PHONE notification activation failed: ${DexAccessDiagnostics.safeTrace(error)}")
            failure = when (error) {
                is DexAccessException, is DesktopPreparationException -> error.message
                else -> getString(R.string.desktop_couldn_t_get_ready_check_wi_fi_and_wireless, DesktopTransport.shortName)
            }
        } finally {
            coordinator = null
            val attempt = replyToken
            main.post {
                if (!destroyed && replyToken == attempt) {
                    if (!cancelled || !dispatched) finish(failure) else stopSelf()
                }
            }
        }
    }

    private fun ensureDiscovery() {
        postAttempt { startDiscoveryOnMain() }
    }

    private fun startDiscoveryOnMain() {
        if (discovery != null || !PhoneAdbEndpointRequest.pairingDiscoveryAllowed(this)) return
        discovery = DexDiscovery(this) { p, c, _ ->
            if (!destroyed && !finished && !cancelled) {
                pairingWindow.advertised(p)
                connection = c
            }
        }.also { it.start() }
    }

    /** An unavailable listener is not evidence that Android revoked the saved key. */
    private fun waitForSavedConnectionOrCode() {
        val deadline = SystemClock.elapsedRealtime() + CODE_TIMEOUT_MS
        if (pairedThisAttempt) update(getString(R.string.desktop_pairing_saved_waiting_for_connection))
        else showCode(null, savedRetryAllowed = true)
        while (SystemClock.elapsedRealtime() < deadline) {
            checkActive()
            if (codeGate?.hasCode() == true) return
            ensureDiscovery()
            val environment = PhoneAdbEndpointRequest.environment(this)
            if (environment.first != false) {
                val live = VdmRecoveryEndpoints.advertised(connection, environment.first, DexAccess::acceptsHost)
                if (live.any { savedRetry.fresh(it, environment) } || savedRetry.fresh(PhoneAdbEndpointRequest.tlsEndpoint(), environment)) {
                    AppLog.i("PHONE saved pairing retry: fresh debugging endpoint or changed wireless state", false)
                    return
                }
            } else savedRetry.markTried(emptyList(), environment)
            Thread.sleep(100)
        }
        throw DesktopPreparationException(getString(R.string.desktop_wireless_debugging_didn_t_show_up_turn_it_on))
    }

    private fun pairWithNotification(initialProblem: String? = null, initialCode: String? = null) {
        pairedThisAttempt = false
        val deadline = SystemClock.elapsedRealtime() + CODE_TIMEOUT_MS
        var problem: String? = initialProblem
        var suppliedCode = initialCode
        while (true) {
            checkActive()
            if (SystemClock.elapsedRealtime() >= deadline) throw DesktopPreparationException(getString(R.string.desktop_the_code_entry_time_has_expired_reactivate))
            if (suppliedCode == null) {
                showCode(problem)
                while (suppliedCode == null && SystemClock.elapsedRealtime() < deadline) {
                    checkActive()
                    ensureDiscovery()
                    suppliedCode = codeGate?.takeCode()
                    if (suppliedCode == null) Thread.sleep(100)
                }
            }
            val code = suppliedCode ?: continue
            suppliedCode = null
            checkActive()
            try {
                update(getString(R.string.desktop_i_m_looking_for_an_open_pairing_window_for), Phase.PAIRING)
                val endpoint = awaitEndpoint(20_000, pairingWindow::take)
                if (endpoint == null) {
                    problem = getString(R.string.desktop_no_pairing_window_found_turn_on_wi_fi_and)
                    continue
                }
                PairingRequest().use { request ->
                    pairingRequest = request
                    try {
                        checkActive()
                        runBlocking { DexAccess.pair(applicationContext, endpoint.port, code, endpoint.host, request = request) }
                        checkActive()
                        pairedThisAttempt = true
                    } finally { pairingRequest = null }
                }
                postAttempt { notifications.cancel(CODE_ID) }
                return
            } catch (e: DexAccessException) {
                if (!PhonePairingPromptPolicy.canRetryWithCode(e.reason)) throw e
                problem = getString(R.string.desktop_open_a_new_code_window_and_repeat)
            } finally { codeGate?.pairingFinished() }
        }
    }

    private fun awaitEndpoint(timeoutMs: Long, value: () -> DexEndpoint?): DexEndpoint? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            checkActive()
            ensureDiscovery()
            value()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            Thread.sleep(100)
        }
    }

    private fun checkActive() { if (cancelled || destroyed || finished || currentInstance !== this || Thread.currentThread().isInterrupted) throw InterruptedException() }
    private fun postAttempt(action: () -> Unit) {
        val attempt = replyToken
        main.post {
            if (!destroyed && !finished && !cancelled && attempt != null && replyToken == attempt) action()
        }
    }
    private fun publish(value: Phase, message: String) {
        if (snapshot.phase != value) AppLog.i("PHONE activation phase=${value.name}", false)
        status = message
        snapshot = Snapshot(value, message, replyToken, inAppOnly)
    }
    private fun update(message: String, value: Phase = Phase.CHECKING) = postAttempt {
        publish(value, message)
        notifications.notify(PROGRESS_ID, progress(message))
    }
    private fun showCode(problem: String?, reopen: Boolean = true, optional: Boolean = false, savedRetryAllowed: Boolean = false) = postAttempt {
        if (optional && pairedThisAttempt) return@postAttempt
        val previous = codeGate?.token()
        synchronized(attemptLock) {
            if (reopen) codeGate?.open()
            waitingCode = codeGate?.token() != null
        }
        if (!waitingCode) return@postAttempt
        if (previous == null) AppLog.i("PHONE pairing input available: optional=$optional", false)
        // A saved key may be rejected after startup, when the code channel was not required.
        // Keep this attempt and its reply token; only switch the visible input surface.
        val blocked = notificationBlock(this, needsCode = true) != null
        if (blocked) inAppOnly = true
        val prompt = if (savedRetryAllowed)
            getString(R.string.desktop_the_saved_pairing_is_not_removed_turn_on_wi)
        else if (inAppOnly)
            getString(R.string.desktop_open_the_pairing_window_in_wireless_debugging_enter_6, DesktopTransport.shortName)
        else getString(R.string.desktop_open_the_pairing_window_in_wireless_debugging_enter_6_e26c, DesktopTransport.shortName)
        val message = listOfNotNull(problem, if (blocked) getString(R.string.desktop_the_code_entry_notification_is_disabled) else null, prompt).joinToString("\n")
        if (!optional) {
            publish(if (savedRetryAllowed) Phase.CHECKING else Phase.WAITING_CODE, message)
            notifications.notify(PROGRESS_ID, progress(status))
        }
        if (inAppOnly) notifications.cancel(CODE_ID) else {
            val token = codeGate?.token() ?: return@postAttempt
            val text = if (optional) getString(R.string.desktop_saved_access_is_verified_automatically_if_you_have_already) else message
            if (postedPrompt != token || postedPromptMessage != text) {
                PairingContinuationStore.save(this, checkNotNull(replyToken), token, attemptStarted)
                notifications.notify(CODE_ID, codeNotification(text))
                postedPrompt = token
                postedPromptMessage = text
            }
        }
    }
    private fun acceptCode(token: String, value: String): Boolean {
        val interrupted = synchronized(attemptLock) {
            if (cancelled || finished || destroyed || dispatched || !waitingCode || codeGate?.offer(token, value) != true) return false
            waitingCode = false
            PairingContinuationStore.clear(this)
            coordinator
        }
        // This request owns only verification/recovery. Never interrupt the committed bootstrap.
        AppLog.i("PHONE pairing code accepted for the current attempt", false)
        postAttempt { if (!inAppOnly) notifications.notify(CODE_ID, codeNotification(getString(R.string.desktop_code_accepted_preparing_pairing), allowReply = false)) }
        closeOffMain(interrupted)
        return true
    }
    private fun progress(text: String): Notification = Notification.Builder(this, PROGRESS_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_hsd)
        .setLargeIcon(BrandArtwork.bitmap(resources))
        .setContentTitle(getString(R.string.desktop_activate_6a11, DesktopTransport.appName))
        .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(openApp(this)).setOnlyAlertOnce(true).setOngoing(true)
        .setVisibility(Notification.VISIBILITY_SECRET)
        .apply { if (!dispatched) addAction(Notification.Action.Builder(null, getString(R.string.desktop_cancel), serviceAction(CANCEL, 2, false)).build()) }
        .build()

    private fun codeNotification(text: String, allowReply: Boolean = true): Notification {
        val promptToken = codeGate?.token()
        val settings = PendingIntent.getActivity(this, 8, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CODE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hsd)
            .setLargeIcon(BrandArtwork.bitmap(resources))
            .setContentTitle(getString(R.string.desktop_enter_the_pairing_code, DesktopTransport.shortName))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .apply {
                if (allowReply && promptToken != null) {
                    val reply = RemoteInput.Builder(CODE).setLabel(getString(R.string.desktop_6_digits_such_as_123456))
                        .setAllowFreeFormInput(true).build()
                    addAction(Notification.Action.Builder(Icon.createWithResource(this@PhonePairingService, android.R.drawable.ic_menu_edit),
                        getString(R.string.desktop_enter_6_digits), serviceAction(REPLY, 1, true, promptToken)).addRemoteInput(reply)
                        .setAllowGeneratedReplies(false).apply {
                            if (android.os.Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true)
                        }.build())
                }
            }.build()
    }
    private fun serviceAction(action: String, request: Int, mutable: Boolean, promptToken: String? = null) = PendingIntent.getService(this, request,
        Intent(this, PhonePairingService::class.java).setAction(action)
            // Extras are not part of PendingIntent identity. A new attempt must not update an old reply/cancel token.
            .setData(Uri.Builder().scheme("hsd").authority("activation")
                .appendPath(checkNotNull(if (action == REPLY) promptToken else replyToken)).appendPath(action).build())
            .putExtra(TOKEN, if (action == REPLY) promptToken else replyToken),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    private fun finish(failure: String?) {
        if (destroyed || finished) return
        finished = true
        PairingContinuationStore.clear(this)
        active = false
        val cancelledMessage = if (cancelled) when (IndependentRecoveryAccess.snapshot(this).kind) {
            IndependentRecoveryAccess.Kind.PENDING, IndependentRecoveryAccess.Kind.RESTORE_REQUIRED ->
                getString(R.string.desktop_preparation_stopped_debugging_setup_is_incomplete_in_activation_select)
            IndependentRecoveryAccess.Kind.OWNED, IndependentRecoveryAccess.Kind.REUSED ->
                getString(R.string.desktop_preparation_stopped_recovery_mode_without_wi_fi_saved_activation)
            IndependentRecoveryAccess.Kind.DISABLED -> getString(R.string.desktop_preparations_are_cancelled)
        } else null
        publish(if (cancelled) Phase.CANCELLED else if (failure != null) Phase.FAILED else Phase.READY,
            cancelledMessage ?: failure ?: getString(R.string.desktop_ready_to_connect_0a85, DesktopTransport.shortName))
        codeGate?.cancel()
        waitingCode = false
        notifications.cancel(CODE_ID)
        if (failure != null) notifyResult(this, failure)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (currentInstance === this) currentInstance = null
        destroyed = true
        finished = true
        synchronized(attemptLock) { cancelled = true }
        active = false
        waitingCode = false
        replyToken = null
        codeGate?.cancel()
        val requests = arrayOf(coordinator, pairingRequest)
        worker?.interrupt()
        closeOffMain(*requests)
        synchronized(attemptLock) { cancellation.shutdown() }
        discovery?.close()
        discovery = null
        notifications.cancel(CODE_ID)
        super.onDestroy()
    }

    companion object {
        @Volatile private var currentInstance: PhonePairingService? = null

        /** Same one-use reply capability as SystemUI; never persisted or logged. */
        internal fun replyAttempt(): String? = currentInstance?.let {
            if (it.waitingCode && !it.cancelled && !it.finished && !it.destroyed) it.codeGate?.token() else null
        }

        internal fun submitCode(context: Context, attempt: String, value: String): Boolean {
            val code = PairingReplyPolicy.code(value) ?: return false
            return currentInstance?.acceptCode(attempt, code) == true
        }

        internal fun cancel(context: Context, attempt: String): Boolean =
            currentInstance?.cancelAttempt(attempt) == true

        private const val START = "com.hsd.universal.activation.START"
        private const val REPLY = "com.hsd.universal.activation.REPLY"
        private const val CANCEL = "com.hsd.universal.activation.CANCEL"
        private const val FORCE_PAIR = "force_pair"
        private const val IN_APP_ONLY = "in_app_only"
        private const val RESUME_COMMITTED = "resume_committed"
        private const val TOKEN = "attempt"
        private const val CODE = "hsd_pairing_code"
        private const val PROGRESS_CHANNEL = "hsd_activation"
        private const val CODE_CHANNEL = "hsd_pairing"
        private const val PROGRESS_ID = 71
        private const val CODE_ID = 72
        private const val RESULT_ID = 73
        private const val CODE_TIMEOUT_MS = 5 * 60_000L
        @Volatile var active = false
            private set
        @Volatile var status = ""
            private set
        @Volatile var snapshot = Snapshot(Phase.IDLE, "", null)
            private set
        @Volatile private var resumedReceiptStarted = Long.MIN_VALUE
        @Volatile private var completedReceiptStarted = Long.MIN_VALUE
        val phase: Phase get() = snapshot.phase

        /** A matching phone bootstrap is one transaction: grants do not yet imply projection readiness. */
        fun blocksProjectionForCommittedActivation(context: Context): Boolean {
            if (!DexPrefs(context).useDelegated) return false
            val receipt = PhoneActivationStatus.read(context)
            return PhoneCommittedActivationPolicy.blocks(true,
                receipt.phase == PhoneActivationStatus.Phase.PENDING,
                receipt.phase == PhoneActivationStatus.Phase.READY,
                receipt.activationBinding, receipt.startedElapsedMs, completedReceiptStarted)
        }

        /** Called only by this attempt's worker after a current, live coordinator READY check. */
        private fun completeCommittedActivation(context: Context, expectedStarted: Long) {
            val receipt = PhoneActivationStatus.read(context)
            if (PhoneCommittedActivationPolicy.canComplete(
                    receipt.phase == PhoneActivationStatus.Phase.READY, receipt.activationBinding,
                    receipt.startedElapsedMs, expectedStarted, DesktopActivationCoordinator.isReady(context))) {
                completedReceiptStarted = receipt.startedElapsedMs
            }
        }

        /** Continue the exact phone activation after instrumentation replaced its old app process. */
        @Synchronized internal fun resumeCommittedActivation(context: Context): Boolean {
            if (!DexPrefs(context).useDelegated || !DelegatedDesktopAccess.isReady(context)) return false
            val receipt = PhoneActivationStatus.read(context)
            if (receipt.phase != PhoneActivationStatus.Phase.READY ||
                receipt.activationBinding != PhoneCommittedActivationPolicy.Binding.CURRENT ||
                completedReceiptStarted == receipt.startedElapsedMs) return false
            if (active) return true
            if (resumedReceiptStarted == receipt.startedElapsedMs) return false
            resumedReceiptStarted = receipt.startedElapsedMs // Failure/cancel never causes an implicit retry loop.
            return try {
                context.startForegroundService(Intent(context, PhonePairingService::class.java).setAction(START)
                    .putExtra(IN_APP_ONLY, true).putExtra(RESUME_COMMITTED, true)) != null
            } catch (_: Exception) {
                status = context.getString(R.string.desktop_couldn_t_continue_checking_the_desktop_click_activate)
                snapshot = Snapshot(Phase.FAILED, status, null, true)
                false
            }
        }
        fun start(context: Context, forcePair: Boolean = false, allowWithoutNotification: Boolean = false): android.content.ComponentName? {
            if (!allowWithoutNotification && !DesktopActivationCoordinator.isReady(context) && notificationBlock(context, forcePair || !DexPrefs(context).hasPaired) != null) {
                status = context.getString(R.string.desktop_activation_notifications_are_offline_and_select_manually_entering_or, DesktopTransport.shortName)
                return null
            }
            return context.startForegroundService(
                Intent(context, PhonePairingService::class.java).setAction(START).putExtra(FORCE_PAIR, forcePair)
                    .putExtra(IN_APP_ONLY, allowWithoutNotification))
        }
        internal fun notificationBlock(context: Context, needsCode: Boolean): PairingNotificationBlock? {
            val manager = context.getSystemService(NotificationManager::class.java)
            return PairingNotificationPolicy.block(manager.areNotificationsEnabled(),
                manager.getNotificationChannel(PROGRESS_CHANNEL)?.importance,
                manager.getNotificationChannel(CODE_CHANNEL)?.importance, needsCode)
        }
        internal fun notificationSettings(context: Context, block: PairingNotificationBlock): Intent =
            if (block == PairingNotificationBlock.APP)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, if (block == PairingNotificationBlock.CODE_CHANNEL) CODE_CHANNEL else PROGRESS_CHANNEL)
        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, context.getString(R.string.desktop_activate_6a11, DesktopTransport.shortName), NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CODE_CHANNEL, context.getString(R.string.desktop_enter_pairing_code), NotificationManager.IMPORTANCE_HIGH))
        }
        private fun openApp(context: Context) = PendingIntent.getActivity(context, 7,
            Intent(context, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        private fun notifyResult(context: Context, text: String) {
            createChannels(context)
            context.getSystemService(NotificationManager::class.java).notify(RESULT_ID, Notification.Builder(context, PROGRESS_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_hsd)
                .setLargeIcon(BrandArtwork.bitmap(context.resources)).setContentTitle("${DesktopTransport.appName}")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context)).setAutoCancel(true).build())
        }
        /** Called after the intentional process restart, only when access is actually confirmed. */
        fun onAccessReady(context: Context) {
            PairingContinuationStore.clear(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.activeNotifications.none { it.id in setOf(PROGRESS_ID, CODE_ID) }) return
            manager.cancel(PROGRESS_ID)
            manager.cancel(CODE_ID)
            notifyResult(context, context.getString(R.string.desktop_access_is_active_we_can_plug_in, DesktopTransport.name))
        }
    }
}
