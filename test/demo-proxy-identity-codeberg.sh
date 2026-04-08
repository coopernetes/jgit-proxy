#!/usr/bin/env bash
# Demo: Identity verification — Codeberg (unresolved, blocked)
# Scenario: Push as coopernetes (Codeberg). No SCM identity entry for Codeberg.
# Identity cannot be resolved → push rejected outright (hard block, no approval).
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
CODEBERG_REPO=${CODEBERG_REPO:-"codeberg.org/coopernetes/test-repo-codeberg.git"}
GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-"Thomas Cooper"}
GIT_EMAIL=${GIT_EMAIL:-"unregistered@example.com"}

# Resolve GIT_PASSWORD from env var or PAT file
GIT_PASSWORD="${GIT_PASSWORD:-}"
if [ -z "${GIT_PASSWORD}" ] && [ -f ~/.codeberg-pat ]; then
    GIT_PASSWORD="$(cat ~/.codeberg-pat)"
fi
if [ -z "${GIT_PASSWORD}" ]; then
    echo "ERROR: GIT_PASSWORD not set and ~/.codeberg-pat not found" >&2
    exit 1
fi

PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${CODEBERG_REPO}"
TEST_BRANCH="test/proxy-identity-codeberg-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/proxy-identity-codeberg-XXXX")

cleanup() {
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

echo "→ Cloning repository (${PROXY_URL//${GIT_PASSWORD}/***}) via git-proxy..."
git clone "https://${GIT_USERNAME}:${GIT_PASSWORD}@${CODEBERG_REPO}" "${REPO_DIR}"
sleep 2

cd "${REPO_DIR}"
echo "→ Creating feature branch..."
git checkout -b "${TEST_BRANCH}"
sleep 1

echo "→ Configuring git user..."
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"
sleep 1

echo "→ Creating a valid commit..."
echo "codeberg identity - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: identity verification — codeberg unresolved"
sleep 1

echo "→ Switching remote to proxy and pushing..."
git remote set-url origin "${PROXY_URL}"
OUTPUT=$(git push origin "${TEST_BRANCH}" 2>&1 || true)
echo "${OUTPUT}"
sleep 2

if echo "${OUTPUT}" | grep -qi "identity not linked"; then
    echo ""
    echo "✓ Correctly blocked: coopernetes has no Codeberg SCM identity"
else
    echo ""
    echo "✗ FAILED: expected 'identity not linked' rejection"
    exit 1
fi
