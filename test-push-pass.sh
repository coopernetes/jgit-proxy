#!/usr/bin/env bash
# Test script: pushes that SHOULD PASS all validation hooks via store-and-forward
# Uses the push path (/push/github.com/...) which runs JGit ReceivePack with sideband
set -euo pipefail

REPO_DIR=$(mktemp -d /tmp/push-test-pass-XXXX)
GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"

echo "=== SETUP: Cloning to ${REPO_DIR} ==="
git clone "${PUSH_URL}" "${REPO_DIR}"
cd "${REPO_DIR}"

# Use a valid author email (example.com domain is in the allow list)
git config user.name "John Developer"
git config user.email "john.developer@example.com"

echo ""
echo "============================================="
echo "  TEST 1: Clean commit message, valid email"
echo "============================================="
echo "pass test 1 - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: add new feature for store-and-forward testing"
git push origin main
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

git push origin main
echo ""
echo ">>> TEST 2: PASSED (multi-commit push succeeded)"

echo ""
echo "=== CLEANUP: Removing ${REPO_DIR} ==="
rm -rf "${REPO_DIR}"
echo "Done!"
