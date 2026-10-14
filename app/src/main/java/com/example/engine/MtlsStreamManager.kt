package com.example.engine

import android.content.Context
import android.app.ActivityManager
import android.net.TrafficStats
import android.provider.Settings
import com.example.BuildConfig
import com.example.auth.OciOAuthManager
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
import kotlinx.coroutines.cancel
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

  private val _transportMode = MutableStateFlow(TransportMode.CANONICAL_PENDING)
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

  fun setExecutionCommandHandler(handler: ((ExecutionCommand) -> Unit)?) {
    executionCommandHandler = handler
  }

  fun sendCommandResult(result: CommandExecutionResult) {
    scope.launch {
      val observer = telemetryObserver
      if (observer == null) {
        _eventsFlow.emit(
          StreamEvent.StreamError(
            id = UUID.randomUUID().toString(),
            code = "COMMAND_RESULT_STREAM_UNAVAILABLE",
            reason = "Command result was not sent because the authenticated device stream is not open."
          )
        )
        return@launch
      }
      try {
        val telemetry = withContext(Dispatchers.IO) {
          buildDeviceTelemetry().toBuilder()
            .setCommandResult(result)
            .putAttributes("message_type", "command_result")
            .build()
        }
        observer.onNext(telemetry)
      } catch (error: Exception) {
        handleLiveStreamError(liveTransport, error)
      }
    }
  }

  /**
   * Performs a real mTLS health RPC and opens a persistent bidirectional device stream when the
   * authenticated server confirms readiness. With mTLS disabled, this method performs only a
   * server-certificate/hostname diagnostic and never claims device authentication or READY.
   */
  fun attemptTlsHandshake() {
    demoStreamingJob?.cancel()
    closeLiveTransport(sendCompleted = true)
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
        var retainedTransport = false
        var transport: ControlPlaneTransport? = null
        try {
          val context = appContext ?: throw IllegalStateException(
            "Application context is required for device-bound mTLS credentials"
          )
          val accessToken = OciOAuthManager(context).getValidAccessToken()
          val activeTransport = ControlPlaneCredentialStore(context).createTransport(
            config.endpointHost,
            config.endpointPort,
            accessToken
          )
          transport = activeTransport
          val started = System.nanoTime()
          val health = withContext(Dispatchers.IO) {
            activeTransport.healthCheck(BuildConfig.VERSION_NAME, timeoutSeconds = 10)
          }
          val measuredRtt = (System.nanoTime() - started) / 1_000_000f
          if (health.serverId.isBlank() || health.serverTimeEpochMs <= 0L) {
            throw SecurityException("Authenticated health response omitted server identity or time evidence")
          }
          _connectionState.value = ConnectionState.TLS_AUTHENTICATED
          _eventsFlow.emit(
            StreamEvent.Handshake(
              id = UUID.randomUUID().toString(),
              message = "[mTLS AUTHENTICATED] Real health RPC completed with trusted server '${health.serverId}'.",
              success = true
            )
          )
          if (!health.controlPlaneReady || !health.deviceReady) {
            _connectionState.value = ConnectionState.WAITING_FOR_DEVICE_READINESS
            _eventsFlow.emit(
              StreamEvent.Handshake(
                id = UUID.randomUUID().toString(),
                message = "[READINESS PENDING] Authenticated server did not confirm both control-plane and device readiness; no device stream was opened.",
                success = false
              )
            )
            return@launch
          }

          liveTransport = activeTransport
          val observer = activeTransport.openDeviceSession(
            onCommand = { command -> handleExecutionCommand(command) },
            onError = { error -> handleLiveStreamError(activeTransport, error) },
            onCompleted = {
              scope.launch {
                if (liveTransport === activeTransport) {
                  closeLiveTransport(sendCompleted = false)
                  _connectionState.value = ConnectionState.DISCONNECTED
                  _eventsFlow.emit(
                    StreamEvent.Handshake(
                      id = UUID.randomUUID().toString(),
                      message = "[STREAM CLOSED] Control plane completed the device session.",
                      success = false
                    )
                  )
                }
              }
            }
          )
          telemetryObserver = observer
          val initialTelemetry = buildDeviceTelemetry()
          observer.onNext(initialTelemetry)
          _currentTelemetry.value = _currentTelemetry.value.copy(
            timestamp = initialTelemetry.observedAtEpochMs,
            roundTripLatencyMs = measuredRtt,
            localCpuPct = initialTelemetry.cpuLoadPercent.toFloat(),
            localCpuMeasured = initialTelemetry.attributesMap["cpu_load_measured"] == "true",
            localMemoryMb = initialTelemetry.availableMemoryBytes / (1024L * 1024L),
            packetsDispatched = _currentTelemetry.value.packetsDispatched + 1L,
            streamFps = 1,
            isSimulated = false,
            profileLabel = "LIVE mTLS TELEMETRY",
            instanceIdLabel = initialTelemetry.deviceId,
            regionLabel = config.endpointHost
          )
          _transportMode.value = TransportMode.LIVE_MTLS
          startLiveTelemetryLoop()
          retainedTransport = true
          _connectionState.value = ConnectionState.READY
          _eventsFlow.emit(
            StreamEvent.Handshake(
              id = UUID.randomUUID().toString(),
              message = "[CONTROL PLANE READY] Authenticated mTLS device stream opened and server readiness was confirmed.",
              success = true
            )
          )
        } catch (e: Exception) {
          _connectionState.value = ConnectionState.ERROR
          val reason = "${e.javaClass.simpleName}: ${e.message ?: "mTLS health or device-stream setup failed"}"
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
        } finally {
          if (!retainedTransport) {
            if (liveTransport === transport) closeLiveTransport(sendCompleted = false)
            else transport?.close()
          }
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


  private data class CpuCounters(val total: Long, val idle: Long)

  private suspend fun buildDeviceTelemetry(): DeviceTelemetry = withContext(Dispatchers.IO) {
    val context = appContext ?: throw IllegalStateException("Application context is unavailable")
    val memoryInfo = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
    val cpu = readCpuLoad()
    val rx = TrafficStats.getTotalRxBytes()
    val tx = TrafficStats.getTotalTxBytes()
    DeviceTelemetry.newBuilder()
      .setDeviceId(deviceId(context))
      .setObservedAtEpochMs(System.currentTimeMillis())
      .setCpuLoadPercent(cpu.first)
      .setAvailableMemoryBytes(memoryInfo.availMem.coerceAtLeast(0L))
      .setNetworkRxBytes(rx.coerceAtLeast(0L))
      .setNetworkTxBytes(tx.coerceAtLeast(0L))
      .setAppVersion(BuildConfig.VERSION_NAME)
      .putAttributes("cpu_load_measured", cpu.second.toString())
      .putAttributes("network_counters_available", (rx >= 0L && tx >= 0L).toString())
      .putAttributes("transport", "authenticated_mtls")
      .build()
  }

  @Synchronized
  private fun readCpuLoad(): Pair<Double, Boolean> {
    val line = runCatching { File("/proc/stat").useLines { lines -> lines.firstOrNull() } }.getOrNull()
      ?: return 0.0 to false
    val values = line.trim().split(" ").filter { it.isNotBlank() }.drop(1).mapNotNull { it.toLongOrNull() }
    if (values.size < 4) return 0.0 to false
    val current = CpuCounters(values.sum(), values[3] + (values.getOrNull(4) ?: 0L))
    val previous = previousCpuCounters
    previousCpuCounters = current
    if (previous == null) return 0.0 to false
    val totalDelta = (current.total - previous.total).coerceAtLeast(0L)
    val idleDelta = (current.idle - previous.idle).coerceAtLeast(0L)
    if (totalDelta <= 0L) return 0.0 to false
    return (((totalDelta - idleDelta).coerceAtLeast(0L).toDouble() / totalDelta.toDouble()) * 100.0)
      .coerceIn(0.0, 100.0) to true
  }

  private fun deviceId(context: Context): String {
    cachedDeviceId?.let { return it }
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    val digest = MessageDigest.getInstance("SHA-256")
      .digest((context.packageName + ":" + androidId).toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
      .take(32)
    cachedDeviceId = digest
    return digest
  }

  private fun handleExecutionCommand(command: ExecutionCommand) {
    val now = System.currentTimeMillis()
    if (command.commandId.isBlank() || command.requestedBy.isBlank() ||
      command.deadlineEpochMs <= now
    ) {
      sendCommandResult(
        CommandExecutionResult.newBuilder()
          .setCommandId(command.commandId)
          .setExitCode(124)
          .setStderr("Command rejected: missing command identity/requester or expired deadline.")
          .setDurationMs(0L)
          .setTimedOut(command.deadlineEpochMs in 1L..now)
          .build()
      )
      return
    }
    val handler = executionCommandHandler
    if (handler == null) {
      sendCommandResult(
        CommandExecutionResult.newBuilder()
          .setCommandId(command.commandId)
          .setExitCode(126)
          .setStderr("Command rejected because no Shizuku execution dispatcher is bound.")
          .setDurationMs(0L)
          .build()
      )
      return
    }
    try {
      handler(command)
    } catch (error: Exception) {
      sendCommandResult(
        CommandExecutionResult.newBuilder()
          .setCommandId(command.commandId)
          .setExitCode(126)
          .setStderr("Command dispatcher rejected the command: " + (error.message ?: error.javaClass.simpleName))
          .setDurationMs(0L)
          .build()
      )
    }
  }

  private fun handleLiveStreamError(transport: ControlPlaneTransport?, error: Throwable) {
    scope.launch {
      if (transport != null && liveTransport !== transport) return@launch
      closeLiveTransport(sendCompleted = false)
      _connectionState.value = ConnectionState.ERROR
      val reason = error.javaClass.simpleName + ": " + (error.message ?: "gRPC device stream failed")
      _eventsFlow.emit(
        StreamEvent.StreamError(
          id = UUID.randomUUID().toString(),
          code = "MTLS_DEVICE_STREAM_FAILED",
          reason = reason
        )
      )
    }
  }

  private fun startLiveTelemetryLoop() {
    liveTelemetryJob?.cancel()
    liveTelemetryJob = scope.launch {
      while (isActive && telemetryObserver != null) {
        delay(15_000L)
        val observer = telemetryObserver ?: break
        try {
          val telemetry = buildDeviceTelemetry()
          observer.onNext(telemetry)
          _currentTelemetry.value = _currentTelemetry.value.copy(
            timestamp = telemetry.observedAtEpochMs,
            localCpuPct = telemetry.cpuLoadPercent.toFloat(),
            localCpuMeasured = telemetry.attributesMap["cpu_load_measured"] == "true",
            localMemoryMb = telemetry.availableMemoryBytes / (1024L * 1024L),
            packetsDispatched = _currentTelemetry.value.packetsDispatched + 1L,
            streamFps = 1,
            isSimulated = false,
            profileLabel = "LIVE mTLS TELEMETRY",
            instanceIdLabel = telemetry.deviceId,
            regionLabel = _mtlsConfig.value.endpointHost
          )
        } catch (error: Exception) {
          handleLiveStreamError(liveTransport, error)
          break
        }
      }
    }
  }

  private fun closeLiveTransport(sendCompleted: Boolean) {
    liveTelemetryJob?.cancel()
    liveTelemetryJob = null
    val observer = telemetryObserver
    telemetryObserver = null
    if (sendCompleted) runCatching { observer?.onCompleted() }
    val transport = liveTransport
    liveTransport = null
    transport?.close()
    _transportMode.value = TransportMode.CANONICAL_PENDING
  }

  fun disconnect() {
    demoStreamingJob?.cancel()
    closeLiveTransport(sendCompleted = true)
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

  fun shutdown() {
    demoStreamingJob?.cancel()
    closeLiveTransport(sendCompleted = true)
    _connectionState.value = ConnectionState.DISCONNECTED
    scope.cancel()
  }

  /**
   * Starts or stops synthetic demo telemetry stream.
   * All frames are explicitly stamped as DEMO.
   */
  fun toggleDemoTelemetryPlayback(enable: Boolean) {
    if (!enable) {
      demoStreamingJob?.cancel()
      demoStreamingJob = null
      _transportMode.value = if (liveTransport != null) TransportMode.LIVE_MTLS else TransportMode.CANONICAL_PENDING
      _currentTelemetry.value = _currentTelemetry.value.copy(streamFps = 0)
      return
    }

    if (demoStreamingJob?.isActive == true) return
    _transportMode.value = TransportMode.SIMULATED_DEMO

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
      localCpuPct = 0f,
      localCpuMeasured = false
    )
  }
}
