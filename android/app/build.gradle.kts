import java.util.Date
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.CertPathBuilder
import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseKeys = providers.environmentVariable("HSD_SIGNING_PROPERTIES").orNull?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
} ?: providers.environmentVariable("HSD_KEYSTORE").orNull?.let { path ->
    val password = providers.environmentVariable("HSD_STORE_PASSWORD").get()
    Properties().apply {
        setProperty("storeFile", path)
        setProperty("storePassword", password)
        setProperty("keyAlias", providers.environmentVariable("HSD_KEY_ALIAS").getOrElse("hsd-release"))
        setProperty("keyPassword", providers.environmentVariable("HSD_KEY_PASSWORD").getOrElse(password))
    }
}

android {
    namespace = "com.hsd.universal"
    compileSdk = 37
    ndkVersion = "27.2.12479018"
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }

    defaultConfig {
        applicationId = "com.hsd.universal"
        minSdk = 29
        targetSdk = 37
        versionCode = 104
        versionName = "0.8.2"
        testInstrumentationRunner = "com.hsd.universal.DriveDeckAapProbe"
        buildConfigField("boolean", "PHONE_PROJECTION", "true")
        buildConfigField("boolean", "WIRELESS_BETA", "true")
        for (field in listOf("BRAND", "LOG_TAG", "APP_TITLE")) {
            buildConfigField("String", field, "\"HSD\"")
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    sourceSets {
        getByName("main").aidl.srcDir(rootProject.file("../tools/drivedeck-companion/aidl"))
        getByName("main").java.srcDir(rootProject.file("../tools/drivedeck-companion/shared"))
        getByName("test").kotlin.srcDir("src/testSupport/java")
        getByName("androidTest").kotlin.srcDir("src/testSupport/java")
    }

    signingConfigs {
        if (releaseKeys != null) create("publisher") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // The user-authorized ADB activation reads its challenge with Android's package-scoped run-as.
            isDebuggable = true
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("publisher")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources {
        merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        excludes += "**/*.proto"
    }
    androidResources.ignoreAssetsPatterns.add("aa")
    testOptions.unitTests.isReturnDefaultValues = true
}

abstract class CopyLogdiagRules : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rules: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val destination = outDir.file("logdiag_rules.json").get().asFile
        destination.parentFile.mkdirs()
        rules.get().asFile.copyTo(destination, overwrite = true)
    }
}

val copyLogdiagRules = tasks.register<CopyLogdiagRules>("copyLogdiagRules") {
    rules.set(rootProject.layout.projectDirectory.file("../tools/logdiag_rules.json"))
}

/** Build input stays outside source control; the signed APK owns its offline TLS identity. */
abstract class PackagePhoneIdentity : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val identity: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val authority: RegularFileProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction fun copy() {
        val source = identity.get().asFile
        check(source.length() in 1..65_536) { "Invalid HSD_PHONE_IDENTITY size" }
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply {
            source.inputStream().use { load(it, password) }
        }
        val aliases = store.aliases().toList().filter(store::isKeyEntry)
        check(aliases.size == 1) { "HSD_PHONE_IDENTITY must contain exactly one private key" }
        val chain = store.getCertificateChain(aliases.single()).map { it as X509Certificate }
        val leaf = chain.first()
        val now = Date()
        val root = authority.get().asFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        root.checkValidity(now)
        check(leaf.basicConstraints < 0 && leaf.keyUsage?.firstOrNull() != false)
        check(leaf.extendedKeyUsage?.let { "1.3.6.1.5.5.7.3.1" in it || "2.5.29.37.0" in it } != false)
        val parameters = PKIXBuilderParameters(
            setOf(TrustAnchor(root, null)),
            X509CertSelector().apply { certificate = leaf }
        ).apply {
            date = now
            isRevocationEnabled = false
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(chain)))
        }
        CertPathBuilder.getInstance("PKIX").build(parameters)
        val key = store.getKey(aliases.single(), password) as PrivateKey
        val algorithm = when (key.algorithm) { "RSA" -> "SHA256withRSA"; "EC" -> "SHA256withECDSA"; else -> error("Unsupported key") }
        val challenge = "HSD build identity validation".toByteArray()
        val signature = Signature.getInstance(algorithm).run { initSign(key); update(challenge); sign() }
        check(Signature.getInstance(algorithm).run { initVerify(leaf); update(challenge); verify(signature) })
        val output = outDir.file("projection/phone.p12").get().asFile
        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
        logger.lifecycle("Bundled AA identity verified; expires {}", leaf.notAfter.toInstant())
    }
}

val packagePhoneIdentity = tasks.register<PackagePhoneIdentity>("packagePhoneIdentity") {
    identity.set(layout.file(providers.environmentVariable("HSD_PHONE_IDENTITY").map { file(it) }))
    authority.set(layout.projectDirectory.file("src/main/res/raw/aa_google_automotive_link.der"))
    // Certificate dates must be checked on every build, including an otherwise up-to-date build.
    outputs.upToDateWhen { false }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLogdiagRules, CopyLogdiagRules::outDir)
        variant.sources.assets?.addGeneratedSourceDirectory(packagePhoneIdentity, PackagePhoneIdentity::outDir)
    }
}

dependencies {
    implementation(project(":aap-transport"))
    implementation(project(":desktop-panel-sdk"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("androidx.core:core:1.7.0")
    implementation("androidx.annotation:annotation:1.8.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.flyfishxu:kadb:2.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.83")
}
