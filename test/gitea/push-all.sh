#!/usr/bin/env bash
# Orchestrator: runs all Gitea store-and-forward smoke tests.
set -euo pipefail

DIR="$(dirname "${BASH_SOURCE[0]}")"
source "${DIR}/common.sh"

print_header "GITEA SMOKE TESTS — ALL (9 suites)" "http://localhost:8080"

run_orchestrated "push: golden path"            "${DIR}/push-pass.sh"
run_orchestrated "push: commit message failures" "${DIR}/push-fail-message.sh"
run_orchestrated "push: author email failures"   "${DIR}/push-fail-author.sh"
run_orchestrated "push: identity (linked)"       "${DIR}/push-identity.sh"
run_orchestrated "push: identity (unlinked)"     "${DIR}/push-identity-unlinked.sh"
run_orchestrated "proxy: golden path"            "${DIR}/proxy-pass.sh"
run_orchestrated "permission: literal"           "${DIR}/push-permission-literal.sh"
run_orchestrated "permission: glob"              "${DIR}/push-permission-glob.sh"
run_orchestrated "permission: regex"             "${DIR}/push-permission-regex.sh"

print_results
[[ ${FAIL} -eq 0 ]]
