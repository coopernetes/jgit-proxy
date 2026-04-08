#!/usr/bin/env bash
# Identity verification via Gitea store-and-forward.
#
# Scenario: push as test-user using their Gitea token. The proxy resolves
# the token → Gitea username "test-user" → scm_identities lookup → proxy user
# "test-user" (docker-default) or "testuser" (ldap/oidc overlays).
# Commit email matches the registered email → identity fully verified.
#
# Expected: push succeeds, resolved_user set on the push record.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_gitea_pat

GIT_REPO=${GIT_REPO:-"gitea/test-owner/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
TEST_BRANCH="test/gitea-identity-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/gitea-identity-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:3000/test-owner/test-repo.git" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PUSH_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
# Use the registered email so identity verification passes cleanly
git config user.name "Test Developer"
git config user.email "testuser@example.com"

echo "identity - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity verification — Gitea token resolved"
git push origin "${TEST_BRANCH}"

echo "PASSED"
