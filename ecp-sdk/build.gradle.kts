// The Emulator Control Protocol Android SDK (MIT, see LICENSE), vendored from
// https://github.com/Denarmo/emulator-control-protocol (sdk/android/ecp, commit b46a352). Only this build file
// differs: it uses AGP 9's built-in Kotlin, like the app, instead of the Kotlin plugin.
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.ecp"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
