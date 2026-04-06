#!/usr/bin/env bash
# Test script: author email validation failures via transparent proxy
# Uses the proxy path (/proxy/...) which runs the servlet filter chain
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

PROXY_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/proxy/${GIT_REPO}"

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

print_header "PROXY: AUTHOR EMAIL VALIDATION FAILURES" "${PROXY_URL}"

# Helper for running failure tests (sets up branch for each test)
run_test() {
    local test_name="$1"
    shift
    setup_repo "${PROXY_URL}" "author"
    "$@"
    run_test_expect_failure "${test_name}"
}

run_test "FAIL: noreply email local part"   test_noreply_email
run_test "FAIL: non-allowed email domain"   test_bad_domain_email
run_test "FAIL: GitHub noreply email"       test_github_noreply_email

print_results
