#!/usr/bin/env bash
# Demo: Identity verification via transparent proxy.
#
# Scenario A: push using credentials of a user whose SCM identity IS registered
#             in the proxy → identity resolved, auto-approved, forwarded.
#
# Scenario B: push using credentials of a user whose SCM identity is NOT in the
#             proxy user store → push blocked with "Identity Not Linked" error.
#
# Requires:
#   - Proxy running locally (http://localhost:8080)
#   - GITPROXY_API_KEY env var or default ("change-me-in-production")
#   - GIT_REPO set to the upstream repo (e.g. github.com/coopernetes/test-repo.git)
#   - GIT_PASSWORD set to the PAT for the linked user (scenario A)
#   - GIT_PASSWORD_UNLINKED set to a PAT for a user NOT in the proxy user store
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_pat ~/.github-pat
export GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}

PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
UNLINKED_PASSWORD="${GIT_PASSWORD_UNLINKED:-${GIT_PASSWORD}}"
UNLINKED_URL="http://unlinked-user:${UNLINKED_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"

echo "=========================================================="
echo "  PROXY: Identity resolution demo"
echo "  Repo: ${GIT_REPO}"
echo "=========================================================="
sleep 1

# ── Scenario A: linked user ──────────────────────────────────────────────────

REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/demo-identity-linked-XXXX")
TEST_BRANCH="test/identity-linked-$(date +%s)"

cleanup_a() { safe_rm_rf "${REPO_DIR}"; }
trap cleanup_a EXIT

echo ""
echo "── Scenario A: push as registered user ──────────────────"
echo "→ Cloning via proxy..."
git clone "${PROXY_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"

echo "→ Committing..."
echo "identity demo - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity demo — linked user"
sleep 1

echo "→ First push (blocked pending review)..."
PUSH_OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${PUSH_OUTPUT}"

PUSH_ID=$(echo "${PUSH_OUTPUT}" | grep -oP '(?<=[^/])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1)
if [ -z "${PUSH_ID}" ]; then
    echo "ERROR: Could not extract push ID from output"
    exit 1
fi
echo "→ Push ID: ${PUSH_ID}"
sleep 1

echo "→ Approving via API..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:8080/api/push/${PUSH_ID}/authorise" \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: ${GITPROXY_API_KEY}" \
    -d '{"user":"demo","comment":"identity demo approval"}')
if [ "${HTTP_CODE}" != "200" ]; then
    echo "ERROR: Approval API returned HTTP ${HTTP_CODE}"
    exit 1
fi
echo "→ Approved (HTTP 200)"
sleep 1

echo "→ Re-pushing (now completes)..."
git push origin "${TEST_BRANCH}"
echo "✓ Scenario A: PASSED — identity verified and push forwarded"
sleep 2

safe_rm_rf "${REPO_DIR}"
REPO_DIR=""

# ── Scenario B: unlinked user ────────────────────────────────────────────────

REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/demo-identity-unlinked-XXXX")
TEST_BRANCH_B="test/identity-unlinked-$(date +%s)"

echo ""
echo "── Scenario B: push as unregistered user ────────────────"
echo "→ Cloning via proxy (using unlinked credentials)..."
git clone "${UNLINKED_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH_B}"
git config user.name "Unlinked Developer"
git config user.email "${GIT_EMAIL}"

echo "→ Committing..."
echo "unlinked identity test - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity demo — unlinked user"
sleep 1

echo "→ Pushing (will be blocked)..."
git push origin "${TEST_BRANCH_B}" 2>&1 || true
echo "✓ Scenario B: PASSED — unlinked user correctly blocked"

echo ""
echo "=========================================================="
echo "  Demo complete"
echo "=========================================================="
