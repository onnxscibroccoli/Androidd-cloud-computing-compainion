package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ComputeJobDao {
  @Query("SELECT * FROM compute_jobs ORDER BY timestamp DESC")
  fun getAllJobs(): Flow<List<ComputeJobEntity>>

  @Query("SELECT * FROM compute_jobs WHERE id = :id LIMIT 1")
  suspend fun getJobById(id: String): ComputeJobEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertJob(job: ComputeJobEntity)

  @Update
  suspend fun updateJob(job: ComputeJobEntity)

  @Query("DELETE FROM compute_jobs WHERE id = :id")
  suspend fun deleteJob(id: String)

  @Query("DELETE FROM compute_jobs")
  suspend fun clearAll()
}

@Dao
interface TelemetryDao {
  @Query("SELECT * FROM telemetry_logs ORDER BY timestamp DESC LIMIT :limit")
  fun getRecentLogs(limit: Int = 40): Flow<List<TelemetryLogEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertLog(log: TelemetryLogEntity)

  @Query("DELETE FROM telemetry_logs")
  suspend fun clearAll()
}
