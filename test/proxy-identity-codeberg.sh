#!/usr/bin/env bash
# Identity verification — Codeberg, transparent proxy mode (unresolved, blocked).
#
# Scenario: push as coopernetes via Codeberg PAT. No entry in user_scm_identities
# for provider=codeberg → identity cannot be resolved → push rejected outright.
# No approval needed (hard block, not pending-review).
#
# Expected: push fails with "identity not linked" error, no push record forwarded.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_pat ~/.codeberg-pat
GIT_REPO=${GIT_REPO:-"codeberg.org/coopernetes/test-repo-codeberg.git"}
GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-"Thomas Cooper"}
GIT_EMAIL=${GIT_EMAIL:-"unregistered@example.com"}
PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
TEST_BRANCH="test/proxy-identity-codeberg-$(date +%s)"
REPO_DIR=$(mktemp -d /tmp/proxy-identity-codeberg-XXXX)

cleanup() {
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT

# Clone directly from Codeberg (proxy would block the clone too for unregistered user)
git clone "https://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"
git remote set-url origin "${PROXY_URL}"

echo "proxy-identity-codeberg - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: proxy identity verification — codeberg unresolved"

OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${OUTPUT}"

if echo "${OUTPUT}" | grep -qi "identity not linked"; then
    echo "PASSED (correctly blocked: coopernetes has no codeberg SCM identity)"
else
    echo "FAILED — expected "identity not linked" rejection"
    exit 1
fi
