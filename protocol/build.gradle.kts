// The link's message format, shared by the glasses app (:app) and the phone companion
// (:phone). Plain Kotlin/JVM: org.json comes from Android at runtime (compileOnly here), and
// from Maven for the JVM tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Bytecode 17, the apps' level, from whatever JDK runs the build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
