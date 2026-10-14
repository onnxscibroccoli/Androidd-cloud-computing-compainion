#!/usr/bin/env bash
set -euo pipefail
# Run as the existing RDC user to preserve the official CLI's per-user auth.
command -v node >/dev/null
command -v npm >/dev/null
if pgrep -u "$(id -u)" -f 'desktop-commander.*remote' >/dev/null; then
  echo 'Existing RDC remote process detected. Preserve it; arrange a controlled handover before installing a second connector.' >&2
  exit 3
fi
root="$HOME/.local/share/broccoli-rdc"
mkdir -p "$root" "$HOME/.config/systemd/user"
npm install --prefix "$root" --save-exact @wonderwhy-er/desktop-commander@0.2.52
node_path=$(command -v node)
python3 - "$root" "$node_path" <<'PY'
from pathlib import Path
import sys
root,node=sys.argv[1:]
if any(any(c in p for c in '\n\r%"\\ ') for p in (root,node)):
    raise SystemExit('Unsupported systemd path characters')
unit=f'''[Unit]
Description=Broccoli Remote Desktop Commander
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
ExecStart={node} {root}/node_modules/@wonderwhy-er/desktop-commander/dist/cli.js remote
Restart=on-failure
RestartSec=10
UMask=0077
[Install]
WantedBy=default.target
'''
# Resolve the actual package bin path rather than assuming an implementation entry point.
import json
package=json.loads((Path(root)/'node_modules/@wonderwhy-er/desktop-commander/package.json').read_text())
entry=package['bin']['desktop-commander'] if isinstance(package['bin'],dict) else package['bin']
unit=unit.replace(f'{root}/node_modules/@wonderwhy-er/desktop-commander/dist/cli.js',str(Path(root)/'node_modules/@wonderwhy-er/desktop-commander'/entry))
(Path.home()/'.config/systemd/user/broccoli-rdc.service').write_text(unit)
PY
printf '%s\n' 'Authenticate this user first using the official command below if not already enrolled:'
printf '%s\n' "\"$root/node_modules/.bin/desktop-commander\" remote"
# The foreground CLI handles account login. It must complete before unattended service start.
if [ "${RDC_AUTHENTICATED:-0}" != 1 ]; then exit 4; fi
systemctl --user daemon-reload
systemctl --user enable --now broccoli-rdc.service
systemctl --user is-active broccoli-rdc.service
