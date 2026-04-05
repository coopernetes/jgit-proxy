#!/usr/bin/env bash
# Test script: pushes that must NOT be blocked by secret scanning (false-positive regression)
# Validates gitleaks git mode correctly applies path-based allowlists.
# Requires secret-scanning.enabled: true in git-proxy.yml / git-proxy-local.yml
set -uo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
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

    CURRENT_REPO=$(mktemp -d /tmp/push-test-pass-secrets-XXXX)
    cd /tmp
    git clone "${PUSH_URL}" "${CURRENT_REPO}" 2>&1
    cd "${CURRENT_REPO}"

    local branch="test/push-pass-secrets-$(date +%s%N | tail -c 8)"
    git checkout -b "${branch}"

    git config user.name "Test Developer"
    git config user.email "developer@example.com"

    "$@"

    local push_exit=0
    git push origin "${branch}" 2>&1 || push_exit=$?

    if [[ ${push_exit} -eq 0 ]]; then
        echo ">>> ${test_name}: PASSED (push correctly allowed)"
        ((PASS++))
        git remote set-url origin "http://${GIT_USERNAME}:${GIT_PASSWORD}@${GIT_REPO}" 2>/dev/null || true
        git push origin --delete "${branch}" 2>/dev/null || true
    else
        echo ">>> ${test_name}: FAILED (push incorrectly rejected — false positive)"
        ((FAIL++))
    fi

    rm -rf "${CURRENT_REPO}"
    CURRENT_REPO=""
}

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

echo "=========================================================="
echo "  STORE-AND-FORWARD: SECRET SCANNING FALSE-POSITIVE CHECK"
echo "  Push URL: ${PUSH_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

run_test "PASS: npm package-lock.json with sha512 integrity hashes"  test_package_lock_sha512
run_test "PASS: plain JS source file with no secrets"                 test_plain_text_no_secrets

echo ""
echo "=========================================================="
echo "  RESULTS: ${PASS} passed, ${FAIL} false positives"
echo "=========================================================="

[[ ${FAIL} -eq 0 ]]
