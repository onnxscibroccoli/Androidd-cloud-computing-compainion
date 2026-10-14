# Current status — handoff on 2026-10-14

**Owner:** Google AI Studio leads development. ChatGPT provides suggestions/review.
**Readiness:** implementation transferred; production acceptance incomplete.

| Area | Evidence | State |
|---|---|---|
| Implementation | Android, protocol, server, OAuth, OCI worker, model runners, deployment scripts and tests included | Implemented; requires acceptance |
| Historical hybrid tests | 24 passing local tests; source `4a7e180` | Verified on historical source |
| Broccoli baseline tests | 136 passing tests after two annotation fixes | Historical upstream evidence, not this repo's suite |
| Historical Android release | CI compile, unit tests, lint, signing and apksigner verification succeeded in [run 38506276313](https://github.com/onnxscibroccoli/broccoli-core/actions/runs/38506276313) | Verified on `4a7e180` |
| Destination verification | [Run 38572425771](https://github.com/onnxscibroccoli/Androidd-cloud-computing-compainion/actions/runs/38572425771), source `cbb4b6ab71ac2919c3426b33523432726259a674` | Python tests and shell syntax job passed; Android compile/lint/signing job in progress at handoff |
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

## Transfer verification

- Published 61 source/document/workflow files on the destination main branch; preserved the initial AI Studio README under docs/handoff.
- Migrated the existing controller signing identity into this repository's GitHub Secrets without exposing key material.
- Retrieved CURRENT_STATUS.md back from GitHub at the migration commit and confirmed its contents.
- Source snapshot for migration commit `cbb4b6a` is backed up to [Google Drive](https://drive.google.com/file/d/1IKY8k0JBj00hp1kuqiK-TArp9ZHZvmXR/view). This archive predates this final status-only update.
- Handoff is complete. Google AI Studio now leads implementation; ChatGPT provides suggestions and review unless the user explicitly requests a new implementation task.
