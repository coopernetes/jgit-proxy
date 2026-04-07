#!/usr/bin/env bash
# REGEX permission smoke test.
#
# user3 has a REGEX permission on /test-owner/test-repo.* — matches any repo
# under test-owner whose name starts with "test-repo".
#
# Expects:
#   PASS — push to /test-owner/test-repo    (matches regex)
#   PASS — push to /test-owner/test-repo-2  (matches regex)
#   FAIL — push to /otherorg/other-foo      (no match)
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TOKEN="${GITEA_USER3_TOKEN:-}"
if [ -z "${TOKEN}" ]; then
    echo "ERROR: GITEA_USER3_TOKEN not set. Run docker/gitea-setup.sh first." >&2
    exit 1
fi

REPO1="localhost:3000/test-owner/test-repo.git"
REPO2="localhost:3000/test-owner/test-repo-2.git"
REPO_DENIED="localhost:3000/otherorg/other-foo.git"

print_header "PERMISSION: REGEX — user3 (/test-owner/test-repo.*)" "http://localhost:8080"

# --- allowed: /test-owner/test-repo ---
PUSH_URL="http://${GIT_USERNAME}:${TOKEN}@localhost:8080/push/${REPO1}"
GIT_REPO="${REPO1}" setup_repo "${PUSH_URL}" "perm-regex-repo1"
echo "regex-repo1 - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: regex permission — test-repo"
run_test_expect_success "PASS: user3 → /test-owner/test-repo (regex match)"

# --- allowed: /test-owner/test-repo-2 ---
PUSH_URL="http://${GIT_USERNAME}:${TOKEN}@localhost:8080/push/${REPO2}"
GIT_REPO="${REPO2}" setup_repo "${PUSH_URL}" "perm-regex-repo2"
echo "regex-repo2 - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: regex permission — test-repo-2"
run_test_expect_success "PASS: user3 → /test-owner/test-repo-2 (regex match)"

# --- denied: /otherorg/other-foo (no match) ---
PUSH_URL="http://${GIT_USERNAME}:${TOKEN}@localhost:8080/push/${REPO_DENIED}"
GIT_REPO="${REPO_DENIED}" setup_repo "${PUSH_URL}" "perm-regex-deny"
echo "regex-deny - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: regex permission — otherorg denied"
run_test_expect_failure "FAIL: user3 → /otherorg/other-foo (no regex match)"

print_results
[[ ${FAIL} -eq 0 ]]
