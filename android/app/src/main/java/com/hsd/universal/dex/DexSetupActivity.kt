package com.hsd.universal.dex

import com.hsd.universal.R
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.DesktopTransport

import com.hsd.universal.link.ReceiverAudioCalibration
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.hsd.universal.AppLog
import com.hsd.universal.BuildConfig
import com.hsd.universal.BridgeService
import com.hsd.universal.aa.AaPrefs
import com.hsd.universal.aa.DesktopResolution
import com.hsd.universal.CarLifeState
import com.hsd.universal.component.DesktopComponentEntry
import com.hsd.universal.component.DesktopComponents
import com.hsd.universal.MainActivity
import com.hsd.universal.ui.Actions
import com.hsd.universal.link.car.CarLifeConnectionMode
import com.hsd.universal.link.car.WirelessPrefs
import com.hsd.universal.link.car.WirelessCarLife
import com.hsd.universal.link.car.WirelessSetupActivity
import com.hsd.universal.ui.LogShare
import com.hsd.universal.update.ComponentPackages
import com.hsd.universal.update.ComponentUpdatesActivity
import com.hsd.universal.update.DesktopComponent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku

/** USB desktop setup; local ADB activation is a separate, explicit action. */
open class DexSetupActivity : Activity() {
    private val projectionName: String get() = if (DesktopTransport.universal) ProjectionRoute.selected(this).protocol else DesktopTransport.name
    private lateinit var prefs: DexPrefs
    private val clusterPrefs by lazy { AaPrefs(this) }
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var installedVersions: TextView
    private var driveDeckInstalled = false
    private lateinit var installProfileButton: Button
    private var connectionModeChoice: Button? = null
    private var projectionRouteRow: DesktopSetupViews.NavigationRow? = null
    private lateinit var wirelessSetupButton: Button
    private var pairingPermissionAction: (() -> Unit)? = null
    private lateinit var initialSetupSection: LinearLayout
    private lateinit var activationStatus: TextView
    private lateinit var activationStatusHint: TextView
    private lateinit var activationProgress: ProgressBar
    private lateinit var initialSetupIntro: LinearLayout
    private lateinit var setupAccessButton: Button
    private lateinit var setupDoneButton: Button
    private var setupRequested = false
    private var phoneReplyAttempt: String? = null
    private lateinit var connectButton: Button
    private lateinit var connectButtonHint: TextView
    private lateinit var phoneActivationSection: LinearLayout
    private lateinit var computerActivationSection: LinearLayout
    private lateinit var computerActivationInstructions: TextView
    private lateinit var phoneActivationButton: Button
    private lateinit var cancelPhoneActivationButton: Button
    private lateinit var phonePairSection: LinearLayout
    private lateinit var phonePairCode: EditText
    private lateinit var phoneActivationHint: TextView
    private lateinit var activationRepairSection: LinearLayout
    private lateinit var activationRepairHint: TextView
    private lateinit var activationRepairButton: Button
    private var phoneLaunchCommitted = false
    private var endingComputerAccess = false
    private var restoringDebugMode = false
    private var phoneStatusSeen = ""
    private var connectionButtonState = DesktopConnectionButtonPolicy.State.IDLE
    private lateinit var phoneScreenButton: Button
    private lateinit var phoneScreenHint: TextView
    private lateinit var phoneCurtainButton: Button
    private lateinit var idleModeChoice: Button
    private lateinit var idleTimeoutChoice: Button
    private var idleModeHelp = ""
    private val phoneIdle by lazy {
        PhoneScreenIdleController(this,
            isEnabled = { when (prefs.phoneIdleMode) {
                PhoneIdleMode.CURTAIN -> prefs.desktopPanelControl
                PhoneIdleMode.PANEL -> !CarLifePhoneIdleTimer.ownsPhysicalTimer && prefs.desktopPanelControl &&
                    (prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.hasPermission(this))
                PhoneIdleMode.DISABLED -> false
            } },
            isConnected = { prefs.useDesktopService && !stopped && desktopIdleSessionReady() },
            prefersPhysicalOff = { prefs.phoneIdleMode == PhoneIdleMode.PANEL },
            requestPhysicalOff = {
                prefs.phoneIdleMode == PhoneIdleMode.PANEL && prefs.desktopPanelControl && desktopIdleSessionReady() &&
                    (prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.isAvailable(this)) && runCatching {
                    startService(DesktopTransport.service(this).setAction(BridgeService.ACTION_PHONE_SCREEN_OFF)) != null
                }.getOrDefault(false)
            },
            panelBusy = { DexState.phoneScreenBusy }, panelOff = { DexState.phoneScreenOff })
    }
    private lateinit var desktopChoice: Button
    private lateinit var desktopChoiceHint: TextView
    private var selectedPanel: DesktopComponentEntry? = null
    private val profilePackageChange = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (DesktopComponent.entries.any { it.pkg == intent.data?.schemeSpecificPart }) renderInstalledVersions()
            if (intent.data?.schemeSpecificPart == selectedPanel?.packageName) {
                selectedPanel = DesktopComponents.selected(this@DexSetupActivity)
                renderAccessControls()
            }
        }
    }
    private val desktopProfileName: String get() = selectedPanel?.takeIf { prefs.desktopMode == DesktopModePolicy.ANDROID_DESKTOP }
        ?.let(DesktopProfilePicker::name) ?: DesktopModePolicy.label(prefs.desktopMode)
    private val desktopName: String get() = if (prefs.useDesktopService) desktopProfileName else "DeX"
    private lateinit var setup: LinearLayout
    private lateinit var shizukuSetup: LinearLayout
    private lateinit var shizukuHelp: TextView
    private lateinit var wizardComputerAction: Button
    private lateinit var shizukuGrantButton: Button
    private lateinit var shizukuManagerButton: Button
    private val ui by lazy { DesktopSetupViews(this) }
    private lateinit var homePage: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var settingsNavigator: DesktopSettingsNavigator
    private lateinit var activationAccessRow: DesktopSetupViews.PermissionRow
    private lateinit var appPermissionList: LinearLayout
    private val wizardStep get() = this is DesktopWizardActivationActivity
    private var wizardProfileAvailable = false
    private lateinit var noticesHint: TextView
    private lateinit var autoMusicChoice: DesktopSetupViews.Toggle
    private lateinit var imageSettings: LinearLayout
    private lateinit var audioSettings: LinearLayout
    private lateinit var phoneSettings: LinearLayout
    private lateinit var phoneControlDetails: LinearLayout
    private lateinit var accessChoice: Button
    private lateinit var adbOptions: Button
    private var manualAdbOpen = false
    private lateinit var adbAdvanced: LinearLayout
    private lateinit var finishComputerAccess: Button
    private lateinit var accessHint: TextView
    private lateinit var accessChangeHint: TextView
    private lateinit var densityChoice: Button
    private lateinit var resolutionChoice: Button
    private lateinit var fpsChoice: Button
    private var videoTuningHelp = ""
    private lateinit var usbAudioChoice: DesktopSetupViews.Toggle
    private var usbAudioHelp = ""
    private lateinit var usbAudioStatus: TextView
    private lateinit var panelControlChoice: DesktopSetupViews.Toggle
    private lateinit var navigationChoice: DesktopSetupViews.Toggle
    private lateinit var streetNamesChoice: DesktopSetupViews.Toggle
    private var panelControlHelp = ""
    private lateinit var panelControlStatus: TextView
    private var densityHelp = ""
    private lateinit var dpiAppsButton: Button
    private val densityPresets = listOf(0, 128, 160, 200)
    private lateinit var code: EditText
    private lateinit var host: EditText
    private lateinit var pairPort: EditText
    private lateinit var connectPort: EditText
    private lateinit var detail: TextView
    private val inputViews = mutableListOf<View>()
    private val main = Handler(Looper.getMainLooper())
    private val flow = DexSetupFlow()
    private var discovery: DexDiscovery? = null
    private var discoveryToken = -1L
    private var discoveryDeadline: Runnable? = null
    private var permissionAction: (() -> Unit)? = null
    private var visible = false
    private var receiverRegistered = false
    private val stopped: Boolean get() = Actions.isUsbAutoStartStopped()
    private var observedStop = false
    private var armed = false
    private var monitorBridge = false
    private var screenBusy = false
    private var retryUsbPermission = false
    private var deferredConnect = false
    private var restartAt = 0L
    private var bridgeStartedAt = 0L
    private var lastUsbScanAt = 0L
    private var applyingFields = false
    private var hostEdited = false
    private var pairPortEdited = false
    private var connectPortEdited = false
    private var lastDiscovery = ""
    private var lastError = ""
    private var applyingAccessChoice = false
    private var shizukuPermissionToken: Long? = null
    private var shizukuListenersRegistered = false
    private var launchInitialSetup = false
    private var activationStopPending = false
    private var setupResumeScheduled = false

    companion object {
        private const val REQUEST_SETTINGS_NOTIFICATIONS = DesktopPermissionList.REQUEST

        const val EXTRA_SHOW_ACTIVATION = "com.hsd.universal.SHOW_ACTIVATION"
        const val EXTRA_COMPUTER_ACTIVATION = "com.hsd.universal.COMPUTER_ACTIVATION"
        const val EXTRA_SHOW_PERMISSIONS = "com.hsd.universal.SHOW_PERMISSIONS"
        const val EXTRA_RETURN_TO_SETUP = "com.hsd.universal.RETURN_TO_SETUP"
        private const val REQUEST_NETWORK = 2
        private const val REQUEST_PAIRING_NOTIFICATIONS = 21
        private const val REQUEST_SHIZUKU = 1005
        private const val DISCOVERY_TIMEOUT_MS = 12_000L
        // Survives Activity recreation: returning to the app cannot start a second ADB worker.
        private val workerBusy = AtomicBoolean(false)
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dex-setup").apply { isDaemon = true } }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!visible) return
            phoneIdle.refresh()
            if (activationStopPending || permissionAction != null) {
                renderAccessControls()
                main.postDelayed(this, 500)
                return
            }
            if (observePhoneActivation()) {
                main.postDelayed(this, 500)
                return
            }
            if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) {
                renderAccessControls()
                main.postDelayed(this, 500)
                return
            }
            if (ProjectionRoute.wireless(this@DexSetupActivity) && !ProjectionRoute.selected(this@DexSetupActivity).carLife) {
                continuePendingConnection()
                val route = ProjectionRoute.selected(this@DexSetupActivity)
                when {
                    desktopConnectionReady() -> showState(getString(R.string.desktop_connected, desktopName), route.label(this@DexSetupActivity) + ". " + DesktopAudioState.status(this@DexSetupActivity))
                    BridgeService.alive -> showState(route.label(this@DexSetupActivity), CarLifeState.status(this@DexSetupActivity), busy = true)
                    !DesktopAccess.isReadyForProjection(this@DexSetupActivity) -> showAccessSetup()
                    !deferredConnect -> {
                        armed = false
                        monitorBridge = false
                        val last = getSharedPreferences("projection", MODE_PRIVATE).getString("last." + route.name, null)
                        showState(route.label(this@DexSetupActivity), last ?: getString(R.string.desktop_tap_connect))
                    }
                }
                renderAccessControls()
                main.postDelayed(this, 500)
                return
            }
            if (!WirelessCarLife.preferUsb(this@DexSetupActivity)) {
                continuePendingConnection()
                when {
                    stopped -> {
                        observedStop = true
                        cancelContinuation()
                        showState(getString(R.string.desktop_stopped), getString(R.string.desktop_tap_connect_to_start_a_wireless_connection))
                    }
                    desktopConnectionReady() -> showState(getString(R.string.desktop_connected, desktopName), getString(R.string.desktop_projecting_to, projectionName, DesktopAudioState.status(this@DexSetupActivity)) + lockIsolationHint())
                    BridgeService.alive -> showState(getString(R.string.desktop_connecting_over_wi_fi), CarLifeState.status(this@DexSetupActivity), busy = true)
                    workerBusy.get() || permissionAction != null || shizukuPermissionToken != null ||
                        flow.phase in setOf(DexSetupFlow.Phase.FIND_PAIRING, DexSetupFlow.Phase.PAIRING,
                            DexSetupFlow.Phase.FIND_CONNECT, DexSetupFlow.Phase.PROBING, DexSetupFlow.Phase.ACTIVATING) -> Unit
                    !DesktopAccess.isReadyForProjection(this@DexSetupActivity) -> showAccessSetup()
                    else -> { armed = false; monitorBridge = false; showState(getString(R.string.desktop_ready_for_wireless, projectionName), CarLifeState.status(this@DexSetupActivity)) }
                }
                renderAccessControls()
                main.postDelayed(this, 500)
                return
            }
            Actions.observeUsbConnection(this@DexSetupActivity)
            if (stopped) {
                observedStop = true
                cancelContinuation()
                showState(getString(R.string.desktop_stopped), getString(R.string.desktop_reconnect_the_usb_cable_or_tap_connect))
                main.postDelayed(this, 500)
                return
            }
            if (WirelessPrefs(this@DexSetupActivity).mode == CarLifeConnectionMode.USB && Actions.isUsbDetachedIdle()) {
                showUsbDetached()
                renderAccessControls()
                main.postDelayed(this, 500)
                return
            }
            if (observedStop) {
                observedStop = false
                if (prefs.useDesktopService) beginConnect(explicit = false)
            }
            val now = SystemClock.elapsedRealtime()
            continuePendingConnection()
            if (!stopped && restartAt == 0L && (monitorBridge || BridgeService.alive)) {
                monitorBridge = true
                when {
                    DexState.error != null -> {
                        lastError = DexState.error.orEmpty()
                        armed = false
                        showState(getString(R.string.desktop_desktop_could_not_start), getString(R.string.desktop_tap_connect_to_retry_if_the_error_persists_send, lastError))
                    }
                    desktopConnectionReady() -> showState(getString(R.string.desktop_connected, desktopName),
                        getString(R.string.desktop_projecting_the_desktop_to, projectionName) + (if (prefs.usbPlaybackAudio)
                            DesktopAudioState.status(this@DexSetupActivity) else getString(R.string.desktop_audio_uses_the_phone_s_current_output)) + lockIsolationHint())
                    DexState.running && CarLifeState.running -> showState(getString(R.string.desktop_waiting_for, projectionName),
                        getString(R.string.desktop_the_head_unit_is_waiting_for_video_open_on, projectionName), busy = true)
                    Actions.lastUsbResult.state == Actions.UsbScanState.FAILED && !BridgeService.alive -> {
                        armed = false
                        lastError = Actions.lastUsbResult.detail.orEmpty()
                        showState(getString(R.string.desktop_usb_connection_failed), getString(R.string.desktop_reconnect_the_cable_and_tap_connect))
                    }
                    Actions.lastUsbResult.state == Actions.UsbScanState.WAITING_FOR_ACCESSORY -> {
                        showUsbResult(Actions.lastUsbResult)
                    }
                    bridgeStartedAt != 0L && now - bridgeStartedAt > 40_000 -> {
                        armed = false
                        showState(getString(R.string.desktop_no_desktop_picture), getString(R.string.desktop_tap_connect_to_retry_or_send_the_log))
                    }
                    BridgeService.alive -> showState(getString(R.string.desktop_starting, desktopName), getString(R.string.desktop_waiting_for_and_the_first_video_frame, projectionName), busy = true)
                }
            }
            // This only checks USB, never ADB. The USB gate deduplicates permission dialogs/starts.
            if (armed && !screenBusy && !CarLifeState.running && !monitorBridge && now - lastUsbScanAt >= 1_500) {
                scanUsb(retryPermission = false)
            }
            detail.updateText(buildString {
                if (lastDiscovery.isNotBlank()) append(lastDiscovery).append('\n')
                if (lastError.isNotBlank()) append(lastError).append('\n')
                append(getString(R.string.desktop_desktop)).append(DexState.displayStatus(this@DexSetupActivity))
                append('\n').append(DesktopTransport.name).append(": ").append(CarLifeState.status(this@DexSetupActivity))
            })
            renderAccessControls()
            main.postDelayed(this, 500)
        }
    }

    private val shizukuReceived = Shizuku.OnBinderReceivedListener {
        main.post {
            if (visible && !stopped && prefs.useShizuku && !screenBusy && !armed && !BridgeService.alive) {
                beginConnect(explicit = false)
            }
        }
    }
    private val shizukuDead = Shizuku.OnBinderDeadListener {
        main.post {
            if (!isDestroyed && prefs.useShizuku) {
                cancelContinuation()
                Actions.stopBridge(this, userInitiated = false)
                if (visible && !stopped) showShizukuSetup(DexPrivilegeAccess.status(this))
            }
        }
    }
    private val shizukuPermissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        main.post {
            val token = shizukuPermissionToken
            if (requestCode != REQUEST_SHIZUKU || token == null || token != flow.token ||
                !visible || isDestroyed || stopped || !prefs.useShizuku) return@post
            shizukuPermissionToken = null
            if (grantResult == PackageManager.PERMISSION_GRANTED && DexPrivilegeAccess.isReady(this)) {
                readyWithDesktop()
            } else {
                showShizukuSetup(DexPrivilegeAccess.status(this), denied = true)
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MainActivity.ACTION_PERMISSION -> Actions.handleUsbPermissionResult(context, intent)?.let {
                    if (visible && !stopped) showUsbResult(it)
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> if (visible) usbAttached()
            }
        }
    }

    // API 30–32 have no NOT_EXPORTED contract; USB grant callbacks also verify a nonce and UsbManager permission.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        if (savedInstanceState == null && intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            Actions.selectAttachedWiredRoute(this)
        prefs = DexPrefs(this)
        launchInitialSetup = savedInstanceState == null && prefs.initialSetupAutoPending &&
            !prefs.initialSetupCompleted && !intent.getBooleanExtra(EXTRA_SHOW_ACTIVATION, false) &&
            !intent.getBooleanExtra(EXTRA_SHOW_PERMISSIONS, false) &&
            !intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)
        prefs.enabled = true
        setupRequested = savedInstanceState?.getBoolean("setup_requested") == true || intent.getBooleanExtra(EXTRA_SHOW_ACTIVATION, false)
        manualAdbOpen = savedInstanceState?.getBoolean("manual_adb_open") == true
        buildView()
        openSettings(savedInstanceState?.getBoolean("settings_open") == true)
        settingsNavigator.restore(savedInstanceState)
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra(EXTRA_SHOW_PERMISSIONS, false)) openPermissionSettings()
            else if (setupRequested) openActivationSetup()
        }
        if (savedInstanceState != null && homePage.visibility == View.VISIBLE) {
            val y = savedInstanceState.getInt("page_scroll")
            homePage.post { homePage.scrollTo(0, y) }
        }
        if (intent.getBooleanExtra(EXTRA_COMPUTER_ACTIVATION, false)) homePage.post { showComputerActivationHelp() }
        Shizuku.addBinderReceivedListenerSticky(shizukuReceived)
        Shizuku.addBinderDeadListener(shizukuDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult)
        shizukuListenersRegistered = true
        val usbFilter = IntentFilter().apply {
            addAction(MainActivity.ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usbReceiver, usbFilter)
        receiverRegistered = true

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_ACTIVATION, false)) {
            setupRequested = true
            openActivationSetup()
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_PERMISSIONS, false)) openPermissionSettings()
        if (intent.getBooleanExtra(EXTRA_COMPUTER_ACTIVATION, false)) showComputerActivationHelp()
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED && visible) usbAttached()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(profilePackageChange, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
        visible = true
        if (launchInitialSetup) {
            launchInitialSetup = false
            startActivity(Intent(this, DesktopOnboardingActivity::class.java))
            return
        }
        Actions.observeUsbConnection(this)
        main.post(refresh)
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false) && !setupRequested) return
        if (observePhoneActivation(force = true)) return
        when {
            setupRequested && prefs.useDelegated && !DelegatedDesktopAccess.isReady(this) -> showDelegatedSetup()
            stopped -> showState(getString(R.string.desktop_stopped), getString(R.string.desktop_reconnect_the_usb_cable_or_tap_connect))
            !ProjectionRoute.wireless(this) && WirelessPrefs(this).mode == CarLifeConnectionMode.USB && Actions.isUsbDetachedIdle() -> showUsbDetached()
            BridgeService.alive && CarLifeState.running -> { armed = true; monitorBridge = true; bridgeStartedAt = SystemClock.elapsedRealtime() }
            prefs.useDesktopService -> beginConnect(explicit = false)
            workerBusy.get() -> {
                deferredConnect = true
                showState(getString(R.string.desktop_finishing_the_previous_check), getString(R.string.desktop_the_connection_will_continue_automatically), busy = true)
            }
            prefs.hasPaired -> beginConnect(explicit = false)
            else -> showSetup()
        }
    }

    override fun onStop() {
        unregisterReceiver(profilePackageChange)
        visible = false
        activationStopPending = false
        main.removeCallbacks(refresh)
        cancelContinuation()
        code.text.clear()
        phonePairCode.text.clear()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        selectedPanel = DesktopComponents.selected(this)
        renderInstalledVersions()
        if (wizardStep) wizardProfileAvailable = DesktopOnboardingActivity.profileAvailable(this, prefs)
        refreshPermissionList()
        renderAccessControls()
        navigationChoice.control.isChecked = clusterPrefs.metaNavi
        streetNamesChoice.control.isChecked = clusterPrefs.metaNaviStreetNames
        phoneIdle.onStart()
    }

    override fun onPause() {
        phoneIdle.onStop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::prefs.isInitialized) phoneIdle.onFocusChanged(hasFocus)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::prefs.isInitialized) phoneIdle.onUserInteraction()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val settingsOpen = settingsPage.visibility == View.VISIBLE
        outState.putBoolean("settings_open", settingsOpen)
        settingsNavigator.save(outState)
        outState.putInt("page_scroll", if (settingsOpen) settingsPage.scrollY else homePage.scrollY)
        outState.putBoolean("manual_adb_open", manualAdbOpen)
        outState.putBoolean("setup_requested", setupRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closeDiscovery()
        if (shizukuListenersRegistered) {
            Shizuku.removeBinderReceivedListener(shizukuReceived)
            Shizuku.removeBinderDeadListener(shizukuDead)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult)
        }
        if (receiverRegistered) unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun buildView() {
        val frame = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        window.setDecorFitsSystemWindows(false)
        frame.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        fun page(content: LinearLayout): ScrollView = ScrollView(this).apply {
            isFillViewport = true
            content.setPadding(dp(16), dp(12), dp(16), dp(20))
            content.isFocusableInTouchMode = true
            addView(content)
            frame.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        val home = ui.column()
        val settings = ui.column()
        homePage = page(home)
        settingsPage = page(settings)
        setContentView(frame)

        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            home.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.brand(header, 48)
        ui.text(header, "${DesktopTransport.appName}", 23f, true).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        ui.button(header, getString(R.string.home_settings)) { openSettings(true) }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        installedVersions = ui.text(home, "", 12f)
        renderInstalledVersions()
        initialSetupSection = ui.section(home)
        ui.text(initialSetupSection, getString(R.string.desktop_initial_setup), 18f, true)
        initialSetupIntro = ui.group(initialSetupSection)
        ui.text(initialSetupIntro, getString(R.string.desktop_set_up_drivedeck_grant_permissions_and_activate_hsd_before), 14f)
        ui.button(initialSetupIntro, getString(R.string.desktop_set_up_the_app)) {
            startActivity(Intent(this, DesktopOnboardingActivity::class.java))
        }
        settingsNavigator = DesktopSettingsNavigator(ui, settingsPage, settings) {
            if (wizardStep) returnToWizardPermissions() else if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) finish() else openSettings(false)
        }
        val activation = settingsNavigator.subpage("activation", getString(R.string.desktop_activation), "")
        if (wizardStep) ui.text(activation, getString(R.string.desktop_initial_setup_3_of_3), 14f)
        activationStatus = ui.text(activation, "", 20f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        activationStatusHint = ui.text(activation, "", 14f)
        activationProgress = ProgressBar(this).apply {
            visibility = View.GONE
            activation.addView(this, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        val connection = ui.section(home, getString(R.string.desktop_connection))
        title = ui.text(connection, getString(R.string.desktop_ready_to_set_up), 22f, true)
        hint = ui.text(connection, "", 15f)
        accessHint = ui.text(connection, "", 13f)
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            connection.addView(this, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        connectButton = ui.button(connection, getString(R.string.desktop_connect)) {
            // Honor the action the user saw, even if USB disconnected since the last refresh.
            if (connectionButtonState.stopsSession) stop() else beginConnect(explicit = true)
        }
        connectButtonHint = ui.text(connection, getString(R.string.desktop_press_again_to_cancel_the_connection), 13f).apply { visibility = View.GONE }
        phoneScreenButton = ui.button(connection, getString(R.string.desktop_turn_off_the_phone_display_panel)) { togglePhoneScreen() }
        phoneCurtainButton = ui.button(connection, getString(R.string.desktop_turn_off_the_phone_screen)) {
            phoneIdle.showNow(manualCurtain = true)
        }
        phoneScreenHint = ui.text(connection, "", 13f)
        setupAccessButton = ui.button(connection, getString(R.string.desktop_activate)) { openActivationSetup() }

        val profile = ui.section(home, getString(R.string.desktop_profile))
        desktopChoice = ui.button(profile, getString(R.string.desktop_profile_6aef, desktopProfileName)) {
            if (!accessChoiceLocked()) DesktopProfilePicker.show(this, prefs.desktopMode, { !accessChoiceLocked() }, ::selectDesktopProfile)
        }.apply { setTextColor(Color.WHITE) }
        desktopChoiceHint = ui.text(profile, "", 13f)
        installProfileButton = ui.button(profile, getString(R.string.desktop_install_drivedeck, BuildConfig.BRAND)) {
            startActivity(Intent(this, ComponentUpdatesActivity::class.java))
        }
        val transport = ui.section(home, if (DesktopTransport.universal) null else getString(R.string.desktop_connection_type))
        if (DesktopTransport.universal) {
            projectionRouteRow = ui.navigation(transport, getString(R.string.desktop_connection_type), ProjectionRoute.selected(this).label(this)) {
                startActivity(Intent().setClassName(this, "com.hsd.universal.ProjectionSettingsActivity"))
            }
        } else {
            connectionModeChoice = ui.button(transport, "") {
                if (!accessChoiceLocked()) {
                    val wireless = WirelessPrefs(this)
                    val choices = CarLifeConnectionMode.entries.toTypedArray()
                    AlertDialog.Builder(this).setTitle(getString(R.string.desktop_connection_type))
                        .setSingleChoiceItems(arrayOf("USB", "Wi-Fi", getString(R.string.desktop_automatic)), choices.indexOf(wireless.mode)) { dialog, index ->
                            dialog.dismiss()
                            if (!accessChoiceLocked()) wireless.mode = choices[index]
                            renderAccessControls()
                        }.setNegativeButton(getString(R.string.mock_cancel), null).show()
                }
            }
        }
        wirelessSetupButton = ui.button(transport, getString(R.string.desktop_set_up_wireless_head_unit)) {
            startActivity(Intent(this, WirelessSetupActivity::class.java))
        }
        if (!DesktopTransport.universal) ui.text(transport, getString(R.string.desktop_automatic_uses_usb_when_a_cable_is_connected_then), 13f)
        accessChoice = ui.button(activation, "") { chooseAccessMode() }
        accessChangeHint = ui.text(activation, "", 13f)
        phoneActivationSection = ui.group(activation)
        ui.text(phoneActivationSection,
            getString(R.string.desktop_connect_your_phone_to_wi_fi_and_enable_wireless, DesktopTransport.shortName), 14f)
        phoneActivationHint = ui.text(phoneActivationSection, "", 14f)
        phonePairSection = ui.group(phoneActivationSection).apply { visibility = View.GONE }
        ui.text(phonePairSection, getString(R.string.desktop_the_code_shows_android_wireless_debugging_connect_device_with, DesktopTransport.shortName), 13f)
        phonePairCode = field(phonePairSection, getString(R.string.desktop_6_digit_code), "", 1005, secret = true, legacyBusyControl = false)
        phoneActivationButton = ui.button(phoneActivationSection, getString(R.string.desktop_activate)) {
            if (PhonePairingService.replyAttempt() != null) beginPhonePair() else beginPhoneActivation()
        }
        cancelPhoneActivationButton = ui.button(phoneActivationSection, getString(R.string.desktop_cancel_activation)) {
            PhonePairingService.snapshot.attempt?.let { PhonePairingService.cancel(this, it) }
            renderAccessControls()
        }
        computerActivationSection = ui.group(activation)
        computerActivationInstructions = ui.text(computerActivationSection, "", 14f).apply { setTextIsSelectable(true) }
        ui.text(computerActivationSection,
            getString(R.string.desktop_commands_give_access_to_the_app_on_android_17, if (DesktopTransport.universal) getString(R.string.desktop_head_unit) else "USB", DesktopTransport.shortName), 13f)
        if (wizardStep) {
            ui.text(computerActivationSection, getString(R.string.desktop_the_button_below_copies_the_commands_for_the_computer), 13f)
            wizardComputerAction = ui.button(computerActivationSection, getString(R.string.desktop_activate)) {
                val guide = DesktopActivationGuide.instructions(this, prefs.usbPlaybackAudio, prefs.desktopPanelControl)
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.desktop_activate_6a11, DesktopTransport.appName), guide))
                android.widget.Toast.makeText(this, getString(R.string.desktop_activation_instructions_copied), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        ui.button(computerActivationSection, getString(R.string.desktop_download_android_platform_tools)) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DesktopActivationGuide.PLATFORM_TOOLS_URL))) }
                .onFailure { android.widget.Toast.makeText(this, getString(R.string.desktop_open_developer_android_com_tools_releases_platform_tools), android.widget.Toast.LENGTH_LONG).show() }
        }
        shizukuSetup = ui.group(activation).apply { visibility = View.GONE }

        shizukuHelp = ui.text(shizukuSetup, getString(R.string.desktop_start_shizuku_and_grant_access_to_while_shizuku_is, DesktopTransport.appName, projectionName))
        shizukuManagerButton = ui.button(shizukuSetup, getString(R.string.desktop_open_shizuku)) { openShizuku() }
        shizukuGrantButton = ui.button(shizukuSetup, getString(R.string.desktop_allow_shizuku_access)) { beginShizuku(explicit = true) }

        setup = ui.group(activation)
        ui.text(setup, getString(R.string.desktop_pair_dex_over_adb), 16f, true)
        ui.text(setup, getString(R.string.desktop_turn_on_wi_fi_and_wireless_debugging_open_the, DesktopTransport.appName))
        ui.text(setup, getString(R.string.desktop_keep_the_pairing_code_visible_until_pairing_finishes_enter), 14f, true)
        ui.button(setup, getString(R.string.desktop_open_debugging_settings)) { withNetworkPermission { openDebugSettings() } }
        code = field(setup, getString(R.string.desktop_6_digit_code), "", 1003, secret = true)
        ui.button(setup, getString(R.string.desktop_pair_and_connect)) { beginPair(manual = false) }.also(inputViews::add)

        adbOptions = ui.button(activation, getString(R.string.desktop_enter_address_and_ports_manually)) {
            manualAdbOpen = !manualAdbOpen
            renderAccessControls()
        }
        adbAdvanced = ui.group(activation)
        ui.text(adbAdvanced, getString(R.string.desktop_normally_these_fields_are_not_needed_you_can_manually), 13f)
        host = field(adbAdvanced, getString(R.string.desktop_this_phone_s_ip_address), prefs.host, 1004, address = true)
        pairPort = field(adbAdvanced, getString(R.string.desktop_port_in_the_pairing_dialog), "", 1002).apply { isSaveEnabled = false }
        connectPort = field(adbAdvanced, getString(R.string.desktop_port_on_the_wireless_debugging_screen), prefs.connectPort.takeIf { it > 0 }?.toString().orEmpty(), 1001)
        ui.button(adbAdvanced, getString(R.string.desktop_check_manually)) {
            val endpoint = manualEndpoint(connectPort) ?: return@button
            withNetworkPermission(endpoint.host) {
                if (!canBeginWork()) return@withNetworkPermission
                prepareAttempt(explicit = true)
                execute(flow.beginConnect(manual = endpoint))
            }
        }.also(inputViews::add)
        ui.button(adbAdvanced, getString(R.string.desktop_pair_using_port)) {
            setup.visibility = View.VISIBLE
            beginPair(manual = true)
        }.also(inputViews::add)
        ui.button(adbAdvanced, getString(R.string.desktop_reconfigure_the_pairing)) {
            cancelContinuation()
            showSetup()
        }.also(inputViews::add)
        ui.button(adbAdvanced, getString(R.string.desktop_open_debugging_settings)) { withNetworkPermission { openDebugSettings() } }

        setupDoneButton = ui.button(activation, getString(R.string.desktop_home)) {
            if (wizardStep) {
                wizardProfileAvailable = DesktopOnboardingActivity.profileAvailable(this, prefs)
                if (wizardCanFinish()) {
                    prefs.initialSetupCompleted = true
                    prefs.initialSetupAutoPending = false
                    prefs.initialSetupInProgress = false
                    finish()
                } else renderAccessControls()
                return@button
            }
            if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) {
                finish()
                return@button
            }
            setupRequested = false
            openSettings(false)
            renderAccessControls()
        }
        if (wizardStep) ui.button(activation, getString(R.string.desktop_close_setup)) {
            prefs.initialSetupInProgress = false
            finish()
        }
        finishComputerAccess = ui.button(activation, getString(R.string.desktop_complete_access_and_close, DesktopTransport.shortName)) {
            finishActivationForBackendChange()
        }
        activationRepairSection = ui.section(activation, getString(R.string.desktop_activation_setup_incomplete)).apply { visibility = View.GONE }
        activationRepairHint = ui.text(activationRepairSection, "")
        activationRepairButton = ui.button(activationRepairSection, getString(R.string.desktop_fix_the_activation_setting)) { confirmRestoreDebugMode() }
        detail = ui.text(adbAdvanced, "", 12f).apply { setTextIsSelectable(true) }
        watch(host) { hostEdited = true }
        watch(pairPort) { pairPortEdited = true }
        watch(connectPort) { connectPortEdited = true }
        setup.visibility = if (prefs.useDesktopService || prefs.hasPaired) View.GONE else View.VISIBLE

        buildDisplaySettings()
        buildOutputSettings()
        buildPermissionSettings()
        val components = settingsNavigator.block(getString(R.string.desktop_components, DesktopTransport.appName))
        ui.button(components, getString(R.string.desktop_install_and_update_components)) {
            startActivity(Intent(this, com.hsd.universal.update.ComponentUpdatesActivity::class.java))
        }
        val maintenance = settingsNavigator.block(getString(R.string.desktop_service))
        val languages = listOf(R.string.lang_system, R.string.lang_en, R.string.lang_ru).map(::getString)
        val selectedLanguage = com.hsd.universal.AppLocale.get(this)
        ui.navigation(maintenance, getString(R.string.lang_title), languages[com.hsd.universal.AppLocale.TAGS.indexOf(selectedLanguage)]) {
            AlertDialog.Builder(this).setTitle(R.string.lang_title).setSingleChoiceItems(
                languages.toTypedArray(), com.hsd.universal.AppLocale.TAGS.indexOf(selectedLanguage)
            ) { dialog, position ->
                dialog.dismiss()
                com.hsd.universal.AppLocale.set(this, com.hsd.universal.AppLocale.TAGS[position])
            }.show()
        }
        ui.button(maintenance, getString(R.string.desktop_repeat_the_original_setup)) {
            prefs.initialSetupInProgress = false
            setupResumeScheduled = false
            startActivity(Intent(this, DesktopOnboardingActivity::class.java))
        }
        ui.button(maintenance, getString(R.string.desktop_share_log)) { LogShare.shareLog(this) }
        renderAccessControls()
    }

    private fun buildDisplaySettings() {
        imageSettings = settingsNavigator.block(getString(R.string.desktop_video_settings))
        resolutionChoice = ui.button(imageSettings, "") {
            if (!accessChoiceLocked()) {
                val values = DesktopResolution.choices(DesktopTransport.universal)
                android.app.AlertDialog.Builder(this).setTitle(getString(R.string.desktop_head_unit_resolution))
                    .setSingleChoiceItems(values.map { it.displayTitle(this) }.toTypedArray(), values.indexOf(prefs.desktopResolution)) { dialog, index ->
                        dialog.dismiss()
                        if (!accessChoiceLocked()) {
                            if (values[index] == DesktopResolution.CUSTOM) showCustomResolution()
                            else prefs.desktopResolution = values[index]
                        }
                        renderAccessControls()
                    }.setNegativeButton(getString(R.string.mock_cancel), null).show()
            }
        }
        densityChoice = ui.button(imageSettings, "") {
            if (!accessChoiceLocked()) {
                val densityValues = (densityPresets + prefs.desktopDensity).distinct()
                android.app.AlertDialog.Builder(this).setTitle(getString(R.string.desktop_head_unit_interface_size))
                    .setSingleChoiceItems((densityValues.map { if (it == 0) getString(R.string.desktop_automatic_for_the_selected_resolution) else "$it DPI" } + getString(R.string.desktop_custom_size)).toTypedArray(),
                        densityValues.indexOf(prefs.desktopDensity)) { dialog, index ->
                        dialog.dismiss()
                        if (!accessChoiceLocked()) {
                            if (index == densityValues.size) showCustomDensity()
                            else prefs.desktopDensity = densityValues[index]
                        }
                        renderAccessControls()
                    }.setNegativeButton(getString(R.string.mock_cancel), null).show()
            }
        }
        fpsChoice = ui.button(imageSettings, "") {
            if (!accessChoiceLocked()) {
                val values = (listOf(1, 2, 4, 6, 8, 12, 16, 20) + prefs.desktopBitrateMbps).distinct().sorted()
                AlertDialog.Builder(this).setTitle(getString(R.string.desktop_video_bitrate))
                    .setSingleChoiceItems((values.map { getString(R.string.desktop_mbit_s, it) } + getString(R.string.desktop_custom_bitrate)).toTypedArray(),
                        values.indexOf(prefs.desktopBitrateMbps)) { dialog, index ->
                        dialog.dismiss()
                        if (!accessChoiceLocked()) {
                            if (index == values.size) showCustomBitrate()
                            else prefs.desktopBitrateMbps = values[index]
                        }
                        renderAccessControls()
                    }.setNegativeButton(getString(R.string.mock_cancel), null).show()
            }
        }
        ui.help(resolutionChoice, getString(R.string.desktop_head_unit_resolution)) { densityHelp }
        ui.help(densityChoice, getString(R.string.desktop_head_unit_interface_size)) {
            getString(R.string.desktop_the_dpi_you_select_sets_the_size_of_the, densityHelp)
        }
        ui.help(fpsChoice, getString(R.string.desktop_video_bitrate)) { videoTuningHelp }
        dpiAppsButton = ui.button(imageSettings, getString(R.string.desktop_adjust_app_dpi)) {
            startActivity(Intent(this, DpiCorrectionActivity::class.java))
        }
        ui.help(dpiAppsButton, getString(R.string.desktop_adjust_app_dpi)) {
            getString(R.string.desktop_select_apps_that_require_a_separate_scaling_correction_it)
        }
    }

    /** Local installed package versions only; never called from the periodic status renderer. */
    private fun renderInstalledVersions() {
        driveDeckInstalled = runCatching { ComponentPackages.trustedInstalled(this, DesktopComponent.DRIVEDECK) != null }.getOrDefault(false)
        val components = DesktopComponent.entries.mapNotNull { spec ->
            val info = try { ComponentPackages.installed(this, spec) }
                catch (_: SecurityException) { null }
            info?.let {
                val version = it.versionName?.takeIf(String::isNotBlank)?.let { name -> "$name (${it.longVersionCode})" }
                    ?: getString(R.string.desktop_build, it.longVersionCode)
                "${spec.title.substringBefore(" · ")} $version"
            }
        }
        installedVersions.updateText((listOf(getString(R.string.desktop_version, BuildConfig.VERSION_NAME)) + components).joinToString("  ·  "))
    }

    private fun buildOutputSettings() {
        audioSettings = settingsNavigator.block(getString(R.string.home_stage_audio))
        usbAudioChoice = ui.toggle(audioSettings, getString(R.string.desktop_send_audio_over_usb, projectionName), prefs.usbPlaybackAudio) { checked ->
            if (!applyingAccessChoice && !accessChoiceLocked() && prefs.useDelegated) {
                prefs.usbPlaybackAudio = checked
                renderAccessControls()
            }
        }
        ui.help(usbAudioChoice.row, getString(R.string.desktop_send_audio_over_usb, projectionName)) { usbAudioHelp }
        usbAudioStatus = ui.text(audioSettings, "", 13f)
        ui.button(audioSettings, getString(R.string.desktop_audio_sync)) { showReceiverAudioTiming() }
        autoMusicChoice = ui.toggle(audioSettings, getString(R.string.desktop_resume_music_automatically_on_connection), prefs.autoMusicPlayback) {
            prefs.autoMusicPlayback = it
        }
        ui.help(autoMusicChoice.row, getString(R.string.desktop_resume_music_automatically_on_connection)) {
            getString(R.string.desktop_access_mode_the_music_continues_after_the_image_and, DesktopTransport.shortName)
        }
        phoneSettings = settingsNavigator.subpage("phone-display", getString(R.string.desktop_phone_screen_control), "")
        ui.text(phoneSettings, getString(R.string.desktop_when_the_control_is_turned_off_the_phone_controls, DesktopTransport.shortName, projectionName))
        panelControlChoice = ui.toggle(phoneSettings, getString(R.string.desktop_managing_the_phone_display), prefs.desktopPanelControl) { checked ->
            if (!applyingAccessChoice && !accessChoiceLocked()) {
                prefs.desktopPanelControl = checked
                phoneIdle.refresh()
                renderAccessControls()
            }
        }
        ui.help(panelControlChoice.row, getString(R.string.desktop_managing_the_phone_display)) { panelControlHelp }
        phoneControlDetails = ui.group(phoneSettings)
        panelControlStatus = ui.text(phoneControlDetails, "", 13f)
        idleModeChoice = ui.button(phoneControlDetails, getString(R.string.desktop_how_to_turn_off_the_screen)) {
            val modes = listOf(PhoneIdleMode.PANEL, PhoneIdleMode.CURTAIN)
            android.app.AlertDialog.Builder(this).setTitle(getString(R.string.desktop_how_to_turn_off_the_phone_screen))
                .setSingleChoiceItems(modes.map { getString(it.labelRes) }.toTypedArray(), modes.indexOf(prefs.phoneScreenMode)) { dialog, index ->
                    prefs.phoneScreenMode = modes[index]
                    dialog.dismiss()
                    phoneIdle.refresh()
                    renderAccessControls()
                }.setNegativeButton(getString(R.string.mock_cancel), null).show()
        }
        ui.help(idleModeChoice, getString(R.string.desktop_how_to_turn_off_the_phone_screen)) { idleModeHelp }
        idleTimeoutChoice = ui.button(phoneControlDetails, getString(R.string.desktop_turn_screen_off_after)) {
            val choices = PhoneIdleTimeout.entries
            android.app.AlertDialog.Builder(this).setTitle(getString(R.string.desktop_extinguish_the_screen_when_connecting_to, projectionName))
                .setSingleChoiceItems((choices.map { getString(it.labelRes) } + getString(R.string.desktop_no_timer)).toTypedArray(),
                    if (prefs.phoneTimerEnabled) prefs.phoneIdleTimeout.ordinal else choices.size) { dialog, index ->
                    prefs.phoneTimerEnabled = index < choices.size
                    if (index < choices.size) prefs.phoneIdleTimeout = choices[index]
                    dialog.dismiss()
                    phoneIdle.refresh()
                    renderPhoneScreenControl()
                }.setNegativeButton(getString(R.string.mock_cancel), null).show()
        }
        ui.help(idleTimeoutChoice, getString(R.string.desktop_phone_screen_timer)) {
            getString(R.string.desktop_select_the_time_of_inactivity_when_connecting_to_the, projectionName, idleModeHelp)
        }
        val notices = settingsNavigator.block(getString(R.string.desktop_head_unit_notifications))
        val shade = ui.toggle(notices, getString(R.string.desktop_show_notification_shade), false) {}
        shade.setEnabled(false)
        ui.help(shade.row, getString(R.string.desktop_show_notification_shade)) {
            getString(R.string.desktop_the_curtain_is_temporarily_disabled_the_interface_will_be)
        }
        noticesHint = ui.text(notices, getString(R.string.desktop_temporarily_disabled), 13f)
        val hud = settingsNavigator.block(getString(R.string.desktop_instrument_cluster_and_hud))
        ui.help(hud.getChildAt(0), getString(R.string.desktop_instrument_cluster_and_hud)) {
            getString(R.string.desktop_the_title_of_the_track_and_the_available_cover)
        }
        navigationChoice = ui.toggle(hud, getString(R.string.desktop_turn_guidance_on_the_instrument_cluster_and_hud), clusterPrefs.metaNavi) {
            clusterPrefs.metaNavi = it
            renderSettingsOverview()
        }
        ui.help(navigationChoice.row, getString(R.string.desktop_turn_guidance_on_the_instrument_cluster_and_hud)) {
            getString(R.string.desktop_permission_access_notifications_and_start_routing_on_android_16, DesktopTransport.shortName, DesktopTransport.shortName)
        }
        streetNamesChoice = ui.toggle(hud, getString(R.string.desktop_street_names_for_turns), clusterPrefs.metaNaviStreetNames) {
            clusterPrefs.metaNaviStreetNames = it
        }
        ui.help(streetNamesChoice.row, getString(R.string.desktop_street_names_for_turns)) {
            getString(R.string.desktop_hsd_sends_the_next_street_name_with_the_turn)
        }
        val appNotifications = ui.button(hud, getString(R.string.desktop_setup_of_application_notifications)) {
            openNotificationSettings(Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS),
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
                getString(R.string.desktop_open_the_app_notification_settings_on_your_phone_and))
        }
        ui.help(appNotifications, getString(R.string.desktop_setup_of_application_notifications)) {
            getString(R.string.desktop_opens_the_android_system_settings_allow_the_navigator_to, DesktopTransport.shortName)
        }

    }

    private fun showReceiverAudioTiming() {
        val selected = ReceiverAudioCalibration.selected(this)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.desktop_audio_sync))
        if (selected == null) {
            dialog.setMessage(getString(R.string.desktop_synchronization_works_automatically_here_you_can_make_an_adjustment, projectionName))
                .setPositiveButton(getString(R.string.apps_warn_ok), null).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            setSingleLine(); setText(selected.ms.toString()); selectAll()
            contentDescription = getString(R.string.desktop_additional_audio_delay_in_milliseconds)
        }
        val alert = dialog.setMessage(getString(R.string.desktop_synchronizes_the_streams_automatically_if_the_sound_is_from, selected.name, DesktopTransport.shortName, projectionName))
            .setView(input).setNegativeButton(getString(R.string.mock_cancel), null).setPositiveButton(getString(R.string.desktop_save), null).create()
        alert.setOnShowListener {
            alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ms = input.text.toString().toIntOrNull()
                if (ms == null || ms !in 0..ReceiverAudioCalibration.MAX_MS) input.error = getString(R.string.desktop_enter_0_to_2000_ms)
                else { ReceiverAudioCalibration.set(this, selected, ms); alert.dismiss() }
            }
        }
        alert.show()
    }

    private fun buildPermissionSettings() {
        val permissions = settingsNavigator.subpage("permissions", getString(R.string.desktop_app_permissions), "")
        ui.help(ui.text(permissions, getString(R.string.desktop_permission_status), 16f, true), getString(R.string.desktop_permission_status)) {
            getString(R.string.desktop_the_green_check_box_is_available_the_red_cross)
        }
        activationAccessRow = ui.permission(permissions, getString(R.string.desktop_desktop_access)) { openActivationSetup() }
        appPermissionList = ui.group(permissions)
        refreshPermissionList()
    }

    private fun refreshPermissionList() {
        if (!::appPermissionList.isInitialized) return
        appPermissionList.removeAllViews()
        DesktopPermissionList(this, ui).hsd(appPermissionList)
    }

    private fun hasNotificationAccess(): Boolean = runCatching {
        getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(
            android.content.ComponentName(this, com.hsd.universal.cluster.ClusterNavigationListener::class.java))
    }.getOrDefault(false)

    private fun renderSettingsOverview() {
        if (!::settingsNavigator.isInitialized || settingsPage.visibility != View.VISIBLE) return
        val listener = hasNotificationAccess()
        val battery = DesktopOnboardingActivity.batteryExempt(this)
        val notificationBlock = PhonePairingService.notificationBlock(this, needsCode = true)
        noticesHint.updateText(getString(R.string.desktop_temporarily_disabled))
        val missing = mutableListOf<String>()
        val usbRequired = DesktopUsbDebugging.required(this)
        val usbState = DesktopUsbDebugging.state(this)
        if (usbRequired && usbState == DesktopUsbDebuggingPolicy.State.DISABLED) missing += getString(R.string.desktop_usb_debugging)
        if (!DesktopAccess.isReady(this)) missing += getString(R.string.desktop_activation_3a21)
        if (clusterPrefs.metaNavi && !listener) missing += getString(R.string.desktop_notification)
        if (notificationBlock != null) missing += when (notificationBlock) {
            PairingNotificationBlock.CODE_CHANNEL -> getString(R.string.desktop_codenotification)
            PairingNotificationBlock.PROGRESS_CHANNEL -> getString(R.string.desktop_activation_notification)
            PairingNotificationBlock.APP -> getString(R.string.desktop_notice, DesktopTransport.shortName)
        }
        settingsNavigator.summary("permissions", when {
            missing.isNotEmpty() -> getString(R.string.desktop_setup_required, missing.joinToString(", "))
            !battery -> getString(R.string.desktop_permissions_issued_remove_the_battery_restriction)
            else -> getString(R.string.desktop_ready)
        })
        val access = DesktopAccess.isReady(this)
        activationAccessRow.update(access, if (access) getString(R.string.desktop_permissions_granted) else getString(R.string.desktop_app_activation_required))
    }

    private fun openNotificationSettings(primary: Intent, fallback: String, instructions: String) {
        for (intent in listOf(primary, Intent(fallback))) {
            try {
                startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Some vendor Settings packages omit the more specific screen.
            } catch (_: SecurityException) {
                // The OEM may also expose a screen that only system apps can start.
            }
        }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.desktop_notification_settings)).setMessage(instructions)
            .setPositiveButton(getString(R.string.desktop_close), null).show()
    }

    private fun openSettings(open: Boolean) {
        if (open && settingsPage.visibility != View.VISIBLE) settingsNavigator.open(null)
        homePage.visibility = if (open) View.GONE else View.VISIBLE
        settingsPage.visibility = if (open) View.VISIBLE else View.GONE
        setTitle(if (open) getString(R.string.home_settings) else "${DesktopTransport.appName}")
        if (open) renderSettingsOverview()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsPage.visibility == View.VISIBLE) settingsNavigator.back() else super.onBackPressed()
    }

    private fun field(parent: LinearLayout, label: String, initial: String, id: Int, secret: Boolean = false,
        address: Boolean = false, legacyBusyControl: Boolean = true) = EditText(this).apply {
        this.id = id
        hint = label
        setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); setSingleLine(true)
        inputType = when {
            address -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            secret -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_NUMBER
        }
        filters = arrayOf(InputFilter.LengthFilter(if (address) 80 else if (secret) 6 else 5))
        setText(initial)
        isSaveEnabled = !secret
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        parent.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
        // The phone activation prompt stays editable while its foreground worker is waiting.
        // Toggling enabled off/on during a status refresh dismisses Android's input connection.
        if (legacyBusyControl) inputViews.add(this)
    }


    private fun accessLabel(mode: DesktopAccessMode): String = when (mode) {
        DesktopAccessMode.COMPUTER -> getString(R.string.desktop_access, DesktopTransport.shortName)
        DesktopAccessMode.SHIZUKU -> "Shizuku"
        DesktopAccessMode.LEGACY_ADB -> getString(R.string.desktop_built_in_adb_fallback)
    }

    private fun chooseAccessMode() {
        if (accessChoiceLocked()) return
        val methods = ActivationMethod.entries.filter { it.backend(prefs.desktopMode) != null }
        AlertDialog.Builder(this).setTitle(getString(R.string.desktop_activation_method))
            .setSingleChoiceItems(methods.map { getString(it.labelRes) }.toTypedArray(), methods.indexOf(prefs.activationMethod)) { dialog, index ->
                dialog.dismiss()
                val method = methods[index]
                if (accessChoiceLocked() || method == prefs.activationMethod) return@setSingleChoiceItems
                val backend = method.backend(prefs.desktopMode) ?: return@setSingleChoiceItems
                if (requireBackendHandoff(backend)) return@setSingleChoiceItems
                cancelContinuation()
                prefs.activationMethod = method
                manualAdbOpen = false
                renderAccessControls()
                showAccessSetup()
            }.setNegativeButton(getString(R.string.mock_cancel), null).show()
    }

    private fun selectDesktopProfile(mode: Int, entry: DesktopComponentEntry?, mediaAllowed: Boolean) {
        if (accessChoiceLocked()) return
        if (requireBackendHandoff(prefs.accessModeFor(mode))) return
        if (entry == null) DesktopComponents.clear(this)
        else if (!DesktopComponents.select(this, entry, mediaAllowed)) {
            AlertDialog.Builder(this).setTitle(getString(R.string.desktop_component_has_changed))
                .setMessage(getString(R.string.desktop_open_the_profile_list_again_and_check_the_package))
                .setPositiveButton(getString(R.string.apps_warn_ok), null).show()
            return
        }
        selectedPanel = entry
        cancelContinuation()
        prefs.desktopMode = mode
        manualAdbOpen = false
        renderAccessControls()
        showAccessSetup()
    }

    private fun requireBackendHandoff(next: DesktopAccessMode): Boolean {
        if (!DesktopBackendTransitionPolicy.requiresActivationEnd(DelegatedDesktopAccess.ownsActivation,
                prefs.accessMode, next)) return false
        showBackendHandoff(next)
        return true
    }

    private fun showBackendHandoff(next: DesktopAccessMode) {
        AlertDialog.Builder(this).setTitle(getString(R.string.desktop_switching_to, accessLabel(next)))
            .setMessage(getString(R.string.desktop_first_complete_the_active_activation_it_shuts_down_and, DesktopTransport.shortName))
            .setPositiveButton(getString(R.string.desktop_complete_access_and_close_b0cd)) { _, _ -> finishActivationForBackendChange() }
            .setNegativeButton(getString(R.string.mock_cancel), null).show()
    }

    private fun finishActivationForBackendChange() {
        if (accessChoiceLocked() || !DelegatedDesktopAccess.ownsActivation) return
        if (DelegatedDesktopAccess.requestStop()) {
            endingComputerAccess = true
            cancelContinuation()
            val recovery = if (IndependentRecoveryAccess.hasManagedState(this))
                getString(R.string.desktop_your_own_additional_debugging_will_be_closed_if_the) else ""
            showState(getString(R.string.desktop_finishing_access, DesktopTransport.shortName), getString(R.string.desktop_the_application_will_close_after_the_desktop_is_released, recovery), busy = true)
            renderAccessControls()
        }
    }

    private fun numberField(label: String, value: Int, maxDigits: Int) = EditText(this).apply {
        hint = label
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(maxDigits))
        setSingleLine(true)
        setText(value.toString())
        selectAll()
    }

    private fun showCustomResolution() {
        val width = numberField(getString(R.string.desktop_width_in_pixels), prefs.customDesktopWidth, 5)
        val height = numberField(getString(R.string.desktop_height_in_pixels), prefs.customDesktopHeight, 5)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@DexSetupActivity).apply {
                text = if (DesktopTransport.universal) getString(R.string.desktop_specify_the_desired_width_and_height_with_even_numbers)
                    else getString(R.string.desktop_the_width_is_640_1920_the_height_is_480)
            })
            addView(width)
            addView(height)
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle(getString(R.string.dev_custom_res_title))
            .setView(fields).setPositiveButton(getString(R.string.desktop_save), null).setNegativeButton(getString(R.string.mock_cancel), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (accessChoiceLocked()) { dialog.dismiss(); renderAccessControls(); return@setOnClickListener }
                val w = width.text.toString().toIntOrNull()
                val h = height.text.toString().toIntOrNull()
                if (w == null || h == null) {
                    (if (w == null) width else height).error = getString(R.string.desktop_enter_the_pixel_number)
                    return@setOnClickListener
                }
                runCatching { prefs.setCustomDesktopSize(w, h) }
                    .onSuccess { dialog.dismiss(); renderAccessControls() }
                    .onFailure { height.error = it.message ?: getString(R.string.desktop_size_unsupported) }
            }
        }
        dialog.show()
    }

    private fun showCustomBitrate() {
        val value = numberField(getString(R.string.desktop_mbit_s_1041), prefs.desktopBitrateMbps, 2)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@DexSetupActivity).apply {
                text = getString(R.string.desktop_integers_between_1_and_20_mbit_s_the_frame)
            })
            addView(value)
        }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.desktop_custom_video_bitrate))
            .setView(fields).setPositiveButton(getString(R.string.desktop_save), null).setNegativeButton(getString(R.string.mock_cancel), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (accessChoiceLocked()) { dialog.dismiss(); renderAccessControls(); return@setOnClickListener }
                val mbps = value.text.toString().trim().toIntOrNull()
                if (mbps == null || mbps !in 1..20) {
                    value.error = getString(R.string.desktop_enter_an_integer_from_1_to_20); return@setOnClickListener
                }
                prefs.desktopBitrateMbps = mbps
                dialog.dismiss()
                renderAccessControls()
            }
        }
        dialog.show()
    }

    private fun showCustomDensity() {
        val value = numberField("DPI", prefs.desktopDensity.takeIf { it > 0 } ?: 160, 4)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@DexSetupActivity).apply {
                text = getString(R.string.desktop_from_96_to_640_dpi_the_larger_number_is)
            })
            addView(value)
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle(getString(R.string.desktop_custom_interface_size))
            .setView(fields).setPositiveButton(getString(R.string.desktop_save), null).setNegativeButton(getString(R.string.mock_cancel), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (accessChoiceLocked()) { dialog.dismiss(); renderAccessControls(); return@setOnClickListener }
                val dpi = value.text.toString().toIntOrNull()
                if (dpi == null || dpi !in 96..640) {
                    value.error = getString(R.string.desktop_enter_the_number_from_96_to_640); return@setOnClickListener
                }
                prefs.desktopDensity = dpi
                dialog.dismiss()
                renderAccessControls()
            }
        }
        dialog.show()
    }

    private fun beginShizuku(explicit: Boolean) {
        if (!canBeginWork()) return
        prepareAttempt(explicit)
        when (val status = DexPrivilegeAccess.status(this)) {
            DexPrivilegeAccess.Status.READY -> readyWithDesktop()
            DexPrivilegeAccess.Status.PERMISSION_REQUIRED -> {
                showShizukuSetup(status)
                if (!explicit) return
                shizukuPermissionToken = flow.token
                showState(getString(R.string.desktop_allow_access_to_shizuku), getString(R.string.desktop_confirm_the_request_for_after_that_the_connection_will, DesktopTransport.appName), busy = true)
                try { DexPrivilegeAccess.requestPermission(REQUEST_SHIZUKU) }
                catch (_: Exception) {
                    shizukuPermissionToken = null
                    showShizukuSetup(DexPrivilegeAccess.status(this))
                }
            }
            else -> showShizukuSetup(status)
        }
    }

    /** Every backend prepares its access first; only this continuation chooses a transport. */
    private fun readyWithDesktop() {
        if (!visible || stopped) return
        if (!DesktopAccess.isReadyForProjection(this)) { showAccessSetup(); return }
        if (wizardStep) {
            showState(getString(R.string.desktop_access_is_active), getString(R.string.desktop_tap_finish_setup_then_connect_to_your_head_unit))
            return
        }
        setup.visibility = View.GONE
        shizukuSetup.visibility = View.GONE
        armed = true
        if (ProjectionRoute.wireless(this) && !ProjectionRoute.selected(this).carLife) {
            if (!BridgeService.alive && !stopped) {
                try {
                    startForegroundService(DesktopTransport.service(this))
                    showState(ProjectionRoute.selected(this).label(this), getString(R.string.desktop_connecting_to_head_unit), busy = true)
                } catch (failure: RuntimeException) {
                    armed = false
                    showState(getString(R.string.desktop_connection_failed), failure.message.orEmpty())
                }
            }
            return
        }
        // No local ADB work is needed after activation.
        if (!WirelessCarLife.preferUsb(this)) {
            if (retryUsbPermission && WirelessCarLife.missingPermissions(this).isNotEmpty()) {
                armed = false
                WirelessCarLife.start(this)
                return
            }
            if (WirelessCarLife.startReady(this)) {
                monitorBridge = true
                bridgeStartedAt = SystemClock.elapsedRealtime()
                showState(getString(R.string.desktop_connecting_over_wi_fi), getString(R.string.desktop_open_wireless_on_your_head_unit, projectionName), busy = true)
            } else {
                armed = false
                showState(getString(R.string.desktop_set_up_wi_fi, projectionName), WirelessCarLife.unavailableReason(this) ?: CarLifeState.status(this@DexSetupActivity))
            }
        } else scanUsb(retryUsbPermission)
    }

    private fun beginDelegated(explicit: Boolean) {
        if (!canBeginWork()) return
        prepareAttempt(explicit)
        if (DelegatedDesktopAccess.isReady(this)) readyWithDesktop() else {
            showDelegatedSetup()
            if (explicit) openActivationSetup()
        }
    }

    private fun showAccessSetup() {
        when {
            prefs.useDelegated -> showDelegatedSetup()
            prefs.useShizuku -> showShizukuSetup(DexPrivilegeAccess.status(this))
            else -> showSetup()
        }
    }

    private fun showDelegatedSetup() {
        setup.visibility = View.GONE
        shizukuSetup.visibility = View.GONE
        if (DelegatedDesktopAccess.isReady(this)) {
            showState(getString(R.string.desktop_access_active, DesktopTransport.shortName), getString(R.string.desktop_connect_using_the_selected_method_the_desktop_will_start, projectionName))
        } else {
            showState(getString(R.string.desktop_activation_required), getString(R.string.desktop_activate_then_plug_in_in_the_chosen_way, DesktopTransport.shortName, projectionName))
        }
    }

    private fun openActivationSetup() {
        setupRequested = true
        openSettings(true)
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) settingsNavigator.openEntry("activation")
        else settingsNavigator.open("activation")
        renderAccessControls()
    }

    private fun wizardCanFinish(): Boolean = DesktopOnboardingPolicy.canComplete(prefs.initialSetupReviewed,
        wizardProfileAvailable, if (prefs.useDelegated) DesktopAccess.activationSetupComplete(this) else DesktopAccess.isReadyForProjection(this))

    private fun returnToWizardPermissions() {
        prefs.initialSetupStep = 1
        startActivity(Intent(this, DesktopOnboardingActivity::class.java))
        finish()
    }

    private fun openPermissionSettings() {
        openSettings(true)
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) settingsNavigator.openEntry("permissions")
        else settingsNavigator.open("permissions")
        renderSettingsOverview()
    }

    private fun showComputerActivationHelp() {
        if (accessChoiceLocked() || requireBackendHandoff(DesktopAccessMode.COMPUTER)) return
        prefs.activationMethod = ActivationMethod.COMPUTER
        openActivationSetup()
    }

    private fun beginPhoneActivation() {
        if (canRetryFailedProjection()) {
            stopProjectionForActivation()
            return
        }
        if (!canStartPhoneActivation()) return
        if (DesktopUsbDebugging.confirmedMissing(this)) {
            AlertDialog.Builder(this).setTitle(getString(R.string.desktop_enable_usb_debugging))
                .setMessage(DesktopUsbDebugging.MISSING)
                .setNegativeButton(getString(R.string.upd_later), null)
                .setPositiveButton(getString(R.string.desktop_open_settings)) { _, _ -> openDebugSettings() }.show()
            return
        }
        if (DesktopActivationCoordinator.currentStep(this) == DesktopReadinessPolicy.Step.RECONFIGURATION_REQUIRED) {
            showState(getString(R.string.desktop_we_need_to_update_access), getString(R.string.desktop_selected_features_need_additional_permissions_complete_the_current_access, DesktopTransport.shortName))
            return
        }
        if (independentRecoveryApplicable() && !prefs.independentRecoveryWithoutWifi) {
            AlertDialog.Builder(this).setTitle(getString(R.string.desktop_automatic_recovery))
                .setMessage(getString(R.string.desktop_recovery_after_disabling_usb_it_will_enable_adb_network, DesktopTransport.shortName))
                .setNegativeButton(getString(R.string.mock_cancel), null)
                .setPositiveButton(getString(R.string.desktop_allow_and_activate)) { _, _ ->
                    if (canStartPhoneActivation()) {
                        prefs.independentRecoveryWithoutWifi = true
                        startNotificationActivation(openSettings = !prefs.hasPaired)
                    }
                }.show()
        } else startNotificationActivation(openSettings = !prefs.hasPaired)
    }

    private fun startNotificationActivation(openSettings: Boolean, allowWithoutNotification: Boolean = false,
        requestNotificationPermission: Boolean = true) {
        if (!canStartPhoneActivation()) return
        if (DesktopActivationCoordinator.isReady(this)) {
            prepareAttempt(explicit = true)
            PhonePairingService.start(this, allowWithoutNotification = allowWithoutNotification)
            observePhoneActivation(force = true)
            return
        }
        if (!allowWithoutNotification && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            requestNotificationPermission) {
            pairingPermissionAction = { startNotificationActivation(openSettings, requestNotificationPermission = false) }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PAIRING_NOTIFICATIONS)
            return
        }
        val block = PhonePairingService.notificationBlock(this, !prefs.hasPaired)
        if (!allowWithoutNotification && block != null) {
            showPairingNotificationBlocked(block, openSettings)
            return
        }
        withNetworkPermission {
            if (!visible || !canStartPhoneActivation()) return@withNetworkPermission
            prepareAttempt(explicit = true)
            if (PhonePairingService.start(this, allowWithoutNotification = allowWithoutNotification) == null) {
                PhonePairingService.notificationBlock(this, !prefs.hasPaired)?.let { showPairingNotificationBlocked(it, openSettings) }
                return@withNetworkPermission
            }
            showState(getString(R.string.desktop_activation_on_the_phone), getString(R.string.desktop_if_you_need_a_new_code_you_can_enter, DesktopTransport.shortName), busy = true)
            if (openSettings) openDebugSettings()
        }
    }

    private fun showPairingNotificationBlocked(block: PairingNotificationBlock, openSettings: Boolean = !prefs.hasPaired) {
        val reason = when (block) {
            PairingNotificationBlock.APP -> getString(R.string.desktop_notifications_it_s_off, DesktopTransport.shortName)
            PairingNotificationBlock.CODE_CHANNEL -> getString(R.string.desktop_the_pairing_code_enter_channel_is_disabled)
            PairingNotificationBlock.PROGRESS_CHANNEL -> getString(R.string.desktop_channel_activation_it_s_off, DesktopTransport.shortName)
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.desktop_enter_pairing_code))
            .setMessage(getString(R.string.desktop_turn_on_notifications_or_continue_with_the_code_in, reason))
            .setPositiveButton(getString(R.string.desktop_notification_settings)) { _, _ ->
                runCatching { startActivity(PhonePairingService.notificationSettings(this, block)) }
                    .onFailure { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
            }
            .setNegativeButton(getString(R.string.desktop_enter_code_in_app)) { _, _ ->
                startNotificationActivation(openSettings, allowWithoutNotification = true)
            }.show()
    }

    private fun beginPhonePair() {
        val value = PairingReplyPolicy.code(phonePairCode.text)
        if (value == null) {
            phonePairCode.error = getString(R.string.desktop_enter_the_6_digit_code)
            phonePairCode.requestFocus()
            return
        }
        val attempt = phoneReplyAttempt
        if (attempt != null && PhonePairingService.submitCode(this, attempt, value)) phonePairCode.text.clear()
        else phonePairCode.error = getString(R.string.desktop_this_input_has_already_been_completed_wait_for_the)
    }

    private fun canStartPhoneActivation(): Boolean {
        if (!visible || activationStopPending || !prefs.useDelegated || prefs.activationMethod != ActivationMethod.WIFI || phoneLaunchCommitted || PhonePairingService.active) return false
        if (BridgeService.alive || DexState.running) {
            showState(getString(R.string.desktop_stop_the_connection_first), getString(R.string.desktop_stop_the_session_with_a_button_on_the_main))
            return false
        }
        if (workerBusy.get()) {
            showState(getString(R.string.desktop_the_previous_operation_is_still_being_completed), getString(R.string.desktop_wait_for_it_to_be_completed_and_press_activate))
            return false
        }
        return true
    }

    /** A failed projection must not trap the user behind a disabled reactivation button. */
    private fun canRetryFailedProjection(): Boolean = visible && !endingComputerAccess && permissionAction == null && prefs.useDelegated &&
        prefs.activationMethod == ActivationMethod.WIFI && !activationStopPending &&
        !phoneLaunchCommitted && !PhonePairingService.active && !workerBusy.get() &&
        DexState.error != null && !desktopConnectionReady() && (BridgeService.alive || DexState.running)

    private fun stopProjectionForActivation() {
        cancelContinuation()
        activationStopPending = true
        Actions.stopBridge(this)
        val deadline = SystemClock.elapsedRealtime() + 5_000
        showState(getString(R.string.desktop_preparing_to_reactivate), getString(R.string.desktop_completion_of_the_failed_connection_permissions_are_retained), busy = true)
        main.post(object : Runnable {
            override fun run() {
                if (!activationStopPending || !visible || isDestroyed) return
                if (!BridgeService.alive && !DexState.running) {
                    activationStopPending = false
                    screenBusy = false
                    beginPhoneActivation()
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    activationStopPending = false
                    showState(getString(R.string.desktop_the_connection_is_still_complete), getString(R.string.desktop_wait_for_the_stop_and_click_re_activation))
                } else main.postDelayed(this, 100)
            }
        })
    }

    private fun confirmRestoreDebugMode() {
        if (accessChoiceLocked() || !DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.snapshot(this).kind)) return
        AlertDialog.Builder(this).setTitle(getString(R.string.desktop_bring_back_the_normal_adb_mode))
            .setMessage(getString(R.string.desktop_additional_network_mode_adb_which_uses_debugging_connections_including, DesktopTransport.shortName))
            .setNegativeButton(getString(R.string.mock_cancel), null)
            .setPositiveButton(getString(R.string.desktop_restore_standard_mode)) { _, _ -> restoreDebugMode() }.show()
    }

    private fun restoreDebugMode() {
        if (accessChoiceLocked() || !workerBusy.compareAndSet(false, true)) return
        val app = applicationContext
        restoringDebugMode = true
        showState(getString(R.string.desktop_returning_the_usual_adb_mode), getString(R.string.desktop_wait_for_the_closure_of_the_additional_port), busy = true)
        worker.execute {
            val result = runCatching { IndependentRecoveryAccess.restoreExplicitly(app) }
            workerBusy.set(false)
            main.post {
                if (isFinishing || isDestroyed) return@post
                restoringDebugMode = false
                screenBusy = false
                result.fold(
                    onSuccess = { showState(getString(R.string.desktop_regular_adb_mode_restored), getString(R.string.desktop_extra_port_closed_wi_fi_free_recovery_turned_off)) },
                    onFailure = { showState(getString(R.string.desktop_mode_restore_unconfirmed), it.message ?: getString(R.string.desktop_couldn_t_confirm_a_return_to_normal_mode_try)) }
                )
                renderAccessControls()
            }
        }
    }

    private fun observePhoneActivation(force: Boolean = false): Boolean {
        if (!prefs.useDelegated) return false
        if (!PhonePairingService.active && PhonePairingService.resumeCommittedActivation(this)) {
            phoneLaunchCommitted = false
            showState(getString(R.string.desktop_checking_desktop), getString(R.string.desktop_license_issued_checking_the_independent_screen_and_automatic_recovery), busy = true)
            return true
        }
        val attempt = PhonePairingService.snapshot
        if (PhonePairingService.active) {
            phoneLaunchCommitted = attempt.phase == PhonePairingService.Phase.DISPATCHED
            showState(getString(R.string.desktop_activation_on_the_phone), attempt.message, busy = true)
            return true
        }
        val status = PhoneActivationStatus.read(this)
        // The new process may already hold grants while its original launcher is still checking them.
        // Keep that exact transaction busy; do not offer a second activation in this interval.
        if (status.phase == PhoneActivationStatus.Phase.PENDING &&
            status.activationBinding != PhoneCommittedActivationPolicy.Binding.OTHER) {
            phoneLaunchCommitted = true
            showState(getString(R.string.desktop_enabling_access, DesktopTransport.shortName), getString(R.string.desktop_i_m_checking_to_activate_the_independent_screen_activation), busy = true)
            return true
        }
        val ready = DesktopAccess.activationSetupComplete(this)
        val baseReady = DelegatedDesktopAccess.isReady(this)
        // A receipt describes one earlier attempt. Live capabilities take precedence after restart.
        if (ready) {
            phoneLaunchCommitted = false
            if (!setupResumeScheduled && prefs.initialSetupInProgress && prefs.initialSetupStep == 2 &&
                !intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) {
                setupResumeScheduled = true
                startActivity(Intent(this, DesktopOnboardingActivity::class.java))
                return true
            }
            if (force || phoneStatusSeen != "live-ready") {
                phoneStatusSeen = "live-ready"
                cancelContinuation()
                showState(getString(R.string.desktop_access_active, DesktopTransport.shortName), getString(R.string.desktop_connect_re_activation_will_check_the_access_without_undue, projectionName))
                if (!setupRequested && !stopped && visible && !intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) readyWithDesktop()
                return true
            }
            return false
        }
        val liveStatus = activationUiStatus(ready)
        if (liveStatus.state == DesktopActivationUiPolicy.State.CONNECTED ||
            liveStatus.state == DesktopActivationUiPolicy.State.ACCESS_ACTIVE) {
            phoneLaunchCommitted = false
            val key = "live:${liveStatus.state}:${liveStatus.recovery}"
            if (force || phoneStatusSeen != key) {
                phoneStatusSeen = key
                showState(activationSummary(liveStatus), activationExplanation(liveStatus))
                return true
            }
            return false
        }
        if (attempt.phase == PhonePairingService.Phase.FAILED || attempt.phase == PhonePairingService.Phase.CANCELLED) {
            val key = "service:${attempt.attempt}:${attempt.phase}:${attempt.message}"
            if (force || phoneStatusSeen != key) {
                phoneStatusSeen = key
                phoneLaunchCommitted = false
                cancelContinuation()
                showState(if (attempt.phase == PhonePairingService.Phase.FAILED) getString(R.string.desktop_activation_incomplete) else getString(R.string.desktop_activation_cancelled), attempt.message)
                return true
            }
            return false
        }
        if (baseReady) {
            phoneLaunchCommitted = false
            if (force || phoneStatusSeen != "live-access") {
                phoneStatusSeen = "live-access"
                showState(getString(R.string.desktop_desktop_preparations_incomplete), getString(R.string.desktop_application_permissions_saved_click_re_activate_to_check_the))
                return true
            }
            return false
        }
        val key = "${status.phase}:${status.startedElapsedMs}:${status.message}"
        when (status.phase) {
            PhoneActivationStatus.Phase.PENDING -> {
                phoneLaunchCommitted = true
                showState(getString(R.string.desktop_enabling_access, DesktopTransport.shortName), getString(R.string.desktop_wait_for_the_restart_and_the_activation_result), busy = true)
                return true
            }
            PhoneActivationStatus.Phase.FAILED -> if (force || phoneStatusSeen != key || phoneLaunchCommitted) {
                phoneStatusSeen = key
                phoneLaunchCommitted = false
                cancelContinuation()
                showState(getString(R.string.desktop_activation_incomplete), status.message)
                return true
            }
            PhoneActivationStatus.Phase.READY, PhoneActivationStatus.Phase.IDLE -> {
                phoneLaunchCommitted = false
            }
        }
        return false
    }

    private fun showShizukuSetup(status: DexPrivilegeAccess.Status, denied: Boolean = false) {
        setup.visibility = View.GONE
        shizukuSetup.visibility = if (status == DexPrivilegeAccess.Status.READY) View.GONE else View.VISIBLE
        shizukuManagerButton.updateText(if (status == DexPrivilegeAccess.Status.NOT_INSTALLED) getString(R.string.desktop_install_shizuku) else getString(R.string.desktop_open_shizuku))
        val instruction = when (status) {
            DexPrivilegeAccess.Status.NOT_INSTALLED -> getString(R.string.desktop_install_shizuku_from_the_official_page_launch_it_and)
            DexPrivilegeAccess.Status.NOT_RUNNING -> getString(R.string.desktop_shizuku_service_is_not_running_open_it_and_run, DesktopTransport.shortName)
            DexPrivilegeAccess.Status.UPDATE_REQUIRED -> getString(R.string.desktop_to_run_the_desktop_you_need_shizuku_13_or)
            DexPrivilegeAccess.Status.PERMISSION_REQUIRED -> if (denied) getString(R.string.desktop_access_not_allowed_press_connect_to_repeat_the_request) else getString(R.string.desktop_click_connect_and_allow_access_to_in_shizuku_s, DesktopTransport.appName)
            DexPrivilegeAccess.Status.DENIED_PERMANENTLY -> getString(R.string.desktop_open_shizuku_authorized_apps_and_allow_then_come_back, DesktopTransport.appName)
            DexPrivilegeAccess.Status.READY -> getString(R.string.desktop_connect_in_the_chosen_way_the_desktop_will_start, projectionName)
        }
        shizukuHelp.updateText(if (status == DexPrivilegeAccess.Status.NOT_INSTALLED)
            getString(R.string.desktop_the_setup_is_performed_in_the_official_shizuku_app)
        else getString(R.string.desktop_once_the_phone_is_restarted_shizuku_must_be_restarted))
        val heading = when (status) {
            DexPrivilegeAccess.Status.NOT_INSTALLED -> getString(R.string.desktop_i_need_shizuku)
            DexPrivilegeAccess.Status.NOT_RUNNING -> getString(R.string.desktop_launch_shizuku)
            DexPrivilegeAccess.Status.UPDATE_REQUIRED -> getString(R.string.desktop_update_shizuku)
            DexPrivilegeAccess.Status.PERMISSION_REQUIRED, DexPrivilegeAccess.Status.DENIED_PERMANENTLY -> getString(R.string.desktop_allow_access_to_shizuku)
            DexPrivilegeAccess.Status.READY -> getString(R.string.desktop_shizuku_ready_to_go)
        }
        showState(heading, instruction)
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(DexPrivilegeAccess.MANAGER_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(DexPrivilegeAccess.DOWNLOAD_URL))
        try { startActivity(intent) }
        catch (_: Exception) { showState(getString(R.string.desktop_failed_to_open_shizuku), getString(R.string.desktop_open_the_shizuku_app_on_your_phone_and_start)) }
    }

    private fun accessChoiceLocked(): Boolean = endingComputerAccess || activationStopPending || PhonePairingService.active || phoneLaunchCommitted || screenBusy || armed || monitorBridge || workerBusy.get() ||
        BridgeService.alive || DexState.running || restartAt != 0L || permissionAction != null || shizukuPermissionToken != null

    private fun independentRecoveryApplicable(): Boolean = Build.VERSION.SDK_INT >= 37 && prefs.useDelegated &&
        prefs.desktopMode != DesktopModePolicy.SAMSUNG_DEX

    private fun activationUiStatus(setupReady: Boolean = DesktopAccess.activationSetupComplete(this)): DesktopActivationUiPolicy.Status {
        val facts = if (prefs.useDelegated) DesktopActivationCoordinator.facts(this) else null
        val needsNewGrants = facts != null && facts.grants &&
            (prefs.usbPlaybackAudio && !facts.audio || prefs.desktopPanelControl && !facts.panel && !facts.panelUnsupported)
        return DesktopActivationUiPolicy.status(
            inProgress = restoringDebugMode || activationStopPending || PhonePairingService.active || phoneLaunchCommitted,
            committedBlocked = PhonePairingService.blocksProjectionForCommittedActivation(this),
            accessReady = DesktopAccess.isReady(this), needsNewGrants = needsNewGrants,
            hostMissing = facts != null && facts.hostRequired && !facts.host,
            projectionReady = desktopConnectionReady(), setupReady = setupReady,
            attemptFailed = !setupReady && PhonePairingService.snapshot.phase == PhonePairingService.Phase.FAILED,
            recoveryRequested = IndependentRecoveryAccess.requested(this),
            recoveryNeedsApply = facts?.modePending == true,
            recoveryNeedsVerification = facts?.recoveryNeedsVerification == true,
            usbDebuggingMissing = facts?.usbDebuggingMissing == true)
    }

    private fun activationSummary(status: DesktopActivationUiPolicy.Status): String = when (status.state) {
        DesktopActivationUiPolicy.State.ACTIVATING -> getString(R.string.desktop_working)
        DesktopActivationUiPolicy.State.INCOMPLETE -> getString(R.string.desktop_preparations_incomplete)
        DesktopActivationUiPolicy.State.NEEDS_ACCESS -> getString(R.string.desktop_activation_required)
        DesktopActivationUiPolicy.State.NEEDS_UPDATED_ACCESS -> getString(R.string.desktop_we_need_to_update_access)
        DesktopActivationUiPolicy.State.DESKTOP_UNAVAILABLE -> getString(R.string.desktop_desktop_unavailable)
        DesktopActivationUiPolicy.State.CONNECTED -> getString(R.string.desktop_desktop_connected)
        DesktopActivationUiPolicy.State.READY, DesktopActivationUiPolicy.State.ACCESS_ACTIVE -> getString(R.string.desktop_access_is_active)
        DesktopActivationUiPolicy.State.FAILED -> getString(R.string.desktop_activation_incomplete)
        DesktopActivationUiPolicy.State.USB_DEBUGGING_REQUIRED -> getString(R.string.desktop_enable_usb_debugging)
    }

    private fun recoveryExplanation(status: DesktopActivationUiPolicy.Status): String = when (status.recovery) {
        DesktopActivationUiPolicy.Recovery.NEEDS_SETUP -> getString(R.string.desktop_recovery_after_shutdown_requires_adjustment_reactivation_will_check_the)
        DesktopActivationUiPolicy.Recovery.UNVERIFIED -> getString(R.string.desktop_readiness_for_the_next_reconnection_has_not_yet_been)
        DesktopActivationUiPolicy.Recovery.NOT_REQUIRED, DesktopActivationUiPolicy.Recovery.READY -> ""
    }

    private fun activationExplanation(status: DesktopActivationUiPolicy.Status): String = when (status.state) {
        DesktopActivationUiPolicy.State.ACTIVATING -> hint.text.toString()
        DesktopActivationUiPolicy.State.INCOMPLETE -> getString(R.string.desktop_the_permissions_are_retained_but_the_current_activation_check)
        DesktopActivationUiPolicy.State.NEEDS_ACCESS -> getString(R.string.desktop_activate_in_the_selected_way_then_connect, DesktopTransport.shortName, projectionName)
        DesktopActivationUiPolicy.State.NEEDS_UPDATED_ACCESS -> getString(R.string.desktop_selected_features_need_additional_permissions_complete_the_current_access, DesktopTransport.shortName)
        DesktopActivationUiPolicy.State.DESKTOP_UNAVAILABLE -> getString(R.string.desktop_application_permissions_are_saved_but_the_independent_screen_is)
        DesktopActivationUiPolicy.State.CONNECTED -> listOf(getString(R.string.desktop_projecting_to, projectionName, DesktopAudioState.status(this@DexSetupActivity)), recoveryExplanation(status)).filter(String::isNotBlank).joinToString("\n")
        DesktopActivationUiPolicy.State.ACCESS_ACTIVE -> listOf(getString(R.string.desktop_application_permissions_and_desktop_are_available), recoveryExplanation(status)).filter(String::isNotBlank).joinToString("\n")
        DesktopActivationUiPolicy.State.READY -> getString(R.string.desktop_connect_re_activation_will_be_required_after_restarting_the, projectionName)
        DesktopActivationUiPolicy.State.FAILED -> PhonePairingService.snapshot.message
        DesktopActivationUiPolicy.State.USB_DEBUGGING_REQUIRED -> DesktopUsbDebugging.MISSING
    }

    private fun renderAccessControls() {
        val desktop = prefs.useDesktopService
        val enabled = !accessChoiceLocked()
        val computerReady = prefs.useDelegated && DelegatedDesktopAccess.isReady(this)
        val accessReady = DesktopAccess.isReady(this)
        initialSetupSection.visibility = if (prefs.initialSetupCompleted) View.GONE else View.VISIBLE
        setupDoneButton.visibility = if (wizardStep || !PhonePairingService.active && !phoneLaunchCommitted) View.VISIBLE else View.GONE
        setupDoneButton.isEnabled = !wizardStep || (!PhonePairingService.active && !phoneLaunchCommitted && wizardCanFinish())
        setupDoneButton.updateText(if (wizardStep) getString(R.string.desktop_finish_setup) else if (intent.getBooleanExtra(EXTRA_RETURN_TO_SETUP, false)) getString(R.string.desktop_return_to_setup) else getString(R.string.desktop_home))
        val activationReady = DesktopAccess.activationSetupComplete(this)
        val activationUi = activationUiStatus(activationReady)
        setupAccessButton.visibility = if (activationReady || activationUi.state == DesktopActivationUiPolicy.State.CONNECTED) View.GONE else View.VISIBLE
        setupAccessButton.updateText(if (computerReady) getString(R.string.desktop_reactivate) else getString(R.string.desktop_activate))
        setupAccessButton.isEnabled = !PhonePairingService.active && !phoneLaunchCommitted
        val activationInProgress = restoringDebugMode || activationStopPending || PhonePairingService.active || phoneLaunchCommitted
        settingsNavigator.summary("activation", activationSummary(activationUi))
        activationProgress.visibility = if (activationInProgress) View.VISIBLE else View.GONE
        activationStatus.updateText(if (activationInProgress) title.text.toString() else activationSummary(activationUi))
        activationStatusHint.updateText(activationExplanation(activationUi))
        val wirelessPrefs = WirelessPrefs(this)
        val wirelessSelected = wirelessPrefs.enabled
        val transportLabel = when (wirelessPrefs.mode) {
            CarLifeConnectionMode.USB -> "USB"
            CarLifeConnectionMode.WIFI -> "Wi-Fi"
            CarLifeConnectionMode.AUTO -> getString(R.string.desktop_automatic)
        }
        projectionRouteRow?.updateSummary(ProjectionRoute.selected(this).label(this))
        connectionModeChoice?.updateText("$transportLabel ▾")
        connectionModeChoice?.isEnabled = enabled
        wirelessSetupButton.visibility = if (wirelessSelected && !DesktopTransport.universal) View.VISIBLE else View.GONE
        wirelessSetupButton.isEnabled = enabled
        accessHint.updateText(when {
            computerReady && !DelegatedDesktopInstrumentation.keyboardRoutingReady -> getString(R.string.desktop_waiting_for_the_keyboard_for_the_head_unit_screen, DesktopTransport.shortName)
            desktop -> (if (DesktopTransport.universal) ProjectionRoute.selected(this).label(this) else "$transportLabel ${projectionName}") + " · ${prefs.desktopResolutionLabel}"
            else -> (if (DesktopTransport.universal) ProjectionRoute.selected(this).label(this) else "$transportLabel ${projectionName}") + getString(R.string.desktop_dex_by_adb_1280_720)
        })
        if (desktop) setup.visibility = View.GONE
        adbOptions.visibility = if (desktop) View.GONE else View.VISIBLE
        adbAdvanced.visibility = if (!desktop && manualAdbOpen) View.VISIBLE else View.GONE
        adbOptions.updateText(if (manualAdbOpen) getString(R.string.desktop_hide_manual_entry) else getString(R.string.desktop_enter_address_and_ports_manually))
        desktopChoice.updateText(getString(R.string.desktop_profile_6aef, desktopProfileName))
        desktopChoice.isEnabled = enabled
        installProfileButton.visibility = if (prefs.desktopMode == DesktopModePolicy.DRIVEDECK && !driveDeckInstalled) View.VISIBLE else View.GONE
        desktopChoiceHint.updateText(if (prefs.desktopMode == DesktopModePolicy.DRIVEDECK && !driveDeckInstalled)
            getString(R.string.desktop_for_this_profile_install_drivedeck_the_normal_version_of, BuildConfig.BRAND)
            else if (!enabled) getString(R.string.desktop_to_change_the_profile_stop_the_connection)
            else when (prefs.desktopMode) {
                DesktopModePolicy.DRIVEDECK -> getString(R.string.desktop_map_and_music_side_by_side_on_the_head)
                DesktopModePolicy.SAMSUNG_DEX -> getString(R.string.desktop_samsung_dex_on_the_head_unit)
                else -> getString(R.string.desktop_android_desktop_and_app_drawer_on_the_head_unit)
            })
        accessChoice.updateText(getString(R.string.desktop_method, getString(prefs.activationMethod.labelRes)))
        accessChoice.isEnabled = enabled
        accessChangeHint.visibility = if (enabled) View.GONE else View.VISIBLE
        accessChangeHint.updateText(getString(R.string.desktop_to_change_the_activation_method_stop_the_connection))
        activationRepairSection.visibility = if (!activationInProgress &&
            DesktopActivationUiPolicy.showRepair(IndependentRecoveryAccess.snapshot(this).kind)) View.VISIBLE else View.GONE
        activationRepairButton.isEnabled = enabled
        activationRepairHint.updateText(getString(R.string.desktop_the_closure_of_additional_network_debugging_has_not_been) +
            if (enabled) getString(R.string.desktop_the_fix_will_request_a_separate_confirmation_it_can)
            else getString(R.string.desktop_first_stop_the_connection_then_you_can_fix_the))
        finishComputerAccess.visibility = if (!wizardStep && DelegatedDesktopAccess.ownsActivation) View.VISIBLE else View.GONE
        finishComputerAccess.isEnabled = enabled && DelegatedDesktopAccess.ownsActivation
        densityChoice.isEnabled = enabled
        dpiAppsButton.isEnabled = desktop
        resolutionChoice.isEnabled = enabled && desktop
        fpsChoice.isEnabled = enabled && desktop
        resolutionChoice.updateText(getString(R.string.desktop_resolution, prefs.desktopResolutionLabel))
        densityChoice.updateText(getString(R.string.desktop_interface_size, if (prefs.desktopDensity == 0) getString(R.string.desktop_auto) else "${prefs.desktopDensity} DPI"))
        fpsChoice.updateText(getString(R.string.desktop_video_bitrate_mbit_s, prefs.desktopBitrateMbps))
        videoTuningHelp = if (desktop)
            getString(R.string.desktop_the_frame_rate_adjusts_to_the_image_the_capabilities)
            else getString(R.string.desktop_in_backup_adb_mode_dex_automatic_fps_and_selected)
        densityHelp = when {
            !enabled -> getString(R.string.desktop_stop_the_connection_to_change_the_image)
            DesktopTransport.universal && desktop -> getString(R.string.desktop_if_the_size_is_not_available_the_nearest_size, projectionName)
            !desktop -> getString(R.string.desktop_dex_over_adb_uses_1280_720_the_selected_dpi)
            else -> getString(R.string.desktop_changes_take_effect_the_next_time_you_connect)
        }
        applyingAccessChoice = true
        try { usbAudioChoice.control.isChecked = prefs.usbPlaybackAudio } finally { applyingAccessChoice = false }
        usbAudioChoice.setEnabled(enabled && prefs.useDelegated)
        autoMusicChoice.setEnabled(prefs.useDelegated)
        usbAudioHelp = when {
            !prefs.useDelegated -> getString(R.string.desktop_transmission_of_sound_to_available_in_access_mode_you, projectionName, DesktopTransport.shortName)
            !enabled -> getString(R.string.desktop_to_change_the_sound_transmission_stop_the_connection, if (prefs.usbPlaybackAudio) getString(R.string.desktop_music_and_spoken_directions_use, projectionName) else getString(R.string.desktop_audio_uses_the_phone_s_current_output))
            !prefs.usbPlaybackAudio -> getString(R.string.desktop_audio_uses_the_phone_s_current_output_such_as)
            !DesktopAudioAccess.hasPermission(this) -> getString(R.string.desktop_sound_transmission_selected_activate_access_to_transmit_music_and, projectionName)
            else -> getString(R.string.desktop_the_phone_s_music_and_tips_are_transmitted_through, projectionName)
        }
        usbAudioStatus.updateText(when {
            !prefs.useDelegated || !prefs.usbPlaybackAudio -> ""
            DesktopAudioState.error != null -> DesktopAudioState.error.orEmpty()
            !DesktopAudioAccess.hasPermission(this) -> getString(R.string.desktop_activation_needed_to_transmit_the_sound)
            else -> DesktopAudioState.status(this@DexSetupActivity)
        })
        usbAudioStatus.visibility = if (usbAudioStatus.text.isEmpty()) View.GONE else View.VISIBLE
        applyingAccessChoice = true
        try { panelControlChoice.control.isChecked = prefs.desktopPanelControl } finally { applyingAccessChoice = false }
        panelControlChoice.setEnabled(enabled && desktop)
        settingsNavigator.summary("phone-display", if (prefs.desktopPanelControl) getString(R.string.desktop_manages, DesktopTransport.appName) else getString(R.string.desktop_screen_control_phone))
        phoneControlDetails.visibility = View.VISIBLE
        idleModeChoice.isEnabled = desktop && prefs.desktopPanelControl
        idleTimeoutChoice.isEnabled = desktop && prefs.desktopPanelControl
        panelControlHelp = when {
            !desktop -> getString(R.string.desktop_screen_control_is_not_available_in_backup_adb_mode)
            !enabled -> getString(R.string.desktop_to_change_access_to_the_screen_control_stop_the)
            !prefs.desktopPanelControl -> getString(R.string.desktop_turn_on_the_screen_control_to_change_the_damping)
            prefs.phoneScreenMode == PhoneIdleMode.CURTAIN -> getString(R.string.desktop_leave_it_it_s_open_on_the_phone_double, DesktopTransport.shortName)
            PhonePanelPolicy.prefersCurtain -> getString(R.string.desktop_on_this_model_a_hardware_shutdown_can_extinguish_the)
            DelegatedPanelAccess.unsupportedThisActivation -> PhoneActivationPolicy.PANEL_MESSAGE
            !DelegatedPanelAccess.isAvailable(this) -> getString(R.string.desktop_for_a_timer_and_double_tap_repeat_the_activation, DesktopTransport.shortName)
            else -> getString(R.string.desktop_before_the_panel_is_turned_off_it_opens_its, DesktopTransport.shortName)
        }
        panelControlStatus.updateText(when {
            DexState.phoneScreenError != null -> DexState.phoneScreenError.orEmpty()
            !prefs.desktopPanelControl -> ""
            !desktop -> getString(R.string.desktop_unavailable_for_the_selected_activation_method)
            prefs.phoneScreenMode == PhoneIdleMode.CURTAIN -> ""
            prefs.useDelegated && DelegatedPanelAccess.unsupportedThisActivation -> PhoneActivationPolicy.PANEL_MESSAGE
            prefs.useDelegated && !DelegatedPanelAccess.isAvailable(this) -> getString(R.string.desktop_activation_required_to_control_the_panel)
            else -> ""
        })
        panelControlStatus.visibility = if (panelControlStatus.text.isEmpty()) View.GONE else View.VISIBLE
        renderConnectionControl()
        renderPhoneActivationControls()
        renderPhoneScreenControl()
        renderSettingsOverview()
    }

    private fun renderPhoneActivationControls() {
        val method = prefs.activationMethod
        val desktopWifi = prefs.useDelegated && method == ActivationMethod.WIFI
        phoneActivationSection.visibility = if (desktopWifi) View.VISIBLE else View.GONE
        phoneActivationSection.keepScreenOn = desktopWifi && (PhonePairingService.active || phoneLaunchCommitted)
        if (wizardStep) {
            wizardComputerAction.updateText(if (DelegatedDesktopAccess.isReady(this)) getString(R.string.desktop_reactivate) else getString(R.string.desktop_activate))
            shizukuGrantButton.updateText(if (method == ActivationMethod.SHIZUKU && DesktopAccess.isReady(this)) getString(R.string.desktop_reactivate) else getString(R.string.desktop_activate))
        }
        computerActivationSection.visibility = if (method == ActivationMethod.COMPUTER) View.VISIBLE else View.GONE
        if (method == ActivationMethod.COMPUTER) {
            computerActivationInstructions.updateText(DesktopActivationGuide.instructions(this, prefs.usbPlaybackAudio, prefs.desktopPanelControl))
        }
        shizukuSetup.visibility = if (method == ActivationMethod.SHIZUKU) View.VISIBLE else View.GONE
        val nextReplyAttempt = PhonePairingService.replyAttempt()
        if (phoneReplyAttempt != nextReplyAttempt) { phonePairCode.text.clear(); phonePairCode.error = null }
        phoneReplyAttempt = nextReplyAttempt
        phonePairSection.visibility = if (phoneReplyAttempt != null) View.VISIBLE else View.GONE
        phonePairCode.isEnabled = phoneReplyAttempt != null
        val baseReady = DelegatedDesktopAccess.isReady(this)
        val attempt = PhonePairingService.snapshot
        phoneActivationButton.updateText(when {
            phoneReplyAttempt != null -> getString(R.string.desktop_confirm_code)
            activationStopPending || PhonePairingService.active || phoneLaunchCommitted -> getString(R.string.desktop_activating)
            DesktopUsbDebugging.confirmedMissing(this) -> getString(R.string.desktop_open_debugging_settings)
            baseReady -> getString(R.string.desktop_reactivate)
            else -> getString(R.string.desktop_activate)
        })
        phoneActivationButton.isEnabled = phoneReplyAttempt != null || canRetryFailedProjection() ||
            (!activationStopPending && !PhonePairingService.active && !phoneLaunchCommitted &&
            !screenBusy && !workerBusy.get() && permissionAction == null && !BridgeService.alive && !DexState.running)
        cancelPhoneActivationButton.visibility = if (PhonePairingService.active && attempt.phase != PhonePairingService.Phase.DISPATCHED) View.VISIBLE else View.GONE
        phoneActivationHint.updateText(when {
            PhonePairingService.active -> attempt.message
            DesktopUsbDebugging.required(this) && DesktopUsbDebugging.state(this) != DesktopUsbDebuggingPolicy.State.ENABLED ->
                DesktopUsbDebugging.INSTRUCTION
            canRetryFailedProjection() -> getString(R.string.desktop_re_activation_will_stop_the_failed_connection_and_re)
            BridgeService.alive || DexState.running -> getString(R.string.desktop_to_re_activate_first_stop_the_connection_on_the)
            baseReady -> getString(R.string.desktop_first_valid_access_is_checked_and_if_it_s)
            prefs.hasPaired -> getString(R.string.desktop_the_saved_pairing_will_be_checked_automatically_the_new)
            else -> getString(R.string.desktop_the_code_is_entered_once_when_activated_again_it, DesktopTransport.shortName)
        })
    }

    private fun lockIsolationHint(): String {
        if (prefs.desktopMode == DesktopModePolicy.SAMSUNG_DEX) return ""
        return "\n" + when (DexState.independentPhoneLock) {
            true -> getString(R.string.desktop_locking_the_phone_does_not_turn_off_the_gu)
            false -> getString(R.string.desktop_for_this_firmware_use_screen_damping, DesktopTransport.shortName)
            null -> getString(R.string.desktop_the_screen_s_independence_from_locking_has_not_yet)
        }
    }

    private fun renderConnectionControl() {
        val wireless = ProjectionRoute.wireless(this) || !WirelessCarLife.preferUsb(this)
        val state = DesktopConnectionButtonPolicy.state(
            stopped = stopped, detached = !wireless && Actions.isUsbDetachedIdle(),
            serviceAlive = BridgeService.alive, desktopRunning = DexState.running,
            carLifeRunning = CarLifeState.running,
            videoFlowing = DesktopConnectionState.videoIsFlowing(SystemClock.elapsedRealtime()),
            failed = DexState.error != null || !wireless && Actions.lastUsbResult.state == Actions.UsbScanState.FAILED,
            working = screenBusy || deferredConnect || restartAt != 0L ||
                permissionAction != null || shizukuPermissionToken != null,
            connectionPending = armed || monitorBridge && BridgeService.alive,
        )
        connectionButtonState = state
        connectButton.updateText(getString(state.labelRes))
        val color = when (state) {
            DesktopConnectionButtonPolicy.State.IDLE -> Color.rgb(30, 120, 65)
            DesktopConnectionButtonPolicy.State.CONNECTING, DesktopConnectionButtonPolicy.State.CONNECTED -> Color.rgb(183, 40, 43)
        }
        if (connectButton.backgroundTintList?.defaultColor != color) {
            connectButton.backgroundTintList = ColorStateList.valueOf(color)
        }
        connectButton.setTextColor(Color.WHITE)
        connectButton.isEnabled = !phoneLaunchCommitted && !PhonePairingService.active
        connectButton.contentDescription = getString(state.labelRes)
        connectButton.tooltipText = null
        connectButtonHint.updateText(getString(R.string.desktop_the_connection_isn_t_ready_yet_the_wait_can))
        connectButtonHint.visibility = if (state == DesktopConnectionButtonPolicy.State.CONNECTING && !phoneLaunchCommitted) View.VISIBLE else View.GONE

    }

    private fun desktopIdleSessionReady(): Boolean = BridgeService.alive && DexState.running &&
        CarLifeState.running && DexState.error == null && DesktopConnectionState.aliveForIdleTimer(SystemClock.elapsedRealtime())

    private fun desktopConnectionReady(): Boolean = BridgeService.alive && DexState.running &&
        CarLifeState.running && DexState.error == null && DesktopConnectionState.videoIsFlowing(SystemClock.elapsedRealtime())

    private fun renderPhoneScreenControl() {
        val available = prefs.useDesktopService && BridgeService.alive && DexState.running && !stopped &&
            (prefs.desktopPanelControl || DexState.phoneScreenOff)
        val visibility = if (available) View.VISIBLE else View.GONE
        val mode = prefs.phoneScreenMode
        val canBlankPanel = available && prefs.desktopPanelControl && (prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.isAvailable(this))
        idleModeChoice.updateText(getString(R.string.desktop_deactivation_method, getString(mode.labelRes)))
        idleTimeoutChoice.updateText(getString(R.string.desktop_extinguish_through, if (prefs.phoneTimerEnabled) getString(prefs.phoneIdleTimeout.labelRes) else getString(R.string.desktop_no_timer)))
        idleModeHelp = when {
            mode == PhoneIdleMode.CURTAIN -> getString(R.string.desktop_the_window_is_darkening_leave_it_open_to_return, DesktopTransport.shortName)
            prefs.useDelegated && DelegatedPanelAccess.unsupportedThisActivation -> PhoneActivationPolicy.PANEL_MESSAGE
            prefs.useDelegated && !DelegatedPanelAccess.hasPermission(this) -> getString(R.string.desktop_to_turn_off_the_panel_hardware_repeat_the_activation)
            else -> getString(R.string.desktop_the_power_of_the_phone_panel_is_turned_off)
        }
        phoneCurtainButton.visibility = if (available && prefs.desktopPanelControl && mode == PhoneIdleMode.CURTAIN && !DexState.phoneScreenOff) View.VISIBLE else View.GONE
        phoneCurtainButton.updateText(getString(R.string.desktop_dim_phone_screen))
        phoneCurtainButton.isEnabled = desktopConnectionReady() && !DexState.phoneScreenBusy
        phoneScreenButton.visibility = if (DexState.phoneScreenOff || canBlankPanel && mode == PhoneIdleMode.PANEL) View.VISIBLE else View.GONE
        phoneScreenHint.visibility = visibility
        phoneScreenButton.updateText(when {
            DexState.phoneScreenBusy -> getString(R.string.desktop_changing_the_state_of_the_screen)
            DexState.phoneScreenOff -> getString(R.string.desktop_turn_phone_screen_on)
            else -> getString(R.string.desktop_turn_off_the_phone_display_panel)
        })
        val enabled = (canBlankPanel || DexState.phoneScreenOff) && !DexState.phoneScreenBusy
        if (phoneScreenButton.isEnabled != enabled) phoneScreenButton.isEnabled = enabled
        phoneScreenHint.updateText(DexState.phoneScreenError ?: idleModeHelp)
    }

    private fun togglePhoneScreen() {
        if (!(prefs.useShizuku || prefs.useDelegated && DelegatedPanelAccess.isAvailable(this)) || !BridgeService.alive || !DexState.running || stopped || DexState.phoneScreenBusy) return
        val action = if (DexState.phoneScreenOff) BridgeService.ACTION_PHONE_SCREEN_ON else BridgeService.ACTION_PHONE_SCREEN_OFF
        try { startService(DesktopTransport.service(this).setAction(action)) }
        catch (_: Exception) {
            android.app.AlertDialog.Builder(this)
                .setMessage(getString(R.string.desktop_couldn_t_change_the_state_of_the_phone_s))
                .setPositiveButton(getString(R.string.apps_warn_ok), null).show()
        }
    }

    private fun beginConnect(explicit: Boolean) {
        if (!visible || endingComputerAccess || phoneLaunchCommitted || PhonePairingService.active) return
        if (prefs.desktopMode == DesktopModePolicy.DRIVEDECK && !driveDeckInstalled) {
            showState(getString(R.string.desktop_install_drivedeck_4dba, BuildConfig.BRAND), getString(R.string.desktop_open_the_components_and_install_the_adapted_drivedeck_then))
            return
        }
        if (DesktopBackendTransitionPolicy.blocksProjection(DelegatedDesktopAccess.ownsActivation, prefs.accessMode)) {
            if (explicit) showBackendHandoff(prefs.accessMode)
            else showState(getString(R.string.desktop_complete_previous_activation), getString(R.string.desktop_click_close_access_and_complete_access_then_open_the, DesktopTransport.shortName, accessLabel(prefs.accessMode)))
            return
        }
        if (WirelessPrefs(this).mode != CarLifeConnectionMode.WIFI) Actions.observeUsbConnection(this)
        // A deliberate retry must not be superseded by the next refresh noticing
        // the previous Stop (especially while a permission dialog is open).
        if (explicit || !stopped) observedStop = false
        when (Actions.connectDecision(explicit, BridgeService.alive, desktopConnectionReady())) {
            DexAutoConnectPolicy.Decision.STOPPED -> return
            DexAutoConnectPolicy.Decision.MONITOR -> {
                armed = true
                monitorBridge = true
                if (bridgeStartedAt == 0L) bridgeStartedAt = SystemClock.elapsedRealtime()
                if (desktopConnectionReady()) {
                    showState(getString(R.string.desktop_connected, desktopName), getString(R.string.desktop_already_projecting_over, projectionName))
                } else {
                    showState(getString(R.string.desktop_starting, desktopName), getString(R.string.desktop_the_connection_is_already_underway_wait_for_the_image), busy = true)
                }
                return
            }
            DexAutoConnectPolicy.Decision.RESTART -> {
                Actions.stopBridge(this, userInitiated = false)
                cancelContinuation()
                restartAt = SystemClock.elapsedRealtime()
                showState(getString(R.string.desktop_reconnecting), getString(R.string.desktop_this_will_take_a_few_seconds), busy = true)
                return
            }
            DexAutoConnectPolicy.Decision.CONNECT -> Unit
        }
        if (prefs.useDelegated) { beginDelegated(explicit); return }
        if (prefs.useShizuku) { beginShizuku(explicit); return }
        if (!prefs.hasPaired) { showSetup(); return }
        if (!canBeginWork()) {
            deferredConnect = true
            return
        }
        withNetworkPermission {
            prepareAttempt(explicit)
            execute(flow.beginConnect(saved = savedEndpoint()))
        }
    }

    private fun continuePendingConnection() {
        if (stopped) return
        if (restartAt != 0L) {
            if (!BridgeService.alive) {
                restartAt = 0
                beginConnect(explicit = true)
            } else if (SystemClock.elapsedRealtime() - restartAt > 5_000) {
                restartAt = 0
                showState(getString(R.string.desktop_it_was_not_possible_to_stop_the_desktop), getString(R.string.desktop_click_stop_and_reconnect))
            }
        } else if (deferredConnect && !workerBusy.get()) {
            deferredConnect = false
            if (prefs.useDesktopService || prefs.hasPaired) beginConnect(explicit = false) else showSetup()
        }
    }

    private fun beginPair(manual: Boolean) {
        if (prefs.useDesktopService) return
        val value = code.text.toString()
        if (!value.matches(Regex("[0-9]{6}"))) {
            code.error = getString(R.string.desktop_enter_the_6_digit_code)
            code.requestFocus()
            showState(getString(R.string.desktop_enter_pairing_code_280d), getString(R.string.desktop_the_code_is_shown_in_an_open_wireless_debugging))
            return
        }
        val endpoint = if (manual) manualEndpoint(pairPort) ?: return else null
        if (!canBeginWork()) return
        withNetworkPermission(endpoint?.host) {
            prepareAttempt(explicit = true)
            execute(flow.beginPair(value, manual = endpoint))
        }
    }

    private fun prepareAttempt(explicit: Boolean) {
        cancelContinuation()
        if (explicit) {
            observedStop = false
            Actions.resumeUsbAutoStart()
        }
        retryUsbPermission = explicit
        lastError = ""
        monitorBridge = false
        bridgeStartedAt = 0
    }

    private fun canBeginWork(): Boolean {
        if (!workerBusy.get()) return true
        deferredConnect = true // Only a connection may resume; never repeat pairing automatically.
        showState(getString(R.string.desktop_finishing_the_previous_check), getString(R.string.desktop_wait_until_it_s_complete_reconjugation_doesn_t_start), busy = true)
        return false
    }

    private fun execute(command: DexSetupFlow.Command) {
        when (command) {
            DexSetupFlow.Command.None -> Unit
            is DexSetupFlow.Command.Discover -> {
                if (command.token != flow.token) return
                if (!hasNetworkPermission()) {
                    withNetworkPermission { execute(command) }
                    return
                }
                val pairing = command.kind == DexService.PAIRING
                showState(if (pairing) getString(R.string.desktop_looking_for_pairing_dialog) else getString(R.string.desktop_looking_for_phone),
                    if (pairing) getString(R.string.desktop_leave_the_code_window_open_in_the_split_screen) else getString(R.string.desktop_i_m_checking_the_saved_connection_turn_on_wi), busy = true)
                startDiscovery(command.token)
                cancelDeadline()
                discoveryDeadline = Runnable { execute(flow.expired(command.token, command.kind)) }.also { main.postDelayed(it, DISCOVERY_TIMEOUT_MS) }
            }
            is DexSetupFlow.Command.Pair -> {
                cancelDeadline()
                showState(getString(R.string.desktop_pairing_phone), getString(R.string.desktop_do_not_close_the_code_window_in_settings), busy = true)
                launch(command.token, DexSetupFlow.Phase.PAIRING, {
                    runBlocking { DexAccess.pair(applicationContext, command.endpoint.port, command.code, command.endpoint.host) }
                }) {
                    code.text.clear()
                    phonePairCode.text.clear()
                    setup.visibility = View.GONE
                    execute(flow.paired(command.token))
                }
            }
            is DexSetupFlow.Command.Probe -> {
                cancelDeadline()
                showState(getString(R.string.desktop_checking_phone), getString(R.string.desktop_after_checking_the_connection_to_it_will_continue_automatically, projectionName), busy = true)
                launch(command.token, DexSetupFlow.Phase.PROBING, {
                    DexAccess.probe(applicationContext, command.endpoint.port, command.endpoint.host)
                    prefs.host = command.endpoint.host
                    prefs.connectPort = command.endpoint.port
                }) { execute(flow.verified(command.token)) }
            }
            is DexSetupFlow.Command.Ready -> {
                closeDiscovery()
                readyWithDesktop()
            }
            is DexSetupFlow.Command.ProbeFailed -> showSetupFailure(command.error, DexSetupFlow.Phase.PROBING)
            is DexSetupFlow.Command.NotFound -> {
                closeDiscovery()
                if (command.kind == DexService.PAIRING) {
                    setup.visibility = View.VISIBLE
                    showState(getString(R.string.desktop_pairing_dialog_not_found), getString(R.string.desktop_open_the_code_window_in_the_split_screen_enter))
                } else {
                    showState(getString(R.string.desktop_phone_not_found_yet), getString(R.string.desktop_turn_on_wi_fi_and_wireless_debugging_then_press))
                }
            }
        }
    }

    private fun launch(token: Long, phase: DexSetupFlow.Phase, work: () -> Unit, success: () -> Unit) {
        if (!workerBusy.compareAndSet(false, true)) {
            flow.cancel()
            closeDiscovery()
            showState(getString(R.string.desktop_the_previous_test_is_still_being_completed), getString(R.string.desktop_after_a_few_seconds_press_the_desired_button_again))
            return
        }
        worker.execute {
            val result = runCatching(work)
            workerBusy.set(false)
            main.post {
                if (!visible || isDestroyed || stopped || !flow.isCurrent(token, phase)) return@post
                result.fold(onSuccess = { success() }, onFailure = { failure ->
                    if (phase == DexSetupFlow.Phase.PROBING && failure is DexAccessException) {
                        execute(flow.probeFailed(token, failure))
                        return@fold
                    }
                    flow.failed(token)
                    showSetupFailure(failure, phase)
                })
            }
        }
    }

    private fun showSetupFailure(failure: Throwable, phase: DexSetupFlow.Phase) {
        closeDiscovery()
        armed = false
        lastError = failure.message ?: failure.javaClass.simpleName
        if (phase == DexSetupFlow.Phase.PAIRING) {
            code.text.clear()
            setup.visibility = View.VISIBLE
        }
        showState(if (phase == DexSetupFlow.Phase.PAIRING) getString(R.string.desktop_pairing_incomplete) else getString(R.string.desktop_the_phone_s_not_ready_yet), friendlyFailure(failure, phase))
        AppLog.i("DEX setup failed: ${failure.javaClass.simpleName}")
    }

    private fun startDiscovery(token: Long) {
        if (discovery != null && discoveryToken == token) return
        closeDiscovery()
        discoveryToken = token
        discovery = DexDiscovery(this) { pairing, connection, error ->
            if (!visible || token != flow.token) return@DexDiscovery
            applyingFields = true
            try {
                (pairing ?: connection)?.let { if (!hostEdited) host.setText(it.host) }
                if (!pairPortEdited) pairPort.setText(pairing?.port?.toString().orEmpty())
                if (!connectPortEdited && connection != null) connectPort.setText(connection.port.toString())
            } finally { applyingFields = false }
            lastDiscovery = getString(R.string.desktop_pairing_connection, pairing?.let { "${it.host}:${it.port}" } ?: getString(R.string.desktop_not_found), connection?.let { "${it.host}:${it.port}" } ?: getString(R.string.desktop_not_found))
            if (error != null) lastError = error
            execute(flow.discovered(token, pairing, connection))
        }.also { it.start() }
    }

    private fun closeDiscovery() {
        cancelDeadline()
        discovery?.close()
        discovery = null
        discoveryToken = -1
    }
    private fun cancelDeadline() {
        discoveryDeadline?.let(main::removeCallbacks)
        discoveryDeadline = null
    }

    private fun cancelContinuation() {
        flow.cancel()
        closeDiscovery()
        armed = false
        monitorBridge = false
        deferredConnect = false
        screenBusy = false
        restartAt = 0
        permissionAction = null
        shizukuPermissionToken = null
    }

    private fun stop() {
        if (phoneLaunchCommitted) return
        cancelContinuation()
        code.text.clear()
        phonePairCode.text.clear()
        Actions.stopBridge(this)
        showState(getString(R.string.desktop_stopped), getString(R.string.desktop_reconnect_the_usb_cable_or_tap_connect))
    }

    private fun usbAttached() {
        Actions.selectAttachedWiredRoute(this)
        if (ProjectionRoute.wireless(this)) return
        if (phoneLaunchCommitted || PhonePairingService.active || WirelessPrefs(this).mode == CarLifeConnectionMode.WIFI) return
        Actions.observeUsbConnection(this)
        if (stopped) return
        when {
            !CarLifeState.running && BridgeService.alive && prefs.useDesktopService && DesktopAccess.isReady(this) -> {
                armed = true
                scanUsb(retryPermission = false)
            }
            armed -> scanUsb(retryPermission = false)
            screenBusy -> Unit // The active flow will scan USB after its single probe completes.
            prefs.useDesktopService || prefs.hasPaired -> beginConnect(explicit = false)
            else -> showSetup()
        }
    }

    private fun scanUsb(retryPermission: Boolean) {
        if (!visible || stopped || !armed || WirelessPrefs(this).mode == CarLifeConnectionMode.WIFI) return
        if (prefs.useDesktopService && !DesktopAccess.isReady(this)) {
            cancelContinuation()
            showAccessSetup()
            return
        }
        lastUsbScanAt = SystemClock.elapsedRealtime()
        showUsbResult(Actions.scan(this, getString(R.string.desktop_dex_the_phone_is_ready), retryPermission))
    }

    private fun showUsbResult(result: Actions.UsbScanResult) {
        when (result.state) {
            Actions.UsbScanState.DETACHED -> showUsbDetached()
            Actions.UsbScanState.WAITING_FOR_ACCESSORY -> {
                monitorBridge = false
                bridgeStartedAt = 0
                showState(getString(R.string.desktop_ready_to_connect_automatically), if (prefs.useDesktopService)
                    getString(R.string.desktop_open_up_on_a_head_unit_plug_in_a, projectionName)
                else getString(R.string.desktop_open_up_on_a_head_unit_and_connect_a, projectionName))
            }
            Actions.UsbScanState.PERMISSION_REQUESTED -> showState(getString(R.string.desktop_allow_usb_access), getString(R.string.desktop_confirm_the_system_request_on_the_phone_after_that))
            Actions.UsbScanState.STARTING, Actions.UsbScanState.ALREADY_RUNNING -> {
                monitorBridge = true
                if (bridgeStartedAt == 0L) bridgeStartedAt = SystemClock.elapsedRealtime()
                showState(getString(R.string.desktop_starting, desktopName), getString(R.string.desktop_waiting_for_and_the_first_video_frame, projectionName), busy = true)
            }
            Actions.UsbScanState.FAILED -> {
                armed = false
                lastError = result.detail.orEmpty()
                showState(getString(R.string.desktop_usb_connection_failed), getString(R.string.desktop_allow_access_to_the_usb_or_reconnect_the_cable))
            }
        }
    }

    private fun showUsbDetached() {
        cancelContinuation()
        bridgeStartedAt = 0
        lastError = ""
        if (BridgeService.alive) {
            showState(getString(R.string.desktop_usb_disconnected), getString(R.string.desktop_there_s_a_short_pause_to_reconnect_if_the))
        } else {
            showState(getString(R.string.desktop_session_stopped), getString(R.string.desktop_the_phone_can_be_connected_to_the_computer_the, projectionName))
        }
    }

    private fun showSetup() {
        if (prefs.useDelegated) { showDelegatedSetup(); return }
        if (prefs.useShizuku) { showShizukuSetup(DexPrivilegeAccess.status(this)); return }
        setup.visibility = View.VISIBLE
        showState(getString(R.string.desktop_we_need_the_first_connection), getString(R.string.desktop_once_enter_the_code_from_the_debugging_settings_then))
    }

    private fun showState(value: String, explanation: String, busy: Boolean = false) {
        title.updateText(value)
        val panelWarning = prefs.useDelegated && DelegatedPanelAccess.unsupportedThisActivation &&
            !explanation.contains(PhoneActivationPolicy.PANEL_MESSAGE)
        hint.updateText(if (panelWarning) explanation + "\n" + PhoneActivationPolicy.PANEL_MESSAGE else explanation)
        screenBusy = busy
        val visibility = if (busy) View.VISIBLE else View.GONE
        if (progress.visibility != visibility) progress.visibility = visibility
        inputViews.forEach { if (it.isEnabled == busy) it.isEnabled = !busy }
        renderAccessControls()
    }

    private fun friendlyFailure(error: Throwable, phase: DexSetupFlow.Phase): String {
        val pairing = phase == DexSetupFlow.Phase.PAIRING
        val problem = error as? DexAccessException
        return when (problem?.reason) {
            DexAccessReason.PERMISSION_DENIED -> getString(R.string.desktop_allow_the_application_to_access_the_local_network_then)
            DexAccessReason.INVALID_HOST, DexAccessReason.NETWORK_UNREACHABLE -> getString(R.string.desktop_connect_to_wi_fi_and_enable_wireless_debugging_then)
            DexAccessReason.IDENTITY_FAILURE -> getString(R.string.desktop_couldn_t_open_the_saved_key_send_the_log)
            DexAccessReason.AUTHORIZATION_REJECTED, DexAccessReason.TLS_VERSION_UNSUPPORTED, DexAccessReason.TLS_PROTOCOL,
            DexAccessReason.LOCAL_ADB_CONFIGURATION, DexAccessReason.LOCAL_ADB_RESTORE_REQUIRED,
            DexAccessReason.USB_DEBUGGING_DISABLED -> problem?.message.orEmpty()
            DexAccessReason.PAIR_REJECTED -> getString(R.string.desktop_open_a_new_pairing_window_and_enter_fresh_code)
            else -> if (pairing) getString(R.string.desktop_open_a_new_window_with_the_code_in_the) else getString(R.string.desktop_check_that_wireless_debugging_is_enabled_and_click_connect)
        }
    }

    private fun hasNetworkPermission() = Build.VERSION.SDK_INT < 37 ||
        checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun withNetworkPermission(address: String? = null, action: () -> Unit) {
        if (hasNetworkPermission() || address == "127.0.0.1" || address == "::1") { action(); return }
        permissionAction = action
        showState(getString(R.string.desktop_let_me_find_this_phone), getString(R.string.desktop_automatic_connection_requires_access_to_the_local_network))
        if (Build.VERSION.SDK_INT >= 37) requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), REQUEST_NETWORK)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SETTINGS_NOTIFICATIONS) {
            refreshPermissionList()
            renderSettingsOverview()
            return
        }
        if (requestCode == REQUEST_PAIRING_NOTIFICATIONS) {
            val action = pairingPermissionAction
            pairingPermissionAction = null
            action?.invoke()
            return
        }
        if (requestCode != REQUEST_NETWORK) return
        val action = permissionAction
        permissionAction = null
        if (hasNetworkPermission() && visible) action?.invoke() else if (visible) {
            showState(getString(R.string.desktop_you_need_local_network_permission), getString(R.string.desktop_allow_it_in_the_application_settings_then_repeat_the))
        }
    }

    private fun savedEndpoint(): DexEndpoint? {
        return if (prefs.connectPort in 1..65535 && DexAccess.acceptsHost(prefs.host)) DexEndpoint(prefs.host, prefs.connectPort) else null
    }
    private fun manualEndpoint(port: EditText): DexEndpoint? {
        val address = host.text.toString().trim()
        val number = port.text.toString().toIntOrNull()
        if (!DexAccess.acceptsHost(address)) {
            host.error = getString(R.string.desktop_you_need_the_ip_of_this_particular_phone)
            return null
        }
        if (number == null || number !in 1..65535) { port.error = getString(R.string.desktop_enter_a_port_from_1_to_65535); return null }
        return DexEndpoint(address, number)
    }

    private fun openDebugSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    private fun watch(field: EditText, edited: () -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!applyingFields) edited() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private fun TextView.updateText(value: String) {
        val label = DesktopTransport.statusText(value)
        if (text.toString() != label) text = label
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
