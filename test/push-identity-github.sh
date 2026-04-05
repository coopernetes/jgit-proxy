#!/usr/bin/env bash
# Identity verification — GitHub (full resolution expected).
#
# Scenario: push as coopernetes using a GitHub PAT. The proxy resolves
# coopernetes → admin (via user_scm_identities for provider=github).
# Commit email matches admin's registered email → identity fully verified.
#
# Expected: push succeeds, resolved_user = admin on the push record.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"coopernetes"}
GIT_PASSWORD=${GIT_PASSWORD:-"$(cat ~/.github-pat)"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
TEST_BRANCH="test/identity-github-$(date +%s)"
REPO_DIR=$(mktemp -d /tmp/push-identity-github-XXXX)

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PUSH_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
# Use registered email so identity verification passes cleanly
git config user.name "Test Developer"
git config user.email "developer@example.com"

echo "identity-github - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity verification — github fully resolved"
git push origin "${TEST_BRANCH}"

echo "PASSED"
