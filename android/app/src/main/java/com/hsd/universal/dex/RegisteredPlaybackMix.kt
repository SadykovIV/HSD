package com.hsd.universal.dex

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Process
import android.os.UserHandle
import java.io.Closeable

/** One atomic policy, disjoint playback routes, and their address-bound recorders. */
internal class RegisteredPlaybackMix private constructor(
    val records: List<AudioRecord>,
    val stems: List<PlaybackStem>,
    val navigationState: () -> NavigationPlaybackState?,
    private val unregister: () -> Unit
) : Closeable {
    override fun close() {
        try {
            var failure: Exception? = null
            records.forEach { record -> try { record.release() } catch (error: Exception) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } }
            failure?.let { throw it }
        } finally {
            unregister()
        }
    }

    companion object {
        // These are system APIs. Only the temporary --audio instrumentation may use them.
        @SuppressLint("PrivateApi")
        fun open(context: Context): RegisteredPlaybackMix {
            val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val selected = DexPrefs(context).navigationAudioApps
            val user = Process.myUid() / 100000
            val navigationUids = selected.mapNotNull { name ->
                try { context.packageManager.getApplicationInfo(name, 0).uid.takeIf { it / 100000 == user && it % 100000 >= 10000 } }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
            }.toSet()
            val stems = PlaybackStem.rules(navigationUids)
            val mixes = stems.map { buildMix(mixClass, it) }
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilder = Class.forName(policyClass.name + "\$Builder")
                .getConstructor(Context::class.java).newInstance(context)
            val addMix = policyBuilder.javaClass.getMethod("addMix", mixClass)
            mixes.forEach { addMix.invoke(policyBuilder, it) }
            val policy = policyBuilder.javaClass.getMethod("build").invoke(policyBuilder)
            // Resolve the cleanup method before registering anything on this ROM.
            val unregisterMethod = AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass)
            val createSink = policyClass.getMethod("createAudioRecordSink", mixClass)
            val navigation = java.util.concurrent.atomic.AtomicReference<NavigationPlaybackState?>()
            val diagnostics = runCatching { PlaybackDiagnostics(context, onPlayback = { tracks ->
                val active = navigationPlaybackActive(tracks, stems)
                if (active == null) navigation.set(null)
                else if (navigation.get()?.active != active)
                    navigation.set(NavigationPlaybackState(active, System.nanoTime() / 1000))
            }) {
                @Suppress("UNCHECKED_CAST")
                val owners = policyClass.getMethod("getFocusStack").invoke(policy) as List<Any>
                owners.takeLast(8).joinToString(" | ") { owner ->
                    fun field(name: String) = owner.javaClass.getMethod(name).invoke(owner)
                    "${field("getPackageName")}:gain=${field("getGainRequest")}/loss=${field("getLossReceived")}"
                }
            } }.getOrNull()
            val result = try { AudioManager::class.java.getMethod("registerAudioPolicy", policyClass).invoke(manager, policy) }
                catch (error: Throwable) { diagnostics?.close(); throw error }
            if (result != 0) diagnostics?.close()
            check(result == 0) { "Android отклонил регистрацию маршрута USB-звука (код $result)" }
            val unregister: () -> Unit = {
                try { unregisterMethod.invoke(manager, policy) }
                finally { diagnostics?.close() }
            }
            val records = ArrayList<AudioRecord>()
            try {
                mixes.forEach { mix ->
                    val record = createSink.invoke(policy, mix) as? AudioRecord
                    if (record != null) records.add(record)
                    check(record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                        "Android не создал источник звука для зарегистрированного маршрута"
                    }
                }
                return RegisteredPlaybackMix(records, stems, navigation::get, unregister)
            } catch (error: Throwable) {
                records.forEach { record -> runCatching { record.release() }.onFailure(error::addSuppressed) }
                runCatching(unregister).onFailure(error::addSuppressed)
                throw error
            }
        }

        @SuppressLint("PrivateApi")
        private fun buildMix(mixClass: Class<*>, stem: PlaybackStem): Any {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilder = Class.forName(ruleClass.name + "\$Builder").getConstructor().newInstance()
            val addRule = ruleBuilder.javaClass.getMethod("addRule", AudioAttributes::class.java, Int::class.java)
            val usageRule = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            for (usage in stem.usages) {
                addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), usageRule)
            }
            val userId = UserHandle::class.java.getMethod("getIdentifier").invoke(Process.myUserHandle())
            ruleBuilder.javaClass.getMethod("addMixRule", Int::class.java, Any::class.java)
                .invoke(ruleBuilder, ruleClass.getField("RULE_MATCH_USERID").getInt(null), userId)
            val uidRule = ruleClass.getField("RULE_MATCH_UID").getInt(null)
            for ((method, uids) in listOf("addMixRule" to stem.includeUids, "excludeMixRule" to stem.excludeUids)) {
                val add = ruleBuilder.javaClass.getMethod(method, Int::class.java, Any::class.java)
                uids.forEach { uid -> add.invoke(ruleBuilder, uidRule, uid) }
            }
            val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)

            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(DesktopAudioPolicy.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val mixBuilder = Class.forName(mixClass.name + "\$Builder").getConstructor(ruleClass).newInstance(rule)
            mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.java)
                .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
            return mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)
        }
    }
}
