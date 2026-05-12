package com.aipaca.app.server.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal
import android.security.keystore.KeyProperties

private const val TAG = "TlsManager"
private const val KEYSTORE_FILE = "tls/server.p12"
private const val KEYSTORE_PASSWORD_PREFS = "tls_keystore_password"
private const val KEYSTORE_PASSWORD_PREFS_FILE = "aipaca_tls_meta"
private const val CERT_ALIAS = "aipaca_tls"
private const val CERT_VALIDITY_DAYS = 3650L

object TlsManager {

    data class TlsConfig(
        val keyStore: KeyStore,
        val keystorePassword: String,  // random per-device password, protected by Android Keystore
        val certFingerprint: String    // SHA-256 hex, colon-separated (AA:BB:CC:...)
    )

    fun getOrCreate(context: Context, localIp: String? = null): TlsConfig {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        val password = getOrCreateKeystorePassword(context)
        if (ksFile.exists()) {
            return try {
                val config = load(ksFile, password)
                // Regenerate if the local IP changed and the cert has no matching SAN
                if (localIp != null && !certHasIpSan(config.keyStore, localIp)) {
                    Log.i(TAG, "Local IP changed to $localIp — regenerating TLS cert with updated SAN")
                    ksFile.delete()
                    generate(ksFile, password, localIp)
                } else {
                    config
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load existing keystore, regenerating: ${e.message}")
                ksFile.delete()
                generate(ksFile, password, localIp)
            }
        }
        return generate(ksFile, password, localIp)
    }

    private fun certHasIpSan(keyStore: KeyStore, ip: String): Boolean {
        return try {
            val cert = keyStore.getCertificate(CERT_ALIAS) as X509Certificate
            val sans = cert.subjectAlternativeNames ?: return false
            sans.any { it[0] == 7 && it[1] == ip } // type 7 = IP address SAN
        } catch (e: Exception) { false }
    }

    fun getCertFingerprint(context: Context): String {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        if (!ksFile.exists()) return "NOT_GENERATED"
        val password = getOrCreateKeystorePassword(context)
        return try { load(ksFile, password).certFingerprint } catch (e: Exception) { "NOT_GENERATED" }
    }

    /**
     * Returns the keystore password stored in EncryptedSharedPreferences, creating
     * a random 32-byte (256-bit) password on first call.  The password is protected
     * by the Android Keystore-backed AES-256-GCM master key.
     */
    private fun getOrCreateKeystorePassword(context: Context): String {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            KEYSTORE_PASSWORD_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val stored = prefs.getString(KEYSTORE_PASSWORD_PREFS, null)
        if (stored != null) return stored
        // Generate a random 32-byte password, base64-encoded
        val passwordBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val password = Base64.getEncoder().encodeToString(passwordBytes)
        prefs.edit().putString(KEYSTORE_PASSWORD_PREFS, password).apply()
        return password
    }

    private fun load(ksFile: File, password: String): TlsConfig {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { ks.load(it, password.toCharArray()) }
        val cert = ks.getCertificate(CERT_ALIAS) as X509Certificate
        return TlsConfig(keyStore = ks, keystorePassword = password, certFingerprint = cert.sha256Fingerprint())
    }

    private fun generate(ksFile: File, password: String, localIp: String? = null): TlsConfig {
        Log.i(TAG, "Generating new TLS self-signed certificate")

        // Generate RSA-2048 key pair
        val keyPairGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
        keyPairGen.initialize(2048, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()

        // Build self-signed X.509 certificate using Android's built-in BouncyCastle
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000L)
        val subject = X500Principal("CN=AIpaca, O=AIpaca, C=CH")

        // Use reflection to reach sun.security.x509 or android.net.http to build cert.
        // On Android API 28+ we use the Bouncy Castle provider that ships with the platform.
        val cert = buildSelfSignedCert(keyPair, subject, notBefore, notAfter, localIp)

        // Store in PKCS12 keystore
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password.toCharArray())
        ks.setKeyEntry(CERT_ALIAS, keyPair.private, password.toCharArray(), arrayOf(cert))

        ksFile.parentFile?.mkdirs()
        FileOutputStream(ksFile).use { ks.store(it, password.toCharArray()) }

        val fingerprint = cert.sha256Fingerprint()
        Log.i(TAG, "TLS cert generated, fingerprint: $fingerprint")
        return TlsConfig(keyStore = ks, keystorePassword = password, certFingerprint = fingerprint)
    }

    private fun buildSelfSignedCert(
        keyPair: java.security.KeyPair,
        subject: X500Principal,
        notBefore: Date,
        notAfter: Date,
        localIp: String? = null
    ): X509Certificate {
        // Use Android's bundled BouncyCastle (org.bouncycastle) to build the cert
        try {
            val bcCertGenClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder")
            val contentSignerClass = Class.forName("org.bouncycastle.operator.jcajce.JcaContentSignerBuilder")
            val holderClass = Class.forName("org.bouncycastle.cert.X509CertificateHolder")
            val converterClass = Class.forName("org.bouncycastle.cert.jcajce.JcaX509CertificateConverter")

            val serial = BigInteger(64, SecureRandom())
            val bcX500NameClass = Class.forName("org.bouncycastle.asn1.x500.X500Name")
            val bcSubject = bcX500NameClass.getMethod("getInstance", Any::class.java)
                .invoke(null, subject.encoded)
            val certBuilder = bcCertGenClass
                .getConstructor(bcX500NameClass, BigInteger::class.java, Date::class.java, Date::class.java, bcX500NameClass, java.security.PublicKey::class.java)
                .newInstance(bcSubject, serial, notBefore, notAfter, bcSubject, keyPair.public)

            // Add SAN with local IP so clients can verify the hostname
            if (localIp != null) {
                try {
                    val asn1OidClass    = Class.forName("org.bouncycastle.asn1.ASN1ObjectIdentifier")
                    val asn1EncClass    = Class.forName("org.bouncycastle.asn1.ASN1Encodable")
                    val generalNameClass  = Class.forName("org.bouncycastle.asn1.x509.GeneralName")
                    val generalNamesClass = Class.forName("org.bouncycastle.asn1.x509.GeneralNames")
                    val extensionClass    = Class.forName("org.bouncycastle.asn1.x509.Extension")
                    val derOctetClass     = Class.forName("org.bouncycastle.asn1.DEROctetString")

                    val inetAddress = java.net.InetAddress.getByName(localIp)
                    val ipBytes   = derOctetClass.getConstructor(ByteArray::class.java).newInstance(inetAddress.address)
                    val iPAddress = generalNameClass.getField("iPAddress").getInt(null)
                    val generalName  = generalNameClass.getConstructor(Int::class.java, asn1EncClass).newInstance(iPAddress, ipBytes)
                    val generalNames = generalNamesClass.getConstructor(generalNameClass).newInstance(generalName)
                    val sanOid       = extensionClass.getField("subjectAlternativeName").get(null)

                    certBuilder.javaClass
                        .getMethod("addExtension", asn1OidClass, Boolean::class.java, asn1EncClass)
                        .invoke(certBuilder, sanOid, false, generalNames)
                    Log.i(TAG, "Added SAN IP: $localIp")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not add SAN to cert: ${e.message}")
                }
            }

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
            // CertificateSubjectName/CertificateIssuerName no longer needed —
            // we set subject.value/issuer.value directly with X500Name
            val certKeyClass = Class.forName("sun.security.x509.CertificateX509Key")

            val serial = BigInteger(64, SecureRandom())
            val x500name = x500NameClass.getConstructor(String::class.java).newInstance("CN=AIpaca, O=AIpaca, C=CH")
            val validity = certnValClass.getConstructor(Date::class.java, Date::class.java).newInstance(notBefore, notAfter)
            val algOid = Class.forName("sun.security.x509.AlgorithmId")
                .getMethod("get", String::class.java).invoke(null, "SHA256withRSA")

            val info = certInfoClass.getDeclaredConstructor().newInstance()
            val setMethod = certInfoClass.getMethod("set", String::class.java, Any::class.java)
            setMethod.invoke(info, "validity", validity)
            setMethod.invoke(info, "serialNumber", certsNumClass.getConstructor(BigInteger::class.java).newInstance(serial))
            // Use dotted paths "subject.value" / "issuer.value" to set the X500Name directly,
            // avoiding CertificateSubjectName/CertificateIssuerName wrappers which cause
            // "Subject class type invalid" on some Android versions.
            setMethod.invoke(info, "subject.value", x500name)
            setMethod.invoke(info, "issuer.value", x500name)
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
