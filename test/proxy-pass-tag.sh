#!/usr/bin/env bash
# Transparent-proxy tag push: lightweight and annotated tags should pass all checks.
# Tags point to an existing upstream commit so no approval step is required.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_pat ~/.github-pat
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
GITPROXY_API_KEY=${GITPROXY_API_KEY:-"change-me-in-production"}


PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
LIGHTWEIGHT_TAG="test/proxy-lw-tag-$(date +%s)"
ANNOTATED_TAG="test/proxy-ann-tag-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/proxy-test-pass-tag-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "https://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "refs/tags/${LIGHTWEIGHT_TAG}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "refs/tags/${ANNOTATED_TAG}" 2>/dev/null || true
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PROXY_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"

# Tag the existing HEAD — commit already exists upstream, no approval needed
git tag "${LIGHTWEIGHT_TAG}"
# Capture push output to extract the pending push ID
PUSH_OUTPUT=$(git push origin "refs/tags/${LIGHTWEIGHT_TAG}" 2>&1 || true)
echo "${PUSH_OUTPUT}"

if [ -z "${PUSH_OUTPUT}" ]; then
    echo "ERROR: No push output captured so can't approve!"
    exit 1
fi

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
    -d '{"user":"test-script","comment":"auto-approved by proxy-pass.sh","attestations":{"reviewed-content":"true","policy-compliance":"true"}}')
if [ "${APPROVE_RESPONSE}" != "200" ]; then
    echo "ERROR: Approval API returned HTTP ${APPROVE_RESPONSE}"
    exit 1
fi
echo "==> Approved"
git push origin "refs/tags/${LIGHTWEIGHT_TAG}"
echo "==> Lightweight tag push via proxy passed"

git tag -a "${ANNOTATED_TAG}" -m "Annotated tag for proxy-pass-tag test"
# Capture push output to extract the pending push ID
PUSH_OUTPUT=$(git push origin "refs/tags/${ANNOTATED_TAG}" 2>&1 || true)
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
    -d '{"user":"test-script","comment":"auto-approved by proxy-pass.sh","attestations":{"reviewed-content":"true","policy-compliance":"true"}}')
if [ "${APPROVE_RESPONSE}" != "200" ]; then
    echo "ERROR: Approval API returned HTTP ${APPROVE_RESPONSE}"
    exit 1
fi
echo "==> Approved"
git push origin "refs/tags/${ANNOTATED_TAG}"
echo "==> Annotated tag push via proxy passed"

