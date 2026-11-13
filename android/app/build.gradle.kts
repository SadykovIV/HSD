import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseKeys = providers.environmentVariable("HSD_SIGNING_PROPERTIES").orNull?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "com.hsd.universal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hsd.universal"
        minSdk = 33
        targetSdk = 37
        versionCode = 69
        versionName = "0.7.2"
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
