package com.example.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.TimeUnit

/** OAuth 2.0 Authorization Code + PKCE client for an OCI Identity Domain public client. */
class OciOAuthManager(
    context: Context,
    identityDomainBaseUrl: String = BuildConfigValues.domainUrl(),
    private val clientId: String = BuildConfigValues.clientId(),
    private val redirectUri: String = "com.onnxscibroccoli.omnikali://oauth/callback",
    private val scopes: List<String> = listOf("openid", "profile"),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) {
    data class AuthorizationRequest(val url: Uri, internal val state: String)
    data class TokenSet(
        val accessToken: String,
        val idToken: String,
        val refreshToken: String?,
        val tokenType: String,
        val expiresInSeconds: Long?,
        val scope: String?
    )

    private val domain = identityDomainBaseUrl.trimEnd('/')
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "oci_oauth_secure_tokens",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        require(domain.startsWith("https://", true) && Uri.parse(domain).host != null) {
            "OCI Identity Domain must be a valid HTTPS URL"
        }
        require(clientId.isNotBlank()) { "OCI OAuth client ID is missing" }
        require(redirectUri.contains("://")) { "A registered custom-scheme redirect URI is required" }
        require(scopes.contains("openid")) { "OIDC scope 'openid' is required" }
    }

    fun createAuthorizationRequest(): AuthorizationRequest {
        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.s256Challenge(verifier)
        val state = Pkce.randomUrlSafe(32)
        val nonce = Pkce.randomUrlSafe(32)
        val url = Uri.parse("$domain/oauth2/v1/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scopes.distinct().joinToString(" "))
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .build()
        prefs.edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .putString(KEY_NONCE, nonce)
            .apply()
        return AuthorizationRequest(url, state)
    }

    suspend fun handleAuthorizationRedirect(callback: Uri): TokenSet = withContext(Dispatchers.IO) {
        val registered = Uri.parse(redirectUri)
        require(callback.scheme == registered.scheme && callback.host == registered.host &&
            callback.port == registered.port && callback.path == registered.path) {
            "Unexpected OAuth redirect URI"
        }
        val receivedState = callback.getQueryParameter("state") ?: ""
        val expectedState = prefs.getString(KEY_STATE, null)
            ?: throw SecurityException("No pending authorization request")
        if (!MessageDigest.isEqual(receivedState.toByteArray(), expectedState.toByteArray())) {
            clearPending()
            throw SecurityException("OAuth state validation failed")
        }
        callback.getQueryParameter("error")?.let {
            clearPending()
            throw IOException("OCI authorization failed: $it")
        }
        val code = callback.getQueryParameter("code")?.takeIf { it.isNotBlank() }
            ?: throw IOException("OAuth callback has no authorization code")
        val verifier = prefs.getString(KEY_VERIFIER, null)
            ?: throw SecurityException("PKCE verifier is missing")
        val nonce = prefs.getString(KEY_NONCE, null)
            ?: throw SecurityException("OIDC nonce is missing")

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .build()
        val responseTokens = postToken(body)
        verifyIdToken(responseTokens.idToken, nonce)
        persist(responseTokens)
        clearPending()
        responseTokens
    }

    suspend fun refreshAccessToken(): TokenSet = withContext(Dispatchers.IO) {
        val refresh = prefs.getString(KEY_REFRESH, null)
            ?: throw IllegalStateException("No refresh token is stored")
        val fresh = postToken(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("refresh_token", refresh)
                .build()
        )
        val result = fresh.copy(
            idToken = prefs.getString(KEY_ID, "").orEmpty(),
            refreshToken = fresh.refreshToken ?: refresh
        )
        persist(result)
        result
    }

    fun storedTokens(): TokenSet? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        return TokenSet(
            accessToken = access,
            idToken = prefs.getString(KEY_ID, "").orEmpty(),
            refreshToken = prefs.getString(KEY_REFRESH, null),
            tokenType = prefs.getString(KEY_TYPE, "Bearer") ?: "Bearer",
            expiresInSeconds = prefs.getLong(KEY_EXPIRY, -1L).takeIf { it >= 0 },
            scope = prefs.getString(KEY_SCOPE, null)
        )
    }

    fun clearTokens() = prefs.edit().clear().apply()

    private fun postToken(body: FormBody): TokenSet {
        val request = Request.Builder()
            .url("$domain/oauth2/v1/token")
            .header("Accept", "application/json")
            .post(body)
            .build()
        return http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("OCI token endpoint returned HTTP ${response.code}: ${safeError(payload)}")
            }
            val json = JSONObject(payload)
            val access = json.optString("access_token")
            val id = json.optString("id_token")
            if (access.isBlank()) throw IOException("Token response omitted access_token")
            val tokenType = json.optString("token_type", "Bearer")
            if (!tokenType.equals("Bearer", true)) throw IOException("Unsupported token type")
            TokenSet(
                accessToken = access,
                idToken = id,
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                tokenType = tokenType,
                expiresInSeconds = json.optLong("expires_in", -1L).takeIf { it >= 0 },
                scope = json.optString("scope").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun verifyIdToken(token: String, expectedNonce: String) {
        val parts = token.split(".")
        if (parts.size != 3) throw SecurityException("ID token is not a compact JWS")
        val header = JSONObject(String(Pkce.decode(parts[0]), Charsets.UTF_8))
        val claims = JSONObject(String(Pkce.decode(parts[1]), Charsets.UTF_8))
        if (header.optString("alg") != "RS256") throw SecurityException("Unsupported ID token algorithm")
        val kid = header.optString("kid")
        if (kid.isBlank()) throw SecurityException("ID token has no signing key ID")

        val discovery = http.newCall(
            Request.Builder().url("$domain/.well-known/openid-configuration").build()
        ).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("OIDC discovery failed with HTTP ${response.code}")
            JSONObject(raw)
        }
        val issuer = discovery.optString("issuer")
        val jwksUrl = discovery.optString("jwks_uri")
        if (issuer.isBlank() || !jwksUrl.startsWith("https://")) {
            throw SecurityException("OIDC discovery metadata is invalid")
        }
        val jwks = http.newCall(Request.Builder().url(jwksUrl).build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("JWKS fetch failed with HTTP ${response.code}")
            JSONObject(raw)
        }
        val keys = jwks.optJSONArray("keys") ?: throw SecurityException("JWKS response omitted keys")
        val key = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
            .firstOrNull {
                it.optString("kid") == kid && it.optString("kty") == "RSA" &&
                    it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
            } ?: throw SecurityException("No matching trusted RSA signing key")
        val modulus = BigInteger(1, Pkce.decode(key.getString("n")))
        val exponent = BigInteger(1, Pkce.decode(key.getString("e")))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        if (!verifier.verify(Pkce.decode(parts[2]))) throw SecurityException("ID token signature invalid")

        if (claims.optString("iss") != issuer) throw SecurityException("ID token issuer mismatch")
        val audience = claims.opt("aud")
        val audienceMatches = when (audience) {
            is String -> audience == clientId
            is JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
            else -> false
        }
        if (!audienceMatches) throw SecurityException("ID token audience mismatch")
        val now = System.currentTimeMillis() / 1000
        if (claims.optString("sub").isBlank()) throw SecurityException("ID token subject is missing")
        if (claims.optLong("exp", 0) <= now) throw SecurityException("ID token expired")
        if (claims.optLong("iat", Long.MAX_VALUE) > now + 60) throw SecurityException("ID token issued-at time is in the future")
        if (claims.has("nbf") && claims.optLong("nbf") > now + 60) throw SecurityException("ID token not yet valid")
        if (claims.optString("nonce") != expectedNonce) throw SecurityException("ID token nonce mismatch")
        val authorizedParty = claims.optString("azp")
        if (authorizedParty.isNotBlank() && authorizedParty != clientId) {
            throw SecurityException("ID token authorized-party mismatch")
        }
        if (audience is JSONArray && audience.length() > 1 && authorizedParty != clientId) {
            throw SecurityException("ID token authorized-party is required for multiple audiences")
        }
    }

    private fun persist(tokens: TokenSet) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_ID, tokens.idToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putString(KEY_TYPE, tokens.tokenType)
            .putLong(KEY_EXPIRY, tokens.expiresInSeconds ?: -1L)
            .putString(KEY_SCOPE, tokens.scope)
            .apply()
    }

    private fun clearPending() {
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).remove(KEY_NONCE).apply()
    }

    private fun safeError(raw: String): String = try {
        val json = JSONObject(raw)
        json.optString("error_description", json.optString("error", "unknown")).take(200)
    } catch (_: Exception) { "unparseable error response" }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_ID = "id_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_TYPE = "token_type"
        private const val KEY_EXPIRY = "expires_in"
        private const val KEY_SCOPE = "scope"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "oauth_state"
        private const val KEY_NONCE = "oidc_nonce"
    }
}

object Pkce {
    private val random = SecureRandom()
    fun generateVerifier(): String = randomUrlSafe(64)
    fun s256Challenge(verifier: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    fun randomUrlSafe(byteCount: Int): String {
        require(byteCount >= 32)
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private object BuildConfigValues {
    fun clientId(): String = com.example.BuildConfig.OCI_CLIENT_ID
    fun domainUrl(): String = com.example.BuildConfig.OCI_DOMAIN_URL
}
