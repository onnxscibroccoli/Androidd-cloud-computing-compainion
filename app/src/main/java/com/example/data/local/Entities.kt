package com.example.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import com.example.model.CommandType
import com.example.model.ComputeJob
import com.example.model.ExecutionTarget
import com.example.model.JobStatus
import com.example.model.Priority

@Entity(tableName = "compute_jobs")
data class ComputeJobEntity(
  @PrimaryKey val id: String,
  val title: String,
  val algorithm: String,
  val target: String,
  val priority: String,
  val status: String,
  val progress: Float,
  val durationMs: Long,
  val complexityUnits: Int,
  val resultSummary: String,
  val speedupRatio: Float,
  val localCpuPct: Float,
  @ColumnInfo(defaultValue = "0") val localCpuMeasured: Boolean = false,
  val cloudCpuPct: Float,
  val timestamp: Long,
  val error: String?,
  @ColumnInfo(defaultValue = "1") val isSimulatedCloud: Boolean = false
) {
  fun toDomain(): ComputeJob {
    return ComputeJob(
      id = id,
      title = title,
      algorithm = try { CommandType.valueOf(algorithm) } catch (e: Exception) { CommandType.MATRIX_MULTIPLY },
      target = try { ExecutionTarget.valueOf(target) } catch (e: Exception) { ExecutionTarget.OCI_CLOUD_STREAM },
      priority = try { Priority.valueOf(priority) } catch (e: Exception) { Priority.NORMAL },
      status = try { JobStatus.valueOf(status) } catch (e: Exception) { JobStatus.COMPLETED },
      progress = progress,
      durationMs = durationMs,
      complexityUnits = complexityUnits,
      resultSummary = resultSummary,
      speedupRatio = speedupRatio,
      localCpuPct = localCpuPct,
      localCpuMeasured = localCpuMeasured,
      cloudCpuPct = cloudCpuPct,
      timestamp = timestamp,
      error = error,
      isSimulatedCloud = isSimulatedCloud
    )
  }

  companion object {
    fun fromDomain(job: ComputeJob): ComputeJobEntity {
      return ComputeJobEntity(
        id = job.id,
        title = job.title,
        algorithm = job.algorithm.name,
        target = job.target.name,
        priority = job.priority.name,
        status = job.status.name,
        progress = job.progress,
        durationMs = job.durationMs,
        complexityUnits = job.complexityUnits,
        resultSummary = job.resultSummary,
        speedupRatio = job.speedupRatio,
        localCpuPct = job.localCpuPct,
        localCpuMeasured = job.localCpuMeasured,
        cloudCpuPct = job.cloudCpuPct,
        timestamp = job.timestamp,
        error = job.error,
        isSimulatedCloud = job.isSimulatedCloud
      )
    }
  }
}

@Entity(tableName = "telemetry_logs")
data class TelemetryLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val timestamp: Long,
  val roundTripLatencyMs: Float,
  val ociCpuPct: Float,
  val ociMemoryMb: Long,
  val localCpuPct: Float,
  val throughputKbps: Float
)
