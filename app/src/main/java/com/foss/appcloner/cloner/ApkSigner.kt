package com.foss.appcloner.cloner

import android.content.Context
import android.util.Log
import com.android.apksig.ApkSigner as GoogleApkSigner
import com.foss.appcloner.util.CryptoUtils
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs the cloned APK using Google's official `apksig` library
 * (com.android.tools.build:apksig).
 *
 * This produces a fully compliant V1 (JAR) + V2 (APK Signature Scheme v2) signed APK,
 * compatible with Android 7+ package verification and Android 14 install enforcement.
 *
 * The signing key is generated once on first run and persisted in the app's private
 * files directory as a PKCS12 keystore.  Every clone produced by this app is signed
 * with the same key, allowing them to share the same signature-level permission
 * (IDENTITY_UPDATE) while being distinct packages.
 */
object ApkSigner {

    private const val TAG           = "ApkSigner"
    private const val KEYSTORE_FILE = "appcloner_signing.p12"
    private const val KEY_ALIAS     = "key0"
    private const val KEY_PASSWORD  = "appcloner"

    /**
     * Sign [unsignedApk] → [signedApk].
     * If [signedApk] already exists it is deleted before signing.
     */
    fun sign(context: Context, unsignedApk: File, signedApk: File) {
        require(unsignedApk.exists())     { "Unsigned APK missing: ${unsignedApk.absolutePath}" }
        require(unsignedApk.length() > 0) { "Unsigned APK is empty" }

        if (signedApk.exists()) signedApk.delete()
        signedApk.parentFile?.mkdirs()

        val (privateKey, cert) = getOrCreateSigningKey(context)

        Log.d(TAG, "Signing ${unsignedApk.name} → ${signedApk.name}")

        val signerConfig = GoogleApkSigner.SignerConfig.Builder(
            KEY_ALIAS,
            privateKey,
            listOf(cert)
        ).build()

        val signer = GoogleApkSigner.Builder(listOf(signerConfig))
            .setV1SigningEnabled(true)   // JAR signing  (META-INF/CERT.*)
            .setV2SigningEnabled(true)   // APK Signature Scheme v2  (required Android 7+)
            .setV3SigningEnabled(false)  // v3 (key rotation) — skip, not needed
            .setMinSdkVersion(26)       // Aligns with our minSdk; selects appropriate algos
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .build()

        signer.sign()

        check(signedApk.exists() && signedApk.length() > 0) {
            "apksig produced no output — signing failed silently"
        }
        Log.d(TAG, "Signed APK written: ${signedApk.length()} bytes")
    }

    // ── Key management ────────────────────────────────────────────────────────

    /**
     * Load the persisted signing key from the app's private storage, or generate
     * a fresh 2048-bit RSA key pair and save it for future use.
     */
    private fun getOrCreateSigningKey(context: Context): Pair<PrivateKey, X509Certificate> {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        return if (ksFile.exists() && ksFile.length() > 0) {
            try {
                Log.d(TAG, "Loading existing signing key from ${ksFile.name}")
                CryptoUtils.loadKeystore(ksFile, KEY_ALIAS, KEY_PASSWORD)
            } catch (e: Exception) {
                Log.w(TAG, "Keystore corrupt, regenerating: ${e.message}")
                ksFile.delete()
                generateAndSave(ksFile)
            }
        } else {
            Log.d(TAG, "Generating new signing key pair")
            generateAndSave(ksFile)
        }
    }

    private fun generateAndSave(ksFile: File): Pair<PrivateKey, X509Certificate> {
        val pair = CryptoUtils.generateSigningKey()
        CryptoUtils.saveKeystore(ksFile, pair.first, pair.second, KEY_ALIAS, KEY_PASSWORD)
        return pair
    }
}
