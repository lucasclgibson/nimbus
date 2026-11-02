# Media3 loads the FFmpeg renderer reflectively.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.media3.** { *; }

# Room generated code is referenced reflectively by the database builder.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
