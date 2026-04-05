#!/usr/bin/env bash
# Test script: author email validation failures via store-and-forward
# Uses the push path (/push/...) which runs JGit ReceivePack with sideband
set -uo pipefail

GIT_USERNAME=${GIT_USERNAME:-"coopernetes"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
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

    CURRENT_REPO=$(mktemp -d /tmp/push-test-author-XXXX)
    cd /tmp
    git clone "${PUSH_URL}" "${CURRENT_REPO}" 2>&1
    cd "${CURRENT_REPO}"

    local branch="test/push-author-$(date +%s%N | tail -c 8)"
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

echo "=========================================================="
echo "  STORE-AND-FORWARD: AUTHOR EMAIL VALIDATION FAILURES"
echo "  Push URL: ${PUSH_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

run_test "FAIL: noreply email local part"   test_noreply_email
run_test "FAIL: non-allowed email domain"   test_bad_domain_email
run_test "FAIL: GitHub noreply email"       test_github_noreply_email

echo ""
echo "=========================================================="
echo "  RESULTS: ${PASS} passed, ${FAIL} unexpected"
echo "=========================================================="
