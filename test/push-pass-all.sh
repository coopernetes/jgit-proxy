#!/usr/bin/env bash
# Run all store-and-forward (push) tests that should succeed
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

run "Golden-path push"              "${SCRIPT_DIR}/push-pass.sh"
run "Tag push (lightweight + annotated)" "${SCRIPT_DIR}/push-pass-tag.sh"
run "Secret scanning false-positive check" "${SCRIPT_DIR}/push-pass-secrets.sh"

echo ""
echo "━━━ Results: ${PASS} passed, ${FAIL} failed ━━━"
[ "${FAIL}" -eq 0 ]
