package com.hsd.universal.ui

import com.hsd.universal.ProjectionRoute

/** Enumeration may complete after the attach intent. Only a newly visible wired protocol
 * can switch the profile, so a later manual selection is respected on the same connection. */
internal class WiredAccessorySelection {
    private var previous = emptySet<ProjectionRoute>()

    fun observe(selected: ProjectionRoute, available: Set<ProjectionRoute>): ProjectionRoute {
        val added = available - previous
        previous = available
        return if (selected.usesUsbAccessory && selected !in available) added.singleOrNull() ?: selected else selected
    }
}

/** Process-local claims prevent duplicate permission dialogs and service launches. */
internal class UsbScanGate(private val nowMs: () -> Long) {
    enum class Step { WAITING, ACTIVE, START, STARTING, REQUEST_PERMISSION, PERMISSION_PENDING, DENIED, PERMISSION_TIMEOUT, START_FAILED }
    private var key: String? = null
    private var permissionToken: Long? = null
    private var permissionAt = 0L
    private var denied = false
    private var permissionExpired = false
    private var startingAt: Long? = null
    private var startFailed = false
    var failureDetail: String? = null
        private set
    private var sequence = 0L
    val pendingToken: Long? get() = permissionToken
    val isStarting: Boolean get() = startingAt != null

    fun decide(accessoryKey: String?, granted: Boolean, active: Boolean, retry: Boolean): Step {
        if (accessoryKey != key) {
            reset()
            key = accessoryKey
        }
        // A stale running flag must never treat ordinary PC USB as a CarLife session.
        if (accessoryKey == null) return Step.WAITING
        if (active) {
            permissionToken = null
            startingAt = null
            startFailed = false
            return Step.ACTIVE
        }
        if (granted) {
            permissionToken = null
            denied = false
            permissionExpired = false
            startingAt?.let {
                if (nowMs() - it < START_TIMEOUT_MS) return Step.STARTING
                startingAt = null
                startFailed = true
            }
            if (startFailed && !retry) return Step.START_FAILED
            startFailed = false
            failureDetail = null
            startingAt = nowMs()
            return Step.START
        }
        startingAt = null
        permissionToken?.let {
            if (nowMs() - permissionAt < PERMISSION_TIMEOUT_MS) return Step.PERMISSION_PENDING
            permissionToken = null
            permissionExpired = true
        }
        if (permissionExpired && !retry) return Step.PERMISSION_TIMEOUT
        if (denied && !retry) return Step.DENIED
        denied = false
        permissionExpired = false
        permissionToken = ++sequence
        permissionAt = nowMs()
        return Step.REQUEST_PERMISSION
    }

    fun permissionResult(accessoryKey: String, token: Long, granted: Boolean): Boolean {
        if (key != accessoryKey || permissionToken != token) return false
        permissionToken = null
        denied = !granted
        return true
    }

    fun started() {
        startingAt = null
        startFailed = false
    }

    fun handshakeFailed(accessoryKey: String, detail: String): Boolean {
        if (key != null && key != accessoryKey) return false
        key = accessoryKey
        failed()
        failureDetail = detail
        return true
    }

    fun failed() {
        permissionToken = null
        denied = true
        startingAt = null
        startFailed = true
    }

    fun reset() {
        failureDetail = null
        key = null
        permissionToken = null
        denied = false
        permissionExpired = false
        startingAt = null
        startFailed = false
    }

    private companion object {
        const val PERMISSION_TIMEOUT_MS = 60_000L
        const val START_TIMEOUT_MS = 15_000L
    }
}

internal object UsbAccessoryPolicy {
    fun route(manufacturer: String?, model: String?): com.hsd.universal.ProjectionRoute? = when {
        acceptsAndroidAuto(manufacturer, model) -> com.hsd.universal.ProjectionRoute.USB
        accepts(manufacturer, model) -> com.hsd.universal.ProjectionRoute.CARLIFE_USB
        else -> null
    }

    fun acceptsAndroidAuto(manufacturer: String?, model: String?): Boolean = manufacturer.equals("Android", true) &&
        (model.equals("Android Auto", true) || model.equals("Android Open Automotive Protocol", true))

    /** Mirrors accessory_filter.xml, including the user's Mac CarLife test head unit. */
    fun accepts(manufacturer: String?, model: String?): Boolean =
        (manufacturer == "Baidu" && model == "CarLife") ||
            (manufacturer == "Mac HU Lab" && model == "CarLife HU")

    /** Neither serial (permission protected) nor Binder/object identity participates. */
    fun key(manufacturer: String?, model: String?, description: String?, version: String?, uri: String?): String =
        listOf(manufacturer, model, description, version, uri).joinToString("") {
            if (it == null) "-1:" else "${it.length}:$it"
        }
}
