#!/usr/bin/env bash
# LITERAL permission smoke test.
#
# test-user has a LITERAL permission on /test-owner/test-repo only.
#
# Expects:
#   PASS — push to /test-owner/test-repo  (the permitted repo)
#   FAIL — push to /otherorg/other-foo    (not covered by test-user's literal grant)
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TOKEN="${GITEA_TESTUSER_TOKEN:-}"
if [ -z "${TOKEN}" ]; then
    echo "ERROR: GITEA_TESTUSER_TOKEN not set. Run docker/gitea-setup.sh first." >&2
    exit 1
fi

ALLOWED_REPO="localhost:3000/test-owner/test-repo.git"
DENIED_REPO="localhost:3000/otherorg/other-foo.git"

print_header "PERMISSION: LITERAL — test-user" "http://localhost:8080"

# --- allowed: /test-owner/test-repo ---
PUSH_URL="http://${GIT_USERNAME}:${TOKEN}@localhost:8080/push/${ALLOWED_REPO}"
GIT_REPO="${ALLOWED_REPO}" setup_repo "${PUSH_URL}" "perm-literal-allow"
echo "allowed - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: literal permission — should be allowed"
run_test_expect_success "PASS: test-user → /test-owner/test-repo (literal grant)"

# --- denied: /otherorg/other-foo (no grant for test-user) ---
PUSH_URL="http://${GIT_USERNAME}:${TOKEN}@localhost:8080/push/${DENIED_REPO}"
GIT_REPO="${DENIED_REPO}" setup_repo "${PUSH_URL}" "perm-literal-deny"
echo "denied - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: literal permission — should be denied"
run_test_expect_failure "FAIL: test-user → /otherorg/other-foo (no grant)"

print_results
[[ ${FAIL} -eq 0 ]]
