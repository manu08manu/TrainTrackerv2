# Kafka Clients Missing Classes
-dontwarn org.apache.kafka.**
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.xerial.snappy.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn javax.security.sasl.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.security.auth.spi.**
-dontwarn javax.security.auth.callback.**
-dontwarn javax.security.auth.kerberos.**

# Keep app classes but allow R8 to obfuscate/shrink them
-keep class com.traintracker.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Google Play Services Location
-dontwarn com.google.android.gms.location.**
-keep class com.google.android.gms.location.** { *; }

# Remove all logging in debug/release if possible
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
