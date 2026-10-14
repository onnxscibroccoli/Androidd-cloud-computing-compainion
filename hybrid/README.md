# Broccoli hybrid Android controller

This directory contains the Android controller, OCI control plane, model API adapter, workstation/Termux executor, transport tests, and signed APK workflow. It is an implementation under acceptance testing, **not a production-approved deployment**. A signed build does not prove device behavior, provisioning privileges, latency, or reboot persistence.

## Architecture and trust boundaries

| Component | Implementation | Responsibility |
|---|---|---|
| Android UI/session | `android/app/.../MainActivity.java`, `ControllerService.java` | Accessible native controls, explicit Start/Stop, foreground notification, Shizuku authorization, outgoing TLS stream |
| Shell execution | `ShellService.java`, `IShell.aidl`, `Commands.java` | Shizuku/Shevery UserService under UID 2000; typed argv, bounded runtime/output; no root fallback |
| Local CLI | `ShellProvider.java`, `scripts/broccoli-controller` | Shell-only content-provider calls through the existing canonical rish wrapper |
| Device journal | `Journal.java` | Commit before dispatch, durable outcomes, no replay of uncertain effects, result acknowledgment |
| Protocol | `proto/control.proto` | gRPC bidirectional device events, commands, results and acknowledgments |
| Control plane | `server/main.py`, `store.py`, `registry.py` | Direct TLS termination, certificate/device binding, durable job admission and dispatch, revocation |
| OAuth | `server/auth.py` | RFC 8705 certificate-bound 5-minute RS256 client-credentials tokens, scopes, target/operation policy |
| Cloud resource worker | `server/oci_worker.py` | Asynchronous OCI instance-principal launch using named administrator-owned profiles and `opc_retry_token` |
| Termux/Linux worker | `server/worker.py` | Approved executable profiles, SHA-256 gate, subprocess isolation, output/time bounds |
| Model runner | `server/model_runner.py`, `client.py` | OpenAI, Gemini and Grok API tool calls through an owned mTLS client |
| Workstation control | `deploy/rdc-profiles.json`, `scripts/install-rdc.sh` | Official RDC remote CLI, existing account authentication, systemd restart supervision |
| Delivery | `../.github/workflows/build.yml` | Tests, Android lint/build, release signing, independent apksigner verification and checksums |

### Execution lifecycle

1. After boot, start Shizuku/Shevery with the supported wireless-debugging or ADB flow. Non-root activation can require user interaction; the controller cannot create shell privileges from nothing.
2. Open the controller and authorize Shizuku. Start the foreground session.
3. Run `broccoli-controller init` from Termux using the canonical rish wrapper. The bridge verifies Binder caller UID 2000, live Shizuku permission, and UserService UID 2000.
4. The app advertises `READY`. Binder death, app service restart, or reboot clears the in-memory readiness state. Remote commands remain blocked until initialization is repeated.
5. OCI authenticates the client certificate and binds it to one registered device. Jobs carry IDs and absolute deadlines. The executor journals an ID before side effects.
6. Results are resent until acknowledged. Reboot during execution reports `UNKNOWN`; no automatic replay. Local CLI results have a separate local ID namespace and never enter the cloud outbox. Re-enrollment uses a separate journal to prevent identity crossover.
7. Stop removes the foreground session, disconnects the stream, discards queued in-memory work, and removes the Shizuku UserService. A subsequent start requires a new rish init.

Shevery is the privilege backend, not an OCI SDK. The cloud worker and user-space executors implement asynchronous provisioning and tasks. Modules exchange typed protocol events and bounded queues; an observation never becomes a command automatically.

### Supported commands

`status` reports the Android model; `snapshot` returns hierarchy XML; `tap` takes integer x/y coordinates; `key` accepts navigation keys; `launch` accepts an explicit package/activity component. `run` selects a local worker profile. `provision` selects an OCI profile. Arbitrary shell strings, environment injection and unregistered profiles are rejected.

The controller is an automation control plane, not a video-streaming app. Existing Grasshopper/noVNC/Redroid display services are preserved. Screenshot/video streaming is not represented as completed by this implementation.

## Authentication and platform compatibility

All inbound control-plane HTTP requests require TLS client authentication, including `/oauth/token`. Obtain a token using OAuth2 `client_credentials`, the registered client ID and allowed scopes. The token's `cnf.x5t#S256` must equal the actual TLS peer certificate digest. Forwarded headers cannot supply identity. No bearer-only fallback is supported.

The provided adapter supports API models from OpenAI, Gemini and xAI. It runs in infrastructure you control, holds a client certificate, and calls this gateway with OAuth+mTLS. Vendor API calls use each vendor's documented authentication. This **does not establish native mTLS support for hosted ChatGPT Actions, Gemini web, or Grok web**. ChatGPT Actions documents OAuth but does not expose a client-certificate setting. Requiring native client-certificate authentication from all three hosted products is a compatibility blocker, not something that can be repaired by inventing credentials or proxy headers.

The default PKI policy authorizes only `status` on one device. Broaden `targets` and `operations` deliberately for each certificate. Registry file replacements are read during HTTP authentication and active device dispatch. Invalid registry JSON denies access. Replace the registry atomically. Existing TLS connections are periodically renewed; deleting a device fingerprint terminates its stream. Certificate rotation requires a new certificate, updated registry, and re-enrollment. Retain the old public fingerprint for audit, revoke its registry entry, and keep the CA signing key offline.

## Local verification

From the repository root:

```bash
python3 -m venv hybrid/.venv
hybrid/.venv/bin/pip install -r hybrid/requirements-test.txt
PATH="$PWD/hybrid/.venv/bin:$PATH" bash hybrid/scripts/generate-proto.sh
PYTHONPATH=hybrid hybrid/.venv/bin/python -m pytest hybrid/tests -q
for script in hybrid/scripts/*.sh; do bash -n "$script"; done
```

The transport tests create temporary certificate authorities and real HTTPS/gRPC sockets. They exercise certificate rejection, token binding, target authorization, typed dispatch and result acknowledgment. The worker tests execute real bounded subprocesses. No test certificate is used as production trust.

## OCI deployment

`deploy/main.tf` creates a separate private compute instance and network security group. It takes existing compartment, availability domain, VCN, subnet, image and SSH-key inputs. It does not modify existing workloads, ports 80/443, or production ingress. Ingress CIDRs default to empty. Use an existing authenticated VPN/private route or reviewed ingress path for Android access. An NSG cannot compensate for an overly permissive subnet security list: review both.

```bash
terraform -chdir=hybrid/deploy init
terraform -chdir=hybrid/deploy validate
terraform -chdir=hybrid/deploy plan -out=controller.tfplan
terraform -chdir=hybrid/deploy apply controller.tfplan
```

Supply required values through a private `terraform.tfvars` or `TF_VAR_*` variables; never commit account material. The Oracle provider uses its supported local configuration or workload authentication. The runtime OCI worker uses instance principals. Provisioning requires a dynamic group containing only the controller instance and policies scoped to the dedicated worker compartment. Grant only the instance-launch and associated image/volume/VNIC/subnet permissions needed by that profile. Do not grant tenancy-wide administration.

On the OCI instance, install Python 3.12 and copy this exact source revision. Generate enrollment in a private directory, using the actual DNS hostname clients will reach:

```bash
python3.12 -m venv hybrid/.venv
hybrid/.venv/bin/pip install -r hybrid/requirements.txt
read -r -p 'Controller DNS hostname: ' CONTROLLER_HOST
hybrid/.venv/bin/python hybrid/scripts/provision-pki.py \
  --host "$CONTROLLER_HOST" --device phone --client model-runner \
  --out "$HOME/.config/broccoli-controller-private"
bash hybrid/scripts/install-server.sh \
  "$PWD/hybrid" "$HOME/.config/broccoli-controller-private/server.json"
```

Generation refuses an existing output directory. The configuration defaults to loopback-only listeners. Change `https_host` and `grpc_listen` to the reviewed private interface only after confirming routing/firewall policy. Server ports are 8443 and 8444. Move CA/client/device private keys away from the running server after enrollment; the server needs only its TLS key/certificate, CA public certificate, JWT key/public key, registry and database. The client needs its own key/certificate, CA and `client.json`. Transfer Android enrollment securely and delete the transport copy after import. APK enrollment imports its private key into AndroidKeyStore and disables Android backup.

For user systemd persistence, an administrator runs `loginctl enable-linger` for the service account. Test a controlled service restart, HTTPS health, device reconnect and a read-only job before considering the deployment persistent. Back up SQLite using its backup API, not an inconsistent copy of a live WAL database.

### OCI job profiles

Set `oci_profiles` in `server.json` to a private JSON file. Each key is an approved profile name; its value contains `compartment_id`, `availability_domain`, `shape`, `ocpus`, `memory_in_gbs`, `image_id`, and `subnet_id`, with optional administrator-owned `metadata`. No request can override these fields. Grant a specific gateway client target `oci` and operation `provision` to enable it.

A successful launch job returns the actual instance OCID and current lifecycle state. It means OCI accepted the launch; it does not mean the guest OS or Android workload passed readiness. A network error returns `UNKNOWN`; reconcile the `broccoli_job_id` resource tag before a new launch request. This avoids creating duplicate paid instances after an ambiguous API response.

## Termux and workstation execution

Install the controller APK, import its private enrollment JSON with the app's document picker, authorize Shizuku/Shevery, and press Start. Install just the new CLI without altering the canonical wrapper:

```bash
mkdir -p "$HOME/.local/bin"
install -m 700 hybrid/scripts/broccoli-controller "$HOME/.local/bin/broccoli-controller"
"$HOME/.local/bin/broccoli-controller" init
"$HOME/.local/bin/broccoli-controller" status
"$HOME/.local/bin/broccoli-controller" submit status
```

Use the returned UUID with `broccoli-controller result UUID`. The default wrapper is `$HOME/broccoli-core/bin/broccoli-rish`, which takes one command string. For direct exported rish, pass `--raw-rish --rish "$PREFIX/bin/rish"`. The script verifies UID 2000 before any content-provider call and returns a failure exit status for blocked initialization.

To run development tests under Termux, run `python -m server.worker --config` with a private worker JSON file containing `endpoint`, `device`, `database`, `ca`, `key`, `cert` and `profiles`. Each profile contains an absolute `argv` array, SHA-256 of `argv[0]`, absolute `cwd`, explicit `path` and `timeout` (maximum 60 seconds). Register that worker certificate independently. `run` invokes the named profile and returns exit status/output. The process runs as the Termux/Linux user, never as root. Profile files and all referenced scripts must remain administrator-owned and non-writable by untrusted code.

### RDC workstation connectors

`deploy/rdc-profiles.json` records the two verified device IDs and actual OS versions. `install-rdc.sh` installs the observed official version 0.2.52 and resolves its actual package entry point. It preserves per-user account authentication and refuses to install a competing remote process. During a controlled handover, authenticate the official foreground CLI as the existing user; then set `RDC_AUTHENTICATED=1` when installing the service. This flag is operator attestation, not authentication bypass. The official CLI still validates its session.

Verify persistence with `systemctl --user is-active broccoli-rdc`, `journalctl --user -u broccoli-rdc`, and an actual RDC ping/command after a controlled restart. Process existence alone is not a successful remote-connection test. The implementation does not contain invented RDC credentials or undocumented REST endpoints.

## API and multi-model invocation

The secure HTTP API is specified in `openapi.json`. `POST /v1/jobs` takes target, operation, arguments and absolute epoch deadline; `Idempotency-Key` is mandatory and bound to the complete body. `GET /v1/jobs/{id}` returns only a job owned by the authenticated client. `GET /health` requires `jobs:read`. Maximum body size is 16 KiB; admission is limited to 10,000 nonterminal jobs and 120 requests per client certificate per minute.

```bash
python -m server.client --config "$HOME/.config/broccoli-controller-private/client.json" \
  --target phone --operation status
python -m server.model_runner --config "$HOME/.config/broccoli-controller-private/client.json" \
  --provider openai --model "$OPENAI_MODEL" --target phone --allow status \
  'Check the Android model using the status tool.'
```

Set `OPENAI_API_KEY`, `GEMINI_API_KEY`, or `XAI_API_KEY` only in the runner's private environment. Supply an available tool-capable model ID with `--model`; provider/model availability is not hardcoded. Select `--provider gemini` or `grok` for the other adapters. Defaults allow only status. A model cannot expand runner policy, target selection, or gateway authorization. The runner executes at most eight requested jobs per invocation and returns their durable IDs plus the model's response. Fetch results using the client; do not treat model prose as proof of execution.

## Signed artifact delivery

The workflow in this repository compiles and unit-tests release code, runs Android lint, aligns the APK, signs using a persistent PKCS#12 release identity, independently verifies it with `apksigner`, and emits:

- `controller.apk`
- `SHA256SUMS`
- `signature-verification.txt`
- `manifest.json` containing exact source commit and workflow run ID

Secrets are `CONTROLLER_SIGNING_P12_B64` and `CONTROLLER_SIGNING_PASSWORD`; the PKCS#12 alias is `controller`. Pull-request jobs never receive a signed artifact. The release identity is stored privately on the authorized OCI workstation and in GitHub Secrets, never generated ephemerally in CI. Back up that identity securely before relying on it for long-term app upgrades.

Artifacts are named `controller-signed-<full source SHA>` on the corresponding GitHub Actions run. A release attachment is an additional publication step after verification; no download URL is represented as existing before that step succeeds.

```bash
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs controller.apk
```

Expected signer SHA-256 for the identity established in this session:
`15eef597aaf8ae0b49a36c4305c76a8dd8ecacc714b71bcfd41cc9f6915fd74b`.

## Acceptance gates and operational limits

Keep deployment unapproved until these are evidenced for the exact artifact and transport:

- Installation and launch on the physical Galaxy A14; accessibility labels and stop controls usable.
- Shizuku denial, grant, revocation, Binder death, reboot and fresh rish init.
- End-to-end Android-to-OCI mTLS status job; offline/reconnect and late result delivery.
- Cancel/stop during an active command; no replay on restart.
- Authorized and unauthorized real model API invocations for each selected provider/model.
- OCI launch profile under least-privilege instance principals, result OCID reconciliation and cleanup.
- RDC reconnection after restart on both workstations and after reboot where permitted.
- Measured p50/p95 command/telemetry latency over the actual network; no latency claim is inferred from design.

The server uses a single process/SQLite database. High availability, multi-region failover, long-term log retention and automatic PKI renewal are not implemented or claimed. Terminal job tombstones must outlive any possible redelivery; deleting them without a retention contract can re-enable side effects. Snapshot XML can contain sensitive on-screen text; authorize that operation only where its storage and downstream disclosure are appropriate.
