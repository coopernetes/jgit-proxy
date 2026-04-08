#!/usr/bin/env bash
# Test script: pushes that must NOT be blocked by secret scanning (false-positive regression)
# Validates gitleaks git mode correctly applies path-based allowlists.
# Requires secret-scanning.enabled: true in git-proxy.yml / git-proxy-local.yml
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"

# --- Test functions ---

test_package_lock_sha512() {
    # Regression: npm package-lock.json integrity hashes (sha512-...) must not
    # be flagged as secrets. gitleaks --pipe mode had no file-path context so
    # the generic-api-key rule fired on these high-entropy strings.
    # gitleaks git mode applies the built-in allowlist for package-lock.json.
    cat > package-lock.json << 'EOF'
{
  "name": "my-app",
  "version": "1.0.0",
  "lockfileVersion": 3,
  "packages": {
    "node_modules/express": {
      "version": "4.18.2",
      "integrity": "sha512-af0I6gWRBdJBHK6YiGaFOI3bLfVdXbSxOxEWujq8hWqAFfpkUrRaU5F2tNF7Jq+4P3c6jSNGCZzfOvEMgGOA==",
      "dependencies": {
        "accepts": "~1.3.8"
      }
    },
    "node_modules/accepts": {
      "version": "1.3.8",
      "integrity": "sha512-PkABbGgSPDjJRLNsvkEMnSR2CExVpqBWBYk7SRnIFGFKHAQiIqcqBCxpkFVfJLzmpE3dMb9RKX35lx2Hn2EA==",
      "dependencies": {}
    },
    "node_modules/lodash": {
      "version": "4.17.21",
      "integrity": "sha512-v2kDEe57lecTulaDIuNTPy3Ry4gLGJ6Z1O3vE1krgXZNrsQ+LFTGHVxVjcXPs17LhbZngButEoYWwW3crJFaA=="
    }
  }
}
EOF
    git add package-lock.json
    git commit -m "chore: add package-lock.json"
}

test_plain_text_no_secrets() {
    # Baseline: a normal source file with no secrets should always pass.
    mkdir -p src
    cat > src/index.js << 'EOF'
const express = require('express');
const app = express();

app.get('/', (req, res) => {
  res.send('Hello World');
});

app.listen(3000);
EOF
    git add src/index.js
    git commit -m "feat: add hello world server"
}

# --- Run tests ---

print_header "STORE-AND-FORWARD: SECRET SCANNING FALSE-POSITIVE CHECK" "${PUSH_URL}"

# Helper for running success tests that clean up after themselves
run_test() {
    local test_name="$1"
    shift
    setup_repo "${PUSH_URL}" "pass-secrets"
    "$@"
    run_test_expect_success "${test_name}"

    # Cleanup remote ref (run_test_expect_success doesn't do this)
    git remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
    git push origin --delete "${BRANCH}" 2>/dev/null || true
}

run_test "PASS: npm package-lock.json with sha512 integrity hashes"  test_package_lock_sha512
run_test "PASS: plain JS source file with no secrets"                 test_plain_text_no_secrets

print_results

[[ ${FAIL} -eq 0 ]]
