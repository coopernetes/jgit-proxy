#!/usr/bin/env bash
# Test script: pushes that SHOULD PASS all content validation filters
# Uses the proxy path (/proxy/github.com/...) which has the full filter chain
set -euo pipefail

REPO_DIR=$(mktemp -d /tmp/proxy-test-pass-XXXX)
GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"

echo "=== SETUP: Cloning to ${REPO_DIR} ==="
git clone "${PROXY_URL}" "${REPO_DIR}" 2>&1
cd "${REPO_DIR}"

# Use a valid author email (example.com domain is in the allow list)
git config user.name "Jane Developer"
git config user.email "jane.developer@example.com"

echo ""
echo "============================================="
echo "  TEST 1: Clean commit message, valid email"
echo "============================================="
echo "pass test 1 - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: add new feature for proxy filter testing"
git push origin main 2>&1
echo ""
echo ">>> TEST 1: PASSED (push succeeded)"

echo ""
echo "============================================="
echo "  TEST 2: Multiple clean commits"
echo "============================================="
echo "pass test 2a - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "docs: update documentation"

echo "pass test 2b - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "refactor: clean up internal logic"

git push origin main 2>&1
echo ""
echo ">>> TEST 2: PASSED (multi-commit push succeeded)"

echo ""
echo "=== CLEANUP: Removing ${REPO_DIR} ==="
rm -rf "${REPO_DIR}"
echo "Done!"
