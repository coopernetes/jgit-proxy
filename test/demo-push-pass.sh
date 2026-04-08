#!/usr/bin/env bash
# Demo: Golden-path store-and-forward push with sleeps for readability
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
TEST_BRANCH="test/push-pass-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/push-test-pass-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

echo "→ Cloning repository via git-proxy..."
git clone "${PUSH_URL}" "${REPO_DIR}"
sleep 2

cd "${REPO_DIR}"
echo "→ Creating test branch..."
git checkout -b "${TEST_BRANCH}"
sleep 1

git config user.name "Test Developer"
git config user.email "developer@example.com"

echo "→ Creating a valid commit..."
echo "pass - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: golden-path store-and-forward test"
sleep 1

echo "→ Pushing through git-proxy..."
git push origin "${TEST_BRANCH}"
sleep 1

echo ""
echo "✓ PASSED: valid commit accepted"
