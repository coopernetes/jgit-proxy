#!/usr/bin/env bash
# Run all three proxy-mode identity verification scenarios back to back.
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

run "GitHub  (fully resolved)"          "${SCRIPT_DIR}/proxy-identity-github.sh"
run "GitLab  (resolved, email warning)" "${SCRIPT_DIR}/proxy-identity-gitlab.sh"
run "Codeberg (unresolved, blocked)"    "${SCRIPT_DIR}/proxy-identity-codeberg.sh"

echo ""
echo "━━━ Results: ${PASS} passed, ${FAIL} failed ━━━"
[ "${FAIL}" -eq 0 ]
