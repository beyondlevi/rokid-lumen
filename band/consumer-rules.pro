# For an app that shrinks its code (R8): the Rust bridge's JNI symbols
# (Java_dev_lumen_band_Bridge_*) bind to this class and its methods by name.
-keep class dev.lumen.band.Bridge { *; }
