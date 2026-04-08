#!/usr/bin/env bash
# Demo: Identity verification — GitLab (resolved, email warning)
# Scenario: Push as coopernetes (GitLab). Identity maps to admin user.
# Commit email does NOT match admin's registered email → resolved via SCM, email warning.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
GITLAB_REPO=${GITLAB_REPO:-"gitlab.com/coopernetes/test-repo-gitlab.git"}
GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-"Thomas Cooper"}
GIT_EMAIL=${GIT_EMAIL:-"unregistered@example.com"}
GITPROXY_API_KEY=${GITPROXY_API_KEY:-"change-me-in-production"}

# Resolve GIT_PASSWORD from env var or PAT file
GIT_PASSWORD="${GIT_PASSWORD:-}"
if [ -z "${GIT_PASSWORD}" ] && [ -f ~/.gitlab-pat ]; then
    GIT_PASSWORD="$(cat ~/.gitlab-pat)"
fi
if [ -z "${GIT_PASSWORD}" ]; then
    echo "ERROR: GIT_PASSWORD not set and ~/.gitlab-pat not found" >&2
    exit 1
fi

PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GITLAB_REPO}"
TEST_BRANCH="test/proxy-identity-gitlab-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/proxy-identity-gitlab-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GITLAB_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

echo "→ Cloning repository (${PROXY_URL//${GIT_PASSWORD}/***}) via git-proxy..."
git clone "${PROXY_URL}" "${REPO_DIR}"
sleep 2

cd "${REPO_DIR}"
echo "→ Creating feature branch..."
git checkout -b "${TEST_BRANCH}"
sleep 1

echo "→ Configuring git user (email NOT in registered identities)..."
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"
sleep 1

echo "→ Creating a valid commit..."
echo "gitlab identity - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity verification — gitlab resolved, email unregistered"
sleep 1

echo "→ Pushing through proxy..."
PUSH_OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${PUSH_OUTPUT}"
sleep 2

PUSH_ID=$(echo "${PUSH_OUTPUT}" | grep -oP '(?<=/push/)[0-9a-f-]{36}' | head -1)
if [ -z "${PUSH_ID}" ]; then
    echo "ERROR: Could not extract push ID"
    exit 1
fi
echo "→ Push ID: ${PUSH_ID}"
sleep 1

echo "→ Auto-approving..."
APPROVE_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:8080/api/push/${PUSH_ID}/authorise" \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: ${GITPROXY_API_KEY}" \
    -d '{"user":"demo-script","comment":"auto-approved"}')
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
echo "✓ Identity resolved (GitLab SCM → local user), email warning issued"
