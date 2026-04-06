#!/usr/bin/env bash
# Test fetch operations via transparent proxy (/proxy/ path).
#
# Covers two cases:
#   1. Allowed fetch (repo in whitelist) — clone should succeed
#   2. Blocked fetch (repo not in whitelist) — should fail with a clean "Fetch Blocked"
#      error, NOT "fatal: invalid ls-refs response"
#
# Configure WHITELISTED_GIT_REPO and BLOCKED_GIT_REPO to match your whitelist config.
set -uo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
# Must be present in filters.whitelists with operation FETCH
WHITELISTED_GIT_REPO=${WHITELISTED_GIT_REPO:-"github.com/finos/git-proxy.git"}
# Must NOT be in any whitelist entry
BLOCKED_GIT_REPO=${BLOCKED_GIT_REPO:-"github.com/coopernetes/test-repo.git"}

WHITELISTED_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${WHITELISTED_GIT_REPO}"
BLOCKED_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${BLOCKED_GIT_REPO}"

PASS=0
FAIL=0

CURRENT_REPO=""
cleanup() { [[ -n "${CURRENT_REPO}" && -d "${CURRENT_REPO}" ]] && rm -rf "${CURRENT_REPO}"; }
trap cleanup EXIT INT TERM

echo "=========================================================="
echo "  PROXY: FETCH TESTS"
echo "  Whitelisted: ${WHITELISTED_URL//${GIT_PASSWORD}/***}"
echo "  Blocked:     ${BLOCKED_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

# --- Test 1: allowed fetch should succeed ---

echo ""
echo "============================================="
echo "  PASS: fetch from whitelisted repo"
echo "============================================="
CURRENT_REPO=$(mktemp -d /tmp/proxy-test-fetch-XXXX)
clone_exit=0
git clone --depth 1 "${WHITELISTED_URL}" "${CURRENT_REPO}" 2>&1 || clone_exit=$?
if [[ ${clone_exit} -eq 0 ]]; then
    echo ">>> PASS: fetch from whitelisted repo: PASSED"
    ((PASS++))
else
    echo ">>> PASS: fetch from whitelisted repo: FAILED (clone should have succeeded, exit=${clone_exit})"
    ((FAIL++))
fi
rm -rf "${CURRENT_REPO}"
CURRENT_REPO=""

# --- Test 2: blocked fetch should fail with a clean error ---

echo ""
echo "============================================="
echo "  FAIL: fetch from non-whitelisted repo"
echo "============================================="
CURRENT_REPO=$(mktemp -d /tmp/proxy-test-fetch-XXXX)
clone_output=""
clone_exit=0
clone_output=$(git clone --depth 1 "${BLOCKED_URL}" "${CURRENT_REPO}" 2>&1) || clone_exit=$?
echo "${clone_output}"
if [[ ${clone_exit} -eq 0 ]]; then
    echo ">>> FAIL: fetch from non-whitelisted repo: FAILED (clone should have been rejected)"
    ((FAIL++))
elif echo "${clone_output}" | grep -q "invalid ls-refs\|invalid.*response"; then
    echo ">>> FAIL: fetch from non-whitelisted repo: FAILED (rejected but with malformed error — sideband/content-type bug)"
    ((FAIL++))
else
    echo ">>> FAIL: fetch from non-whitelisted repo: PASSED (correctly rejected with clean error)"
    ((PASS++))
fi
rm -rf "${CURRENT_REPO}"
CURRENT_REPO=""

echo ""
echo "=========================================================="
echo "  RESULTS: ${PASS} passed, ${FAIL} failed"
echo "=========================================================="
[[ ${FAIL} -eq 0 ]]
