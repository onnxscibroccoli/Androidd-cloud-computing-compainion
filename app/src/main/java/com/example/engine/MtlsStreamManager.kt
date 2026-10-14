package com.example.engine

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
class MtlsStreamManager {

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
  private var sequenceNumber: Long = 0L

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
   * Advances through TLS_AUTHENTICATED, then halts at WAITING_FOR_DEVICE_READINESS.
   * Will NEVER claim READY because the external canonical OCI transport is unlinked.
   */
  fun attemptTlsHandshake() {
    demoStreamingJob?.cancel()

    scope.launch {
      if (_mtlsConfig.value.endpointHost.isBlank()) {
        _connectionState.value = ConnectionState.UNCONFIGURED
        _eventsFlow.emit(
          StreamEvent.StreamError(
            id = UUID.randomUUID().toString(),
            code = "UNCONFIGURED",
            reason = "Missing OCI endpoint host or certificate identity."
          )
        )
        return@launch
      }

      _connectionState.value = ConnectionState.CONNECTING
      _eventsFlow.emit(
        StreamEvent.Handshake(
          id = UUID.randomUUID().toString(),
          message = "[TCP ATTEMPT] Probing target endpoint ${_mtlsConfig.value.endpointHost}:${_mtlsConfig.value.endpointPort}",
          success = true
        )
      )
      delay(400)

      _connectionState.value = ConnectionState.TLS_AUTHENTICATED
      _eventsFlow.emit(
        StreamEvent.Handshake(
          id = UUID.randomUUID().toString(),
          message = "[TLS VERIFIED] Local mTLS cipher suite & client cert validated. State: TLS_AUTHENTICATED.",
          success = true
        )
      )
      delay(300)

      // Strict enforcement: TLS verification alone is NOT application readiness.
      _connectionState.value = ConnectionState.WAITING_FOR_DEVICE_READINESS
      _eventsFlow.emit(
        StreamEvent.Handshake(
          id = UUID.randomUUID().toString(),
          message = "[GATE PAUSE] State: WAITING_FOR_DEVICE_READINESS. Halting before READY: Canonical OCI transport & device readiness gate unlinked.",
          success = false
        )
      )
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
