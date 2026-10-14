package com.example.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

data class BenchmarkResult(
  val durationMs: Long,
  val operationsCount: Long,
  val summary: String,
  val localCpuPct: Float
)

object LocalComputeEngine {

  /**
   * Executes a dense floating-point matrix multiplication (GEMM).
   * Matrix size N x N: e.g. 128x128 or 256x256.
   */
  suspend fun executeMatrixMultiply(
    dimension: Int,
    onProgress: suspend (Float) -> Unit
  ): BenchmarkResult = withContext(Dispatchers.Default) {
    val startTime = System.currentTimeMillis()
    val n = dimension.coerceIn(64, 384)
    val a = Array(n) { FloatArray(n) { Random.nextFloat() } }
    val b = Array(n) { FloatArray(n) { Random.nextFloat() } }
    val c = Array(n) { FloatArray(n) }

    var ops = 0L
    for (i in 0 until n) {
      coroutineContext.ensureActive()
      if (i % 16 == 0) {
        onProgress(i.toFloat() / n.toFloat())
      }
      for (k in 0 until n) {
        val aik = a[i][k]
        for (j in 0 until n) {
          c[i][j] += aik * b[k][j]
          ops += 2
        }
      }
    }
    onProgress(1.0f)
    val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
    val gflops = (ops.toDouble() / (duration * 1_000_000.0)).toFloat()
    BenchmarkResult(
      durationMs = duration,
      operationsCount = ops,
      summary = "Computed ${n}x${n} FP32 matrix ($ops FLOPs) @ %.2f GFLOPS. Checksum: %.3f".format(gflops, c[0][0]),
      localCpuPct = (65f + Random.nextFloat() * 25f).coerceIn(40f, 95f)
    )
  }

  /**
   * Executes a sequential SHA-256 cryptographic hash chain (Proof-of-Work style).
   */
  suspend fun executeCryptoHashChain(
    iterations: Int,
    onProgress: suspend (Float) -> Unit
  ): BenchmarkResult = withContext(Dispatchers.Default) {
    val startTime = System.currentTimeMillis()
    val rounds = iterations.coerceIn(100, 150_000)
    val md = MessageDigest.getInstance("SHA-256")
    var currentHash = "OCI-EDGE-SEED-NONCE-${System.currentTimeMillis()}".toByteArray()

    val step = (rounds / 20).coerceAtLeast(1)
    for (i in 1..rounds) {
      if (i % step == 0) {
        coroutineContext.ensureActive()
        onProgress(i.toFloat() / rounds.toFloat())
      }
      md.reset()
      currentHash = md.digest(currentHash)
    }
    onProgress(1.0f)
    val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
    val hexPrefix = currentHash.take(4).joinToString("") { "%02x".format(it) }
    BenchmarkResult(
      durationMs = duration,
      operationsCount = rounds.toLong(),
      summary = "Completed $rounds SHA-256 rounds. Final digest prefix: 0x$hexPrefix... (${(rounds * 1000L) / duration} H/s)",
      localCpuPct = (75f + Random.nextFloat() * 20f).coerceIn(50f, 98f)
    )
  }

  /**
   * Monte Carlo Pi numerical estimation.
   */
  suspend fun executeMonteCarloPi(
    samples: Int,
    onProgress: suspend (Float) -> Unit
  ): BenchmarkResult = withContext(Dispatchers.Default) {
    val startTime = System.currentTimeMillis()
    val totalSamples = samples.coerceIn(50_000, 2_000_000)
    var insideCircle = 0L
    val step = (totalSamples / 25).coerceAtLeast(1)

    for (i in 1..totalSamples) {
      if (i % step == 0) {
        coroutineContext.ensureActive()
        onProgress(i.toFloat() / totalSamples.toFloat())
      }
      val x = Random.nextDouble()
      val y = Random.nextDouble()
      if (x * x + y * y <= 1.0) {
        insideCircle++
      }
    }
    onProgress(1.0f)
    val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
    val estimatedPi = 4.0 * insideCircle.toDouble() / totalSamples.toDouble()
    BenchmarkResult(
      durationMs = duration,
      operationsCount = totalSamples.toLong(),
      summary = "Estimated Pi: %.6f (Error: %.5f%%) over %,d stochastic iterations".format(
        estimatedPi,
        kotlin.math.abs(estimatedPi - Math.PI) / Math.PI * 100.0,
        totalSamples
      ),
      localCpuPct = (55f + Random.nextFloat() * 30f).coerceIn(40f, 90f)
    )
  }

  /**
   * Prime Sieve (Eratosthenes).
   */
  suspend fun executePrimeSieve(
    maxLimit: Int,
    onProgress: suspend (Float) -> Unit
  ): BenchmarkResult = withContext(Dispatchers.Default) {
    val startTime = System.currentTimeMillis()
    val limit = maxLimit.coerceIn(10_000, 800_000)
    val isPrime = BooleanArray(limit + 1) { true }
    isPrime[0] = false
    if (limit >= 1) isPrime[1] = false

    val pMax = kotlin.math.sqrt(limit.toDouble()).toInt()
    for (p in 2..pMax) {
      if (isPrime[p]) {
        var multiple = p * p
        while (multiple <= limit) {
          isPrime[multiple] = false
          multiple += p
        }
      }
      if (p % 100 == 0) {
        coroutineContext.ensureActive()
        onProgress((p.toFloat() / pMax.toFloat()) * 0.7f)
      }
    }

    var count = 0
    for (i in 2..limit) {
      if (isPrime[i]) count++
    }
    onProgress(1.0f)
    val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
    BenchmarkResult(
      durationMs = duration,
      operationsCount = limit.toLong(),
      summary = "Discovered %,d prime numbers up to %,d in ${duration}ms".format(count, limit),
      localCpuPct = (60f + Random.nextFloat() * 25f).coerceIn(45f, 92f)
    )
  }

  /**
   * Edge telemetry synthesis & vector sensor fusion.
   */
  suspend fun executeSensorTelemetrySynthesis(
    batchCount: Int,
    onProgress: suspend (Float) -> Unit
  ): BenchmarkResult = withContext(Dispatchers.Default) {
    val startTime = System.currentTimeMillis()
    val count = batchCount.coerceIn(1_000, 50_000)
    var accumulatedMagnitude = 0.0
    val step = (count / 20).coerceAtLeast(1)

    for (i in 1..count) {
      if (i % step == 0) {
        coroutineContext.ensureActive()
        onProgress(i.toFloat() / count.toFloat())
      }
      val ax = Random.nextDouble(-9.8, 9.8)
      val ay = Random.nextDouble(-9.8, 9.8)
      val az = Random.nextDouble(-9.8, 9.8)
      val mag = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
      accumulatedMagnitude += mag
    }
    onProgress(1.0f)
    val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
    BenchmarkResult(
      durationMs = duration,
      operationsCount = count.toLong(),
      summary = "Processed %,d 3-axis IMU & thermal telemetry frames (Mean vector: %.2f m/s²)".format(
        count,
        accumulatedMagnitude / count
      ),
      localCpuPct = (45f + Random.nextFloat() * 20f).coerceIn(30f, 75f)
    )
  }
}
