# Agoria ProGuard/R8 rules

# Keep annotations, generic signatures and source attributes used by reflection-based libraries.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Room entities and generated code
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Kotlin metadata
-keep class kotlin.Metadata { *; }

# OkHttp / Okio optional platform dependencies
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**