package com.example

import com.example.engine.LocalComputeEngine
import com.example.engine.MtlsStreamManager
import com.example.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ExampleUnitTest {
    @Test
    fun localBenchmarksReturnMeasuredOrExplicitlyUnavailableCpuMetrics() = runBlocking {
        val noProgress: suspend (Float) -> Unit = {}
        val results = listOf(
            LocalComputeEngine.executeMatrixMultiply(64, noProgress),
            LocalComputeEngine.executeCryptoHashChain(100, noProgress),
            LocalComputeEngine.executeMonteCarloPi(50_000, noProgress),
            LocalComputeEngine.executePrimeSieve(10_000, noProgress),
            LocalComputeEngine.executeSensorTelemetrySynthesis(1_000, noProgress)
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
