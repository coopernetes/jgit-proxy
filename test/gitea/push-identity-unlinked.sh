#!/usr/bin/env bash
# Identity verification — unlinked Gitea user (push blocked expected).
#
# Scenario: push using the Gitea ADMIN token (gitproxyadmin). The proxy resolves
# the token → "gitproxyadmin" but no proxy user maps to that Gitea username
# → "Identity Not Linked" → push blocked.
#
# Expected: push fails with "identity not linked" in the output.
# This mirrors push-identity-codeberg.sh in the local smoke tests.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

# Use the Gitea admin credentials (gitproxyadmin has no proxy scm-identity)
GITEA_TOKEN="${GITEA_ADMIN_TOKEN:-}"
if [ -z "${GITEA_TOKEN}" ] && [ -f ~/.gitea-admin-pat ]; then
    GITEA_TOKEN="$(cat ~/.gitea-admin-pat)"
fi
if [ -z "${GITEA_TOKEN}" ]; then
    echo "ERROR: GITEA_ADMIN_TOKEN not set and ~/.gitea-admin-pat not found." >&2
    echo "       Generate a token for gitproxyadmin in Gitea and save it to ~/.gitea-admin-pat" >&2
    exit 1
fi

GIT_REPO=${GIT_REPO:-"localhost:3000/test-owner/test-repo.git"}
PUSH_URL="http://me:${GITEA_TOKEN}@localhost:8080/push/${GIT_REPO}"
TEST_BRANCH="test/gitea-unlinked-$(date +%s)"
REPO_DIR=$(mktemp -d /tmp/gitea-unlinked-XXXX)

cleanup() {
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

# Clone directly from Gitea (not via proxy) so we have a repo to push from
git clone "http://me:${GITEA_TOKEN}@${GIT_REPO}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "Gitea Admin"
git config user.email "admin@example.com"
git remote set-url origin "${PUSH_URL}"

echo "unlinked - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity unlinked — gitproxyadmin has no scm-identity"

OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${OUTPUT}"

if echo "${OUTPUT}" | grep -qi "identity not linked"; then
    echo "PASSED (correctly blocked: gitproxyadmin has no proxy scm-identity)"
else
    echo "FAILED — expected 'identity not linked' rejection"
    exit 1
fi
