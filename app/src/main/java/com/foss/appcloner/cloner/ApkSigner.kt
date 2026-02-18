package com.foss.appcloner.cloner

import android.content.Context
import com.foss.appcloner.util.CryptoUtils
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs the cloned APK using Google's official 'apksig' library.
 * This ensures full V1 (JAR) and V2 (APK Scheme v2) compliance.
 */
object ApkSigner {

    private const val KEYSTORE_FILE = "appcloner_signing.p12"
    private const val KEY_ALIAS     = "key0"
    private const val KEY_PASSWORD  = "appcloner"

    fun sign(context: Context, unsignedApk: File, signedApk: File) {
        val (privateKey, cert) = getOrCreateSigningKey(context)
        
        // Use Google's apksig library for V1 + V2 signing
        val signer = com.android.apksig.ApkSigner.Builder(
            listOf(
                com.android.apksig.ApkSigner.SignerConfig.Builder(
                    KEY_ALIAS,
                    privateKey,
                    listOf(cert)
                ).build()
            )
        )
        .setV1SigningEnabled(true)  // Forces JAR signing (META-INF/MANIFEST.MF)
        .setV2SigningEnabled(true)  // Forces APK Signature Scheme v2 (Required for Android 11+)
        .setMinSdkVersion(24)       // Ensures algorithms compatible with Android 7+ are used
        .setInputApk(unsignedApk)
        .setOutputApk(signedApk)
        .build()

        signer.sign()
    }

    private fun getOrCreateSigningKey(context: Context): Pair<PrivateKey, X509Certificate> {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        return if (ksFile.exists()) {
            CryptoUtils.loadKeystore(ksFile, KEY_ALIAS, KEY_PASSWORD)
        } else {
            val pair = CryptoUtils.generateSigningKey()
            CryptoUtils.saveKeystore(ksFile, pair.first, pair.second, KEY_ALIAS, KEY_PASSWORD)
            pair
        }
    }
}
