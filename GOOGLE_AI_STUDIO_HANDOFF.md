# Google AI Studio engineering handoff — 2026-10-14

## Ownership and requested outcome

The user asked to move all current hybrid-controller development to `onnxscibroccoli/Androidd-cloud-computing-compainion` and have Google AI Studio lead completion. ChatGPT is switching to a suggestion engine and review collaborator after the transfer. This repo is the canonical continuation point. The source Broccoli branch remains available for provenance; it has not been merged, deleted, or used to replace production.

The goal is an accessibility-first, non-root Android controller APK that coordinates local Shizuku/rish operations and OCI work over authenticated gRPC, connects existing Grasshopper/Kali workstations via RDC, accepts authorized tasks from model platforms, and produces persistently signed APKs through GitHub Actions.

## Read in this order

1. CURRENT_STATUS.md — actual evidence and unverified gates.
2. hybrid/README.md — architecture, command contract and operational setup.
3. docs/superpowers/specs/2026-10-14-hybrid-controller-design.md — design decisions.
4. hybrid/proto/control.proto and hybrid/openapi.json — wire contracts.
5. hybrid/tests and Android CommandsTest.java — present automated coverage.
6. docs/handoff/REVIEW_FINDINGS.md — fixed defects and remaining review work.

## What has been implemented

- Java Android app with accessible native controls, foreground notification, explicit Start/Stop, Shizuku authorization, shell UserService and rish-only content-provider hooks.
- Local initialization gate requiring UID 2000, live Binder and app permission; root fallback rejected.
- gRPC bidirectional device events, commands, results and acknowledgments over mTLS.
- SQLite job persistence, body-bound request keys, device-bound results, durable execution tombstones and UNKNOWN crash outcomes.
- HTTPS OAuth2 client-credentials endpoint issuing five-minute RS256 tokens bound to the actual TLS client certificate. Per-client target, operation and scope restrictions.
- Live registry reload/revocation and fail-closed malformed-registry handling.
- Profile-based asynchronous OCI SDK launch worker using instance principals and retry tokens.
- Profile-based Termux/Linux execution worker with executable hashes, timeout/output limits and cancellation handling.
- Owned OpenAI/Gemini/Grok API tool runners. They submit authorized jobs; hosted web chat products are not impersonated.
- Terraform for isolated private OCI compute/NSG, PKI generation, systemd installation scripts and real RDC device profiles.
- GitHub Actions tests, Android compile/unit/lint, zipalign, persistent release signing, independent apksigner verification, manifest and checksums.

All copied implementation files, including the changes that had not yet been pushed to Broccoli, are enumerated and hashed in docs/handoff/migration-manifest.json. No runtime secrets or live snapshots are in that manifest.

## Verified historical evidence

Source repository: `onnxscibroccoli/broccoli-core`.
Source branch: `feat/hybrid-controller-20261014`.
Last built source commit: `4a7e18009784e1f646b7c23cc18cb300beaab5d5`.
Successful signed build: https://github.com/onnxscibroccoli/broccoli-core/actions/runs/38506276313
Historical signed artifact: https://github.com/onnxscibroccoli/broccoli-core/actions/runs/38506276313/artifacts/11866929369

The historical build compiled, ran Android unit tests and lint, signed the APK and passed apksigner verification. Local hybrid tests passed 24 cases. The Broccoli-wide unittest suite passed 136 tests after correcting two existing `Dict` annotation import errors. That upstream correction is preserved as a patch under docs/handoff; this standalone repo does not duplicate Broccoli's full runtime or claim to contain its suite.

RDC reached the physical Galaxy A14 and proved `SM-A146U` plus shell UID `2000`. The canonical wrapper takes a single shell-command string, not an extra `-c`. The new CLI now uses that contract and offers `--raw-rish` for direct exported rish. Both `com.hamondev.shevery` and `moe.shizuku.privileged.api` packages were listed; this does not prove which backend served every request.

Grasshopper and Kali were reachable through RDC. Grasshopper reported Oracle Linux 9.8; Kali reported 2026.3. RDC command success does not establish reboot persistence. The physical phone had pre-existing uncommitted Broccoli files; none were overwritten.

## Changes after the historical APK build

These are included in this migration and must be rebuilt/tested as the new source revision:

- Explicit AIDL transaction IDs, including Shizuku's documented destroy transaction `16777114` in AIDL. This is necessary for UserService teardown.
- Guard `ready()` against Binder races instead of allowing an exception to escape UI status updates.
- Isolate Android journals by enrollment so old cloud results cannot cross identities.
- Complete operating README and OpenAPI contract.

The exact patch is docs/handoff/post-build-lifecycle-fixes.patch. Do not install the historical APK as evidence that these fixes work.

## Immediate next work, in order

1. **Confirm the destination build.** Inspect `.github/workflows/build.yml` on this repository, obtain the new signed artifact and verify its source SHA and signer. The existing signing identity has been configured in this repo's two required GitHub Secrets. Do not generate a replacement identity.
2. **Android device acceptance.** Install the newly verified APK using the existing authorized rish/RDC path. Test launch, explicit grant/denial, fresh init, local status, local result isolation, Stop, service restart and Binder loss. Reboot needs a separate authorized window because it interrupts the user's active phone.
3. **OCI end-to-end acceptance.** Stage the server on an isolated listener with private routing and actual DNS/certificate configuration. Enroll the phone securely. Prove device-to-OCI TLS handshake, certificate/device binding, status dispatch, result acknowledgment, offline/reconnect, late result, revocation and same-ID redelivery. Do not change existing 80/443 ingress or production cloud Android workloads for this test.
4. **Close crash/lifecycle coverage gaps.** Add direct tests for cancellation of an in-flight worker when its stream fails, local Android result exclusion, re-enrollment isolation, Stop destroying the UserService, and duplicate in-flight dispatch. Current tests do not cover all of these fixes.
5. **Validate infrastructure and identity.** Run Terraform init/validate/plan against the chosen configuration, without spending money solely to make a test pass. Check instance-principal policies and execute one approved OCI launch profile; reconcile the returned OCID and cleanup plan. HCL parsing or a resource ID alone is not guest readiness.
6. **RDC persistence.** Inspect the current process supervisor before handover. The install script intentionally refuses a competing process. Verify an actual RDC command after a controlled restart on each workstation, then persistence through an approved reboot.
7. **Model integrations.** With user-owned credentials, test selected tool-capable models from OpenAI, Gemini and xAI against the owned mTLS runner. Preserve Gemini thought signatures. Verify policy refusal and actual job outcomes, not model assertions.
8. **Production gaps.** Add the shared event-bus integration with the existing Governor/semantic accessibility/planner/knowledge graph. Current typed streams and queues do not yet prove that all modules communicate strictly through that event bus. Add measured latency, disk/retention policy, lifecycle observability and certificate renewal/rotation procedures. Review accessibility on the actual phone.

## Important compatibility boundary

The current API is strict OAuth2+mTLS. Hosted ChatGPT Actions documents OAuth but no client-certificate setting. The owned API runner can satisfy the gateway's client certificate requirement; that is a different trust boundary from native hosted-chat integration. Do not claim direct native OAuth+mTLS support across ChatGPT, Gemini web and Grok web without platform evidence. Do not weaken mTLS or trust a header containing a certificate fingerprint to make a demo appear connected.

Shevery supplies the privilege backend. There is no invented Shevery cloud SDK. OCI provisioning runs in the Python cloud worker, not in the privileged shell process. This design deliberately keeps cloud credentials out of Android shell execution.

## Signing, deployment and operational information

Expected signing certificate SHA-256:
`15eef597aaf8ae0b49a36c4305c76a8dd8ecacc714b71bcfd41cc9f6915fd74b`.

Secrets: `CONTROLLER_SIGNING_P12_B64`, `CONTROLLER_SIGNING_PASSWORD`. Alias: `controller`. Private backup on the authorized OCI workstation: `/home/grasshopper/.local/share/broccoli-controller-signing`. Do not print or copy it into source, chat, an artifact or a public Drive file. Secure archival of that signing identity still needs an explicit backup policy.

RDC identifiers are in `hybrid/deploy/rdc-profiles.json`; physical Android is `566d623e-df45-4b16-b2be-4cbd08567a49`. Treat reachability as time-sensitive and rediscover before use. No new server was deployed, no new OCI compute was provisioned, and no controller APK was installed during the work transferred here.

## How ChatGPT can help from here

Ask for a focused review or recommendation: diagnose a failing build log, compare a proposed architecture against the invariants, review a patch for replay/auth/lifecycle hazards, or design the next acceptance test. ChatGPT should return prioritized suggestions with evidence and trade-offs. It should not modify the repo or infrastructure unless the user explicitly requests implementation again.
