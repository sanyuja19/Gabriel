# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
-keep class com.gabriel.android.protocol.** { *; }
-keep class com.gabriel.android.crypto.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Keep SecureIdentityStateManager from being obfuscated to prevent reflection issues
-keep class com.gabriel.android.identity.SecureIdentityStateManager {
    private android.content.SharedPreferences prefs;
    *;
}

# Keep all classes that might use reflection
-keep class com.gabriel.android.favorites.** { *; }
-keep class com.gabriel.android.nostr.** { *; }
-keep class com.gabriel.android.identity.** { *; }

# Room loads generated database implementations by name and invokes their no-argument
# constructors reflectively. R8 full-mode can otherwise optimize away WorkDatabase_Impl's
# constructor, causing AndroidX Startup to crash before Application.onCreate.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# Keep Tor implementation (always included)
-keep class com.gabriel.android.net.RealTorProvider { *; }

# Arti (Custom Tor implementation in Rust) ProGuard rules
-keep class info.guardianproject.arti.** { *; }
-keep class org.torproject.arti.** { *; }
-keepnames class org.torproject.arti.**
-dontwarn info.guardianproject.arti.**
-dontwarn org.torproject.arti.**

# Fix for AbstractMethodError on API < 29 where LocationListener methods are abstract
-keepclassmembers class * implements android.location.LocationListener {
    public <methods>;
}
