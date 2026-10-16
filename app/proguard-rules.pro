# Keep model classes and media3 / okhttp
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-dontwarn okhttp3.**
-dontwarn okio.**
