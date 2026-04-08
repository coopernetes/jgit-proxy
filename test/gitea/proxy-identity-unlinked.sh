#!/usr/bin/env bash
# Identity verification via Gitea transparent proxy (unlinked user — expect block).
#
# gitproxyadmin has no proxy scm-identity → "Identity Not Linked" → push blocked.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

GITEA_TOKEN="${GITEA_ADMIN_TOKEN:-}"
if [ -z "${GITEA_TOKEN}" ] && [ -f ~/.gitea-admin-pat ]; then
    GITEA_TOKEN="$(cat ~/.gitea-admin-pat)"
fi
if [ -z "${GITEA_TOKEN}" ]; then
    echo "ERROR: GITEA_ADMIN_TOKEN not set." >&2
    exit 1
fi

GITEA_REPO="localhost:3000/test-owner/test-repo.git"
PUSH_URL="http://me:${GITEA_TOKEN}@localhost:8080/proxy/gitea/test-owner/test-repo.git"
TEST_BRANCH="test/proxy-unlinked-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/proxy-unlinked-XXXX")

cleanup() { rm -rf "${REPO_DIR}"; }
trap cleanup EXIT

git clone "http://me:${GITEA_TOKEN}@${GITEA_REPO}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"
git config user.name "Gitea Admin"
git config user.email "unregistered@example.com"
git remote set-url origin "${PUSH_URL}"

echo "unlinked - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: proxy identity unlinked — gitproxyadmin has no scm-identity"

OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${OUTPUT}"

if echo "${OUTPUT}" | grep -qi "identity not linked"; then
    echo "PASSED (correctly blocked: gitproxyadmin has no proxy scm-identity)"
else
    echo "FAILED — expected 'identity not linked' rejection"
    exit 1
fi
