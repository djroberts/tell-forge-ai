#!/usr/bin/env bash
# Find listening ports for common SearXNG containers / local instances.
set -euo pipefail

echo "--- listening ports ---"
lsof -nP -iTCP -sTCP:LISTEN | grep -Ei 'searx|:3000|:8080|:8888' || echo "(no match for searx/3000/8080/8888 via lsof - needs sudo for other users' processes)"

echo "--- docker ---"
docker ps 2>/dev/null | grep -i searx || echo "(no docker searx found or docker not running)"
