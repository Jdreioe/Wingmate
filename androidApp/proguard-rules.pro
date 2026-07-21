# ──────────────────────────────────────────────────────────────
# Wingmate – R8 / ProGuard rules for release builds
# ──────────────────────────────────────────────────────────────

# ── General ──────────────────────────────────────────────────
# Keep source-file / line-number info for crash-stack readability
-keepattributes SourceFile,LineNumberTable
# Rename source-file attr to a shorter value to save space
-renamesourcefileattribute SourceFile

# ── Kotlin / kotlinx.serialization ──────────────────────────
# kotlinx.serialization generates companion serializers via reflection
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `@Serializable` classes and their generated serializers
-keep,includedescriptorclasses class io.github.jdreioe.wingmate.**$$serializer { *; }
-keepclassmembers class io.github.jdreioe.wingmate.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.jdreioe.wingmate.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all classes annotated with @Serializable
-if @kotlinx.serialization.Serializable class **
-keep class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Ktor ─────────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Koin (DI) ────────────────────────────────────────────────
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# ── MSAL (Microsoft Authentication Library) ──────────────────
-keep class com.microsoft.identity.** { *; }
-dontwarn com.microsoft.identity.**
-keep class com.microsoft.aad.** { *; }
-dontwarn com.microsoft.aad.**


# ── AndroidX / Jetpack Compose ───────────────────────────────
# Compose compiler inserts metadata that R8 needs to preserve
-dontwarn androidx.compose.**

# ── OkHttp / Okio (Ktor OkHttp engine dependency) ───────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── Suppress misc warnings ──────────────────────────────────
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
