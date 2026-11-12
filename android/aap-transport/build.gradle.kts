plugins { id("com.android.library") }

android {
    namespace = "com.hsd.aap"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":aap-transport:wire"))
    testImplementation("junit:junit:4.13.2")
}
