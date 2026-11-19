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
        versionCode = 115
        versionName = "0.8.3"
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
        getByName("main").aidl.directories.add(rootProject.file("../tools/drivedeck-companion/aidl").absolutePath)
        getByName("main").java.directories.add(rootProject.file("../tools/drivedeck-companion/shared").absolutePath)
        getByName("test").kotlin.directories.add(file("src/testSupport/java").absolutePath)
        getByName("test").java.directories.add(file("src/testSupport/java").absolutePath)
        getByName("androidTest").kotlin.directories.add(file("src/testSupport/java").absolutePath)
        getByName("androidTest").java.directories.add(file("src/testSupport/java").absolutePath)
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("publisher")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The universal APK is distributed directly. Android extracts only the device's native ABI.
    packaging.dex.useLegacyPackaging = true
    packaging.jniLibs.useLegacyPackaging = true
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

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLogdiagRules, CopyLogdiagRules::outDir)
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

// HiChain is built from pinned public source, never from a head-unit firmware binary.
val buildHiCarAuth = tasks.register<Exec>("buildHiCarAuth") {
    val source = rootProject.file("../vendor/hicar-auth")
    inputs.dir(source)
    inputs.file(rootProject.file("../tools/hicar-auth/build.py"))
    outputs.dir(layout.buildDirectory.dir("generated/hicar-libs"))
    outputs.dir(layout.buildDirectory.dir("generated/hicar-assets"))
    environment("ANDROID_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath)
    commandLine("python3", rootProject.file("../tools/hicar-auth/build.py").absolutePath)
    if (gradle.startParameter.isOffline) args("--offline")
}
android.sourceSets.getByName("main") {
    jniLibs.directories.add(layout.buildDirectory.dir("generated/hicar-libs").get().asFile.absolutePath)
    assets.directories.add(layout.buildDirectory.dir("generated/hicar-assets").get().asFile.absolutePath)
}
tasks.named("preBuild").configure { dependsOn(buildHiCarAuth) }
