package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.ComputeJobEntity
import com.example.data.local.TelemetryLogEntity
import com.example.engine.BenchmarkResult
import com.example.engine.LocalComputeEngine
import com.example.engine.MtlsStreamManager
import com.example.engine.OciControllerService
import com.example.engine.ExecutionProof
import com.example.model.CommandType
import com.example.model.ComputeJob
import com.example.model.ConnectionState
import com.example.model.ExecutionTarget
import com.example.model.JobStatus
import com.example.model.MtlsConfig
import com.example.model.StreamEvent
import com.example.model.TelemetrySnapshot
import com.example.model.TransportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ComputeRepository(
  private val database: AppDatabase,
  private val streamManager: MtlsStreamManager,
  private val ociControllerService: OciControllerService = com.example.engine.UnconfiguredOciControllerService()
) {
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private val activeJobs = ConcurrentHashMap<String, Job>()

  val connectionState: StateFlow<ConnectionState> = streamManager.connectionState
  val transportMode: StateFlow<TransportMode> = streamManager.transportMode
  val mtlsConfig: StateFlow<MtlsConfig> = streamManager.mtlsConfig
  val currentTelemetry: StateFlow<TelemetrySnapshot> = streamManager.currentTelemetry
  val streamEvents: SharedFlow<StreamEvent> = streamManager.eventsFlow

  val allJobs: Flow<List<ComputeJob>> = database.computeJobDao().getAllJobs().map { list ->
    list.map { it.toDomain() }
  }

  val recentTelemetryLogs: Flow<List<TelemetryLogEntity>> = database.telemetryDao().getRecentLogs()

  fun updateMtlsConfig(config: MtlsConfig) {
    streamManager.updateConfig(config)
  }

  fun attemptTlsHandshake() {
    streamManager.attemptTlsHandshake()
  }

  fun toggleDemoTelemetry(enable: Boolean) {
    streamManager.toggleDemoTelemetryPlayback(enable)
  }

  fun disconnectStream() {
    streamManager.disconnect()
  }

  fun submitJob(job: ComputeJob) {
    val coroutineJob = scope.launch {
      try {
        database.computeJobDao().insertJob(ComputeJobEntity.fromDomain(job.copy(status = JobStatus.QUEUED, progress = 0.05f)))

        database.computeJobDao().updateJob(
          ComputeJobEntity.fromDomain(job.copy(status = JobStatus.RUNNING, progress = 0.10f))
        )

        val result: BenchmarkResult
        var speedupRatio = 1.0f
        var isSimulated = false
        var workerCpuPct: Float? = null

        when (job.target) {
          ExecutionTarget.LOCAL_DEVICE -> {
            // Actual on-device execution
            val rawResult = runLocalAlgorithm(job) { progress ->
              updateProgress(job.id, progress)
            }
            result = rawResult.copy(
              summary = "[LOCAL ACTUAL] ${rawResult.summary}"
            )
            speedupRatio = 1.0f
            isSimulated = false
          }

          ExecutionTarget.OCI_CLOUD_STREAM -> {
            // The service returns explicit evidence. The default adapter is demo-only and cannot
            // claim REAL_OCI_WORKER without an authenticated backend receipt.
            val cloudExecution = ociControllerService.submit(job) { progress ->
              updateProgress(job.id, progress)
            }
            workerCpuPct = cloudExecution.workerCpuPct
            result = cloudExecution.benchmark.copy(
              summary = "${cloudExecution.executionProof}: ${cloudExecution.evidenceSummary} ${cloudExecution.benchmark.summary}"
            )
            val estimatedLocalTime = estimateLocalTimeMs(job.algorithm, job.complexityUnits)
            speedupRatio = if (cloudExecution.executionProof == ExecutionProof.REAL_OCI_WORKER) {
              (estimatedLocalTime.toFloat() / result.durationMs.toFloat().coerceAtLeast(1f)).coerceAtLeast(1.0f)
            } else {
              1.0f
            }
            isSimulated = cloudExecution.executionProof != ExecutionProof.REAL_OCI_WORKER
          }

          ExecutionTarget.HYBRID_SPLIT -> {
            // 25% on Local CPU (Actual), 75% on Cloud Demo Model
            val localFractionUnits = (job.complexityUnits * 0.25).toInt().coerceAtLeast(1)
            val cloudFractionUnits = (job.complexityUnits * 0.75).toInt().coerceAtLeast(1)

            val localDeferred = async {
              runLocalAlgorithm(job.copy(complexityUnits = localFractionUnits)) { p ->
                updateProgress(job.id, p * 0.4f)
              }
            }

            val cloudDeferred = async {
              ociControllerService.submit(job.copy(complexityUnits = cloudFractionUnits)) { p ->
                updateProgress(job.id, 0.4f + p * 0.6f)
              }
            }

            val localResult = localDeferred.await()
            val cloudExecution = cloudDeferred.await()
            val cloudResult = cloudExecution.benchmark
            workerCpuPct = cloudExecution.workerCpuPct

            val totalDuration = maxOf(localResult.durationMs, cloudResult.durationMs)
            val totalOps = localResult.operationsCount + cloudResult.operationsCount
            val estimatedLocalTotal = estimateLocalTimeMs(job.algorithm, job.complexityUnits)
            speedupRatio = if (cloudExecution.executionProof == ExecutionProof.REAL_OCI_WORKER) {
              (estimatedLocalTotal.toFloat() / totalDuration.toFloat().coerceAtLeast(1f)).coerceAtLeast(1.0f)
            } else {
              1.0f
            }

            result = BenchmarkResult(
              durationMs = totalDuration,
              operationsCount = totalOps,
              summary = "[HYBRID] Local Actual + Cloud ${cloudExecution.executionProof}. ${cloudExecution.evidenceSummary} Local=${localResult.durationMs}ms, cloud=${cloudResult.durationMs}ms.",
              localCpuPct = localResult.localCpuPct,
              localCpuMeasured = localResult.localCpuMeasured
            )
            isSimulated = cloudExecution.executionProof != ExecutionProof.REAL_OCI_WORKER
          }
        }

        val completedJob = job.copy(
          status = JobStatus.COMPLETED,
          progress = 1.0f,
          durationMs = result.durationMs,
          resultSummary = result.summary,
          speedupRatio = speedupRatio,
          localCpuPct = if (result.localCpuMeasured) result.localCpuPct else -1f,
          cloudCpuPct = workerCpuPct ?: -1f,
          isSimulatedCloud = isSimulated
        )
        database.computeJobDao().updateJob(ComputeJobEntity.fromDomain(completedJob))

        val currentTel = streamManager.currentTelemetry.value
        database.telemetryDao().insertLog(
          TelemetryLogEntity(
            timestamp = System.currentTimeMillis(),
            roundTripLatencyMs = currentTel.roundTripLatencyMs,
            ociCpuPct = currentTel.ociCpuPct,
            ociMemoryMb = currentTel.ociMemoryMb,
            localCpuPct = if (result.localCpuMeasured) result.localCpuPct else -1f,
            throughputKbps = currentTel.networkThroughputKbps
          )
        )

      } catch (e: CancellationException) {
        val cancelledJob = job.copy(
          status = JobStatus.CANCELLED,
          resultSummary = "Task execution was cancelled by user.",
          progress = 0f
        )
        database.computeJobDao().updateJob(ComputeJobEntity.fromDomain(cancelledJob))
      } catch (e: Exception) {
        val failedJob = job.copy(
          status = JobStatus.FAILED,
          resultSummary = "Execution failure: ${e.message}",
          error = e.localizedMessage,
          progress = 0f
        )
        database.computeJobDao().updateJob(ComputeJobEntity.fromDomain(failedJob))
      } finally {
        activeJobs.remove(job.id)
      }
    }

    activeJobs[job.id] = coroutineJob
  }

  private suspend fun updateProgress(jobId: String, progress: Float) {
    val existing = database.computeJobDao().getJobById(jobId) ?: return
    database.computeJobDao().updateJob(existing.copy(progress = progress.coerceIn(0f, 0.99f)))
  }

  private suspend fun runLocalAlgorithm(
    job: ComputeJob,
    onProgress: suspend (Float) -> Unit
  ): BenchmarkResult {
    return when (job.algorithm) {
      CommandType.MATRIX_MULTIPLY -> LocalComputeEngine.executeMatrixMultiply(job.complexityUnits, onProgress)
      CommandType.CRYPTO_HASH_CHAIN -> LocalComputeEngine.executeCryptoHashChain(job.complexityUnits, onProgress)
      CommandType.MONTE_CARLO_PI -> LocalComputeEngine.executeMonteCarloPi(job.complexityUnits, onProgress)
      CommandType.PRIME_SIEVE -> LocalComputeEngine.executePrimeSieve(job.complexityUnits, onProgress)
      CommandType.SENSOR_TELEMETRY_BATCH -> LocalComputeEngine.executeSensorTelemetrySynthesis(job.complexityUnits, onProgress)
    }
  }

  private fun estimateLocalTimeMs(algorithm: CommandType, units: Int): Long {
    return when (algorithm) {
      CommandType.MATRIX_MULTIPLY -> (units * units * 0.008).toLong().coerceIn(200L, 12_000L)
      CommandType.CRYPTO_HASH_CHAIN -> (units * 0.045).toLong().coerceIn(150L, 8_000L)
      CommandType.MONTE_CARLO_PI -> (units * 0.003).toLong().coerceIn(100L, 5_000L)
      CommandType.PRIME_SIEVE -> (units * 0.007).toLong().coerceIn(100L, 6_000L)
      CommandType.SENSOR_TELEMETRY_BATCH -> (units * 0.02).toLong().coerceIn(80L, 4_000L)
    }
  }

  fun cancelJob(id: String) {
    activeJobs[id]?.cancel()
    scope.launch {
      val existing = database.computeJobDao().getJobById(id)
      if (existing != null && existing.status != JobStatus.COMPLETED.name) {
        database.computeJobDao().updateJob(
          existing.copy(
            status = JobStatus.CANCELLED.name,
            resultSummary = "Task cancelled by user.",
            progress = 0f
          )
        )
      }
    }
  }

  fun deleteJob(id: String) {
    scope.launch {
      database.computeJobDao().deleteJob(id)
    }
  }

  fun clearAllHistory() {
    scope.launch {
      database.computeJobDao().clearAll()
      database.telemetryDao().clearAll()
    }
  }
}
