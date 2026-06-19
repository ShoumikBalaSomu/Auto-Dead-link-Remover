# Keep NanoHTTPD server classes (used via reflection)
-keep class fi.iki.elonen.** { *; }

# Keep OkHttp (uses reflection for platform detection)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep JSON parsing
-keep class org.json.** { *; }
