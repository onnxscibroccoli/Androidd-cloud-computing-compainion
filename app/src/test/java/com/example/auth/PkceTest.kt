package com.example.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import android.util.Base64

@RunWith(RobolectricTestRunner::class)
class PkceTest {
    @Test fun verifierHasHighEntropyAndValidPkceLength() {
        val verifier = Pkce.generateVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertEquals(86, verifier.length)
    }

    @Test fun challengeIsS256AndBase64UrlEncoded() {
        val verifier = Pkce.generateVerifier()
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        assertEquals(expected, Pkce.s256Challenge(verifier))
        assertEquals(43, expected.length)
    }

    @Test fun independentVerifiersDiffer() {
        assertNotEquals(Pkce.generateVerifier(), Pkce.generateVerifier())
    }
}
