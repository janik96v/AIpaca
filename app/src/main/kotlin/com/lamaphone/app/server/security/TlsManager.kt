package com.lamaphone.app.server.security

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import android.security.keystore.KeyProperties

private const val TAG = "TlsManager"
private const val KEYSTORE_FILE = "tls/server.p12"
private const val KEYSTORE_PASSWORD = ""
private const val CERT_ALIAS = "lamaphone_tls"
private const val CERT_VALIDITY_DAYS = 3650L

object TlsManager {

    data class TlsConfig(
        val keyStore: KeyStore,
        val certFingerprint: String   // SHA-256 hex, colon-separated (AA:BB:CC:...)
    )

    fun getOrCreate(context: Context): TlsConfig {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        if (ksFile.exists()) {
            return try {
                load(ksFile)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load existing keystore, regenerating: ${e.message}")
                ksFile.delete()
                generate(ksFile)
            }
        }
        return generate(ksFile)
    }

    fun getCertFingerprint(context: Context): String {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        return if (ksFile.exists()) load(ksFile).certFingerprint else "NOT_GENERATED"
    }

    private fun load(ksFile: File): TlsConfig {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }
        val cert = ks.getCertificate(CERT_ALIAS) as X509Certificate
        return TlsConfig(keyStore = ks, certFingerprint = cert.sha256Fingerprint())
    }

    private fun generate(ksFile: File): TlsConfig {
        Log.i(TAG, "Generating new TLS self-signed certificate")

        // Generate RSA-2048 key pair
        val keyPairGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
        keyPairGen.initialize(2048, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()

        // Build self-signed X.509 certificate using Android's built-in BouncyCastle
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000L)
        val subject = X500Principal("CN=LamaPhone, O=LamaPhone, C=CH")

        // Use reflection to reach sun.security.x509 or android.net.http to build cert.
        // On Android API 28+ we use the Bouncy Castle provider that ships with the platform.
        val cert = buildSelfSignedCert(keyPair, subject, notBefore, notAfter)

        // Store in PKCS12 keystore
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, KEYSTORE_PASSWORD.toCharArray())
        ks.setKeyEntry(CERT_ALIAS, keyPair.private, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))

        ksFile.parentFile?.mkdirs()
        FileOutputStream(ksFile).use { ks.store(it, KEYSTORE_PASSWORD.toCharArray()) }

        val fingerprint = cert.sha256Fingerprint()
        Log.i(TAG, "TLS cert generated, fingerprint: $fingerprint")
        return TlsConfig(keyStore = ks, certFingerprint = fingerprint)
    }

    private fun buildSelfSignedCert(
        keyPair: java.security.KeyPair,
        subject: X500Principal,
        notBefore: Date,
        notAfter: Date
    ): X509Certificate {
        // Use Android's bundled BouncyCastle (org.bouncycastle) to build the cert
        try {
            val bcCertGenClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder")
            val contentSignerClass = Class.forName("org.bouncycastle.operator.jcajce.JcaContentSignerBuilder")
            val holderClass = Class.forName("org.bouncycastle.cert.X509CertificateHolder")
            val converterClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509CertificateConverter")

            val serial = BigInteger(64, SecureRandom())
            val certBuilder = bcCertGenClass
                .getConstructor(X500Principal::class.java, BigInteger::class.java, Date::class.java, Date::class.java, X500Principal::class.java, java.security.PublicKey::class.java)
                .newInstance(subject, serial, notBefore, notAfter, subject, keyPair.public)

            val signer = contentSignerClass
                .getConstructor(String::class.java)
                .newInstance("SHA256withRSA")
                .let { builder ->
                    builder.javaClass.getMethod("build", java.security.PrivateKey::class.java)
                        .invoke(builder, keyPair.private)
                }

            val holder = bcCertGenClass.getMethod("build", Class.forName("org.bouncycastle.operator.ContentSigner"))
                .invoke(certBuilder, signer)

            val converter = converterClass.getDeclaredConstructor().newInstance()
            return converterClass.getMethod("getCertificate", holderClass)
                .invoke(converter, holder) as X509Certificate

        } catch (e: Exception) {
            Log.w(TAG, "BouncyCastle cert generation via reflection failed, using fallback: ${e.message}")
            return buildSelfSignedCertFallback(keyPair, subject, notBefore, notAfter)
        }
    }

    // Fallback: use sun.security.x509 (available on Android as internal API)
    private fun buildSelfSignedCertFallback(
        keyPair: java.security.KeyPair,
        subject: X500Principal,
        notBefore: Date,
        notAfter: Date
    ): X509Certificate {
        // Use android.net.http.X509TrustManagerExtensions indirectly via KeyStore trick:
        // Generate via android.security.keystore for self-sign, then export
        // Simplest fallback on Android: use CertificateFactory from a JCA provider
        try {
            val sunX509Class = Class.forName("sun.security.x509.X509CertImpl")
            val certInfoClass = Class.forName("sun.security.x509.X509CertInfo")
            val x500NameClass = Class.forName("sun.security.x509.X500Name")
            val certnValClass = Class.forName("sun.security.x509.CertificateValidity")
            val certsNumClass = Class.forName("sun.security.x509.CertificateSerialNumber")
            val algIdClass = Class.forName("sun.security.x509.AlgorithmId")
            val certAlgClass = Class.forName("sun.security.x509.CertificateAlgorithmId")
            val certSubjClass = Class.forName("sun.security.x509.CertificateSubjectName")
            val certIssClass = Class.forName("sun.security.x509.CertificateIssuerName")
            val certKeyClass = Class.forName("sun.security.x509.CertificateX509Key")

            val serial = BigInteger(64, SecureRandom())
            val x500name = x500NameClass.getConstructor(String::class.java).newInstance("CN=LamaPhone, O=LamaPhone, C=CH")
            val validity = certnValClass.getConstructor(Date::class.java, Date::class.java).newInstance(notBefore, notAfter)
            val algOid = Class.forName("sun.security.x509.AlgorithmId")
                .getMethod("get", String::class.java).invoke(null, "SHA256withRSA")

            val info = certInfoClass.getDeclaredConstructor().newInstance()
            val setMethod = certInfoClass.getMethod("set", String::class.java, Any::class.java)
            setMethod.invoke(info, "validity", validity)
            setMethod.invoke(info, "serialNumber", certsNumClass.getConstructor(BigInteger::class.java).newInstance(serial))
            setMethod.invoke(info, "subject", certSubjClass.getConstructor(x500NameClass).newInstance(x500name))
            setMethod.invoke(info, "issuer", certIssClass.getConstructor(x500NameClass).newInstance(x500name))
            setMethod.invoke(info, "key", certKeyClass.getConstructor(java.security.PublicKey::class.java).newInstance(keyPair.public))
            setMethod.invoke(info, "algorithmID", certAlgClass.getConstructor(algIdClass).newInstance(algOid))

            val cert = sunX509Class.getConstructor(certInfoClass).newInstance(info)
            sunX509Class.getMethod("sign", java.security.PrivateKey::class.java, String::class.java)
                .invoke(cert, keyPair.private, "SHA256withRSA")
            return cert as X509Certificate

        } catch (e2: Exception) {
            throw RuntimeException("All cert generation approaches failed", e2)
        }
    }

    private fun X509Certificate.sha256Fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(encoded)
        return bytes.joinToString(":") { "%02X".format(it) }
    }
}
