package com.foss.appcloner.cloner

import android.content.Context
import com.foss.appcloner.util.CryptoUtils
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

object ApkSigner {

    private const val KEYSTORE_FILE = "appcloner_signing.p12"
    private const val KEY_ALIAS     = "key0"
    private const val KEY_PASSWORD  = "appcloner"

    fun sign(context: Context, unsignedApk: File, signedApk: File) {
        val (privateKey, cert) = getOrCreateSigningKey(context)
        
        // Use Google's apksig library for V1 + V2 signing (Required for Android 11+)
        val signer = com.android.apksig.ApkSigner.Builder(
            listOf(
                com.android.apksig.ApkSigner.SignerConfig.Builder(
                    KEY_ALIAS,
                    privateKey,
                    listOf(cert)
                ).build()
            )
        )
        .setV1SigningEnabled(true)
        .setV2SigningEnabled(true) // Crucial for Android 14
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
