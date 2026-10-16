package com.example.transport

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ControlPlaneTransportTest {
    @Test
    fun missingClientIdentityFailsClosedWithoutNetworkAccess() {
        val missingIdentity = File(
            System.getProperty("java.io.tmpdir"),
            "absent-control-plane-identity-${UUID.randomUUID()}.p12"
        )
        missingIdentity.delete()

        try {
            ControlPlaneIdentityValidator.requireProvisionedIdentity(missingIdentity)
            throw AssertionError("Transport identity validation accepted a missing client identity")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("PKCS#12 identity"))
        } finally {
            missingIdentity.delete()
        }
    }
}
