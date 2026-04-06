#!/usr/bin/env bash
# Combined validation smoke test — transparent proxy mode.
# Makes ONE push containing multiple commits, each targeting a different filter:
#   1. Noreply author email        → author email filter
#   2. WIP commit message          → commit message filter
#   3. GitHub PAT in file          → secret scanning filter (requires secret-scanning.enabled: true)
#   4. Internal hostname in file   → diff content filter
#   5. Unregistered commit email   → identity filter (flagged, not blocked)
# The push is expected to be rejected due to commits 1–4.
set -uo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-"Thomas Cooper"}
PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
REPO_DIR=$(mktemp -d /tmp/proxy-fail-all-XXXX)
BRANCH="test/fail-all-$(date +%s)"

cleanup() {
    rm -rf "${REPO_DIR}"
}
trap cleanup EXIT INT TERM

echo "=========================================================="
echo "  PROXY: COMBINED VALIDATION SMOKE TEST"
echo "  Proxy URL: ${PROXY_URL//${GIT_PASSWORD}/***}"
echo "  Branch:    ${BRANCH}"
echo "=========================================================="

git clone "${PROXY_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"
git checkout -b "${BRANCH}"

# Commit 1: noreply author email — targeting author email filter
git config user.name "Bot User"
git config user.email "noreply@example.com"
echo "author email test - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: commit 1 — noreply author email"

# Commit 2: WIP message — targeting commit message filter
git config user.name "Test Developer"
git config user.email "developer@example.com"
echo "wip message test - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "WIP: commit 2 — bad commit message"

# Commit 3: GitHub PAT in file — targeting secret scanning filter
git config user.name "Test Developer"
git config user.email "developer@example.com"
cat > ci-config.env << 'EOF'
GITHUB_TOKEN=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef01234567
EOF
git add ci-config.env
git commit -m "test: commit 3 — github pat in diff"

# Commit 4: internal hostname literal — targeting diff content filter
git config user.name "Test Developer"
git config user.email "developer@example.com"
cat > config.yml << 'EOF'
upstream:
  api: https://internal.corp.example.com/api/v1
EOF
git add config.yml
git commit -m "test: commit 4 — blocked hostname in diff"

# Commit 5: unregistered email — flagged by identity filter but not blocked
git config user.name "${GIT_AUTHOR_NAME}"
git config user.email "unregistered@example.com"
echo "unregistered email test - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "test: commit 5 — unregistered commit email"

echo ""
echo "==> Pushing all 5 commits in one shot..."
PUSH_OUTPUT=$(git push origin "${BRANCH}" 2>&1 || true)
PUSH_EXIT=${PIPESTATUS[0]}
echo "${PUSH_OUTPUT}"

echo ""
if [[ ${PUSH_EXIT} -ne 0 ]]; then
    echo "PASSED — push was rejected (check output above for per-filter details)"
else
    echo "UNEXPECTED — push was not rejected"
fi
