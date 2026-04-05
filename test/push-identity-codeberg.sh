#!/usr/bin/env bash
# Identity verification — Codeberg (unresolved, push blocked expected).
#
# Scenario: push as coopernetes using a Codeberg PAT. No entry exists in
# user_scm_identities for provider=codeberg → identity cannot be resolved.
# With a user store configured, the proxy blocks the push as "not registered".
#
# Expected: push fails with "not registered" error, no push record created.
#
# Override GIT_EMAIL / GIT_AUTHOR_NAME when adapting for a different deployment.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"coopernetes"}
GIT_PASSWORD=${GIT_PASSWORD:-"$(cat ~/.codeberg-pat)"}
GIT_REPO=${GIT_REPO:-"codeberg.org/coopernetes/test-repo-codeberg.git"}
GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-"Thomas Cooper"}
GIT_EMAIL=${GIT_EMAIL:-"unregistered@example.com"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
TEST_BRANCH="test/identity-codeberg-$(date +%s)"
REPO_DIR=$(mktemp -d /tmp/push-identity-codeberg-XXXX)

cleanup() {
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

# Clone using the Codeberg PAT directly (bypassing the proxy) so we have a repo to push from
git clone "https://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"
git remote set-url origin "${PUSH_URL}"

echo "identity-codeberg - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity verification — codeberg unresolved"

OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${OUTPUT}"

if echo "${OUTPUT}" | grep -q "not registered"; then
    echo "PASSED (correctly blocked: coopernetes has no codeberg SCM identity)"
else
    echo "FAILED — expected 'not registered' rejection"
    echo "If running in open mode (no user store), this push may have succeeded."
    exit 1
fi
