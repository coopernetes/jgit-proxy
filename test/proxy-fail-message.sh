#!/usr/bin/env bash
# Test script: commit message validation failures via transparent proxy
# Uses the proxy path (/proxy/...) which runs the servlet filter chain
set -uo pipefail

GIT_USERNAME=${GIT_USERNAME:-"coopernetes"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
PASS=0
FAIL=0

CURRENT_REPO=""
cleanup() { [[ -n "${CURRENT_REPO}" && -d "${CURRENT_REPO}" ]] && rm -rf "${CURRENT_REPO}"; }
trap cleanup EXIT INT TERM

run_test() {
    local test_name="$1"
    shift

    echo ""
    echo "============================================="
    echo "  ${test_name}"
    echo "============================================="

    CURRENT_REPO=$(mktemp -d /tmp/proxy-test-msg-XXXX)
    cd /tmp
    git clone "${PROXY_URL}" "${CURRENT_REPO}" 2>&1
    cd "${CURRENT_REPO}"

    local branch="test/proxy-msg-$(date +%s%N | tail -c 8)"
    git checkout -b "${branch}"

    "$@"

    local push_exit=0
    git push origin "${branch}" 2>&1 || push_exit=$?

    if [[ ${push_exit} -ne 0 ]]; then
        echo ">>> ${test_name}: PASSED (push correctly rejected)"
        ((PASS++))
    else
        echo ">>> ${test_name}: UNEXPECTED (push should have been rejected)"
        ((FAIL++))
    fi

    rm -rf "${CURRENT_REPO}"
    CURRENT_REPO=""
}

# --- Test functions ---

test_wip_message() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    echo "wip message test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "WIP: still working on this feature"
}

test_fixup_message() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    echo "fixup message test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "fixup! previous commit that needs squashing"
}

test_do_not_merge_message() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    echo "do not merge test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "DO NOT MERGE - experimental branch"
}

test_secret_in_message() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    echo "secret leak test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "fix: update config where password= hunter2 was exposed"
}

test_token_in_message() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    echo "token leak test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "chore: rotate token=ghp_abc123def456 in CI config"
}

# --- Run tests ---

echo "=========================================================="
echo "  PROXY: COMMIT MESSAGE VALIDATION FAILURES"
echo "  Proxy URL: ${PROXY_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

run_test "FAIL: WIP commit message"             test_wip_message
run_test "FAIL: fixup! commit message"          test_fixup_message
run_test "FAIL: DO NOT MERGE message"           test_do_not_merge_message
run_test "FAIL: password in commit message"     test_secret_in_message
run_test "FAIL: token in commit message"        test_token_in_message

echo ""
echo "=========================================================="
echo "  RESULTS: ${PASS} passed, ${FAIL} unexpected"
echo "=========================================================="
