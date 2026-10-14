# Android Cloud Computing Companion

**Development lead: Google AI Studio. ChatGPT role: suggestions and review support.**

This is the canonical repository for the hybrid Android controller implementation transferred from `onnxscibroccoli/broccoli-core`. It includes working source, transport/security tests, Android build/signing workflow, OCI deployment manifests, Termux hooks, and the engineering handoff. It is **not yet production-approved**.

Start with [GOOGLE_AI_STUDIO_HANDOFF.md](GOOGLE_AI_STUDIO_HANDOFF.md), then [CURRENT_STATUS.md](CURRENT_STATUS.md). The original AI Studio starter README is preserved in [docs/handoff](docs/handoff/AI_STUDIO_STARTER_README.md).

| Area | Location |
|---|---|
| Complete implementation and operating guide | [hybrid/README.md](hybrid/README.md) |
| Java Android controller and Shizuku UserService | [hybrid/android](hybrid/android) |
| gRPC protocol | [hybrid/proto/control.proto](hybrid/proto/control.proto) |
| Python control plane, OAuth/mTLS, OCI and model adapters | [hybrid/server](hybrid/server) |
| REST contract | [hybrid/openapi.json](hybrid/openapi.json) |
| Automated tests | [hybrid/tests](hybrid/tests) |
| Termux, PKI and installation scripts | [hybrid/scripts](hybrid/scripts) |
| OCI infrastructure and RDC profiles | [hybrid/deploy](hybrid/deploy) |
| Signed APK workflow | [.github/workflows/build.yml](.github/workflows/build.yml) |
| Design and implementation plan | [docs/superpowers](docs/superpowers) |
| Source provenance, review findings, historical evidence | [docs/handoff](docs/handoff) |

## Run verification

```bash
python3 -m venv .venv
.venv/bin/pip install -r hybrid/requirements-test.txt
PATH="$PWD/.venv/bin:$PATH" bash hybrid/scripts/generate-proto.sh
PYTHONPATH=hybrid .venv/bin/python -m pytest hybrid/tests -q
for script in hybrid/scripts/*.sh; do bash -n "$script"; done
```

The Android workflow compiles release code, executes unit tests and lint, signs `controller.apk`, and verifies its signature. Secrets use the existing persistent controller identity; no private keys are committed. See [current status](CURRENT_STATUS.md) for build evidence and the distinction between a signed artifact and live acceptance.

## Architectural boundaries

- Physical Android: non-root Shizuku/Shevery plus the verified Broccoli rish wrapper; explicit initialization after boot/service restart.
- Persistent cloud: **OCI**, with RDC for workstation access. This transfer does not authorize moving the Android worker to AWS.
- Device control: outbound mTLS gRPC, durable command IDs, bounded execution and no automatic replay of uncertain effects.
- API ingress: certificate-bound OAuth2 tokens plus mTLS, with explicit scopes, targets and operations.
- AI integration: owned API runners for OpenAI, Gemini and Grok; do not claim native hosted-chat mTLS compatibility.
- Accessibility and human stop take priority over automation.

Google AI Studio should implement the next bounded task from the handoff, attach evidence to its exact commit, and update status. ChatGPT should advise and review when asked; it should not resume autonomous implementation unless the user explicitly changes that role.
