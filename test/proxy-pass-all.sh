#!/usr/bin/env bash
# Run all transparent-proxy tests that should succeed (require manual approval)
# Each script is independent — failure of one does not stop the others
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PASS=0
FAIL=0

run() {
    local name="$1"
    local script="$2"
    echo ""
    echo "━━━ ${name} ━━━"
    if bash "${script}"; then
        echo "✓ ${name}"
        ((++PASS))
    else
        echo "✗ ${name}"
        ((++FAIL))
    fi
}

run "Golden-path proxy (with manual approval)" "${SCRIPT_DIR}/proxy-pass.sh"
run "Tag push via proxy"                       "${SCRIPT_DIR}/proxy-pass-tag.sh"

echo ""
echo "━━━ Results: ${PASS} passed, ${FAIL} failed ━━━"
[ "${FAIL}" -eq 0 ]
