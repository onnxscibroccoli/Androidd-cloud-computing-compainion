# Hybrid Android execution security contract

## OAuth/OIDC

The native client is a public OAuth client and does not embed a client secret. It uses Authorization Code + PKCE, a 64-byte CSPRNG input encoded as an 86-character base64url verifier, S256, state validation, OIDC nonce validation, HTTPS discovery/JWKS, RS256 signature verification, issuer/audience/expiry checks, and Android Keystore-backed encrypted token storage. Configure the registered Identity Domain URL, client ID, and exact custom-scheme redirect URI. Register the redirect URI in OCI Identity Domains and declare the matching intent filter in the Android manifest before shipping.

## gRPC mTLS

The gRPC client explicitly initializes KeyManagerFactory from a provisioned per-device PKCS#12 identity and TrustManagerFactory from a trusted CA bundle. TLS is restricted to TLS 1.3. Missing identity, password, CA certificates, or valid trust manager fails closed. Do not ship one shared private key inside the APK. Provision unique client identities through an authenticated, device-bound process and store their passwords with Android Keystore-backed encryption. The server must validate client certificate identity and authorization.

The repository secrets supplied so far do not include a per-device client certificate or control-plane root CA. The release workflow therefore compiles and tests without making a live connection; it does not manufacture certificates or bypass trust checks. A real deployment requires the provisioning process and the actual control-plane CA trust anchor.

## Shizuku/Rish

The service waits for Shizuku binder availability and the user's explicit permission. It requires a trusted Rish launcher installed in app-private no-backup storage, passes RISH_PRESERVE_ENV=1, accepts only an allowlisted executable plus a bounded argument array, captures stdout and stderr concurrently, and enforces a timeout. A Shizuku grant is a powerful capability; never execute arbitrary command text from an unauthenticated server.

## GitHub Actions

Repository secrets required by the requested release pipeline: RELEASE_KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD, OCI_CLIENT_ID, OCI_DOMAIN_URL. OAuth client ID and domain are build configuration, not authorization credentials. mTLS private identities should be provisioned per device rather than embedded as a shared app asset. CI unit tests are offline and must not call a live OCI domain.
