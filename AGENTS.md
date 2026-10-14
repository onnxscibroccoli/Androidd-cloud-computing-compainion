# Engineering collaboration contract

The user designated Google AI Studio as development lead on 2026-10-14. ChatGPT has moved to a suggestion/review role after this repository handoff. Do not interpret the earlier autonomous-build request as ongoing authorization for ChatGPT to lead new implementation. The user may explicitly change roles later.

Read GOOGLE_AI_STUDIO_HANDOFF.md, CURRENT_STATUS.md, and hybrid/README.md before changes.

1. Preserve existing user changes and verify the current branch head before writing.
2. Build one bounded, testable module at a time. Keep source and status synchronized.
3. OCI is the persistent Android cloud environment. Preserve existing production services and chat sessions. Do not reconfigure shared ingress or replace running workloads as a side effect of a test.
4. Physical Android is non-root. Use documented Android and Shizuku APIs. Reuse the canonical Broccoli rish wrapper; do not patch it to accommodate a broken caller.
5. Require fresh shell readiness after boot/Binder loss. Observations must never replay commands.
6. Treat interrupted side effects as UNKNOWN and reconcile before issuing a fresh job. Do not claim exactly-once semantics.
7. Keep client identity, OAuth token binding, scopes, target policy and command validation fail-closed.
8. Never commit private keys, enrollment bundles, credentials, live databases, device snapshots or production configuration. The signing identity exists in GitHub Secrets and private OCI storage.
9. A green build is not live acceptance. Record source commit, artifact SHA-256, signer, device, transport, timestamp and actual output for acceptance tests.
10. Tests and reported outcomes must be real. Do not suppress a failing gate or change a test merely to obtain green status.
11. README and CURRENT_STATUS.md must reflect what is implemented, verified, blocked and proposed. Recommendations are not completed work.
12. No automatic merge or production release until the acceptance matrix is evidenced. Do not reboot the user's phone or interrupt production without authorization for that disruptive test.
