# Hybrid Android execution contract

The requested deliverable is a non-root Android controller, OCI control plane, workstation connectors, provider-neutral API, and signed APK build pipeline. This implementation is isolated under hybrid/ in broccoli-core. Existing production listeners and running Android workloads are not changed.

## Boundaries
The APK owns an accessible foreground session and Shizuku UserService. Only shell UID 2000 is permitted. Shevery supplies the compatible Shizuku Binder service; it is not a cloud SDK. OCI provisioning belongs to an asynchronous cloud worker, never the privileged Android process. A shell-only content provider accepts a fresh per-boot rish initialization and typed local commands. Binder death and reboot revoke readiness. No root or hidden Android API is used by controller code.

The device initiates a mutually authenticated TLS gRPC bidirectional stream. Certificates identify devices through a configured SHA-256 fingerprint registry. A bounded command queue persists jobs in SQLite WAL. Delivery can repeat; executors persist IDs before side effects. Interrupted effects become UNKNOWN and are never automatically rerun. Results are acknowledged and resent until acknowledged. Expired commands are rejected on the device. Observations do not create commands.

The HTTPS gateway requires a client certificate and an OAuth2 client-credentials access token bound to that certificate (RFC 8705). Client registry entries authorize scopes and targets. A certificate-authenticated token endpoint issues short-lived RS256 tokens. Hosted AI products that cannot present client certificates cannot directly invoke this endpoint: a user-operated adapter is required. An adapter is a separate trust boundary, not evidence of native platform mTLS support. No plaintext or bearer-only fallback exists.

OCI uses instance principals and deployment-time allowlists for compartments, shapes, subnets, and images. Provision requests select a named administrator-owned profile. Workstation execution selects a local allowlisted executable, never arbitrary remote shell text. RDC remains the human/agent control connector; its authenticated session is managed by the official remote CLI, not an invented API.

## Failure semantics
Offline endpoints retain queued jobs until deadline. A disconnected stream cannot automatically retry a side effect marked RUNNING on a prior process. The system exposes UNKNOWN for reconciliation. Human stop always disables device dispatch. Output is capped at 64 KiB. Commands have a maximum 60-second local execution timeout. TLS verification, client identity, token binding, scopes, target ownership, input validation, and idempotency all fail closed.

## Acceptance
Build and lint the Android release; run unit and real TLS/stream integration tests; cryptographically verify the signed APK; retain SHA-256 and signer certificate evidence tied to source commit. Device acceptance additionally requires installation, boot, rish handshake, permission denial/revocation, offline/reconnect, screen input, and stop tests on the physical phone. OCI provisioning and RDC restart persistence require live tests in their target environments. No build-only result substitutes for those gates.
