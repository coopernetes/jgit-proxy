#!/usr/bin/env bash
# Identity verification — GitHub (full resolution expected).
#
# Scenario: push as coopernetes using a GitHub PAT. The proxy resolves
# coopernetes → thomas-cooper (via user_scm_identities for provider=github).
# Commit email matches thomas-cooper's registered email → identity fully verified.
#
# Expected: push succeeds, resolved_user = thomas-cooper on the push record.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_pat ~/.github-pat
GITHUB_REPO=${GITHUB_REPO:-"github.com/coopernetes/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GITHUB_REPO}"
TEST_BRANCH="test/identity-github-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/push-identity-github-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GITHUB_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PUSH_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"

echo "identity-github - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity verification — github fully resolved"
git push origin "${TEST_BRANCH}"

echo "PASSED"
