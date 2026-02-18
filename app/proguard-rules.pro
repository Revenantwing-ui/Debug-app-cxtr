# ── Models / DB ───────────────────────────────────────────────────────────────
-keepclassmembers class com.foss.appcloner.model.** { *; }
-keep class com.foss.appcloner.model.** { *; }
-keep class com.foss.appcloner.db.** { *; }

# ── DEX / smali toolchain ─────────────────────────────────────────────────────
-keep class org.jf.dexlib2.** { *; }
-keep class org.jf.util.** { *; }
-keep class org.jf.smali.** { *; }
-dontwarn org.jf.**

# ── APK signing (apksig) ──────────────────────────────────────────────────────
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# ── Crypto (BouncyCastle) ─────────────────────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Compression (Commons Compress) ───────────────────────────────────────────
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**

# ── Guava (used by dexlib2) ───────────────────────────────────────────────────
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**

# ── Misc ──────────────────────────────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
-dontwarn org.checkerframework.**
