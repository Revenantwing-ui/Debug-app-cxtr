package com.foss.appcloner.cloner

import android.content.Context
import com.foss.appcloner.util.CryptoUtils
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.*
import java.util.jar.*
import java.util.zip.*

/**
 * Signs an APK with JAR/v1 signing (compatible with all Android versions).
 *
 * For a production-grade release you would add APK Signature Scheme v2/v3 on top.
 * v1 signing alone is fully functional and supported on Android 14.
 */
object ApkSigner {

    private const val KEYSTORE_FILE = "appcloner_signing.p12"
    private const val KEY_ALIAS     = "key0"
    private const val KEY_PASSWORD  = "appcloner"

    /**
     * Sign [unsignedApk] and write the result to [signedApk].
     * Uses a persisted signing key (generated once and stored in the app's files dir).
     */
    fun sign(context: Context, unsignedApk: File, signedApk: File) {
        val (privateKey, cert) = getOrCreateSigningKey(context)
        signApk(unsignedApk, signedApk, privateKey, cert)
    }

    // ─── Key management ───────────────────────────────────────────────────────

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

    // ─── V1 JAR signing ───────────────────────────────────────────────────────

    private fun signApk(
        input: File,
        output: File,
        privateKey: PrivateKey,
        cert: X509Certificate
    ) {
        val manifest = buildManifest(input)
        val sf       = buildSignatureFile(manifest)
        val sigBlock = buildSignatureBlock(sf, privateKey, cert)

        // Write new APK with META-INF entries added
        ZipInputStream(input.inputStream().buffered()).use { zin ->
            ZipOutputStream(output.outputStream().buffered()).use { zout ->
                // Copy all entries except existing META-INF/MANIFEST.MF, CERT.SF, CERT.RSA
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.name.startsWith("META-INF/")) {
                        val newEntry = ZipEntry(entry.name)
                        zout.putNextEntry(newEntry)
                        zin.copyTo(zout)
                        zout.closeEntry()
                    }
                    entry = zin.nextEntry
                }
                // Write signing files
                writeZipEntry(zout, "META-INF/MANIFEST.MF", manifest)
                writeZipEntry(zout, "META-INF/CERT.SF",     sf)
                writeZipEntry(zout, "META-INF/CERT.RSA",    sigBlock)
            }
        }
    }

    private fun buildManifest(apk: File): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("Manifest-Version: 1.0")
        sb.appendLine("Created-By: AppCloner FOSS")
        sb.appendLine()

        ZipFile(apk).use { zf ->
            for (entry in Collections.list(zf.entries())) {
                if (entry.isDirectory) continue
                val digest = sha256Base64(zf.getInputStream(entry).readBytes())
                sb.appendLine("Name: ${entry.name}")
                sb.appendLine("SHA-256-Digest: $digest")
                sb.appendLine()
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildSignatureFile(manifest: ByteArray): ByteArray {
        val mainDigest = sha256Base64(manifest)
        val sb = StringBuilder()
        sb.appendLine("Signature-Version: 1.0")
        sb.appendLine("Created-By: AppCloner FOSS")
        sb.appendLine("SHA-256-Digest-Manifest: $mainDigest")
        sb.appendLine()
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildSignatureBlock(sfBytes: ByteArray, key: PrivateKey, cert: X509Certificate): ByteArray {
        val gen = CMSSignedDataGenerator()
        val sha256Signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(key)
        gen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
            ).build(sha256Signer, cert)
        )
        gen.addCertificates(JcaCertStore(listOf(cert)))
        return gen.generate(CMSProcessableByteArray(sfBytes), true).encoded
    }

    private fun sha256Base64(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun writeZipEntry(zout: ZipOutputStream, name: String, data: ByteArray) {
        zout.putNextEntry(ZipEntry(name))
        zout.write(data)
        zout.closeEntry()
    }
}
