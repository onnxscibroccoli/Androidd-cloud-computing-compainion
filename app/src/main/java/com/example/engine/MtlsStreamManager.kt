package com.example.engine

import android.content.Context
import android.app.ActivityManager
import android.net.TrafficStats
import android.provider.Settings
import com.example.BuildConfig
import com.example.transport.ControlPlaneCredentialStore
import com.example.transport.ControlPlaneTransport
import com.example.transport.proto.CommandExecutionResult
import com.example.transport.proto.DeviceTelemetry
import com.example.transport.proto.ExecutionCommand
import io.grpc.stub.StreamObserver
import java.io.File
import java.security.MessageDigest

import com.example.model.CommandType
import com.example.model.ComputeJob
import com.example.model.ConnectionState
import com.example.model.MtlsConfig
import com.example.model.Priority
import com.example.model.StreamEvent
import com.example.model.TelemetrySnapshot
import com.example.model.TransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Controller transport manager enforcing strict state separation:
 * UNCONFIGURED -> CONNECTING -> TLS_AUTHENTICATED -> WAITING_FOR_DEVICE_READINESS -> READY.
 *
 * CRITICAL INVARIANT:
 * Simulation is NOT an authenticated connection.
 * Demo mode must never produce a real connection-success state (READY).
 * Only actual transport evidence from canonical controller integration can advance to READY.
 */
class MtlsStreamManager(private val appContext: Context? = null) {

  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private val _transportMode = MutableStateFlow(TransportMode.SIMULATED_DEMO)
  val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

  private val _mtlsConfig = MutableStateFlow(MtlsConfig())
  val mtlsConfig: StateFlow<MtlsConfig> = _mtlsConfig.asStateFlow()

  private val _currentTelemetry = MutableStateFlow(
    TelemetrySnapshot(
      isSimulated = true,
      profileLabel = "DEMO SIMULATION PROFILE (Unconnected)",
      instanceIdLabel = "DEMO-NONCE-UNCONNECTED",
      regionLabel = "Simulated Model (us-ashburn-1)"
    )
  )
  val currentTelemetry: StateFlow<TelemetrySnapshot> = _currentTelemetry.asStateFlow()

  private val _eventsFlow = MutableSharedFlow<StreamEvent>(replay = 50)
  val eventsFlow: SharedFlow<StreamEvent> = _eventsFlow.asSharedFlow()

  private var demoStreamingJob: Job? = null
  private var liveTelemetryJob: Job? = null
  private var liveTransport: ControlPlaneTransport? = null
  @Volatile private var telemetryObserver: StreamObserver<DeviceTelemetry>? = null
  @Volatile private var executionCommandHandler: ((ExecutionCommand) -> Unit)? = null
  private var sequenceNumber: Long = 0L
  @Volatile private var previousCpuCounters: CpuCounters? = null
  @Volatile private var cachedDeviceId: String? = null

  init {
    // Initial state is cleanly DISCONNECTED, awaiting user action or configuration
    _connectionState.value = ConnectionState.DISCONNECTED
    scope.launch {
      _eventsFlow.emit(
        StreamEvent.Handshake(
          id = UUID.randomUUID().toString(),
          message = "[CLIENT INIT] Controller initialized in Sandbox. State: DISCONNECTED. No live OCI socket established.",
          success = false
        )
      )
    }
  }

  fun updateConfig(newConfig: MtlsConfig) {
    _mtlsConfig.value = newConfig
    disconnect()
    _connectionState.value = ConnectionState.DISCONNECTED
  }

  /**
   * Performs an honest handshake diagnostic test.
   * A server-only diagnostic advances to TLS_SERVER_VERIFIED, never TLS_AUTHENTICATED.
   * Only the gRPC mTLS transport with a client identity can establish mutual authentication.
   * This diagnostic never claims READY because it does not speak the control-plane protocol.
   */
  /**
   * Performs a real TLS server-certificate and hostname verification probe.
   *
   * This prototype has no client-certificate/private-key provider. Therefore an mTLS-required
   * configuration fails closed rather than claiming a synthetic TLS_AUTHENTICATED state.
   * Disabling mTLS allows a genuine platform-trust TLS diagnostic only; it does not authenticate
   * an OCI identity or establish application/device readiness.
   */
  fun attemptTlsHandshake() {
    demoStreamingJob?.cancel()
    scope.launch {
      val config = _mtlsConfig.value
      if (config.endpointHost.isBlank() || config.endpointPort !in 1..65535) {
        _connectionState.value = ConnectionState.UNCONFIGURED
        _eventsFlow.emit(
          StreamEvent.StreamError(
            id = UUID.randomUUID().toString(),
            code = "UNCONFIGURED",
            reason = "A valid control-plane hostname and TCP port are required."
          )
        )
        return@launch
      }

      _connectionState.value = ConnectionState.CONNECTING
      _eventsFlow.emit(
        StreamEvent.Handshake(
          id = UUID.randomUUID().toString(),
          message = if (config.isMtlsEnforced) {
            "[mTLS CONNECT] Starting an authenticated control-plane health RPC to ${config.endpointHost}:${config.endpointPort}."
          } else {
            "[TLS DIAGNOSTIC] Starting server-certificate and hostname verification only for ${config.endpointHost}:${config.endpointPort}."
          },
          success = false
        )
      )

      if (config.isMtlsEnforced) {
        try {
          val context = appContext ?: throw IllegalStateException(
            "Application context is required for device-bound mTLS credentials"
          )
          val transport = ControlPlaneCredentialStore(context).createTransport(
            config.endpointHost,
            config.endpointPort
          )
          try {
            val health = withContext(Dispatchers.IO) {
              transport.healthCheck(BuildConfig.VERSION_NAME, timeoutSeconds = 10)
            }
            if (health.serverId.isBlank() || health.serverTimeEpochMs <= 0L) {
              throw SecurityException("Authenticated health response omitted server identity or time evidence")
            }
            _connectionState.value = ConnectionState.TLS_AUTHENTICATED
            _eventsFlow.emit(
              StreamEvent.Handshake(
                id = UUID.randomUUID().toString(),
                message = "[mTLS AUTHENTICATED] TLS/mTLS RPC completed with trusted server '${health.serverId}'.",
                success = true
              )
            )
            if (health.controlPlaneReady && health.deviceReady) {
              _connectionState.value = ConnectionState.READY
              _eventsFlow.emit(
                StreamEvent.Handshake(
                  id = UUID.randomUUID().toString(),
                  message = "[CONTROL PLANE READY] Authenticated server confirmed both control-plane and device readiness.",
                  success = true
                )
              )
            } else {
              _connectionState.value = ConnectionState.WAITING_FOR_DEVICE_READINESS
              _eventsFlow.emit(
                StreamEvent.Handshake(
                  id = UUID.randomUUID().toString(),
                  message = "[READINESS PENDING] mTLS is authenticated, but the server has not confirmed both control-plane and device readiness.",
                  success = false
                )
              )
            }
          } finally {
            transport.close()
          }
        } catch (e: Exception) {
          _connectionState.value = ConnectionState.ERROR
          val reason = "${e.javaClass.simpleName}: ${e.message ?: "mTLS health RPC failed"}"
          _eventsFlow.emit(
            StreamEvent.StreamError(
              id = UUID.randomUUID().toString(),
              code = "MTLS_HEALTH_CHECK_FAILED",
              reason = reason
            )
          )
          _eventsFlow.emit(
            StreamEvent.Handshake(
              id = UUID.randomUUID().toString(),
              message = "[mTLS FAILURE] ${reason}",
              success = false
            )
          )
        }
        return@launch
      }

      try {
        val negotiated = withContext(Dispatchers.IO) {
          val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
          try {
            socket.connect(InetSocketAddress(config.endpointHost, config.endpointPort), 5000)
            socket.soTimeout = 5000
            val parameters = socket.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = parameters
            socket.startHandshake()
            val session = socket.session
            Pair(session.protocol, session.cipherSuite)
          } finally {
            socket.close()
          }
        }

        _connectionState.value = ConnectionState.TLS_SERVER_VERIFIED
        _eventsFlow.emit(
          StreamEvent.Handshake(
            id = UUID.randomUUID().toString(),
            message = "[TLS SERVER VERIFIED ONLY] Real TLS handshake succeeded. Protocol=${negotiated.first}; cipher=${negotiated.second}. No client identity was presented and no OCI device authentication occurred.",
            success = true
          )
        )
      } catch (e: Exception) {
        _connectionState.value = ConnectionState.ERROR
        val reason = "${e.javaClass.simpleName}: ${e.message ?: "TLS probe failed"}"
        _eventsFlow.emit(
          StreamEvent.StreamError(
            id = UUID.randomUUID().toString(),
            code = "TLS_PROBE_FAILED",
            reason = reason
          )
        )
        _eventsFlow.emit(
          StreamEvent.Handshake(
            id = UUID.randomUUID().toString(),
            message = "[TLS FAILURE] ${reason}",
            success = false
          )
        )
      }
    }
  }

  fun disconnect() {
    demoStreamingJob?.cancel()
    _connectionState.value = ConnectionState.DISCONNECTED
    scope.launch {
      _eventsFlow.emit(
        StreamEvent.Handshake(
          id = UUID.randomUUID().toString(),
          message = "[STATE] Transport state set to DISCONNECTED.",
          success = false
        )
      )
    }
  }

  /**
   * Starts or stops synthetic demo telemetry stream.
   * All frames are explicitly stamped as DEMO.
   */
  fun toggleDemoTelemetryPlayback(enable: Boolean) {
    if (!enable) {
      demoStreamingJob?.cancel()
      demoStreamingJob = null
      _currentTelemetry.value = _currentTelemetry.value.copy(streamFps = 0)
      return
    }

    if (demoStreamingJob?.isActive == true) return

    demoStreamingJob = scope.launch {
      var packetsIngested = 0L
      while (isActive) {
        delay(1200)
        packetsIngested += Random.nextLong(15, 45)
        sequenceNumber++

        val baseLatency = _mtlsConfig.value.simulatedFastConnectLatencyMs.toFloat()
        val jitter = (Random.nextFloat() * 3f - 1.5f)
        val rtt = (baseLatency + jitter).coerceAtLeast(5f)

        val ociCpu = (22f + Random.nextFloat() * 16f).coerceIn(15f, 90f)
        val ociMemory = 18400L + Random.nextLong(-100L, 200L)
        val localCpu = (10f + Random.nextFloat() * 12f).coerceIn(5f, 80f)
        val throughput = 240f + Random.nextFloat() * 80f

        val snapshot = _currentTelemetry.value.copy(
          timestamp = System.currentTimeMillis(),
          roundTripLatencyMs = rtt,
          ociCpuPct = ociCpu,
          ociMemoryMb = ociMemory,
          localCpuPct = localCpu,
          networkThroughputKbps = throughput,
          packetsIngested = packetsIngested,
          streamFps = 25,
          isSimulated = true
        )
        _currentTelemetry.value = snapshot

        if (Random.nextInt(4) == 0) {
          _eventsFlow.emit(
            StreamEvent.InboundTelemetry(
              id = UUID.randomUUID().toString(),
              rttMs = rtt,
              summary = "[DEMO SYNTHETIC] Model Spec: CPU %.1f%% | Mem %dMB | %.1f kbps".format(
                ociCpu,
                ociMemory,
                throughput
              ),
              isDemo = true
            )
          )
        }
      }
    }
  }

  /**
   * Simulated cloud execution for demonstration purposes.
   * Explicitly flagged as synthetic demonstration in outcome summaries.
   */
  suspend fun dispatchDemoCloudSimulation(
    job: ComputeJob,
    onProgress: suspend (Float) -> Unit
  ): BenchmarkResult {
    val startTime = System.currentTimeMillis()

    val payloadSize = when (job.algorithm) {
      CommandType.MATRIX_MULTIPLY -> job.complexityUnits * job.complexityUnits * 4
      CommandType.CRYPTO_HASH_CHAIN -> 128
      CommandType.MONTE_CARLO_PI -> 64
      CommandType.PRIME_SIEVE -> 32
      CommandType.SENSOR_TELEMETRY_BATCH -> job.complexityUnits * 32
    }

    _eventsFlow.emit(
      StreamEvent.OutboundCommand(
        id = UUID.randomUUID().toString(),
        commandType = job.algorithm,
        payloadSize = payloadSize,
        priority = job.priority,
        isDemo = true
      )
    )

    val rtt = _mtlsConfig.value.simulatedFastConnectLatencyMs.toLong()
    delay(rtt / 2)
    onProgress(0.2f)

    // Projected cloud model duration
    val steps = 6
    val simulatedDuration = when (job.algorithm) {
      CommandType.MATRIX_MULTIPLY -> (job.complexityUnits * 0.45).toLong().coerceIn(40L, 350L)
      CommandType.CRYPTO_HASH_CHAIN -> (job.complexityUnits * 0.003).toLong().coerceIn(30L, 300L)
      CommandType.MONTE_CARLO_PI -> (job.complexityUnits * 0.0003).toLong().coerceIn(30L, 250L)
      CommandType.PRIME_SIEVE -> (job.complexityUnits * 0.0008).toLong().coerceIn(30L, 280L)
      CommandType.SENSOR_TELEMETRY_BATCH -> (job.complexityUnits * 0.004).toLong().coerceIn(20L, 200L)
    }

    val stepDelay = (simulatedDuration / steps).coerceAtLeast(10L)
    for (i in 1..steps) {
      delay(stepDelay)
      onProgress(0.2f + (i.toFloat() / steps.toFloat()) * 0.7f)
    }

    delay(rtt / 2)
    onProgress(1.0f)

    val totalDuration = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
    val summary = "[DEMO SIMULATION] Projected model duration: ${totalDuration}ms (modeled RTT: ${rtt}ms). No live OCI socket used."

    _eventsFlow.emit(
      StreamEvent.InboundTelemetry(
        id = UUID.randomUUID().toString(),
        rttMs = rtt.toFloat(),
        summary = summary,
        isDemo = true
      )
    )

    return BenchmarkResult(
      durationMs = totalDuration,
      operationsCount = job.complexityUnits.toLong(),
      summary = summary,
      localCpuPct = 2.0f
    )
  }
}
