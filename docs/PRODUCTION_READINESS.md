# Production Readiness Gate

Last reviewed: 2026-10-14

This document separates implemented behavior from requirements that still need integration evidence. A successful Android build is not proof of OCI execution, OAuth login, mTLS, gRPC, or device bootstrap.

## Evidence status

| Requirement | Status | Evidence / next gate |
|---|---|---|
| Android unit tests | PASS on CI run #10 | Run #10 completed `testDebugUnitTest` successfully. |
| Debug APK packaging | NOT_PROVEN | Run #10 failed at `validateSigningDebug` because the repository intentionally does not contain `debug.keystore`. CI run #11 now generates an ephemeral debug key before packaging. |
| Production APK signing | NOT_PROVEN | A release keystore and signing secrets are not configured in CI. Never commit a keystore or passwords. |
| OCI OAuth authorization-code + PKCE | NOT_IMPLEMENTED | Requires the OCI Identity Domain issuer/discovery URL, native OAuth client ID, and registered redirect URI. Do not put client secrets in the APK. |
| Real OCI worker dispatch | NOT_IMPLEMENTED | The current `DemoOciControllerService` returns `SIMULATED_FALLBACK` and cannot report `REAL_OCI_WORKER`. |
| gRPC bidirectional control/telemetry | NOT_IMPLEMENTED | A deployed authenticated controller endpoint, protocol/schema, TLS trust, retry policy, and receipt contract are required. A raw TLS probe is not gRPC. |
| Client mTLS identity | NOT_IMPLEMENTED | The app deliberately fails closed when mTLS is required and no client certificate/private-key provider exists. |
| Shizuku / rish bootstrap | NOT_PROVEN | Requires integration tests on the physical Android device and permission-grant/reboot recovery evidence. |
| Remote Desktop Commander connectors | NOT_INTEGRATED | Must be configured and verified against the actual Grasshopper and Kali endpoints. |
| Physical device install / smoke test | NOT_RUN | Requires a device run and captured evidence. |

## Required non-secret deployment inputs

1. OCI Identity Domain issuer or OpenID Connect discovery URL.
2. OAuth native-app client ID.
3. Exact registered redirect URI for Authorization Code + PKCE.
4. Public HTTPS controller API base URL, with job submission, status/telemetry, and signed execution-receipt endpoints.
5. Controller TLS hostname and the intended certificate-enrollment flow for mTLS.
6. Production signing configuration added through repository Actions secrets, not source control.

Do not send passwords, refresh tokens, OCI API private keys, keystore files, or client secrets in chat or commit them to this repository.

## Release gate

A release may be labeled production-ready only after:
- CI unit tests and APK packaging pass;
- production signing is verified using the configured release key;
- OAuth PKCE login and token renewal are tested against the real identity domain;
- backend authorization and TLS/mTLS are verified end-to-end;
- an actual OCI worker returns a verifiable receipt bound to the unique job ID;
- simulated results remain visibly distinguished from real execution;
- Shizuku/rish startup and reconnect are tested on the target Android version;
- Grasshopper and Kali connectors pass live health checks;
- the APK is installed and smoke-tested on a physical device.

Until then, any APK produced by the workflow is a **physical-test build**, not a production controller.
