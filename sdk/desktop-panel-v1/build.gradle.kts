plugins { id("com.android.library") }

group = "com.hsd.universal"
version = "1.0.0-dev"

android {
    namespace = "com.hsd.universal.component.api"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { aidl = true; buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
