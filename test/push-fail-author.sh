#!/usr/bin/env bash
# Test script: author email validation failures via store-and-forward
# Uses the push path (/push/...) which runs JGit ReceivePack with sideband
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"

# --- Test functions ---

test_noreply_email() {
    git config user.name "Bot User"
    git config user.email "noreply@example.com"
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

# --- Run tests ---

print_header "STORE-AND-FORWARD: AUTHOR EMAIL VALIDATION FAILURES" "${PUSH_URL}"

# Helper for running failure tests (sets up branch for each test)
run_test() {
    local test_name="$1"
    shift
    setup_repo "${PUSH_URL}" "author"
    "$@"
    run_test_expect_failure "${test_name}"
}

run_test "FAIL: noreply email local part"   test_noreply_email
run_test "FAIL: non-allowed email domain"   test_bad_domain_email
run_test "FAIL: GitHub noreply email"       test_github_noreply_email

print_results
