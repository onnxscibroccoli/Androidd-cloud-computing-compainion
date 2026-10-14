# Hybrid Controller Implementation Plan

Goal: implement the Android/OCI execution contract with verifiable signed delivery.
Architecture: Android foreground controller and shell-only bridge; outbound mTLS gRPC; durable cloud job store; OAuth certificate-bound REST gateway; OCI instance-principal worker and official RDC CLI supervision.
Tech stack: Java 17/Android, protobuf/gRPC, Python asyncio/aiohttp, SQLite, OCI SDK, GitHub Actions.
Spec: ../specs/2026-10-14-hybrid-controller-design.md

## Global constraints
Non-root UID 2000 only. No hidden Android APIs. No existing production listener changes. No arbitrary shell dispatch. No exactly-once claim. All mutable job calls require an idempotency key. Signing keys never enter source or logs.

## Review focus
- Certificate/token mismatch: deny even with valid individual credentials.
- Crash between side effect and result: UNKNOWN, no automatic replay.
- Reboot/Binder death: initialization invalid until fresh rish readiness proof.
- Duplicate requests with changed body: conflict, no new job.
- Offline/slow consumer: bounded memory and deadline expiration.

## Tasks
- [ ] Store/auth tests: immutable request IDs, terminal transitions, authorization and certificate binding. Implement server/store.py and server/auth.py; pytest hybrid/tests.
- [ ] Protocol/gateway: proto/control.proto, server/main.py, server/oci_worker.py; run actual TLS client/server tests for missing/wrong certificates and stream dispatch/result acknowledgment.
- [ ] Android: app Java classes, shell-only provider, foreground lifecycle, Shizuku service, stream, local journal. Test pure command validation; assemble and lint release.
- [ ] Operations: profile-based OCI Terraform, installation scripts, Termux hooks, RDC user service and health command; syntax-check scripts/manifests.
- [ ] Delivery: build.yml with release signing, checksums and verification; run GitHub Actions and inspect artifact. Record unexecuted live acceptance explicitly.

## Handoff disposition

This is the original implementation plan. Its unchecked items are not the current tracker. The implementation and evidence have moved to the standalone Androidd-cloud-computing-compainion repository; consult CURRENT_STATUS.md and GOOGLE_AI_STUDIO_HANDOFF.md for completed work and outstanding acceptance gates. Google AI Studio is the development lead.
