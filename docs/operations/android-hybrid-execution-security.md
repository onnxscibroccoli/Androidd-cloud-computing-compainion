# Hybrid Android execution security contract

## OAuth/OIDC

The native client is a public OAuth client and does not embed a client secret. It uses Authorization Code + PKCE, a 64-byte CSPRNG input encoded as an 86-character base64url verifier, S256, state validation, OIDC nonce validation, HTTPS discovery/JWKS, RS256 signature verification, issuer/audience/expiry checks, and Android Keystore-backed encrypted token storage. Configure the registered Identity Domain URL and client ID. Register the exact redirect URI `com.onnxscibroccoli.omnikali://oauth/callback` in the OCI Identity Domain application. The Android manifest declares the matching intent filter. Register the exact redirect URI in OCI Identity Domains before live sign-in testing.

## gRPC mTLS

The gRPC client explicitly initializes KeyManagerFactory from a provisioned per-device PKCS#12 identity and TrustManagerFactory from a trusted CA bundle. TLS is restricted to TLS 1.3. Missing identity, password, CA certificates, or valid trust manager fails closed. Do not ship one shared private key inside the APK. Provision unique client identities through an authenticated, device-bound process and store their passwords with Android Keystore-backed encryption. The server must validate client certificate identity and authorization.

The release workflow secrets intentionally do not contain a shared per-device private key or root CA. The app's mTLS settings import a device-specific PKCS#12 identity and trusted CA bundle, validate the certificate/key material, store files in app-private no-backup storage, and encrypt the PKCS#12 password with Android Keystore-backed preferences. The control-plane server must implement the `HealthCheck` and `SubmitExecution` RPCs in `control_plane.proto`; missing credentials, an unimplemented RPC, invalid readiness data, or an invalid execution receipt fails closed. The repository does not manufacture certificates or bypass trust checks.

## Shizuku/Rish

The service waits for Shizuku binder availability and the user's explicit permission. Install the official Rish shell script and matching `rish_shizuku.dex` into app-private no-backup storage through `installRishBundle`; the method requires expected SHA-256 digests from the trusted provisioning channel and makes the DEX read-only. Rish runs as the app UID so it can read the DEX and use the Shizuku binder, then opens the elevated remote shell. Each process explicitly receives `RISH_PRESERVE_ENV=1`. The service accepts only an allowlisted executable plus a bounded argument array, captures stdout and stderr concurrently, and enforces a timeout. A Shizuku grant is a powerful capability; never execute arbitrary command text from an unauthenticated server.

## GitHub Actions

Repository secrets required by the requested release pipeline: RELEASE_KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD, OCI_CLIENT_ID, OCI_DOMAIN_URL. OAuth client ID and domain are build configuration, not authorization credentials. mTLS private identities should be provisioned per device rather than embedded as a shared app asset. CI unit tests are offline and must not call a live OCI domain.
