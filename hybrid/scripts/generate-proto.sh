#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python -m grpc_tools.protoc -I proto --python_out=server --grpc_python_out=server proto/control.proto
python - <<'PY'
from pathlib import Path
p=Path('server/control_pb2_grpc.py')
p.write_text(p.read_text().replace('import control_pb2 as control__pb2','from . import control_pb2 as control__pb2'))
PY
