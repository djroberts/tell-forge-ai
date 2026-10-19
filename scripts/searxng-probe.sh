#!/usr/bin/env bash
# Probe the configured SearXNG instance the same way the plugin does:
# prints HTTP status, response content-type and the first bytes of the body.
set -euo pipefail

BASE="${1:-http://127.0.0.1:3000}"
URL="${BASE%/}/search?q=tellforgeai+test&format=json"

echo "Probing: $URL"
curl -sS -m 10 -D - "$URL" -o /tmp/searxng-probe-body.txt || { echo "curl failed to connect"; exit 1; }
echo "--- body (first 500 bytes) ---"
head -c 500 /tmp/searxng-probe-body.txt
echo
