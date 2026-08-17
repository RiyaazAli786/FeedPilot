# Keep Retrofit / kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.feedpilot.client.**$$serializer { *; }
-keep class com.feedpilot.client.data.remote.dto.** { *; }
-keepclassmembers class com.feedpilot.client.data.remote.dto.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers @androidx.room.Dao interface * { *; }
-keep class com.feedpilot.client.data.local.** { *; }
-keepclassmembers class com.feedpilot.client.data.local.** { *; }

# Tink (via androidx.security.crypto) references errorprone annotations not on the classpath
-dontwarn com.google.errorprone.annotations.**
# The Instagram sealed-box password crypto calls Tink's X25519 directly; keep it and the
# curve arithmetic it pulls in intact so R8 can't drop or reshape the primitive.
-keep class com.google.crypto.tink.subtle.X25519 { *; }
-keep class com.google.crypto.tink.subtle.Field25519 { *; }
-keep class com.google.crypto.tink.subtle.Curve25519 { *; }

# EncryptedSharedPreferences reaches its key managers and proto types by name, so shrinking or
# renaming them turns into a GeneralSecurityException the first time the store is opened —
# which is during startup, on the main thread. Keep the whole Tink surface it registers.
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.proto.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }

# ---- Retrofit / OkHttp (network layer) ----
-keepattributes Signature, Exceptions
-keepclasseswithmembers,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.feedpilot.client.data.remote.ApiService { *; }
-dontwarn okhttp3.internal.**
-dontwarn okio.**

# ---- WorkManager ----
# A periodic request stores its worker's class NAME in WorkManager's database and reflects it
# back at execution time, including after an app update. Let R8 rename or drop a worker and the
# stored name no longer resolves, so keep them and the constructor WorkManager calls.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.feedpilot.client.worker.** { *; }

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---- kotlinx.serialization ----
# The rule below this used to cover only data.remote.dto, which silently left every
# @Serializable model living anywhere else without a serializer at runtime.
-keepclassmembers @kotlinx.serialization.Serializable class com.feedpilot.client.** {
    *** Companion;
    *** serializer(...);
}
-keep,includedescriptorclasses class com.feedpilot.client.**$$serializer { *; }

# ---- Anti-Patching & Obfuscation Guards ----
-repackageclasses ''
-allowaccessmodification
# Line numbers must survive obfuscation, otherwise a stack trace off a user's device names the
# method and nothing else. The source file name is still renamed, so this gives up no layout.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepclassmembers class com.feedpilot.client.security.AntiPatchGuard {
    public static *** verifyAppIntegrity(...);
    public static *** getAppSignatureSha256(...);
    public static *** isHookingFrameworkPresent(...);
    public static *** isDebuggerAttached(...);
}

# Strip debug logging calls in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
