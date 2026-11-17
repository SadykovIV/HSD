package com.hsd.universal.dex

import com.hsd.universal.DesktopTransport

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.hsd.universal.BuildConfig
import com.hsd.universal.AppLog

/** Main-process broker; other app processes enter through the private input gateway. */
object VdmHostLink {
    private val lock = Object()
    // A replacement must not claim the previous token service while it is still retiring.
    // Never acquire this lock while holding the short-lived broker lock.
    private val poolLifecycle = Any()
    private var activationToken: String? = null
    private var generation = 0L
    private var pending: VdmHostRendezvous? = null
    private var host: IVdmDesktopHost? = null
    private var acceptedNonce: String? = null
    private var session: IVdmDesktopSession? = null
    private var opening = false
    private data class PoolLaunch(val ticket: VdmHostRendezvous, val pool: AppOwnedVdmPool, val callbacks: Bundle)
    private var poolLaunch: PoolLaunch? = null
    private val appLifetime = Binder()
    @Volatile var lastError: String = ""
        private set

    private fun hostAlive() = (host as? AppOwnedVdmPool)?.ready ?: (host?.asBinder()?.isBinderAlive == true)
    val isReady: Boolean get() = synchronized(lock) { clearDeadHostLocked(); hostAlive() }
    val adbIndependent: Boolean get() = synchronized(lock) { (host as? AppOwnedVdmPool)?.ready == true }
    val nativeHostLifetime: IBinder? get() = synchronized(lock) {
        clearDeadHostLocked()
        (host as? AppOwnedVdmPool)?.lifetime ?: host?.asBinder()?.takeIf { it.isBinderAlive }
    }
    @JvmStatic fun beginActivation(token: String) {
        require(PhoneActivationPolicy.validToken(token))
        synchronized(lock) {
            check(activationToken == null || VdmHostPolicy.sameSecret(activationToken, token)) { "VDM activation already exists" }
            activationToken = token
        }
    }
    @JvmStatic fun endActivation() = synchronized(poolLifecycle) {
        IndependentRecoveryAccess.invalidateLiveVerification()
        val pendingPool = synchronized(lock) { poolLaunch.also { poolLaunch = null } }
        val previous = synchronized(lock) {
            activationToken = null; pending = null; acceptedNonce = null; generation++; session = null
            host.also { host = null; lock.notifyAll() }
        }
        runCatching { previous?.shutdown() }
        runCatching { pendingPool?.pool?.retire() }
    }
    @JvmStatic fun requestLaunch(): VdmHostRendezvous? = synchronized(poolLifecycle) {
        val stale = synchronized(lock) {
            poolLaunch?.takeIf { !VdmHostPolicy.launchFresh(it.ticket.createdMs, SystemClock.elapsedRealtime()) }
                ?.also { poolLaunch = null }
        }
        stale?.pool?.retire()
        synchronized(lock) {
            check(activationToken != null) { "Активируйте доступ ${DesktopTransport.shortName}" }
            clearDeadHostLocked()
            if (hostAlive()) return null
            val now = SystemClock.elapsedRealtime()
            pending?.takeIf { VdmHostPolicy.launchFresh(it.createdMs, now) } ?: VdmHostRendezvous(
                BuildConfig.APPLICATION_ID, Process.myPid(), VdmHostPolicy.nonce(), ++generation, now,
            ).also { pending = it; lastError = "" }
        }
    }
    @JvmStatic fun cancelLaunch(request: VdmHostRendezvous) = synchronized(poolLifecycle) {
        val cancelled = synchronized(lock) {
            clearDeadHostLocked()
            if (pending === request && host == null) {
                pending = null; generation++; lock.notifyAll()
                poolLaunch?.takeIf { it.ticket === request }?.also { poolLaunch = null }
            } else null
        }
        cancelled?.pool?.retire()
    }
    private fun clearDeadHostLocked() {
        if (host != null && !hostAlive()) {
            IndependentRecoveryAccess.invalidateLiveVerification()
            host = null; session = null; currentHostPid = -1
            // Old death recipients compare the captured Binder before modifying a later host.
            lock.notifyAll()
        }
    }
    @JvmStatic fun awaitReady(timeoutMs: Long): Boolean {
        require(timeoutMs in 0..60_000)
        val until = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            while (activationToken != null && !hostAlive()) {
                val left = until - SystemClock.elapsedRealtime()
                if (left <= 0) return false
                lock.wait(left)
            }
            return hostAlive()
        }
    }
    internal fun prepare(token: String?, appPid: Int): Bundle {
        requireActivation(token, appPid)
        val request = requestLaunch()
        return statusBundle().apply { request?.let { putString(VdmHostPolicy.EXTRA_NONCE, it.nonce) } }
    }
    internal fun status(token: String?, appPid: Int, nonce: String?): Bundle {
        requireActivation(token, appPid)
        synchronized(lock) {
            if (nonce != null) check(VdmHostPolicy.sameSecret(pending?.nonce ?: acceptedNonce, nonce)) { "Expired VDM launch" }
            return statusBundle()
        }
    }
    private fun requireActivation(token: String?, appPid: Int) = synchronized(lock) {
        check(appPid == Process.myPid() && VdmHostPolicy.sameSecret(activationToken, token)) { "No matching live activation" }
    }
    private fun statusBundle(): Bundle = synchronized(lock) {
        Bundle().apply {
            putInt(VdmHostPolicy.EXTRA_PROTOCOL, VdmHostPolicy.PROTOCOL)
            putInt(VdmHostPolicy.EXTRA_APP_PID, Process.myPid())
            putBoolean(VdmHostPolicy.EXTRA_READY, hostAlive())
            putString(VdmHostPolicy.EXTRA_ERROR, lastError)
            putString("status", if (hostAlive()) "ready" else "waiting")
            // hostPid is captured from the authenticated handoff, never a reentrant Binder call.
            putInt(VdmHostPolicy.EXTRA_HOST_PID, currentHostPid)
        }
    }
    private var currentHostPid = -1
    private fun poolTicket(nonce: String?, appPid: Int): VdmHostRendezvous = synchronized(lock) {
        val ticket = checkNotNull(pending)
        check(appPid == Process.myPid() && activationToken != null && host == null &&
            VdmHostPolicy.sameSecret(ticket.nonce, nonce) && VdmHostPolicy.launchFresh(ticket.createdMs, SystemClock.elapsedRealtime()))
        ticket
    }
    internal fun preparePool(context: android.content.Context, nonce: String?, appPid: Int): Bundle = synchronized(poolLifecycle) {
        val ticket = poolTicket(nonce, appPid)
        val identity = Binder.clearCallingIdentity()
        var pool: AppOwnedVdmPool? = null
        try {
            if (VdmPoolProtocol.permissions.any { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED })
                return Bundle().apply { putBoolean("enabled", false) }
            synchronized(lock) { check(poolLaunch == null) }
            pool = AppOwnedVdmPool(context.applicationContext)
            val callbacks = pool.prepareTokens()
            synchronized(lock) {
                check(pending === ticket && activationToken != null && host == null)
                poolLaunch = PoolLaunch(ticket, pool, callbacks)
            }
            return Bundle(callbacks).apply { putBoolean("enabled", true) }
        } catch (error: Exception) {
            runCatching { pool?.retire() }
            AppLog.i("VDM app-owned preparation unavailable: ${error.javaClass.simpleName}", false)
            return Bundle().apply { putBoolean("enabled", false) }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    internal fun acceptPool(nonce: String?, appPid: Int, offer: Bundle): Bundle = synchronized(poolLifecycle) {
        val ticket = poolTicket(nonce, appPid)
        val launch = synchronized(lock) { checkNotNull(poolLaunch).also { check(it.ticket === ticket) } }
        val identity = Binder.clearCallingIdentity()
        try {
            check(!offer.getBoolean("failed")) { "Native pool creation failed" }
            launch.pool.install(offer, launch.callbacks)
            val native = checkNotNull(launch.pool.lifetime)
            native.linkToDeath({
                val retained = synchronized(lock) {
                    if (host !== launch.pool) false else {
                        host = null; session = null; currentHostPid = -1
                        lastError = "Служба независимых экранов завершилась"; lock.notifyAll(); activationToken != null
                    }
                }
                if (retained) AppLog.i("VDM app-owned lifetime lost; activation retained=true", false)
                Thread({ runCatching { launch.pool.retire() } }, "hsd-vdm-pool-retire").start()
            }, 0)
            val pid = launch.pool.tokenPid
            synchronized(lock) {
                check(pending === ticket && activationToken != null && host == null && launch.pool.ready)
                host = launch.pool; poolLaunch = null; pending = null; acceptedNonce = ticket.nonce
                currentHostPid = pid; lastError = ""; lock.notifyAll()
            }
            AppLog.i("VDM app-owned pool ready: slots=${VdmPoolProtocol.names.size}; independent of adbd", false)
            return Bundle().apply { putBoolean(VdmHostPolicy.EXTRA_READY, true) }
        } catch (error: Exception) {
            synchronized(lock) { if (poolLaunch === launch) poolLaunch = null }
            runCatching { launch.pool.retire() }
            AppLog.i("VDM app-owned capability rejected: ${error.javaClass.simpleName}: ${error.message}", false)
            return Bundle().apply { putBoolean(VdmHostPolicy.EXTRA_READY, false) }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    internal fun accept(nonce: String?, appPid: Int, hostPid: Int, binder: IBinder, callingPid: Int): Bundle {
        check(hostPid == callingPid && hostPid > 0 && appPid == Process.myPid()) { "VDM host PID mismatch" }
        val ticket = synchronized(lock) {
            clearDeadHostLocked()
            val request = pending ?: error("No VDM handoff requested")
            check(activationToken != null && host == null &&
                VdmHostPolicy.sameSecret(request.nonce, nonce) &&
                VdmHostPolicy.launchFresh(request.createdMs, SystemClock.elapsedRealtime())) { "Expired VDM handoff" }
            request
        }
        val offered = IVdmDesktopHost.Stub.asInterface(binder)
        check(offered.protocol() == VdmHostPolicy.PROTOCOL && offered.hostPid() == hostPid) { "VDM protocol mismatch" }
        val death = IBinder.DeathRecipient {
            val retained = synchronized(lock) {
                if (host?.asBinder() === binder) {
                    // Retire this host's proof before any successor can obtain a new ticket.
                    IndependentRecoveryAccess.invalidateLiveVerification()
                    host = null; session = null; currentHostPid = -1
                    lastError = "Связь с независимым экраном прервалась"; lock.notifyAll()
                    activationToken != null
                } else null
            }
            if (retained != null) {
                AppLog.i("VDM host lost: generation=${ticket.generation}; activation retained=$retained", false)
            }
        }
        binder.linkToDeath(death, 0)
        synchronized(lock) {
            clearDeadHostLocked()
            if (pending !== ticket || activationToken == null || host != null || !binder.isBinderAlive) {
                binder.unlinkToDeath(death, 0); error("VDM handoff cancelled")
            }
            host = offered; pending = null; acceptedNonce = ticket.nonce; currentHostPid = hostPid
            lastError = ""; lock.notifyAll()
        }
        AppLog.i("VDM host ready: generation=${ticket.generation}; startupMs=${SystemClock.elapsedRealtime() - ticket.createdMs}", false)
        return Bundle().apply { putBoolean(VdmHostPolicy.EXTRA_READY, true); putBinder(VdmHostPolicy.EXTRA_CLIENT, appLifetime) }
    }
    @JvmStatic fun openSession(producerLifetime: IBinder, producerPid: Int): IVdmDesktopSession {
        val owner = synchronized(lock) {
            check(!opening) { "VDM session is opening" }
            checkNotNull(host).also { opening = true }
        }
        try {
            val opened = owner.openSession(producerLifetime, producerPid)
            synchronized(lock) {
                if (host !== owner || activationToken == null) { runCatching { opened.close() }; error("VDM activation ended") }
                session = opened
            }
            return opened
        } finally { synchronized(lock) { opening = false } }
    }
    /** In-process capability for this exact authenticated native family; numeric IDs are not authority. */
    internal fun captureOwnedDisplay(displayId: Int): (() -> Unit)? {
        if (displayId <= 0) return null
        val active = synchronized(lock) { session } ?: return null
        val lifetime = runCatching { active.lifetime }.getOrNull() ?: return null
        val checkCurrent = {
            check(synchronized(lock) { session === active && activationToken != null } && lifetime.isBinderAlive) {
                "Independent display session ended"
            }
            check(active.ownsDisplay(displayId) && active.displayGroup(displayId) > 0) { "Foreign independent display" }
            check(synchronized(lock) { session === active } && lifetime.isBinderAlive) { "Independent display changed" }
        }
        return if (runCatching(checkCurrent).isSuccess) checkCurrent else null
    }

    @JvmStatic @JvmOverloads fun ownsDisplay(displayId: Int, proof: IBinder? = null): Boolean {
        val active = synchronized(lock) { session?.takeIf { proof == null || it.asBinder() == proof } } ?: return false
        return runCatching { active.ownsDisplay(displayId) && active.displayGroup(displayId) > 0 }.getOrDefault(false)
    }
}
