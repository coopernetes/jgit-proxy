#!/usr/bin/env bash
# Identity verification — GitLab, transparent proxy mode (resolved, email warning).
#
# Scenario: push as coopernetes via GitLab PAT. Proxy resolves coopernetes → admin
# (user_scm_identities provider=gitlab). Commit email does NOT match admin's
# registered email → identity resolved via SCM, email-level warning fires (warn mode).
# Push is blocked for review, auto-approved via API.
#
# Expected: push succeeds, resolved_user = admin, identity badge = amber.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"coopernetes"}
GIT_PASSWORD=${GIT_PASSWORD:-"$(cat ~/.gitlab-pat)"}
GIT_REPO=${GIT_REPO:-"gitlab.com/coopernetes/test-repo-gitlab.git"}
GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-"Thomas Cooper"}
GIT_EMAIL=${GIT_EMAIL:-"unregistered@example.com"}
GITPROXY_API_KEY=${GITPROXY_API_KEY:-"foobarbaz"}
PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
TEST_BRANCH="test/proxy-identity-gitlab-$(date +%s)"
REPO_DIR=$(mktemp -d /tmp/proxy-identity-gitlab-XXXX)

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PROXY_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"

echo "proxy-identity-gitlab - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: proxy identity verification — gitlab resolved, email unregistered"

PUSH_OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${PUSH_OUTPUT}"

PUSH_ID=$(echo "${PUSH_OUTPUT}" | grep -oP '(?<=/push/)[0-9a-f-]{36}' | head -1)
if [ -z "${PUSH_ID}" ]; then
    echo "ERROR: could not extract push ID"
    exit 1
fi
echo "==> Push ID: ${PUSH_ID}"

APPROVE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:8080/api/push/${PUSH_ID}/authorise" \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: ${GITPROXY_API_KEY}" \
    -d '{"reason":"auto-approved by proxy-identity-gitlab.sh"}')
if [ "${APPROVE_STATUS}" != "200" ]; then
    echo "ERROR: approval returned HTTP ${APPROVE_STATUS}"
    exit 1
fi
echo "==> Approved"

git push origin "${TEST_BRANCH}"
echo "PASSED (check output for identity warning on commit email)"
