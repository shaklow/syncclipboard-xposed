# LSPosed - keep module entry
-keep class io.github.erenche.syncclipboard.xposed.ModuleEntry { *; }

# Keep serializable models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.erenche.syncclipboard.**$$serializer { *; }
-keepclassmembers class io.github.erenche.syncclipboard.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.erenche.syncclipboard.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# OKHttp
-dontwarn okhttp3.**
-dontwarn okio.**
