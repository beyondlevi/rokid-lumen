// Plain Android (no AndroidX); AGP 9 compiles the Kotlin and Java sources itself.
// The band's link and the Rust bridge come from :band (built by ../build-rust.sh).
// The self-arm's two shell helpers live in ../tools and are packaged as raw resources.
plugins {
    id("com.android.application")
}

// Release signing comes from the environment (see .github/workflows/release.yml). Without
// KEYSTORE_PATH the release build is left unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
// A GitHub Actions run for a tag (v0.1.0) names the version after it.
val releaseTag: String? = System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }

/**
 * The versionCode of a release tag, growing with the version as Android needs for an update:
 * (major*10000 + minor*100 + patch)*100, plus the pre-release's last number (beta.5 → 5) or 99 for
 * the release itself (v0.2.0-beta.5 → 20005, v0.2.0 → 20099). Null for a tag that isn't one.
 */
fun versionCodeOf(tag: String?): Int? {
    val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z.]*?(\d+)?)?$""").matchEntire(tag ?: return null) ?: return null
    val (major, minor, patch, pre) = match.destructured
    val base = (major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()) * 100
    val prerelease = tag.contains('-')
    return base + if (prerelease) (pre.toIntOrNull() ?: 0).coerceAtMost(98) else 99
}

android {
    // A missing translation falls back to English (AGENTS.md): not a build error.
    lint {
        warning += "MissingTranslation"
    }

    namespace = "dev.lumen.glasses"
    // GeckoView 156 is built against Android 37.1; targetSdk (below) stays at the Rokid's 34.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.lumen.glasses"
        // L2CAP connection-oriented channels need API 29; the self-arm needs 30 (it says so).
        minSdk = 29
        // The Rokid firmware is tuned for apps at 34.
        targetSdk = 34
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: versionCodeOf(releaseTag) ?: 1
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
            // No R8: the JNI class dev.lumen.band.Bridge must keep its names.
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").res.directories.add(layout.buildDirectory.dir("generated/self-arm-res").get().asFile.path)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        // GeckoView's libxul is ~150 MB stored; compressed in the APK it's a fraction of that
        // (Android extracts native libraries at install).
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/versions/**"
        }
    }
}

val syncSelfArmScripts by tasks.registering(Copy::class) {
    from(rootProject.file("tools/neuralband-shortcut-bridge.sh")) { rename { "neuralband_shortcut_bridge.sh" } }
    from(rootProject.file("tools/neuralband-a11y-watchdog.sh")) { rename { "neuralband_a11y_watchdog.sh" } }
    into(layout.buildDirectory.dir("generated/self-arm-res/raw"))
}

tasks.named("preBuild") {
    dependsOn(syncSelfArmScripts)
}

// Local unit tests (./gradlew testDebugUnitTest) cover the gesture mapping and the simulator.
dependencies {
    implementation(project(":protocol"))
    implementation(project(":band"))
    // ADB over loopback for the self-arm (pairing and shell), as in R08 Access Bridge.
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Document-start script injection for the MRBD web app host (WebAppActivity).
    implementation("androidx.webkit:webkit:1.12.1")
    // Offline speech to text for the web app composer (the glasses have no RecognitionService).
    implementation("com.alphacephei:vosk-android:0.3.75")
    // CXR-S messages to the phone companion, which dictates from the glasses' mic over CXR-L.
    // The same bridge version the companion's CXR-L SDK is built with (no audio-service
    // bind loop, unlike 1.4).
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260715.121510-107")
    // Spike: GeckoView as the web app engine, measured against the system WebView.
    implementation("org.mozilla.geckoview:geckoview-omni-arm64-v8a:156.0.20260921121718")
    testImplementation("junit:junit:4.13.2")
    // The self-arm tests (from R08 Access Bridge) need Android's Context and resources.
    testImplementation("org.robolectric:robolectric:4.13")
    // android.jar's org.json is stubs in local unit tests; the real one goes first.
    testImplementation("org.json:json:20260814")
}
