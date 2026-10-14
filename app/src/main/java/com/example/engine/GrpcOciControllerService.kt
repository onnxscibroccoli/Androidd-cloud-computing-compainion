package com.example.engine

import android.content.Context
import com.example.BuildConfig
import com.example.model.ComputeJob
import com.example.transport.ControlPlaneCredentialStore
import com.example.transport.proto.SubmitExecutionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

/**
 * Production adapter for the authenticated control-plane SubmitExecution RPC.
 * It never falls back to synthetic work. Missing credentials, protocol errors, invalid receipts,
 * and server-side execution failures are propagated to the repository as failed jobs.
 */
class GrpcOciControllerService(
    context: Context,
    private val streamManager: MtlsStreamManager
) : OciControllerService {
    private val credentials = ControlPlaneCredentialStore(context.applicationContext)

    override suspend fun submit(
        job: ComputeJob,
        onProgress: suspend (Float) -> Unit
    ): OciExecutionResult = withContext(Dispatchers.IO) {
        require(job.id.isNotBlank()) { "Job ID is required" }
        require(job.complexityUnits > 0) { "Complexity units must be positive" }

        val config = streamManager.mtlsConfig.value
        require(config.isMtlsEnforced) {
            "Production cloud execution requires mutual TLS. Disablement is not accepted by the production adapter."
        }
        require(config.endpointHost.isNotBlank()) { "Control-plane host is not configured" }

        val requestId = UUID.randomUUID().toString()
        val request = SubmitExecutionRequest.newBuilder()
            .setRequestId(requestId)
            .setJobId(job.id)
            .setAlgorithm(job.algorithm.name)
            .setComplexityUnits(job.complexityUnits)
            .setPriority(job.priority.name)
            .setTitle(job.title.take(MAX_TITLE_LENGTH))
            .build()

        val transport = credentials.createTransport(config.endpointHost, config.endpointPort)
        try {
            onProgress(0.05f)
            val receipt = transport.submitExecution(request, timeoutSeconds = EXECUTION_TIMEOUT_SECONDS)

            if (receipt.requestId != requestId) {
                throw SecurityException("Execution receipt request ID does not match the submitted request")
            }
            if (!receipt.accepted) throw IllegalStateException("Control plane rejected the execution request")
            if (receipt.workerId.isBlank()) throw SecurityException("Execution receipt omitted the worker identity")
            if (receipt.startedAtEpochMs <= 0L ||
                receipt.completedAtEpochMs < receipt.startedAtEpochMs ||
                receipt.durationMs <= 0L
            ) {
                throw SecurityException("Execution receipt contains invalid timing evidence")
            }
            if (receipt.operationsCount < 0L || receipt.resultSummary.isBlank()) {
                throw SecurityException("Execution receipt is missing result evidence")
            }
            if (!receipt.resultSummary.all { it.code >= 0x20 || it == '\n' || it == '\r' || it == '\t' }) {
                throw SecurityException("Execution receipt summary contains control characters")
            }

            onProgress(1f)
            val summary = "[REAL_OCI_WORKER] worker=${receipt.workerId}; request=$requestId; " +
                "started=${receipt.startedAtEpochMs}; completed=${receipt.completedAtEpochMs}; " +
                receipt.resultSummary.take(MAX_SUMMARY_LENGTH)
            OciExecutionResult(
                benchmark = BenchmarkResult(
                    durationMs = receipt.durationMs,
                    operationsCount = receipt.operationsCount,
                    summary = summary,
                    localCpuPct = 0f
                ),
                executionProof = ExecutionProof.REAL_OCI_WORKER,
                workerId = receipt.workerId,
                requestId = requestId,
                evidenceSummary = "Authenticated mTLS SubmitExecution response with matching request ID and worker receipt."
            )
        } finally {
            transport.close()
        }
    }

    companion object {
        private const val EXECUTION_TIMEOUT_SECONDS = 600L
        private const val MAX_TITLE_LENGTH = 160
        private const val MAX_SUMMARY_LENGTH = 2048
    }
}
