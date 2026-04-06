#!/usr/bin/env bash
# Golden-path transparent-proxy push: valid commit that should pass all checks.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_pat ~/.github-pat
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
GITPROXY_API_KEY=${GITPROXY_API_KEY:-"change-me-in-production"}


PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
TEST_BRANCH="test/proxy-pass-$(date +%s)"
REPO_DIR=$(mktemp -d /tmp/proxy-test-pass-XXXX)

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PROXY_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "Test Developer"
git config user.email "developer@example.com"

echo "pass - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: golden-path transparent-proxy test"

# Capture push output to extract the pending push ID
PUSH_OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${PUSH_OUTPUT}"

PUSH_ID=$(echo "${PUSH_OUTPUT}" | grep -oP '(?<=/push/)[0-9a-f-]{36}' | head -1)
if [ -z "${PUSH_ID}" ]; then
    echo "ERROR: Could not extract push ID from push output"
    exit 1
fi
echo "==> Push ID: ${PUSH_ID}"

# Approve via API
APPROVE_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:8080/api/push/${PUSH_ID}/authorise" \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: ${GITPROXY_API_KEY}" \
    -d '{"user":"test-script","comment":"auto-approved by proxy-pass.sh"}')
if [ "${APPROVE_RESPONSE}" != "200" ]; then
    echo "ERROR: Approval API returned HTTP ${APPROVE_RESPONSE}"
    exit 1
fi
echo "==> Approved"

# Re-push — should now go through
git push origin "${TEST_BRANCH}"
echo "PASSED"
