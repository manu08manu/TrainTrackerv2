# Keep all app classes (ViewModel, Activities, etc.)
-keep class com.traintracker.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }

# Android XML / DOM parsing
-keep class javax.xml.** { *; }
-keep class org.xml.sax.** { *; }
-keep class org.w3c.dom.** { *; }

# Remove all logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
