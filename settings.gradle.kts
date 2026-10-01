pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // kadb's SPAKE2 dependency (Wireless Debugging pairing) is published there only.
        maven { url = uri("https://jitpack.io") }
        // GeckoView (Firefox's engine), the up-to-date alternative to the glasses' Chromium 95.
        maven { url = uri("https://maven.mozilla.org/maven2/") }
        // Rokid's CXR SDKs: CXR-L on the phone, the CXR-S bridge on the glasses.
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}
rootProject.name = "rokid-lumen"
include(":app")
// The phone companion: the glasses' microphone and dictation over Rokid's CXR-L link.
include(":phone")
include(":protocol")
// The band's link (Rust bridge, Bluetooth, owner key), for the glasses and the phone.
include(":band")
