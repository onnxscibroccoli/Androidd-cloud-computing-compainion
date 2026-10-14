package com.example.model

enum class CommandType(val displayName: String, val description: String) {
  MATRIX_MULTIPLY("Matrix Multiplication", "FP32 dense matrix computation (NxN GEMM)"),
  CRYPTO_HASH_CHAIN("SHA-256 Hash Chain", "Cryptographic proof-of-work sequential hashing"),
  MONTE_CARLO_PI("Monte Carlo Simulation", "Stochastic numerical integration & distribution modeling"),
  PRIME_SIEVE("Prime Sieve (Eratosthenes)", "Memory-bound integer computation & prime density"),
  SENSOR_TELEMETRY_BATCH("Edge Telemetry Synthesis", "Local sensor fusion & buffer processing")
}

enum class ExecutionTarget(val displayName: String, val badgeColor: Long) {
  LOCAL_DEVICE("Local Mobile CPU (Actual)", 0xFF64748B),
  OCI_CLOUD_STREAM("OCI Cloud gRPC (mTLS)", 0xFFC74634),
  HYBRID_SPLIT("Hybrid Split (Local + OCI mTLS)", 0xFF00E5BE)
}

enum class JobStatus {
  QUEUED,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED
}

enum class Priority(val label: String) {
  HIGH("High (P0)"),
  NORMAL("Normal (P1)"),
  LOW("Low (P2)")
}

/**
 * Strict canonical connection state machine.
 * Advances ONLY upon genuine transport and device readiness evidence.
 * A completed TLS handshake alone must not imply application readiness.
 */
enum class ConnectionState(val label: String, val isReady: Boolean) {
  UNCONFIGURED("UNCONFIGURED", false),
  CONNECTING("CONNECTING", false),
  TLS_SERVER_VERIFIED("TLS_SERVER_VERIFIED (server certificate only)", false),
  TLS_AUTHENTICATED("TLS_AUTHENTICATED (mutual TLS)", false),
  WAITING_FOR_DEVICE_READINESS("WAITING_FOR_DEVICE_READINESS", false),
  READY("READY", true),
  DISCONNECTED("DISCONNECTED", false),
  ERROR("ERROR", false)
}

enum class TransportMode(val label: String) {
  SIMULATED_DEMO("Demo / Simulation (Synthetic Frames)"),
  CANONICAL_PENDING("Control Plane Not Connected"),
  LIVE_MTLS("Live Authenticated mTLS Stream")
}

data class ComputeJob(
  val id: String,
  val title: String,
  val algorithm: CommandType,
  val target: ExecutionTarget,
  val priority: Priority,
  val status: JobStatus = JobStatus.QUEUED,
  val progress: Float = 0f,
  val durationMs: Long = 0L,
  val complexityUnits: Int = 1000,
  val resultSummary: String = "",
  val speedupRatio: Float = 1.0f,
  val localCpuPct: Float = 0f,
  val localCpuMeasured: Boolean = false,
  val cloudCpuPct: Float = 0f,
  val timestamp: Long = System.currentTimeMillis(),
  val isSimulatedCloud: Boolean = false,
  val error: String? = null
)

data class TelemetrySnapshot(
  val timestamp: Long = System.currentTimeMillis(),
  val roundTripLatencyMs: Float = 0f,
  val ociCpuPct: Float = 0f,
  val ociMemoryMb: Long = 0L,
  val ociGpuPct: Float? = null,
  val localCpuPct: Float = 0f,
  val localCpuMeasured: Boolean = false,
  val localMemoryMb: Long = 0L,
  val networkThroughputKbps: Float = 0f,
  val packetsDispatched: Long = 0L,
  val packetsIngested: Long = 0L,
  val packetLossRate: Float = 0.00f,
  val streamFps: Int = 0,
  val isSimulated: Boolean = true,
  val profileLabel: String = "Demo Simulation (Synthetic Spec)",
  val instanceIdLabel: String = "SIMULATED-PROFILE-NOT-CONNECTED",
  val regionLabel: String = "unconnected (simulated profile)"
)

data class MtlsConfig(
  val endpointHost: String = "",
  val endpointPort: Int = 443,
  val clientCertSubject: String = "NOT_PROVISIONED",
  val clientCertIssuer: String = "NOT_PROVISIONED",
  val clientCertFingerprint: String = "NOT_PROVISIONED",
  val caRootSubject: String = "NOT_PROVISIONED",
  val cipherSuite: String = "NOT_NEGOTIATED",
  val tlsVersion: String = "NOT_NEGOTIATED",
  val isMtlsEnforced: Boolean = true,
  val keepAliveIntervalSec: Int = 5,
  val simulatedFastConnectLatencyMs: Int = 15,
  val autoReconnect: Boolean = false
)

sealed class StreamEvent(
  open val id: String,
  open val timestamp: Long = System.currentTimeMillis()
) {
  data class OutboundCommand(
    override val id: String,
    val commandType: CommandType,
    val payloadSize: Int,
    val priority: Priority,
    val isDemo: Boolean = true,
    override val timestamp: Long = System.currentTimeMillis()
  ) : StreamEvent(id, timestamp)

  data class InboundTelemetry(
    override val id: String,
    val rttMs: Float,
    val summary: String,
    val isDemo: Boolean = true,
    override val timestamp: Long = System.currentTimeMillis()
  ) : StreamEvent(id, timestamp)

  data class Handshake(
    override val id: String,
    val message: String,
    val success: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
  ) : StreamEvent(id, timestamp)

  data class KeepAlive(
    override val id: String,
    val sequenceNumber: Long,
    val rttMs: Float,
    val isDemo: Boolean = true,
    override val timestamp: Long = System.currentTimeMillis()
  ) : StreamEvent(id, timestamp)

  data class StreamError(
    override val id: String,
    val code: String,
    val reason: String,
    override val timestamp: Long = System.currentTimeMillis()
  ) : StreamEvent(id, timestamp)
}
