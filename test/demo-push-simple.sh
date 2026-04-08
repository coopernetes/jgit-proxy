#!/usr/bin/env bash
# Demo: Simple store-and-forward push that passes validation
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}

# Resolve GIT_PASSWORD from env var or PAT file
GIT_PASSWORD="${GIT_PASSWORD:-}"
if [ -z "${GIT_PASSWORD}" ] && [ -f ~/.github-pat ]; then
    GIT_PASSWORD="$(cat ~/.github-pat)"
fi
if [ -z "${GIT_PASSWORD}" ]; then
    echo "ERROR: GIT_PASSWORD not set and ~/.github-pat not found" >&2
    exit 1
fi

PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
TEST_BRANCH="test/push-simple-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/push-simple-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

echo "=========================================================="
echo "  PUSH: Golden-path push"
echo "  URL: http://localhost:8080/push/${GIT_REPO}"
echo "=========================================================="
sleep 2

echo "→ Cloning repository (${PUSH_URL//${GIT_PASSWORD}/***}) via git-proxy..."
git clone "${PUSH_URL}" "${REPO_DIR}"
sleep 2

cd "${REPO_DIR}"
echo "→ Creating feature branch..."
git checkout -b "${TEST_BRANCH}"
sleep 1

git config user.name "Test Developer"
git config user.email "developer@example.com"

echo "→ Making a valid commit..."
echo "feature - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: add new feature"
sleep 1

echo "→ Pushing to upstream..."
git push origin "${TEST_BRANCH}"
sleep 2

echo ""
echo "✓ PASSED: commit validated and pushed successfully"
