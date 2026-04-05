#!/usr/bin/env bash
# Test script: diff content scanning failures (internal URL patterns) via transparent proxy
# Uses the proxy path (/proxy/...) which runs the servlet filter chain
# Tests the diff.block.literals and diff.block.patterns config
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

    CURRENT_REPO=$(mktemp -d /tmp/proxy-test-diff-XXXX)
    cd /tmp
    git clone "${PROXY_URL}" "${CURRENT_REPO}" 2>&1
    cd "${CURRENT_REPO}"

    local branch="test/proxy-diff-$(date +%s%N | tail -c 8)"
    git checkout -b "${branch}"

    # All diff tests use a valid author/message to isolate diff scanning
    git config user.name "Test Developer"
    git config user.email "developer@example.com"

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

test_internal_hostname_literal() {
    # Matches diff.block.literals: "internal.corp.example.com"
    cat > config.yml << 'EOF'
upstream:
  api: https://internal.corp.example.com/api/v1
  timeout: 30
EOF
    git add config.yml
    git commit -m "chore: add upstream config"
}

test_internal_url_pattern() {
    # Matches diff.block.patterns: https?://...\.corp\.example\.com
    cat > deploy.sh << 'EOF'
#!/bin/bash
curl -X POST http://ci.corp.example.com/deploy \
  -d '{"version": "1.2.3"}'
EOF
    git add deploy.sh
    git commit -m "chore: add deployment script"
}

# --- Run tests ---

echo "=========================================================="
echo "  PROXY: DIFF CONTENT SCANNING FAILURES"
echo "  Proxy URL: ${PROXY_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

run_test "FAIL: internal hostname literal in diff"  test_internal_hostname_literal
run_test "FAIL: internal URL pattern in diff"       test_internal_url_pattern

echo ""
echo "=========================================================="
echo "  RESULTS: ${PASS} passed, ${FAIL} unexpected"
echo "=========================================================="
