# ONNX Runtime Mobile (S24 Ultra). Keep the runtime if it is linked later; R8 must not strip it.
-keep class ai.onnxruntime.** { *; }

# Kalshi Odds — keep kotlinx.serialization DTOs if minify is enabled later
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
