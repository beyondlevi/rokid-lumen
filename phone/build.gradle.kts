// Rokid Lumen Companion: runs on the phone. The Rokid glasses silence a third-party
// microphone, so dictation for the glasses' web apps listens here: the glasses' mic PCM
// arrives over Rokid's CXR-L link (through the Hi Rokid app), Vosk transcribes it, and the
// text goes back to the glasses app as CXR custom commands.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment, as for the glasses app (app/build.gradle.kts).
// Without KEYSTORE_PATH the release build is left unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
// A GitHub Actions run for a tag (v0.1.0) names the version after it.
val releaseTag: String? = System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }

android {
    // A missing translation falls back to English (AGENTS.md): not a build error.
    lint {
        warning += "MissingTranslation"
    }

    namespace = "dev.lumen.companion"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.lumen.companion"
        // CXR-L 1.0.3+ needs 31.
        minSdk = 31
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        // Release builds take the tag (v0.3.9 → 0.3.9); a local build is the work after the last release.
        versionName = releaseTag?.removePrefix("v") ?: "0.1.0-dev"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            // R8 shrinks and renames the companion's own code and its libraries; what's reached
            // by name (the Rokid SDK's reflection and JNI, JNA under Vosk, the band's JNI class)
            // is kept by proguard-rules.pro and :band's consumer rules.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // android.util.Log in the dictation session answers with defaults in unit tests.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":protocol"))
    // The band's link and the Rust bridge, shared with the glasses app.
    implementation(project(":band"))
    // The screens: Jetpack Compose with Material 3, themed from the MRBD UI Toolkit's tokens.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Rokid CXR-L: authorization through Hi Rokid, the glasses' audio, custom commands.
    implementation("com.rokid.cxr:client-l:1.1.2")
    // Offline speech to text (the same engine the glasses would use locally).
    implementation("com.alphacephei:vosk-android:0.3.75")
    // The cloud dictation engines: REST and WebSocket (as Rokid Nexus).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
