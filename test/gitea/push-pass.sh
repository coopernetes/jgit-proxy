#!/usr/bin/env bash
# Golden-path store-and-forward push via Gitea: valid commit, all checks pass.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_gitea_pat

GIT_REPO=${GIT_REPO:-"gitea/test-owner/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
TEST_BRANCH="test/gitea-push-pass-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/gitea-push-pass-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:3000/test-owner/test-repo.git" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PUSH_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "Test Developer"
git config user.email "testuser@example.com"

echo "pass - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: golden-path Gitea store-and-forward test"
git push origin "${TEST_BRANCH}"

echo "PASSED"
