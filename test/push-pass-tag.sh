#!/usr/bin/env bash
# Store-and-forward tag push: lightweight and annotated tags should pass all checks.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"coopernetes"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
TEST_BRANCH="test/push-pass-tag-$(date +%s)"
LIGHTWEIGHT_TAG="test/lw-tag-$(date +%s)"
ANNOTATED_TAG="test/ann-tag-$(date +%s)"
REPO_DIR=$(mktemp -d /tmp/push-test-pass-tag-XXXX)

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "refs/tags/${LIGHTWEIGHT_TAG}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "refs/tags/${ANNOTATED_TAG}" 2>/dev/null || true
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
git commit -m "feat: tag push test base commit"
git push origin "${TEST_BRANCH}"

# Lightweight tag — points directly to the commit object
git tag "${LIGHTWEIGHT_TAG}"
git push origin "refs/tags/${LIGHTWEIGHT_TAG}"
echo "==> Lightweight tag push passed"

# Annotated tag — points to a tag object which in turn points to the commit
git tag -a "${ANNOTATED_TAG}" -m "Annotated tag for push-pass-tag test"
git push origin "refs/tags/${ANNOTATED_TAG}"
echo "==> Annotated tag push passed"

echo "PASSED"
