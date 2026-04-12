#!/usr/bin/env bash
# Shared library for test scripts
# Source this file in test scripts to get common functions and setup

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_pat ~/.github-pat
export GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}

# Test counters (exported so they survive subshells)
export PASS=0
export FAIL=0

# Current working repo for cleanup
CURRENT_REPO=""

# Cleanup function — removes temp repo if it exists
cleanup() {
    if [[ -n "${CURRENT_REPO}" && -d "${CURRENT_REPO}" ]]; then
        safe_rm_rf "${CURRENT_REPO}"
    fi
}

# Register cleanup on exit and signals
trap cleanup EXIT INT TERM

# print_header() — print a test category header
print_header() {
    local title="$1"
    local url="$2"
    echo "=========================================================="
    echo "  ${title}"
    echo "  URL: ${url//${GIT_PASSWORD}/***}"
    echo "=========================================================="
}

# print_result_header() — print a test case header
print_test_header() {
    local name="$1"
    echo ""
    echo "============================================="
    echo "  ${name}"
    echo "============================================="
}

# print_results() — print final test summary
print_results() {
    echo ""
    echo "=========================================================="
    echo "  RESULTS: ${PASS} passed, ${FAIL} unexpected"
    echo "=========================================================="
}

# setup_repo() — clone repo, create branch, set git config
# Args: $1 = URL, $2 = branch prefix (used for mktemp dir too)
# Sets CURRENT_REPO and BRANCH globals; cd's into the cloned repo
setup_repo() {
    local url="$1"
    local prefix="$2"

    CURRENT_REPO=$(mktemp -d "${TMPDIR:-/tmp}/test-${prefix}-XXXX")
    cd /tmp
    git clone "${url}" "${CURRENT_REPO}"
    cd "${CURRENT_REPO}"

    BRANCH="test/${prefix}-$(date +%s%N | tail -c 8)"
    git checkout -b "${BRANCH}"
    git config user.name "${GIT_AUTHOR_NAME}"
    git config user.email "${GIT_EMAIL}"
}

# run_test_expect_failure() — run a test and expect it to fail (for failure test suites)
# Args: $1 = test name, $2 = test function to call
run_test_expect_failure() {
    local test_name="$1"
    shift

    print_test_header "${test_name}"

    "$@"

    local push_exit=0
    git push origin "${BRANCH}" 2>&1 || push_exit=$?

    if [[ ${push_exit} -ne 0 ]]; then
        echo ">>> ${test_name}: PASSED (push correctly rejected)"
        ((++PASS))
    else
        echo ">>> ${test_name}: UNEXPECTED (push should have been rejected)"
        ((++FAIL))
    fi

    safe_rm_rf "${CURRENT_REPO}"
    CURRENT_REPO=""
}

# run_test_expect_success() — run a test and expect it to succeed (for pass test suites)
# Args: $1 = test name, $2+ = test function to call
run_test_expect_success() {
    local test_name="$1"
    shift

    print_test_header "${test_name}"

    "$@"

    local push_exit=0
    git push origin "${BRANCH}" 2>&1 || push_exit=$?

    if [[ ${push_exit} -eq 0 ]]; then
        echo ">>> ${test_name}: PASSED (push succeeded as expected)"
        ((++PASS))
    else
        echo ">>> ${test_name}: UNEXPECTED (push should have succeeded)"
        ((++FAIL))
    fi

    safe_rm_rf "${CURRENT_REPO}"
    CURRENT_REPO=""
}

# run_orchestrated() — run a test script and aggregate results
# Used by *-all.sh scripts to orchestrate multiple subscripts
# Args: $1 = test name, $2 = script path
run_orchestrated() {
    local name="$1"
    local script="$2"
    echo ""
    echo "━━━ ${name} ━━━"
    if bash "${script}"; then
        echo "✓ ${name}"
        ((++PASS))
    else
        echo "✗ ${name}"
        ((++FAIL))
    fi
}
