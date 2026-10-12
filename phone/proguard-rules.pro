# R8 rules for Rokid Lumen Companion's release build: what's reached by name at runtime.
# (The band's JNI class, dev.lumen.band.Bridge, is kept by :band's consumer-rules.pro.)

# Stack traces keep their line numbers.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Rokid's CXR-L SDK and its CXR-S bridge ship obfuscated, with JNI libraries (libcaps,
# libcxr-bridge-jni, ...) and Gson models, and CompanionService sets AuthorizationHelper's static
# fields a, b and c by reflection (preferGlobalHiRokid, restoreGrantedPermissions). Their
# consumer rules keep only part of that: the SDK stays exactly as it ships.
-keep class com.rokid.** { *; }
-keep interface com.rokid.** { *; }
-keep class com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper {
    static <fields>;
}
-dontwarn com.rokid.**

# Vosk reaches libvosk through JNA, whose native side looks up Java classes, fields and methods by
# name (Structure, Pointer, callbacks, the direct-mapped LibVosk).
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Kadb, the glasses' screen over adb (GlassesAdb, GlassesMirror), as it ships: its TLS and keys go
# through BouncyCastle, whose provider registers each algorithm by class name, and its pairing
# through spake2-java. GlassesAdb calls okio's Path.get(String) by reflection (as the glasses' self-arm
# does). The glasses app, where Kadb has always run, isn't minified.
-keep class com.flyfishxu.kadb.** { *; }
-keep class com.flyfish233.crypto.spake2.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class okio.Path { *; }
-keep class okio.Path$Companion { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.flyfishxu.kadb.**
