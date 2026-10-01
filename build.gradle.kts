plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    // AGP compiles Kotlin with the Kotlin Gradle plugin on the classpath; GeckoView ships a
    // Kotlin 2.4 standard library, which the plugin's bundled 2.2 compiler can't read.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
