# Current status — handoff on 2026-10-14

**Owner:** Google AI Studio leads development. ChatGPT provides suggestions/review.
**Readiness:** implementation transferred; production acceptance incomplete.

| Area | Evidence | State |
|---|---|---|
| Implementation | Android, protocol, server, OAuth, OCI worker, model runners, deployment scripts and tests included | Implemented; requires acceptance |
| Historical hybrid tests | 24 passing local tests; source `4a7e180` | Verified on historical source |
| Broccoli baseline tests | 136 passing tests after two annotation fixes | Historical upstream evidence, not this repo's suite |
| Historical Android release | CI compile, unit tests, lint, signing and apksigner verification succeeded in [run 38506276313](https://github.com/onnxscibroccoli/broccoli-core/actions/runs/38506276313) | Verified on `4a7e180` |
| New repository build | Workflow and persistent signing secrets transferred | Must inspect destination run for exact migrated SHA |
| Post-build lifecycle fixes | AIDL destroy ID, Binder race guard, enrollment-isolated journals | Included; historical APK does not contain them |
| Physical Android prerequisites | RDC command verified `SM-A146U`, UID 2000 and canonical wrapper | Verified earlier in this session; rediscover before use |
| Controller APK installed on phone | No installation performed | Not verified |
| Android-to-OCI production transport | No deployment/enrollment performed | Not verified |
| OCI resource provisioning | Implementation exists; no live launch attempted | Not verified |
| RDC connectors | Both workstation commands succeeded | Restart/reboot persistence not verified |
| Live three-model invocation | API adapters exist; no model credentials used | Not verified |
| Direct hosted-chat mTLS | Platform client-certificate capability not established | Compatibility blocker |
| Shared Broccoli event bus/Governor integration | Typed network events and queues exist | Full integration still needed |
| Production latency, rotation, retention | No live evidence | Not verified |

The historical signed artifact is [available on its source run](https://github.com/onnxscibroccoli/broccoli-core/actions/runs/38506276313/artifacts/11866929369), with an expiration reported as 2026-11-13. It must not be confused with the APK for the migrated lifecycle fixes.

Use the ordered next-work list in GOOGLE_AI_STUDIO_HANDOFF.md. Update each status only with evidence bound to code, artifact, device and transport. A source migration, green CI run, or successful shell identity probe is not a completed production rollout.
