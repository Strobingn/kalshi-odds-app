# Kalshi Odds — keep kotlinx.serialization DTOs if minify is enabled later
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# ONNX Runtime (scalper trees): JNI looks these classes up by name.
-keep class ai.onnxruntime.** { *; }
