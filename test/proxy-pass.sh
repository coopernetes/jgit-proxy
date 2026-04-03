#!/usr/bin/env bash
# Test script: pushes that SHOULD PASS all content validation filters
# Uses the proxy path (/proxy/github.com/...) which has the full filter chain
set -euo pipefail

REPO_DIR=$(mktemp -d /tmp/proxy-test-pass-XXXX)
GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"

TEST_BRANCH="test/proxy-pass-$(date +%s)"

echo "=== SETUP: Cloning to ${REPO_DIR} ==="
git clone "${PROXY_URL}" "${REPO_DIR}" 2>&1
cd "${REPO_DIR}"
git checkout -b "${TEST_BRANCH}"

# Use a valid author email (example.com domain is in the allow list)
git config user.name "Test Developer"
git config user.email "developer@example.com"

echo ""
echo "============================================="
echo "  TEST 1: Clean commit message, valid email"
echo "============================================="
echo "pass test 1 - $(date)" >> test-file.txt
git add test-file.txt
git commit -m "feat: add new feature for proxy filter testing"
git push origin "${TEST_BRANCH}" 2>&1
read -p "Press enter to continue..."
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

git push origin "${TEST_BRANCH}" 2>&1
read -p "Press enter to continue..."
echo ""
echo ">>> TEST 2: PASSED (multi-commit push succeeded)"

echo ""
echo "============================================="
echo "  TEST 3: Clean file content (diff scan pass)"
echo "  Verifies env-style files with no secrets pass diff scanning"
echo "============================================="
# Contains config keys but no values that match blocked patterns
cat >> app.env << 'ENVEOF'
DATABASE_URL=postgres://localhost/mydb
LOG_LEVEL=info
MAX_RETRIES=3
DEBUG=false
ENVEOF
git add app.env
git commit -m "chore: add application env config"
git push origin "${TEST_BRANCH}" 2>&1
read -p "Press enter to continue..."
echo ""
echo ">>> TEST 3: PASSED (push with clean file content succeeded)"

echo ""
echo "============================================="
echo "  TEST 4: File containing 'private' (not a key block)"
echo "  Verifies the literal 'PRIVATE KEY' must match exactly"
echo "============================================="
# 'private' and 'key' appear separately but not as "PRIVATE KEY"
cat >> config.properties << 'CFGEOF'
# private configuration
some_key=value
feature_flag=true
CFGEOF
git add config.properties
git commit -m "chore: add config properties"
git push origin "${TEST_BRANCH}" 2>&1
read -p "Press enter to continue..."
echo ""
echo ">>> TEST 4: PASSED (push with separate 'private' and 'key' words succeeded)"

echo ""
echo "=== CLEANUP: Deleting remote branch ${TEST_BRANCH} ==="
git push origin --delete "${TEST_BRANCH}" 2>&1
echo "=== CLEANUP: Removing ${REPO_DIR} ==="
rm -rf "${REPO_DIR}"
echo "Done!"
