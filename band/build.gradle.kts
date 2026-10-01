// The band's link, shared by the glasses app (:app) and the phone companion (:phone): the Rust
// bridge (built into src/main/jniLibs by ../build-rust.sh), the Bluetooth connection and the
// owner key. The bridge's JNI names are bound to dev.lumen.band.Bridge: keep its names if the
// apps ever shrink their code.
plugins {
    id("com.android.library")
}

android {
    lint {
        warning += "MissingTranslation"
    }

    namespace = "dev.lumen.band"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // L2CAP connection-oriented channels need API 29.
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a") }
        // Keeps Bridge's names in an app built with R8 (the companion's release build).
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
