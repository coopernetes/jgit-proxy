#!/usr/bin/env bash
# Run all store-and-forward (push) tests that should fail / be rejected
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

run "Author email validation failures"  "${SCRIPT_DIR}/push-fail-author.sh"
run "Commit message validation failures" "${SCRIPT_DIR}/push-fail-message.sh"
run "Diff content scanning failures"     "${SCRIPT_DIR}/push-fail-diff.sh"
run "Secret scanning failures"           "${SCRIPT_DIR}/push-fail-secrets.sh"

echo ""
echo "━━━ Results: ${PASS} passed, ${FAIL} failed ━━━"
[ "${FAIL}" -eq 0 ]
