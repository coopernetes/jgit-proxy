#!/usr/bin/env bash
# Test script: diff content scanning failures (internal URL patterns) via store-and-forward
# Uses the push path (/push/...) which runs JGit ReceivePack with sideband
# Tests the diff.block.literals and diff.block.patterns config
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"

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

print_header "STORE-AND-FORWARD: DIFF CONTENT SCANNING FAILURES" "${PUSH_URL}"

# Helper for running failure tests (sets up branch for each test)
run_test() {
    local test_name="$1"
    shift
    setup_repo "${PUSH_URL}" "diff"
    "$@"
    run_test_expect_failure "${test_name}"
}

run_test "FAIL: internal hostname literal in diff"  test_internal_hostname_literal
run_test "FAIL: internal URL pattern in diff"       test_internal_url_pattern

print_results
