# Grasshopper OCI CLI session reauthentication

Last verified: 2026-10-14 (US Ashburn region, `us-ashburn-1`)

This procedure reauthenticates the OCI CLI on Grasshopper using OCI's browser-backed CLI session flow. It is also the reference procedure for Kali, provided the CLI and an interactive browser are installed there. The session is local to the workstation and is not copied between hosts.

## Security and operational rules

- Never commit `~/.oci/config`, session token files, private keys, browser callback URLs containing one-time material, or authentication logs.
- Do not copy a session token from Grasshopper to Kali. Authenticate each workstation independently.
- Run commands as the desktop user that owns the OCI configuration. Do not run the interactive authentication command under `sudo`; root has a different home and cannot reliably use the desktop user's browser/session.
- The session token expires. Treat an expired session as a normal reauthentication event.
- This procedure uses a browser sign-in, not an OAuth client registration for the Android app. Native Android OAuth/OIDC registration is a separate configuration task.

## Grasshopper environment

Verified CLI path: `/home/grasshopper/.local/bin/oci`

Verified profile used for the session: `OMNIKALI_SESSION`

Verified region: `us-ashburn-1`

Expected OCI configuration location: `/home/grasshopper/.oci/config`

## Reauthenticate interactively

Run as the `grasshopper` desktop user in a terminal attached to the active graphical session:

```bash
export HOME=/home/grasshopper
export DISPLAY=:1
export XAUTHORITY=/home/grasshopper/.Xauthority
/home/grasshopper/.local/bin/oci session authenticate \
  --region us-ashburn-1 \
  --profile-name OMNIKALI_SESSION \
  --config-location /home/grasshopper/.oci/config
```

The CLI starts a local callback listener and prints/opens an Oracle sign-in URL. Keep the command running. If the URL does not open automatically, open the exact URL printed by the running CLI in the existing Firefox desktop session. Do not reconstruct, edit, publish, or reuse the one-time URL. Complete the Oracle login and any MFA/account-selection prompts in Firefox. Wait for the CLI to report completion before closing the browser or terminal.

If launching Firefox manually from a remote command, preserve the desktop user's existing session environment. Do not launch a clean profile or use `sudo firefox`, since that can lose the browser's existing login and display access.

## Verify authentication before doing cloud operations

Run as the same desktop user:

```bash
/home/grasshopper/.local/bin/oci session validate \
  --local --auth security_token --profile OMNIKALI_SESSION

/home/grasshopper/.local/bin/oci iam region list \
  --profile OMNIKALI_SESSION --auth security_token --output json
```

The first command must report that the session is valid. The second makes a signed OCI API request and must return region data. A successful region-list call verifies authenticated API access, but does not by itself prove authorization to create or modify compute resources. Verify each required permission with a least-privilege read-only API call before provisioning.

On 2026-10-14, Grasshopper verification succeeded: the session was valid until 2026-10-14 17:43:01 UTC, and `oci iam region list` returned region data. Do not treat this timestamp as current after expiration.

## Applying the same procedure to Kali

Authenticate independently on Kali. Do not copy Grasshopper's `~/.oci/sessions` or security token.

1. Ensure OCI CLI is installed for the Kali desktop user and `oci --version` works.
2. Ensure the target user has a private `~/.oci/config` with the intended profile and region. Set up the config with OCI CLI configuration tooling if it does not already exist; never put credentials in the repository.
3. Run `oci session authenticate --region us-ashburn-1 --profile-name OMNIKALI_SESSION --config-location ~/.oci/config` from that user's interactive desktop session.
4. Complete the sign-in in the existing Firefox session, then run `oci session validate --local --auth security_token --profile OMNIKALI_SESSION` and `oci iam region list --profile OMNIKALI_SESSION --auth security_token --output json`.
5. Keep the session files private to the user (typically directory mode 700 and files mode 600). Do not stage them in Git.

If the Kali CLI uses a different version or its `session authenticate` options differ, inspect `oci session authenticate --help` on that host and use the options supported by the installed version.

## Non-interactive automation and recovery

A script may launch the CLI and wait for the user to finish the browser step, but it must not attempt to bypass MFA, scrape browser credentials, or log session tokens. The reliable handoff is: start the CLI in a persistent terminal/process, leave Firefox visible on the generated Oracle sign-in page, let the user finish authentication, then run the two verification commands above.

For diagnostics, collect only redacted metadata: CLI version, profile name, region, session validity, command exit code, and sanitized error messages. Never attach `~/.oci/config`, token files, full browser authorization URLs, or raw authentication logs to issues or CI artifacts.

## Important distinction: OCI CLI authentication vs Android OAuth

This workstation session authenticates the CLI to OCI. It does not establish the issuer URL, client ID, PKCE redirect URI, or API base URL for the Android application. Those must be discovered from the actual OCI Identity Domain and the deployed controller service, then registered/configured separately. Do not use the OCI Console's internal `iaas_console` client or a CLI session token as the Android application's OAuth client.

## Verification evidence

- Workstation: `grasshopper-workstation`
- Profile: `OMNIKALI_SESSION`
- Region: `us-ashburn-1`
- Session validation: PASS at time of verification on 2026-10-14
- Signed read API (`oci iam region list`): PASS at time of verification on 2026-10-14
- Compute provisioning permissions: NOT VERIFIED by the region-list call
