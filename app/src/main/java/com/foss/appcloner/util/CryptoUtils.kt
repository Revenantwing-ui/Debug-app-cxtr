package com.foss.appcloner.util

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*

object CryptoUtils {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Generate a self-signed RSA key pair and certificate for APK signing.
     * The same key is used for all clones (deterministic from the app's install).
     */
    fun generateSigningKey(): Pair<PrivateKey, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val notBefore = Date()
        val notAfter  = Date(notBefore.time + 30L * 365 * 24 * 60 * 60 * 1000) // 30 years

        val subject = X500Name("CN=AppCloner FOSS, OU=None, O=None, L=None, ST=None, C=US")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(SecureRandom().nextLong().and(Long.MAX_VALUE)),
            notBefore,
            notAfter,
            subject,
            kp.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(kp.private)

        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))

        return kp.private to cert
    }

    /** Save a key pair to a PKCS12 keystore file. */
    fun saveKeystore(
        file: java.io.File,
        privateKey: PrivateKey,
        cert: X509Certificate,
        alias: String = "key0",
        password: String = "appcloner"
    ) {
        val ks = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
        ks.load(null, null)
        ks.setKeyEntry(alias, privateKey, password.toCharArray(), arrayOf(cert))
        file.outputStream().use { ks.store(it, password.toCharArray()) }
    }

    /** Load a key pair from a PKCS12 keystore file. */
    fun loadKeystore(
        file: java.io.File,
        alias: String = "key0",
        password: String = "appcloner"
    ): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
        file.inputStream().use { ks.load(it, password.toCharArray()) }
        val key  = ks.getKey(alias, password.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        return key to cert
    }
}
