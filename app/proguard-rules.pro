# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.arena.tv.**$$serializer { *; }
-keepclassmembers class com.arena.tv.** { *** Companion; }
-keepclasseswithmembers class com.arena.tv.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.xmlpull.v1.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Chromecast: the options provider is only named in the manifest.
-keep class com.arena.tv.ui.player.CastOptionsProvider { *; }

# FFmpeg audio decoder is loaded by name.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
