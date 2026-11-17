package com.hsd.universal

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.AtomicFile
import net.prototux.aare.aap.PhoneIdentity
import java.io.File
import java.io.InputStream
import java.net.URL
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Private atomic cache, independent of APK releases. A failed refresh never retires valid data. */
internal object AaIdentityUpdates {
    private const val JOB_ID = 0x48534441
    private const val DAY = 86_400_000L
    private const val RETRY = 3_600_000L
    private val updating = AtomicBoolean()
    private val completion = java.lang.Object()
    @Volatile private var completedSuccessfully = false
    private val cacheLock = Any()
    @Volatile var checking = false
        private set
    private fun prefs(context: Context) = context.getSharedPreferences("aa-identity-updates", Context.MODE_PRIVATE)
    private fun cache(context: Context) = AtomicFile(File(context.noBackupFilesDir, "aa-downloaded.json"))
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun lastCheck(context: Context) = prefs(context).getLong("lastCheck", 0)
    fun lastSucceeded(context: Context) = prefs(context).getBoolean("lastSucceeded", false)
    fun enable(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("enabled", value).apply()
        schedule(context)
        if (value) maybeCheck(context)
    }

    fun load(context: Context, references: AaCertificateReference, now: Instant): PhoneIdentity? = synchronized(cacheLock) {
        val file = cache(context)
        val input = try { file.openRead() } catch (_: java.io.FileNotFoundException) { return@synchronized null }
        input.use { AaIdentityFeed.decode(readLimited(it), references, now).identity }
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled(context)) { scheduler.cancel(JOB_ID); return }
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AaIdentityUpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setPeriodic(DAY).setBackoffCriteria(RETRY, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }

    fun maybeCheck(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        schedule(app)
        if (!force && !enabled(app)) return
        Thread({ refresh(app, force) }, "HSD-AA-identity-update").start()
    }

    /** Runs outside the UI/session lock. Also used if every offline credential has expired. */
    fun refresh(context: Context, force: Boolean = false, waitForRunning: Boolean = false): Boolean {
        if (!enabled(context) && !force) return true
        if (!updating.compareAndSet(false, true)) {
            if (!waitForRunning) return false
            val until = System.nanoTime() + 25_000_000_000L
            synchronized(completion) {
                while (updating.get()) {
                    val remaining = until - System.nanoTime()
                    if (remaining <= 0) return false
                    completion.wait((remaining / 1_000_000).coerceAtLeast(1))
                }
                return completedSuccessfully
            }
        }
        completedSuccessfully = false
        checking = true
        try {
            val state = prefs(context)
            val nowMs = System.currentTimeMillis()
            val previous = state.getLong("lastCheck", 0)
            val interval = if (lastSucceeded(context)) DAY else RETRY
            if (!force && previous > 0 && nowMs >= previous && nowMs - previous < interval) {
                completedSuccessfully = lastSucceeded(context)
                return true
            }
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val networks = (listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks).distinct()
            val internet = networks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } == true
            } ?: return false // A head-unit hotspot without Internet must not consume the retry window.
            state.edit().putLong("lastCheck", nowMs).putBoolean("lastSucceeded", false).apply()
            val deadline = System.nanoTime() + 20_000_000_000L
            val connection = internet.openConnection(URL(AaIdentityFeed.URL)) as HttpsURLConnection
            val bytes = try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "HSD/${BuildConfig.VERSION_NAME}")
                check(connection.responseCode == 200) { "Identity feed HTTP ${connection.responseCode}" }
                require(connection.contentLengthLong <= AaIdentityFeed.MAX_BYTES)
                connection.inputStream.use { readLimited(it, deadline) }
            } finally { connection.disconnect() }
            check(!Thread.currentThread().isInterrupted)
            val now = Instant.now()
            val references = AaCertificateReference.from(context)
            install(context, bytes, references, now)
            state.edit().putBoolean("lastSucceeded", true).apply()
            completedSuccessfully = true
            return true
        } catch (error: Exception) {
            // Never put envelope contents, keys or a server response body in logs.
            AppLog.i("AA identity update failed: ${error.javaClass.simpleName}; retained offline sources", false)
            return false
        } finally {
            checking = false
            synchronized(completion) { updating.set(false); completion.notifyAll() }
        }
    }

    internal fun install(context: Context, bytes: ByteArray, references: AaCertificateReference, now: Instant): Boolean {
        val candidate = AaIdentityFeed.decode(bytes, references, now)
        return synchronized(cacheLock) {
            val old = runCatching { load(context, references, now) }.getOrNull()
            // Signed certificate validity, not an untrusted JSON version counter, controls rollback.
            if (old != null && !candidate.identity.certificate.notAfter.after(old.certificate.notAfter)) {
                AppLog.i("AA identity update: cached certificate is current", false)
                false
            } else {
                val file = cache(context)
                val output = file.startWrite()
                try { output.write(bytes); file.finishWrite(output) }
                catch (error: Throwable) { file.failWrite(output); throw error }
                AppLog.i("AA identity update: verified package v${candidate.version}; expires=${candidate.identity.certificate.notAfter.toInstant()}", false)
                true
            }
        }
    }

    internal fun readLimited(input: InputStream, deadlineNanos: Long = Long.MAX_VALUE): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            check(System.nanoTime() <= deadlineNanos) { "Identity download timed out" }
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            require(count > 0 && output.size() + count <= AaIdentityFeed.MAX_BYTES) { "Identity feed exceeds limit" }
            output.write(buffer, 0, count)
        }
    }
}

class AaIdentityUpdateJob : JobService() {
    private var worker: Thread? = null
    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread({
            val success = AaIdentityUpdates.refresh(applicationContext)
            if (!Thread.currentThread().isInterrupted) jobFinished(params, !success)
        }, "HSD-AA-identity-job").also(Thread::start)
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt(); worker = null
        return true
    }
}
