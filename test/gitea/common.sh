#!/usr/bin/env bash
# Shared library for Gitea smoke tests.

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
resolve_gitea_pat

export GIT_REPO=${GIT_REPO:-"gitea/test-owner/test-repo.git"}

export PASS=0
export FAIL=0
CURRENT_REPO=""

cleanup() {
    if [[ -n "${CURRENT_REPO}" && -d "${CURRENT_REPO}" ]]; then
        rm -rf "${CURRENT_REPO}"
    fi
}
trap cleanup EXIT INT TERM

print_header() {
    local title="$1"
    local url="$2"
    echo "=========================================================="
    echo "  ${title}"
    echo "  URL: ${url//${GIT_PASSWORD}/***}"
    echo "=========================================================="
}

print_test_header() {
    echo ""
    echo "============================================="
    echo "  $1"
    echo "============================================="
}

print_results() {
    echo ""
    echo "=========================================================="
    echo "  RESULTS: ${PASS} passed, ${FAIL} unexpected"
    echo "=========================================================="
}

# setup_repo() — clone via proxy push path, create branch, set git config
setup_repo() {
    local url="$1"
    local prefix="$2"
    CURRENT_REPO=$(mktemp -d "${TMPDIR:-/tmp}/gitea-${prefix}-XXXX")
    cd /tmp
    git clone "${url}" "${CURRENT_REPO}"
    cd "${CURRENT_REPO}"
    BRANCH="test/${prefix}-$(date +%s%N | tail -c 8)"
    git checkout -b "${BRANCH}"
    git config user.name "Test Developer"
    git config user.email "testuser@example.com"
}

run_test_expect_failure() {
    local test_name="$1"
    shift
    print_test_header "${test_name}"
    "$@"
    local exit_code=0
    git push origin "${BRANCH}" 2>&1 || exit_code=$?
    if [[ ${exit_code} -ne 0 ]]; then
        echo ">>> ${test_name}: PASSED (push correctly rejected)"
        ((++PASS))
    else
        echo ">>> ${test_name}: UNEXPECTED (push should have been rejected)"
        ((++FAIL))
    fi
    rm -rf "${CURRENT_REPO}"
    CURRENT_REPO=""
}

run_test_expect_success() {
    local test_name="$1"
    shift
    print_test_header "${test_name}"
    "$@"
    local exit_code=0
    git push origin "${BRANCH}" 2>&1 || exit_code=$?
    if [[ ${exit_code} -eq 0 ]]; then
        echo ">>> ${test_name}: PASSED"
        ((++PASS))
    else
        echo ">>> ${test_name}: UNEXPECTED (push should have succeeded)"
        ((++FAIL))
    fi
    rm -rf "${CURRENT_REPO}"
    CURRENT_REPO=""
}

run_proxy_test_expect_success() {
    local test_name="$1"
    shift
    print_test_header "${test_name}"
    "$@"
    local output push_id approve_code exit_code=0
    output=$(git push origin "${BRANCH}" 2>&1 || true)
    echo "${output}"
    push_id=$(echo "${output}" | grep -oP '(?<=/push/)[0-9a-f-]{36}' | head -1)
    if [ -z "${push_id}" ]; then
        echo ">>> ${test_name}: UNEXPECTED (no push ID in proxy response)"
        ((++FAIL))
        rm -rf "${CURRENT_REPO}"; CURRENT_REPO=""; return
    fi
    approve_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "http://localhost:8080/api/push/${push_id}/authorise" \
        -H "Content-Type: application/json" \
        -H "X-Api-Key: ${GITPROXY_API_KEY}" \
        -d '{"user":"test-script","comment":"auto-approved"}')
    if [ "${approve_code}" != "200" ]; then
        echo ">>> ${test_name}: UNEXPECTED (approval API returned ${approve_code})"
        ((++FAIL))
        rm -rf "${CURRENT_REPO}"; CURRENT_REPO=""; return
    fi
    git push origin "${BRANCH}" 2>&1 || exit_code=$?
    if [[ ${exit_code} -eq 0 ]]; then
        echo ">>> ${test_name}: PASSED"
        ((++PASS))
    else
        echo ">>> ${test_name}: UNEXPECTED (re-push after approval failed)"
        ((++FAIL))
    fi
    rm -rf "${CURRENT_REPO}"; CURRENT_REPO=""
}

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
