package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.repository.ComputeRepository
import com.example.engine.MtlsStreamManager
import com.example.engine.GrpcOciControllerService
import com.example.model.CommandType
import com.example.model.ComputeJob
import com.example.model.ConnectionState
import com.example.model.ExecutionTarget
import com.example.model.JobStatus
import com.example.model.MtlsConfig
import com.example.model.Priority
import com.example.model.StreamEvent
import com.example.model.TelemetrySnapshot
import com.example.model.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ControllerViewModel(application: Application) : AndroidViewModel(application) {

  private val database = AppDatabase.getDatabase(application)
  private val streamManager = MtlsStreamManager(application)
  private val repository = ComputeRepository(database, streamManager, GrpcOciControllerService(application, streamManager))

  val connectionState: StateFlow<ConnectionState> = repository.connectionState
  val transportMode: StateFlow<TransportMode> = repository.transportMode
  val currentTelemetry: StateFlow<TelemetrySnapshot> = repository.currentTelemetry
  val mtlsConfig: StateFlow<MtlsConfig> = repository.mtlsConfig

  val allJobs: StateFlow<List<ComputeJob>> = repository.allJobs.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = emptyList()
  )

  private val _streamEventsList = MutableStateFlow<List<StreamEvent>>(emptyList())
  val streamEventsList: StateFlow<List<StreamEvent>> = _streamEventsList.asStateFlow()

  private val _activeTab = MutableStateFlow(0)
  val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

  private val _snackbarMessage = MutableStateFlow<String?>(null)
  val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

  private val _isDemoTelemetryActive = MutableStateFlow(false)
  val isDemoTelemetryActive: StateFlow<Boolean> = _isDemoTelemetryActive.asStateFlow()

  init {
    viewModelScope.launch {
      repository.streamEvents.collect { event ->
        val updated = (_streamEventsList.value + event).takeLast(40)
        _streamEventsList.value = updated
      }
    }
  }

  fun setTab(index: Int) {
    _activeTab.value = index
  }

  fun clearSnackbar() {
    _snackbarMessage.value = null
  }

  fun toggleDemoTelemetry(enabled: Boolean) {
    _isDemoTelemetryActive.value = enabled
    repository.toggleDemoTelemetry(enabled)
    _snackbarMessage.value = if (enabled) "Demo synthetic telemetry playback active." else "Demo telemetry paused."
  }

  fun submitNewJob(
    title: String,
    algorithm: CommandType,
    target: ExecutionTarget,
    priority: Priority,
    complexityUnits: Int
  ) {
    val job = ComputeJob(
      id = UUID.randomUUID().toString(),
      title = title.ifBlank { "${algorithm.displayName} Task" },
      algorithm = algorithm,
      target = target,
      priority = priority,
      complexityUnits = complexityUnits,
      status = JobStatus.QUEUED,
      isSimulatedCloud = target != ExecutionTarget.LOCAL_DEVICE
    )
    repository.submitJob(job)
    _snackbarMessage.value = "Dispatched: ${job.title} to ${target.displayName}"
  }

  fun dispatchPreset(algorithm: CommandType, target: ExecutionTarget) {
    val (defaultUnits, presetTitle) = when (algorithm) {
      CommandType.MATRIX_MULTIPLY -> Pair(192, "GEMM 192x192 FP32")
      CommandType.CRYPTO_HASH_CHAIN -> Pair(30_000, "SHA-256 Chain (30k rounds)")
      CommandType.MONTE_CARLO_PI -> Pair(300_000, "Monte Carlo Pi (300k samples)")
      CommandType.PRIME_SIEVE -> Pair(150_000, "Prime Sieve (150k limit)")
      CommandType.SENSOR_TELEMETRY_BATCH -> Pair(8_000, "Edge Telemetry Batch (8k frames)")
    }
    submitNewJob(
      title = presetTitle,
      algorithm = algorithm,
      target = target,
      priority = Priority.NORMAL,
      complexityUnits = defaultUnits
    )
  }

  fun cancelJob(id: String) {
    repository.cancelJob(id)
    _snackbarMessage.value = "Cancelled job #$id"
  }

  fun deleteJob(id: String) {
    repository.deleteJob(id)
  }

  fun clearAllHistory() {
    repository.clearAllHistory()
    _snackbarMessage.value = "Cleared all job history and telemetry records."
  }

  fun attemptTlsHandshake() {
    repository.attemptTlsHandshake()
    _snackbarMessage.value = "Running TLS handshake diagnostic..."
  }

  fun disconnectStream() {
    repository.disconnectStream()
    _snackbarMessage.value = "Transport state: DISCONNECTED"
  }

  fun updateMtlsConfig(
    host: String,
    port: Int,
    simulatedLatency: Int,
    keepAliveSec: Int,
    enforceMtls: Boolean
  ) {
    val updated = repository.mtlsConfig.value.copy(
      endpointHost = host,
      endpointPort = port,
      simulatedFastConnectLatencyMs = simulatedLatency,
      keepAliveIntervalSec = keepAliveSec,
      isMtlsEnforced = enforceMtls
    )
    repository.updateMtlsConfig(updated)
    _snackbarMessage.value = "mTLS configuration updated."
  }

  fun showMessage(message: String) {
    _snackbarMessage.value = message
  }

  fun updateMtlsIdentitySummary(
    subject: String,
    issuer: String,
    fingerprint: String,
    rootCertificateCount: Int
  ) {
    require(subject.isNotBlank() && issuer.isNotBlank())
    require(fingerprint.matches(Regex("(?i)^[0-9a-f]{64}$")))
    require(rootCertificateCount > 0)
    val current = repository.mtlsConfig.value
    repository.updateMtlsConfig(
      current.copy(
        clientCertSubject = subject,
        clientCertIssuer = issuer,
        clientCertFingerprint = fingerprint,
        caRootSubject = "Trusted root CA bundle ($rootCertificateCount certificates)"
      )
    )
    _snackbarMessage.value = "mTLS credential metadata updated from the validated PKCS#12 bundle."
  }

  /**
   * This legacy action is intentionally fail-closed. The app does not mint a fake identity.
   * Import a CA-issued, device-specific PKCS#12 identity through the secure provisioning flow.
   */
  fun generateNewEphemeralCert() {
    _snackbarMessage.value =
      "Client certificate enrollment is not configured. No certificate was generated; mTLS remains unavailable."
  }
}
