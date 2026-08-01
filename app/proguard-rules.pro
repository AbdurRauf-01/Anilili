# Readable crash reports from minified builds (CrashReporter).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization — keep @Serializable metadata and generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.anilili.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.anilili.data.model.**$$serializer { *; }
-keep class com.anilili.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# secp256k1 (Nostr update-manifest signature check).
#
# Only the `Secp256k1` facade is referenced from Kotlin; it reaches its JNI backend reflectively,
# so R8 saw the implementation as unreachable and shrank it away. The facade's static initializer
# then threw ExceptionInInitializerError, which `runCatching { ... }.getOrDefault(false)` swallowed
# into "signature invalid" — the Nostr fallback channel was inert in every release build while
# still shipping ~1.3 MB of .so per ABI. Verified on an Android TV emulator: the release mapping
# contained `fr.acinq.secp256k1.Secp256k1` and nothing else from that package.
-keep class fr.acinq.secp256k1.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-dontwarn fr.acinq.secp256k1.**
