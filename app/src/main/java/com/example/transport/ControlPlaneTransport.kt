package com.example.transport

import android.content.Context
import com.example.transport.proto.ControlPlaneGrpc
import com.example.transport.proto.DeviceTelemetry
import com.example.transport.proto.ExecutionCommand
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.stub.StreamObserver
import io.grpc.okhttp.OkHttpChannelBuilder
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Strict client-certificate gRPC transport. The PKCS#12 identity is provisioned per device into
 * app-private no-backup storage; the password must be supplied by the caller from secure storage.
 * CA certificates are supplied by the trusted app deployment/provisioning path, never accepted
 * from the unauthenticated network. Missing identity or trust anchors fail closed.
 */
class ControlPlaneTransport(
    context: Context,
    private val host: String,
    private val port: Int,
    private val identityFile: File,
    private val identityPassword: CharArray,
    private val rootCaPem: ByteArray
) : AutoCloseable {
    private val appContext = context.applicationContext
    @Volatile private var channel: ManagedChannel? = null

    @Synchronized
    fun connect(): ManagedChannel {
        channel?.takeIf { !it.isShutdown && !it.isTerminated }?.let { return it }
        require(host.isNotBlank() && !host.contains("/") && !host.contains("://")) { "Use a DNS hostname only" }
        require(port in 1..65535) { "Invalid control-plane port" }
        check(identityFile.isFile && identityFile.canRead() && identityFile.length() > 0) {
            "A provisioned per-device PKCS#12 identity is required"
        }
        check(rootCaPem.isNotEmpty()) { "A trusted root CA bundle is required" }

        val keyStore = KeyStore.getInstance("PKCS12")
        identityFile.inputStream().use { keyStore.load(it, identityPassword) }
        check(keyStore.aliases().toList().any { keyStore.isKeyEntry(it) }) {
            "PKCS#12 identity contains no private-key entry"
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, identityPassword)
        val keyManagers: Array<KeyManager> = kmf.keyManagers

        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificates = certificateFactory.generateCertificates(rootCaPem.inputStream())
            .map { it as? X509Certificate ?: throw IOException("CA bundle contains non-X.509 certificate") }
        require(certificates.isNotEmpty()) { "CA bundle has no certificates" }
        require(certificates.all { it.basicConstraints >= 0 }) { "Trust bundle contains a non-CA certificate" }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, cert -> setCertificateEntry("control-plane-root-$index", cert) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            ?: throw IllegalStateException("Expected exactly one X.509 trust manager")

        val tls = SSLContext.getInstance("TLSv1.3")
        tls.init(keyManagers, arrayOf(trustManager), SecureRandom())
        val built = OkHttpChannelBuilder.forAddress(host, port)
            .sslSocketFactory(tls.socketFactory)
            .build()
        channel = built
        return built
    }

    fun openDeviceSession(
        onCommand: (ExecutionCommand) -> Unit,
        onError: (Throwable) -> Unit,
        onCompleted: () -> Unit
    ): StreamObserver<DeviceTelemetry> {
        val stub = ControlPlaneGrpc.newStub(connect())
        return stub.deviceSession(object : StreamObserver<ExecutionCommand> {
            override fun onNext(value: ExecutionCommand) = onCommand(value)
            override fun onError(t: Throwable) = onError(t)
            override fun onCompleted() = onCompleted()
        })
    }

    fun failedStreamObserver(onError: (Throwable) -> Unit): StreamObserver<DeviceTelemetry> =
        object : StreamObserver<DeviceTelemetry> {
            override fun onNext(value: DeviceTelemetry) = Unit
            override fun onError(t: Throwable) = onError(t)
            override fun onCompleted() = onError(Status.UNAVAILABLE.asRuntimeException())
        }

    @Synchronized
    override fun close() {
        channel?.shutdown()
        channel = null
        identityPassword.fill('\u0000')
    }
}
