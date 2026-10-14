package com.example

import com.example.engine.LocalComputeEngine
import com.example.engine.MtlsStreamManager
import com.example.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun localBenchmarksReturnMeasuredOrExplicitlyUnavailableCpuMetrics() {
        val results = listOf(
            LocalComputeEngine.runMatrixBenchmark(16),
            LocalComputeEngine.runHashChain(100),
            LocalComputeEngine.runMonteCarlo(1_000),
            LocalComputeEngine.runPrimeSieve(100),
            LocalComputeEngine.runSensorSynthesis(100)
        )

        results.forEach { result ->
            assertTrue(result.durationMs >= 1L)
            assertTrue(result.localCpuPct in 0f..100f)
            val measured = result.summary.contains("Measured system CPU load")
            val unavailable = result.summary.contains("System CPU load measurement unavailable")
            assertTrue("CPU metric must be measured or explicitly unavailable", measured || unavailable)
            assertEquals(measured, result.localCpuMeasured)
        }
    }

    @Test
    fun unconfiguredControlPlaneNeverClaimsAuthentication() {
        val manager = MtlsStreamManager()
        try {
            assertEquals(ConnectionState.UNCONFIGURED, manager.connectionState.value)
        } finally {
            manager.shutdown()
        }
    }
}
