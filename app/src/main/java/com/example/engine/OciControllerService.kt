package com.example.engine

import com.example.model.ComputeJob

/**
 * Evidence level for a compute result. REAL_OCI_WORKER must only be returned by a controller
 * implementation that verifies a server-side receipt tied to a unique request/job ID.
 */
enum class ExecutionProof {
  REAL_OCI_WORKER,
  SIMULATED_FALLBACK,
  LOCAL_EXECUTION,
  UNVERIFIED
}

data class OciExecutionResult(
  val benchmark: BenchmarkResult,
  val executionProof: ExecutionProof,
  val workerId: String? = null,
  val requestId: String? = null,
  val evidenceSummary: String
)

/**
 * Boundary for the canonical controller/backend. The Android app must not embed OCI API keys,
 * session tokens, or private keys. A production implementation should call an authenticated
 * controller backend and verify a signed/otherwise authenticated worker receipt before returning
 * REAL_OCI_WORKER.
 */
interface OciControllerService {
  suspend fun submit(
    job: ComputeJob,
    onProgress: suspend (Float) -> Unit
  ): OciExecutionResult
}

/**
 * Explicit demo adapter. It is deliberately incapable of returning REAL_OCI_WORKER.
 * Replace only after the controller API and receipt-verification contract are implemented.
 */
class DemoOciControllerService(
  private val streamManager: MtlsStreamManager
) : OciControllerService {
  override suspend fun submit(
    job: ComputeJob,
    onProgress: suspend (Float) -> Unit
  ): OciExecutionResult {
    val benchmark = streamManager.dispatchDemoCloudSimulation(job, onProgress)
    return OciExecutionResult(
      benchmark = benchmark.copy(
        summary = "[SIMULATED_FALLBACK] ${benchmark.summary}"
      ),
      executionProof = ExecutionProof.SIMULATED_FALLBACK,
      evidenceSummary = "Synthetic demo result; no OCI worker receipt or live cloud execution was verified."
    )
  }
}
