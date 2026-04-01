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

    local branch="test/proxy-fail-$(date +%s%N | tail -c 8)"
    git checkout -b "${branch}"

    # Run the test function (passed as remaining args)
    "$@"

    local push_exit=0
    git push origin "${branch}" 2>&1 || push_exit=$?

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

# --- Author email test functions ---

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

# --- Commit message test functions ---

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

# --- Diff content test functions ---

test_diff_private_key_literal() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    # Fake PKCS#8 PEM block — matches the "PRIVATE KEY" literal in diff block config
    cat >> credentials.pem << 'PEMEOF'
-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7
-----END PRIVATE KEY-----
PEMEOF
    git add credentials.pem
    git commit -m "chore: update credentials file"
}

test_diff_rsa_key_literal() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    # Fake PKCS#1 RSA block — matches the "BEGIN RSA" literal in diff block config
    cat >> id_rsa.pem << 'PEMEOF'
-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEA2a2rwplBQLF29amygykEMmYz0+Kcj3bKBp29
-----END RSA PRIVATE KEY-----
PEMEOF
    git add id_rsa.pem
    git commit -m "chore: add RSA key file"
}

test_diff_api_key_pattern() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    # API_KEY=... matches the api[_-]?key pattern in diff block config
    cat >> app.env << 'ENVEOF'
DATABASE_URL=postgres://localhost/mydb
API_KEY=sk-abc123def456ghi789jkl
LOG_LEVEL=info
ENVEOF
    git add app.env
    git commit -m "chore: add application config"
}

test_diff_aws_secret_pattern() {
    git config user.name "Test Developer"
    git config user.email "developer@example.com"
    # aws_secret_access_key=... matches the aws_secret_access_key pattern in diff block config
    cat >> aws-config.env << 'ENVEOF'
AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
ENVEOF
    git add aws-config.env
    git commit -m "docs: add AWS config template"
}

# Both email AND message checks fail in the same push — useful for
# verifying the UI displays multiple simultaneous validation failures.
test_multi_failure() {
    # blocked local part (noreply) + non-allowed domain = email fails
    git config user.name "Noreply Bot"
    git config user.email "noreply@internal.corp.net"
    echo "multi failure test - $(date)" >> test-file.txt
    git add test-file.txt
    # WIP literal + embedded secret pattern = message fails twice
    git commit -m "WIP: temp debug, password=hunter2 hardcoded"
}

# --- Run tests ---

echo "=========================================================="
echo "  PROXY FILTER FAILURE TEST SUITE"
echo "  Proxy URL: ${PROXY_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

# Author email failures
run_test "FAIL: noreply email local part"       fail test_noreply_email
run_test "FAIL: non-allowed email domain"       fail test_bad_domain_email
run_test "FAIL: GitHub noreply email"           fail test_github_noreply_email

# Commit message failures
run_test "FAIL: WIP commit message"             fail test_wip_message
run_test "FAIL: fixup! commit message"          fail test_fixup_message
run_test "FAIL: DO NOT MERGE message"           fail test_do_not_merge_message
run_test "FAIL: password in commit message"     fail test_secret_in_message
run_test "FAIL: token in commit message"        fail test_token_in_message

# Diff content failures
run_test "FAIL: PRIVATE KEY literal in diff"    fail test_diff_private_key_literal
run_test "FAIL: BEGIN RSA literal in diff"      fail test_diff_rsa_key_literal
run_test "FAIL: API_KEY pattern in diff"        fail test_diff_api_key_pattern
run_test "FAIL: AWS_SECRET_ACCESS_KEY in diff"  fail test_diff_aws_secret_pattern

# Multi-check failure
run_test "FAIL: blocked email + WIP + secret in message (multi-check)" fail test_multi_failure

echo ""
echo "=========================================================="
echo "  RESULTS: ${PASS} passed, ${FAIL} unexpected"
echo "=========================================================="
