#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 2 ]; then echo "Usage: $0 ABSOLUTE_SOURCE_DIRECTORY ABSOLUTE_PRIVATE_SERVER_CONFIG" >&2; exit 2; fi
source_dir=$(realpath "$1")
config=$(realpath "$2")
test -f "$source_dir/server/main.py"
test -f "$config"
command -v python3.12 >/dev/null
python3.12 -m venv "$source_dir/.venv"
"$source_dir/.venv/bin/pip" install -r "$source_dir/requirements.txt"
PATH="$source_dir/.venv/bin:$PATH" bash "$source_dir/scripts/generate-proto.sh"
mkdir -p "$HOME/.config/systemd/user"
python3.12 - "$source_dir" "$config" <<'PY'
from pathlib import Path
import sys
root,config=sys.argv[1:]
# Reject systemd directive/control-character injection in deployment paths.
if any(any(c in p for c in '\n\r%"\\') or ' ' in p for p in (root,config)):
    raise SystemExit('Use deployment paths without whitespace or systemd metacharacters')
unit=f'''[Unit]
Description=Broccoli authenticated hybrid control plane
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
WorkingDirectory={root}
ExecStart={root}/.venv/bin/python -m server.main --config {config}
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
[Install]
WantedBy=default.target
'''
(Path.home()/'.config/systemd/user/broccoli-controller.service').write_text(unit)
PY
systemctl --user daemon-reload
systemctl --user enable --now broccoli-controller.service
systemctl --user is-active broccoli-controller.service
printf '%s\n' 'For logout/reboot persistence an administrator must enable linger for this user with loginctl enable-linger.'
