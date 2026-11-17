package com.hsd.universal

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AutomaticAaIdentityTest {
    private fun references(): AaCertificateReference {
        val factory = CertificateFactory.getInstance("X.509")
        fun read(name: String) = File("src/main/res/raw/$name.der").inputStream().use {
            factory.generateCertificate(it) as X509Certificate
        }
        return AaCertificateReference(read("aa_google_automotive_link"), read("aa_car_service"))
    }
    private fun bundled(): File = File(requireNotNull(System.getenv("HSD_PHONE_IDENTITY")))
    private fun read(now: Instant = Instant.now()) = bundled().inputStream().use {
        AutomaticAaIdentity.readStore(it, references(), now)
    }

    @Test fun bundleIsTrustedAndWorksWithoutAnInstalledApplication() {
        val identity = read()
        val selected = AutomaticAaIdentity.choose(listOf("bundled" to { identity }, "installed" to { null })) { _, _ -> fail() }
        assertSame(identity, selected!!.identity)
        assertEquals("bundled", selected.source)
        AaApkIdentity.verifyKey(identity)
    }

    @Test fun brokenOptionalApkCannotDisableTheBundledIdentity() {
        val identity = read()
        val failures = mutableListOf<String>()
        val selected = AutomaticAaIdentity.choose(listOf("bundled" to { identity },
            "installed" to { throw IllegalStateException("Unrecognized APK format") })) { name, _ -> failures += name }
        assertSame(identity, selected!!.identity)
        assertEquals(listOf("installed"), failures)
    }

    @Test fun choosesLatestValidityAndKeepsBundleOnTie() {
        // Providers validate trust before selection; these independent fixtures only exercise ordering.
        fun fixture(years: Int) = SelfSignedCertificate.generate("Ordering fixture", years).let {
            PhoneIdentity(it.certificate, it.keyPair.private)
        }
        val identity = fixture(2)
        val earlier = fixture(1)
        val selected = AutomaticAaIdentity.choose(listOf("bundled" to { identity }, "installed" to { identity })) { _, _ -> fail() }
        assertEquals("bundled", selected!!.source)
        for (providers in listOf(listOf("bundled" to { identity }, "installed" to { earlier }),
            listOf("installed" to { earlier }, "bundled" to { identity }))) {
            assertSame(identity, AutomaticAaIdentity.choose(providers) { _, _ -> fail() }!!.identity)
        }
    }

    @Test fun comparesBundleWithOptionalLocallySuppliedApk() {
        val path = System.getenv("HSD_AA_APK")
        assumeTrue("Set HSD_AA_APK for the local APK integration test", !path.isNullOrBlank())
        val identity = read()
        val apk = File(path!!)
        val installed = AaApkIdentity.extract(listOf(apk), references(), Instant.now())
        for (providers in listOf(listOf("bundled" to { identity }, "installed" to { installed }),
            listOf("installed" to { installed }, "bundled" to { identity }))) {
            val winner = AutomaticAaIdentity.choose(providers) { _, _ -> fail() }!!
            assertEquals(maxOf(identity.certificate.notAfter, installed.certificate.notAfter), winner.identity.certificate.notAfter)
        }
    }

    @Test fun cancellationDoesNotFallThroughToAnotherIdentity() {
        try {
            assertThrows(InterruptedException::class.java) {
                AutomaticAaIdentity.choose(listOf("cancelled" to { throw InterruptedException() },
                    "must not run" to { fail("Identity selection continued after cancellation"); null })) { _, _ -> fail() }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun rejectsExpiredFutureCorruptAndIndependentIdentity() {
        val identity = read()
        assertThrows(Exception::class.java) { read(identity.certificate.notAfter.toInstant().plusSeconds(1)) }
        assertThrows(Exception::class.java) { read(identity.certificate.notBefore.toInstant().minusSeconds(1)) }
        assertThrows(Exception::class.java) { AutomaticAaIdentity.readStore(byteArrayOf(1, 2, 3).inputStream(), references(), Instant.now()) }
        assertThrows(Exception::class.java) { AutomaticAaIdentity.readStore(ByteArray(65_537).inputStream(), references(), Instant.now()) }
        val generated = SelfSignedCertificate.generate("Independent fixture")
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, CharArray(0))
            setKeyEntry("phone", generated.keyPair.private, CharArray(0), arrayOf(generated.certificate))
        }
        val bytes = java.io.ByteArrayOutputStream().also { store.store(it, CharArray(0)) }.toByteArray()
        assertThrows(Exception::class.java) { AutomaticAaIdentity.readStore(bytes.inputStream(), references(), Instant.now()) }
        store.setKeyEntry("phone", generated.keyPair.private, CharArray(0), arrayOf(identity.certificate))
        val wrongKey = java.io.ByteArrayOutputStream().also { store.store(it, CharArray(0)) }.toByteArray()
        assertThrows(Exception::class.java) { AutomaticAaIdentity.readStore(wrongKey.inputStream(), references(), Instant.now()) }
        assertNull(AutomaticAaIdentity.choose(listOf("bad" to { read(identity.certificate.notAfter.toInstant().plusSeconds(1)) })) { _, _ -> })
    }
}
