package com.example.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControlPlaneTransportTest {
    @Test
    fun missingClientIdentityFailsClosedWithoutNetworkAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missingIdentity = File(context.cacheDir, "absent-control-plane-identity.p12")
        missingIdentity.delete()
        val transport = ControlPlaneTransport(
            context = context,
            host = "127.0.0.1",
            port = 443,
            identityFile = missingIdentity,
            identityPassword = "not-a-real-password".toCharArray(),
            rootCaPem = ByteArray(0)
        )

        try {
            transport.connect()
            throw AssertionError("Transport connected without a client identity")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("PKCS#12 identity"))
        } finally {
            transport.close()
        }
    }
}
