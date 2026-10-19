#!/usr/bin/env bash
# Full listen inventory (may need sudo for other users' sockets; run as-is first).
set -euo pipefail
lsof -nP -iTCP -sTCP:LISTEN
