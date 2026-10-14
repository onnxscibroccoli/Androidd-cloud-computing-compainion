package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.LocalComputeEngine
import com.example.engine.MtlsStreamManager
import com.example.model.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("OCI Cloud Controller", appName)
  }

  @Test
  fun `local compute engine executes matrix multiplication`() = runBlocking {
    val result = LocalComputeEngine.executeMatrixMultiply(64) { }
    assertNotNull(result)
    assertTrue("Duration should be >= 0", result.durationMs >= 0)
    assertTrue("Should perform operations", result.operationsCount > 0)
  }

  @Test
  fun `local compute engine executes crypto hash chain`() = runBlocking {
    val result = LocalComputeEngine.executeCryptoHashChain(500) { }
    assertNotNull(result)
    assertTrue("Operations count matches", result.operationsCount == 500L)
  }

  @Test
  fun `local compute engine executes prime sieve`() = runBlocking {
    val result = LocalComputeEngine.executePrimeSieve(10_000) { }
    assertNotNull(result)
    assertTrue("Operations count matches limit", result.operationsCount == 10_000L)
  }

  @Test
  fun `mtls stream manager initial state is disconnected`() {
    val streamManager = MtlsStreamManager()
    assertNotNull(streamManager.mtlsConfig.value)
    assertEquals(443, streamManager.mtlsConfig.value.endpointPort)
    assertEquals(ConnectionState.DISCONNECTED, streamManager.connectionState.value)
    assertFalse("Disconnected state cannot be operational", streamManager.connectionState.value.isReady)
  }

  @Test
  fun `mTLS required without client identity fails closed`() = runBlocking {
    val streamManager = MtlsStreamManager()
    streamManager.updateConfig(streamManager.mtlsConfig.value.copy(isMtlsEnforced = true))
    streamManager.attemptTlsHandshake()
    // The default config has no client-certificate/private-key provider. It must not claim TLS auth.
    delay(250)
    assertEquals(ConnectionState.ERROR, streamManager.connectionState.value)
    assertFalse("Missing client identity must never be reported as ready", streamManager.connectionState.value.isReady)
    assertNotEquals(ConnectionState.READY, streamManager.connectionState.value)
  }
}
