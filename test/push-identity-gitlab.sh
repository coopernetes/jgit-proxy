#!/usr/bin/env bash
# Identity verification — GitLab (resolved, email warning expected).
#
# Scenario: push as coopernetes using a GitLab PAT. The proxy resolves
# coopernetes → admin (via user_scm_identities for provider=gitlab).
# Commit email does NOT match admin's registered email (developer@example.com)
# → identity resolved via SCM but email-level warning fires (warn mode).
#
# Expected: push succeeds (warn mode), resolved_user = admin on push record,
#           identityVerification step = PASS with sideband warning about email.
#
# Override GIT_EMAIL to use your own unregistered address when adapting for
# a different deployment.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_pat ~/.gitlab-pat
GITLAB_REPO=${GITLAB_REPO:-"gitlab.com/coopernetes/test-repo-gitlab.git"}
GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-"Thomas Cooper"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GITLAB_REPO}"
TEST_BRANCH="test/identity-gitlab-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/push-identity-gitlab-XXXX")

cleanup() {
    git -C "${REPO_DIR}" remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GITLAB_REPO}" 2>/dev/null || true
    git -C "${REPO_DIR}" push origin --delete "${TEST_BRANCH}" 2>/dev/null || true
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

git clone "${PUSH_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
# Hardcoded unregistered email — must NOT be in thomas-cooper's registered emails
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "unregistered@example.com"

echo "identity-gitlab - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity verification — gitlab resolved, email unregistered"
git push origin "${TEST_BRANCH}"

echo "PASSED (check sideband output for identity warning on commit email)"
