package com.hsd.universal

import android.app.Instrumentation
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.hsd.universal.aa.*
import com.hsd.universal.dex.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the same production methods on the previously delivered and current signed APKs. */
internal fun checkReportedUsbAndPcm(): Pair<String, Boolean> {
    val hiCar = ProjectionRoute.HICAR_USB
    val baidu = checkNotNull(com.hsd.universal.ui.UsbAccessoryPolicy.route("Baidu", "CarLife"))
    val observed = com.hsd.universal.ui.WiredAccessorySelection().observe(hiCar, setOf(baidu))
    val mixer = PlaybackVolumeMixer(listOf(false, true))
    val before = ByteArray(905 * 4) { 3 }
    val after = ByteArray(960 * 4) { 7 }
    mixer.offer(0, before, 1_000_000, 1_018_854)
    mixer.offer(1, ByteArray(960 * 4), 1_000_000, 1_020_000)
    mixer.offer(0, after, 1_027_438, 1_047_438)
    val quiet = NavigationPlaybackState(false, 900_000)
    val tail = checkNotNull(mixer.poll(1_060_000, 100, 100, quiet)).music
    val next = checkNotNull(mixer.poll(1_060_000, 100, 100, quiet)).music
    return "Baidu AOA with HiCar selected -> ${observed.name}, music tail ${tail.size / 4}/905 frames, next ${next.size / 4}/960" to
        (observed == hiCar && tail.contentEquals(before) && next.contentEquals(after))
}

/** Synthetic tones at the actual receiving channels, never user audio. */
class RoutingReceiver(val mediaRate: Int = 48000, val voiceRate: Int = 16000) {
    private val music = ByteArrayOutputStream()
    private val voice = ByteArrayOutputStream()
    val voiceStarts = AtomicInteger()
    val voiceStops = AtomicInteger()
    val mediaPackets = AtomicInteger()
    private var continuity: PcmContinuityMeter? = null
    @Synchronized fun accept(navigation: Boolean, pcm: ByteArray) {
        if (!navigation) { mediaPackets.incrementAndGet(); continuity?.accept(pcm) }
        val output = if (navigation) voice else music
        if (output.size() < 48000 * 4 * 4) output.write(pcm)
    }
    @Synchronized fun reset() { music.reset(); voice.reset() }
    @Synchronized fun beginContinuity() {
        continuity = PcmContinuityMeter(mediaRate)
    }
    @Synchronized fun endContinuity(source: String): String {
        val result = checkNotNull(continuity).reading()
        continuity = null
        val evidence = "frames=${result.frames} longestQuietMs=${result.longestQuietMs} maxPacketGapMs=${result.maxPacketGapMs}; $source"
        check(result.frames >= mediaRate * 19 && result.longestQuietMs < 20 && result.maxPacketGapMs < 100) { evidence }
        return evidence
    }
    @Synchronized fun snapshot() = music.toByteArray() to voice.toByteArray()
}

private fun tone(usage: Int, hz: Int, rightHz: Int? = null): AudioTrack {
    val channels = if (rightHz == null) 1 else 2
    val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    val samples = ShortArray(4800 * channels) { index ->
        val frequency = if (rightHz != null && index % 2 == 1) rightHz else hz
        (2000 * kotlin.math.sin(2 * Math.PI * frequency * (index / channels) / 48000)).toInt().toShort()
    }
    return AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(mask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build().also {
            check(it.write(samples,0,samples.size) == samples.size)
            check(it.setLoopPoints(0,4800,-1) == AudioTrack.SUCCESS)
        }
}

internal fun checkRestrictedFocus(receiver: RoutingReceiver, hu: net.prototux.aare.aap.sim.HeadUnitSimulator): String {
    fun await(label: String, ready: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (!ready()) { check(SystemClock.elapsedRealtime() < until) { label }; SystemClock.sleep(40) }
    }
    val media = tone(AudioAttributes.USAGE_MEDIA, 440)
    val voice = tone(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, 880)
    try {
        media.play()
        await("MEDIA_ONLY never carried music") { hu.nonSilentAudioChunks.get() >= 30 }
        voice.play()
        await("GUIDANCE_ONLY never carried speech") { receiver.snapshot().second.size > receiver.voiceRate &&
                DesktopPcmFrames.peak(receiver.snapshot().second) > 100 }
        val mediaPackets = receiver.mediaPackets.get()
        SystemClock.sleep(400)
        check(receiver.mediaPackets.get() == mediaPackets) { "Media transmitted under the guidance-only grant" }
        voice.pause()
        await("Last speech packet did not release the guidance-only grant") { receiver.voiceStops.get() > 0 }
        await("Music did not resume on returned media focus") { receiver.mediaPackets.get() > mediaPackets + 15 }
        return "Media-only to guidance-only to media-only: received speech, media stopped on its channel and resumed after speech"
    } finally { media.release(); voice.release() }
}

internal fun checkCapturedRouting(test: Instrumentation, receiver: RoutingReceiver, separate: Boolean,
                                  movingDisplay: Int? = null): String {
    val evidence = java.io.File(test.targetContext.cacheDir,"qa-routing").apply { mkdirs() }
    evidence.listFiles()?.forEach { it.delete() }
    fun save(name: String, data: Pair<ByteArray,ByteArray>) {
        java.io.File(evidence,"$name-media.pcm").writeBytes(data.first)
        java.io.File(evidence,"$name-navigation.pcm").writeBytes(data.second)
        java.io.File(evidence,"format.txt").writeText("PCM16 LE; media=${receiver.mediaRate}/stereo; navigation=${receiver.voiceRate}/mono; separate=$separate")
    }
    fun measure(pcm: ByteArray, rate: Int, channels: Int, hz: Int, channel: Int = 0): Double {
        val count = rate / 20
        check(pcm.size >= count * channels * 2) { "Too little PCM: ${pcm.size} bytes" }
        return (0 until pcm.size / (2 * channels) - count + 1 step count).map { start ->
            var re = 0.0; var im = 0.0
            repeat(count) { i ->
                val at = ((start+i)*channels + channel)*2
                val value = ((pcm[at].toInt() and 255) or (pcm[at+1].toInt() shl 8)).toShort().toDouble()
                val angle = 2*Math.PI*hz*i/rate
                re += value*kotlin.math.cos(angle); im += value*kotlin.math.sin(angle)
            }
            2*kotlin.math.hypot(re,im)/count
        }.average()
    }
    fun snapshot(): Pair<ByteArray,ByteArray> {
        Thread.sleep(450)
        receiver.reset()
        val deadline = SystemClock.elapsedRealtime()+6000
        while (receiver.snapshot().first.size < receiver.mediaRate * 4 && SystemClock.elapsedRealtime()<deadline) Thread.sleep(10)
        return receiver.snapshot().also { check(it.first.size >= receiver.mediaRate*4) { "Media capture stalled: ${DesktopAudioState.error}" } }
    }
    val media = tone(AudioAttributes.USAGE_MEDIA,440, rightHz = if (separate && movingDisplay == null) 660 else null)
    val voice = tone(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,880)
    try {
        media.play(); voice.play()
        // Registration can replace an already-created AudioTrack. Measure steady playback only
        // after real received PCM, rather than letting the registration's initial silence set gain.
        val readyAt = SystemClock.elapsedRealtime()+6000
        while (receiver.snapshot().first.size < receiver.mediaRate*4 && SystemClock.elapsedRealtime()<readyAt) Thread.sleep(10)
        check(receiver.snapshot().first.size >= receiver.mediaRate*4) {
            "Capture did not start: ${DesktopAudioState.status(test.targetContext)}; ${DesktopAudioState.error}"
        }
        val first = snapshot()
        save("playing",first)
        val main = measure(first.first,receiver.mediaRate,2,440)
        val leaked = measure(first.first,receiver.mediaRate,2,880)
        check(main>100)
        if (movingDisplay != null) {
            voice.pause()
            val page = openCarLifeTouchMarker(test, movingDisplay, 0)
            try {
                Thread.sleep(500)
                val source = PcmContinuityMeter()
                val before = media.underrunCount
                DesktopAudioState.nativeMediaMeter = source
                receiver.beginContinuity()
                Thread.sleep(20_000)
                return receiver.endContinuity("native=${source.reading()} playerUnderruns=${media.underrunCount - before}")
            } finally {
                DesktopAudioState.nativeMediaMeter = null
                test.runOnMainSync(page::finish)
            }
        }
        if (separate) {
            check(leaked < main*.05) { "Guidance leaked into MEDIA: $leaked/$main" }
            val right = measure(first.first,receiver.mediaRate,2,660,1)
            check(right > 100 && measure(first.first,receiver.mediaRate,2,440,1) < right*.05 &&
                measure(first.first,receiver.mediaRate,2,660) < main*.05 &&
                measure(first.first,receiver.mediaRate,2,880,1) < right*.05) { "Stereo or navigation leak: left=$main right=$right" }
            val speech = measure(first.second,receiver.voiceRate,1,880)
            check(speech>100 && measure(first.second,receiver.voiceRate,1,440)<speech*.05 &&
                measure(first.second,receiver.voiceRate,1,660)<speech*.05)
            check(receiver.voiceStarts.get()>0)
        } else check(leaked>100 && first.second.isEmpty()) { "Mixed fallback lost/duplicated navigation" }
        repeat(2) {
            val stops = receiver.voiceStops.get()
            voice.pause()
            val quiet = snapshot()
            save("paused-$it",quiet)
            val level = measure(quiet.first,receiver.mediaRate,2,440)
            val voiceLeak = measure(quiet.first,receiver.mediaRate,2,880)
            check(level/main in .8..1.2 && voiceLeak<main*.05) {
                "Music changed after guidance paused: level=$level baseline=$main voiceLeak=$voiceLeak separate=$separate"
            }
            val frames = receiver.mediaRate/20
            val minimum = (0 until quiet.first.size/4-frames+1 step frames).minOf { start ->
                kotlin.math.sqrt((start until start+frames).sumOf { i ->
                    val value = ((quiet.first[4*i].toInt() and 255) or (quiet.first[4*i+1].toInt() shl 8)).toShort().toDouble()
                    value*value
                } / frames)
            }
            check(minimum*kotlin.math.sqrt(2.0)>=main*.8) { "Music dropout while guidance was silent: RMS=$minimum baseline=$main" }
            if (separate) {
                val deadline = SystemClock.elapsedRealtime()+2500
                while (receiver.voiceStops.get()==stops && SystemClock.elapsedRealtime()<deadline) Thread.sleep(20)
                check(receiver.voiceStops.get()>stops) { "Speech channel held after player paused" }
            }
            voice.play()
            val resumed = snapshot()
            check(measure(resumed.first,receiver.mediaRate,2,440)/main in .8..1.2)
            if (separate) check(measure(resumed.second,receiver.voiceRate,1,880)>100)
            else check(measure(resumed.first,receiver.mediaRate,2,880)>100)
        }
        return "${if (separate) "440 Hz left / 660 Hz right / 880 Hz guidance, each isolated at receiver" else "440 Hz music / 880 Hz guidance mixed output"}; two speech endings/restarts, no silent 50 ms music blocks"
    } finally { media.release(); voice.release() }
}

internal fun checkCarLifeRouting(test: Instrumentation): String {
    val context = test.targetContext
    val prefs = DexPrefs(context)
    val settings = context.getSharedPreferences("hsd_dex", android.content.Context.MODE_PRIVATE)
    val oldSeparate = prefs.separateNavigationAudio
    val oldMusic = prefs.musicVolume; val oldNavigation = prefs.navigationVolume; val oldApps = prefs.navigationAudioApps
    val results = arrayListOf<String>()
    try {
        prefs.musicVolume=100; prefs.navigationVolume=100; prefs.navigationAudioApps=emptySet()
        for (mode in listOf("mixed","separate","blocked-video")) {
            val separate = mode == "separate"
            prefs.separateNavigationAudio=separate
            val received = RoutingReceiver()
            val link = object : CarlifeLink {
                override fun sendVideo(payload:ByteArray)=true
                override fun showPlaceholder(text:String) {}
                override fun hidePlaceholder() {}
                override fun sendModuleStatus(module:Int,status:Int)=true
                override fun sendAudio(channel:Int,type:Int,payload:ByteArray):Boolean {
                    when(type) {
                        CarlifeAudio.MEDIA_DATA -> received.accept(false,payload)
                        CarlifeAudio.TTS_DATA -> received.accept(true,payload)
                        CarlifeAudio.TTS_INIT -> received.voiceStarts.incrementAndGet()
                        CarlifeAudio.TTS_STOP -> received.voiceStops.incrementAndGet()
                    }
                    return true
                }
            }
            val core = AaBridgeCore({message,ui -> AppLog.i(message,ui)}).apply { desktopInputEnabled=true }
            core.attach(link); core.onCarlifeStart()
            val capture = DesktopAudioCapture(context)
            val locked = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            var locker: Thread? = null
            try {
                if (mode == "blocked-video") {
                    val output = object : DesktopProjectionOutput {
                        override fun video(payload:ByteArray,ptsUs:Long)=true
                        override fun audio(type:Int,pcm:ByteArray,ptsUs:Long):Boolean {
                            if(type==CarlifeAudio.MEDIA_DATA) received.accept(false,pcm)
                            return true
                        }
                        override fun audioRate()=48000
                        override fun audioOutputAvailable()=true
                        override fun freshVideo()=true
                    }
                    test.runOnMainSync { capture.attach(core,true,sink=ProjectionCapturedPlayback(output)) { true } }
                    locker = kotlin.concurrent.thread(name="QA blocked legacy video") {
                        synchronized(core) { locked.countDown(); check(release.await(30,java.util.concurrent.TimeUnit.SECONDS)) }
                    }
                    check(locked.await(2,java.util.concurrent.TimeUnit.SECONDS))
                } else test.runOnMainSync { capture.attach(core,true) { true } }
                results += checkCapturedRouting(test,received,separate)
            } finally {
                release.countDown(); locker?.join(1000)
                test.runOnMainSync { capture.close() }; core.onCarlifeClosed(link)
                Thread.sleep(400)
            }
        }
    } finally {
        check(settings.edit().putBoolean("separate_navigation_audio",oldSeparate).putInt("music_volume",oldMusic)
            .putInt("navigation_volume",oldNavigation).putStringSet("navigation_audio_apps",oldApps).commit())
    }
    return results.joinToString("; ")
}
