package com.example.transport

import java.io.File

/** Pure-JVM guard used before any socket or TLS setup. */
internal object ControlPlaneIdentityValidator {
    fun requireProvisionedIdentity(identityFile: File) {
        check(identityFile.isFile && identityFile.canRead() && identityFile.length() > 0L) {
            "A provisioned per-device PKCS#12 identity is required"
        }
    }
}
