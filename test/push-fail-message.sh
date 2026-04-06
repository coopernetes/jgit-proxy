#!/usr/bin/env bash
# Test script: commit message validation failures via store-and-forward
# Uses the push path (/push/...) which runs JGit ReceivePack with sideband
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"

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

print_header "STORE-AND-FORWARD: COMMIT MESSAGE VALIDATION FAILURES" "${PUSH_URL}"

# Helper for running failure tests (sets up branch for each test)
run_test() {
    local test_name="$1"
    shift
    setup_repo "${PUSH_URL}" "message"
    "$@"
    run_test_expect_failure "${test_name}"
}

run_test "FAIL: WIP commit message"             test_wip_message
run_test "FAIL: fixup! commit message"          test_fixup_message
run_test "FAIL: DO NOT MERGE message"           test_do_not_merge_message
run_test "FAIL: password in commit message"     test_secret_in_message
run_test "FAIL: token in commit message"        test_token_in_message

print_results
