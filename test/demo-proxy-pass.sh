#!/usr/bin/env bash
# Demo: Transparent proxy push with automated approval
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
GITPROXY_API_KEY=${GITPROXY_API_KEY:-"change-me-in-production"}

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
TEST_BRANCH="test/proxy-pass-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/proxy-test-pass-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

echo "=========================================================="
echo "  PROXY: Golden-path push with auto-approval"
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

echo "→ Creating a valid commit..."
echo "pass - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: golden-path transparent-proxy test"
sleep 1

echo "→ Pushing through git-proxy (will be held for approval)..."
PUSH_OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${PUSH_OUTPUT}"
sleep 2

PUSH_ID=$(echo "${PUSH_OUTPUT}" | grep -oP '(?<=/push/)[0-9a-f-]{36}' | head -1)
if [ -z "${PUSH_ID}" ]; then
    echo "ERROR: Could not extract push ID"
    exit 1
fi
echo "→ Extracted push ID: ${PUSH_ID}"
sleep 1

echo "→ Auto-approving via API..."
APPROVE_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:8080/api/push/${PUSH_ID}/authorise" \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: ${GITPROXY_API_KEY}" \
    -d '{"user":"demo-script","comment":"auto-approved","attestations":{"reviewed-content":"true","policy-compliance":"true"}}')
if [ "${APPROVE_RESPONSE}" != "200" ]; then
    echo "ERROR: Approval API returned HTTP ${APPROVE_RESPONSE}"
    exit 1
fi
echo "✓ Approved"
sleep 1

echo "→ Re-pushing (now will complete)..."
git push origin "${TEST_BRANCH}"
sleep 1

echo ""
echo "✓ PASSED: push validated, approved, and forwarded"
