#!/usr/bin/env bash
# Run all three identity verification scenarios back to back.
# Each script is independent — failure of one does not stop the others.
set -uo pipefail

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
        ((PASS++))
    else
        echo "✗ ${name}"
        ((FAIL++))
    fi
}

run "GitHub  (fully resolved)"          "${SCRIPT_DIR}/push-identity-github.sh"
run "GitLab  (resolved, email warning)" "${SCRIPT_DIR}/push-identity-gitlab.sh"
run "Codeberg (unresolved, blocked)"    "${SCRIPT_DIR}/push-identity-codeberg.sh"

echo ""
echo "━━━ Results: ${PASS} passed, ${FAIL} failed ━━━"
[ "${FAIL}" -eq 0 ]
