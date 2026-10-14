package com.example.transport

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Imports a device-specific PKCS#12 identity and trusted root CA bundle into app-private storage.
 * The identity password is encrypted with an Android Keystore-backed MasterKey. No shared key or
 * generated certificate is embedded in the APK.
 */
class ControlPlaneCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val identityFile = File(appContext.noBackupFilesDir, IDENTITY_FILE)
    private val rootCaFile = File(appContext.noBackupFilesDir, ROOT_CA_FILE)
    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        PREFS_NAME,
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    data class IdentitySummary(
        val subject: String,
        val issuer: String,
        val sha256Fingerprint: String,
        val rootCertificateCount: Int
    )

    fun hasCredentials(): Boolean =
        identityFile.isFile && identityFile.length() > 0 &&
            rootCaFile.isFile && rootCaFile.length() > 0 &&
            !prefs.getString(KEY_PASSWORD, null).isNullOrBlank()

    fun importCredentials(
        identityInput: InputStream,
        identityPassword: CharArray,
        rootCaInput: InputStream
    ): IdentitySummary {
        require(identityPassword.isNotEmpty()) { "PKCS#12 password is required" }
        try {
            val identityBytes = identityInput.use { readLimited(it, MAX_IDENTITY_BYTES) }
            val caBytes = rootCaInput.use { readLimited(it, MAX_CA_BYTES) }
            require(identityBytes.isNotEmpty()) { "PKCS#12 identity file is empty" }
            require(caBytes.isNotEmpty()) { "Root CA bundle is empty" }

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(ByteArrayInputStream(identityBytes), identityPassword)
            val identityAlias = keyStore.aliases().toList().firstOrNull { alias ->
                keyStore.isKeyEntry(alias) &&
                    keyStore.getKey(alias, identityPassword) is java.security.PrivateKey &&
                    keyStore.getCertificate(alias) is X509Certificate
            } ?: throw SecurityException("PKCS#12 bundle has no private-key entry with an X.509 certificate")

            val leaf = keyStore.getCertificate(identityAlias) as X509Certificate
            leaf.checkValidity()
            require(leaf.basicConstraints < 0) { "Client identity certificate must not be a CA certificate" }

            val factory = CertificateFactory.getInstance("X.509")
            val roots = factory.generateCertificates(ByteArrayInputStream(caBytes))
                .map { it as? X509Certificate ?: throw SecurityException("CA bundle contains a non-X.509 certificate") }
            require(roots.isNotEmpty()) { "Root CA bundle contains no certificates" }
            roots.forEach { cert ->
                cert.checkValidity()
                require(cert.basicConstraints >= 0) { "Trusted CA bundle contains a non-CA certificate" }
            }

            val dir = appContext.noBackupFilesDir
            val identityTemp = File(dir, "$IDENTITY_FILE.tmp")
            val caTemp = File(dir, "$ROOT_CA_FILE.tmp")
            identityTemp.writeBytes(identityBytes)
            caTemp.writeBytes(caBytes)
            setPrivateFilePermissions(identityTemp, executable = false)
            setPrivateFilePermissions(caTemp, executable = false)

            replaceAtomically(identityTemp, identityFile)
            replaceAtomically(caTemp, rootCaFile)
            prefs.edit().putString(KEY_PASSWORD, String(identityPassword)).commit()
            check(hasCredentials()) { "Credential storage verification failed" }

            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(leaf.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

            return IdentitySummary(
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                sha256Fingerprint = fingerprint,
                rootCertificateCount = roots.size
            )
        } finally {
            identityPassword.fill('\u0000')
        }
    }

    fun createTransport(host: String, port: Int): ControlPlaneTransport {
        check(hasCredentials()) {
            "mTLS identity is not provisioned. Import a device PKCS#12 identity and trusted root CA bundle first."
        }
        val password = prefs.getString(KEY_PASSWORD, null)
            ?: throw SecurityException("Encrypted PKCS#12 password is missing")
        return ControlPlaneTransport(
            context = appContext,
            host = host,
            port = port,
            identityFile = identityFile,
            identityPassword = password.toCharArray(),
            rootCaPem = rootCaFile.readBytes()
        )
    }

    fun clearCredentials() {
        prefs.edit().remove(KEY_PASSWORD).commit()
        identityFile.delete()
        rootCaFile.delete()
        File(appContext.noBackupFilesDir, "$IDENTITY_FILE.tmp").delete()
        File(appContext.noBackupFilesDir, "$ROOT_CA_FILE.tmp").delete()
    }

    private fun readLimited(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximum) { "Credential file exceeds the permitted size" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun setPrivateFilePermissions(file: File, executable: Boolean) {
        check(file.setReadable(false, false))
        check(file.setWritable(false, false))
        check(file.setExecutable(false, false))
        check(file.setReadable(true, true))
        check(file.setWritable(true, true))
        if (executable) check(file.setExecutable(true, true))
    }

    private fun replaceAtomically(source: File, destination: File) {
        val backup = File(destination.parentFile, destination.name + ".previous")
        if (destination.exists()) {
            backup.delete()
            check(destination.renameTo(backup)) { "Unable to preserve previous credential file" }
        }
        if (!source.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination)
            throw IllegalStateException("Unable to atomically install credential file")
        }
        backup.delete()
    }

    companion object {
        private const val PREFS_NAME = "control_plane_credentials"
        private const val KEY_PASSWORD = "pkcs12_password"
        private const val IDENTITY_FILE = "control-plane-identity.p12"
        private const val ROOT_CA_FILE = "control-plane-root-ca.pem"
        private const val MAX_IDENTITY_BYTES = 8 * 1024 * 1024
        private const val MAX_CA_BYTES = 1024 * 1024
    }
}
