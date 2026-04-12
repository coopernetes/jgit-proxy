#!/usr/bin/env bash
# Demo: Push 5 commits in one shot, each tripping a different validation filter.
# Useful for populating the dashboard with a richly-failed push record.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_pat ~/.github-pat
export GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-"Thomas Cooper"}

PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
TEST_BRANCH="test/proxy-fail-combined-$(date +%s)"
REPO_DIR=$(mktemp -d "${TMPDIR:-/tmp}/proxy-fail-combined-XXXX")

cleanup() {
    safe_rm_rf "${REPO_DIR}"
}
trap cleanup EXIT

echo "=========================================================="
echo "  PROXY: Combined validation failures (demo)"
echo "  URL: http://localhost:8080/proxy/${GIT_REPO}"
echo "=========================================================="
sleep 2

echo "→ Cloning repository (${PROXY_URL//${GIT_PASSWORD}/***}) via git-proxy..."
git clone "${PROXY_URL}" "${REPO_DIR}"
sleep 2

cd "${REPO_DIR}"
echo "→ Creating feature branch..."
git checkout -b "${TEST_BRANCH}"
sleep 1

echo "→ Commit 1: noreply author email..."
git config user.name "Bot User"
git config user.email "noreply@example.com"
echo "author email test - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: commit 1 — noreply author email"
sleep 1

echo "→ Commit 2: WIP commit message..."
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "${GIT_EMAIL}"
echo "wip message test - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "WIP: commit 2 — bad commit message"
sleep 1

echo "→ Commit 3: GitHub PAT in file..."
cat > ci-config.env << 'EOF'
GITHUB_TOKEN=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef01234567
EOF
git add ci-config.env
git commit -m "test: commit 3 — github pat in diff"
sleep 1

echo "→ Commit 4: internal hostname in diff..."
cat > config.yml << 'EOF'
upstream:
  api: https://internal.corp.example.com/api/v1
EOF
git add config.yml
git commit -m "test: commit 4 — blocked hostname in diff"
sleep 1

echo "→ Commit 5: unregistered author email..."
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "unregistered@example.com"
echo "unregistered email test - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: commit 5 — unregistered commit email"
sleep 1

echo "→ Pushing all 5 commits in one shot..."
git push origin "${TEST_BRANCH}" 2>&1 || true
sleep 2

echo ""
echo "✓ Done — check the dashboard for the failed push record"
