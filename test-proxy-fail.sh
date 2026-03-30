#!/usr/bin/env bash
# Test script: pushes that SHOULD FAIL content validation filters
# Uses the proxy path (/proxy/github.com/...) which has the full filter chain
# Each test clones fresh to avoid leftover state from failed pushes
set -uo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"
PASS=0
FAIL=0

run_test() {
    local test_name="$1"
    local expect="$2"  # "fail" or "pass"
    shift 2

    echo ""
    echo "============================================="
    echo "  ${test_name}"
    echo "============================================="

    local repo_dir
    repo_dir=$(mktemp -d /tmp/proxy-test-fail-XXXX)
    cd /tmp
    git clone "${PROXY_URL}" "${repo_dir}" 2>&1
    cd "${repo_dir}"

    # Run the test function (passed as remaining args)
    "$@"

    local push_exit=0
    git push origin main 2>&1 || push_exit=$?

    if [[ "${expect}" == "fail" && ${push_exit} -ne 0 ]]; then
        echo ">>> ${test_name}: PASSED (push correctly rejected)"
        ((PASS++))
    elif [[ "${expect}" == "pass" && ${push_exit} -eq 0 ]]; then
        echo ">>> ${test_name}: PASSED (push correctly accepted)"
        ((PASS++))
    else
        echo ">>> ${test_name}: UNEXPECTED (exit=${push_exit}, expected=${expect})"
        ((FAIL++))
    fi

    rm -rf "${repo_dir}"
}

# --- Test functions ---

test_noreply_email() {
    git config user.name "Bot User"
    git config user.email "noreply@proton.me"
    echo "noreply email test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "feat: this commit has a noreply author"
}

test_bad_domain_email() {
    git config user.name "Internal User"
    git config user.email "developer@internal.corp.net"
    echo "bad domain test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "feat: this commit has a non-allowed domain"
}

test_github_noreply_email() {
    git config user.name "GitHub User"
    git config user.email "12345+user@users.noreply.github.com"
    echo "github noreply test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "feat: this commit uses GitHub's noreply email"
}

test_wip_message() {
    git config user.name "Thomas Cooper"
    git config user.email "coopernetes@proton.me"
    echo "wip message test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "WIP: still working on this feature"
}

test_fixup_message() {
    git config user.name "Thomas Cooper"
    git config user.email "coopernetes@proton.me"
    echo "fixup message test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "fixup! previous commit that needs squashing"
}

test_do_not_merge_message() {
    git config user.name "Thomas Cooper"
    git config user.email "coopernetes@proton.me"
    echo "do not merge test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "DO NOT MERGE - experimental branch"
}

test_secret_in_message() {
    git config user.name "Thomas Cooper"
    git config user.email "coopernetes@proton.me"
    echo "secret leak test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "fix: update config where password= hunter2 was exposed"
}

test_token_in_message() {
    git config user.name "Thomas Cooper"
    git config user.email "coopernetes@proton.me"
    echo "token leak test - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "chore: rotate token=ghp_abc123def456 in CI config"
}

# --- Run tests ---

echo "=========================================================="
echo "  PROXY FILTER FAILURE TEST SUITE"
echo "  Proxy URL: ${PROXY_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

# Author email failures
run_test "FAIL: noreply email local part"       fail test_noreply_email
run_test "FAIL: non-allowed email domain"       fail test_bad_domain_email
run_test "FAIL: GitHub noreply email"            fail test_github_noreply_email

# Commit message failures
run_test "FAIL: WIP commit message"             fail test_wip_message
run_test "FAIL: fixup! commit message"          fail test_fixup_message
run_test "FAIL: DO NOT MERGE message"           fail test_do_not_merge_message
run_test "FAIL: password in commit message"     fail test_secret_in_message
run_test "FAIL: token in commit message"        fail test_token_in_message

echo ""
echo "=========================================================="
echo "  RESULTS: ${PASS} passed, ${FAIL} unexpected"
echo "=========================================================="
