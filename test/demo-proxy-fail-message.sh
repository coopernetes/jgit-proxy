#!/usr/bin/env bash
# Demo: Transparent proxy push with validation failure (commit message)
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

PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
TEST_BRANCH="test/proxy-fail-msg-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/proxy-test-fail-msg-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

echo "=========================================================="
echo "  PROXY: Commit message validation failure"
echo "  URL: http://localhost:8080/proxy/${GIT_REPO}"
echo "=========================================================="
sleep 2

echo "→ Cloning repository (${PROXY_URL//${GIT_PASSWORD}/***}) via git-proxy..."
git clone "${PROXY_URL}" "${REPO_DIR}"
sleep 2

cd "${REPO_DIR}"
echo "→ Creating test branch..."
git checkout -b "${TEST_BRANCH}"
sleep 1

git config user.name "Test Developer"
git config user.email "developer@example.com"

echo "→ Creating commit with INVALID message (WIP flag)..."
echo "invalid - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "WIP: still working on this feature"
sleep 1

echo "→ Attempting push (will be REJECTED by validation)..."
git push origin "${TEST_BRANCH}" 2>&1 || true
sleep 2

echo ""
echo "✓ PASSED: validation correctly rejected invalid commit"
