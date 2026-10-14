package com.example.transport

import android.content.Context
import android.os.Build
import com.example.transport.proto.ControlPlaneGrpc
import com.example.transport.proto.DeviceTelemetry
import com.example.transport.proto.ExecutionCommand
import com.example.transport.proto.ExecutionReceipt
import com.example.transport.proto.HealthCheckRequest
import com.example.transport.proto.HealthCheckResponse
import com.example.transport.proto.SubmitExecutionRequest
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.stub.StreamObserver
import io.grpc.stub.MetadataUtils
import io.grpc.okhttp.OkHttpChannelBuilder
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
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
    private val rootCaPem: ByteArray,
    private val accessToken: String? = null
) : AutoCloseable {
    private val appContext = context.applicationContext
    @Volatile private var channel: ManagedChannel? = null

    @Synchronized
    fun connect(): ManagedChannel {
        channel?.takeIf { !it.isShutdown && !it.isTerminated }?.let { return it }
        require(host.isNotBlank() && !host.contains("/") && !host.contains("://")) { "Use a DNS hostname only" }
        require(port in 1..65535) { "Invalid control-plane port" }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "TLS 1.3 transport requires Android 10/API 29 or newer"
        }
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
        val builder = OkHttpChannelBuilder.forAddress(host, port)
            .sslSocketFactory(tls.socketFactory)
        if (!accessToken.isNullOrBlank()) {
            val headers = Metadata().apply {
                put(AUTHORIZATION_HEADER, "Bearer $accessToken")
            }
            builder.intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
        }
        val built = builder.build()
        channel = built
        return built
    }

    /** A real authenticated RPC. A returned response proves the TLS/mTLS channel reached the service. */
    fun healthCheck(clientVersion: String, timeoutSeconds: Long = 10): HealthCheckResponse {
        require(clientVersion.isNotBlank()) { "Client version is required" }
        require(timeoutSeconds in 1L..60L) { "Health-check deadline must be 1..60 seconds" }
        val request = HealthCheckRequest.newBuilder().setClientVersion(clientVersion).build()
        return ControlPlaneGrpc.newBlockingStub(connect())
            .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
            .healthCheck(request)
    }

    /** Executes a request only through the authenticated mTLS gRPC service. */
    fun submitExecution(request: SubmitExecutionRequest, timeoutSeconds: Long): ExecutionReceipt {
        require(request.requestId.isNotBlank() && request.jobId.isNotBlank()) {
            "Execution request and job IDs are required"
        }
        require(timeoutSeconds in 1L..900L) { "Execution deadline must be 1..900 seconds" }
        return ControlPlaneGrpc.newBlockingStub(connect())
            .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
            .submitExecution(request)
    }

    fun openDeviceSession(
        onCommand: (ExecutionCommand) -> Unit,
        onError: (Throwable) -> Unit,
        onCompleted: () -> Unit
    ): StreamObserver<DeviceTelemetry> {
        val commandErrorCallback = onError
        val completionCallback = onCompleted
        val stub = ControlPlaneGrpc.newStub(connect())
        return stub.deviceSession(object : StreamObserver<ExecutionCommand> {
            override fun onNext(value: ExecutionCommand) = onCommand(value)
            override fun onError(t: Throwable) = commandErrorCallback(t)
            override fun onCompleted() = completionCallback()
        })
    }

    fun failedStreamObserver(onError: (Throwable) -> Unit): StreamObserver<DeviceTelemetry> {
        val errorCallback = onError
        return object : StreamObserver<DeviceTelemetry> {
            override fun onNext(value: DeviceTelemetry) = Unit
            override fun onError(t: Throwable) = errorCallback(t)
            override fun onCompleted() = errorCallback(Status.UNAVAILABLE.asRuntimeException())
        }
    }

    @Synchronized
    override fun close() {
        channel?.shutdown()
        channel = null
        identityPassword.fill('\u0000')
    }

    companion object {
        private val AUTHORIZATION_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}
