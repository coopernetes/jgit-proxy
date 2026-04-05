#!/usr/bin/env bash
# Golden-path store-and-forward push: valid commit that should pass all checks.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
TEST_BRANCH="test/push-pass-$(date +%s)"
REPO_DIR=$(mktemp -d /tmp/push-test-pass-XXXX)

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PUSH_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "Test Developer"
git config user.email "developer@example.com"

echo "pass - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: golden-path store-and-forward test"
git push origin "${TEST_BRANCH}"

echo "PASSED"
