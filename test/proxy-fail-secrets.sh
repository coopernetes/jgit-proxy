#!/usr/bin/env bash
# Test script: gitleaks secret scanning failures via transparent proxy
# Uses the proxy path (/proxy/...) which runs the servlet filter chain
# Requires secret-scanning.enabled: true in git-proxy.yml / git-proxy-local.yml
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

    CURRENT_REPO=$(mktemp -d /tmp/proxy-test-secrets-XXXX)
    cd /tmp
    git clone "${PROXY_URL}" "${CURRENT_REPO}" 2>&1
    cd "${CURRENT_REPO}"

    local branch="test/proxy-secrets-$(date +%s%N | tail -c 8)"
    git checkout -b "${branch}"

    # All secret tests use a valid author to isolate the secret scanning filter
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
# Each test commits a file containing a secret pattern that gitleaks detects
# using its built-in rules. All use valid author/message to isolate scanning.

test_aws_access_key() {
    # gitleaks rule: aws-access-token — detects AKIA... key IDs
    cat > aws-credentials << 'EOF'
[default]
aws_access_key_id = AKIAYVP4CIPPH3TESTKEY
aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
EOF
    git add aws-credentials
    git commit -m "chore: add deployment credentials"
}

test_github_pat() {
    # gitleaks rule: github-pat — detects ghp_ prefixed tokens
    cat > ci-config.env << 'EOF'
GITHUB_TOKEN=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef01234567
EOF
    git add ci-config.env
    git commit -m "chore: add CI environment config"
}

test_private_key_pem() {
    # gitleaks rule: private-key — detects PEM-encoded private keys
    cat > deploy-key.pem << 'PEMEOF'
-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEA2a2rwplBQLzHPtPDSEHbFljEg2kX6BASm1rOBh2cEDAYsNbh
QRFGmGeTKBPs2gJMtaFe0sIliRWAKMq6YIrJTiJNPIqUl/lBOANhwMUwl8n3tMaZ
eDIPDKTpFeJdpMcbh6MqT5QJSjMBb2F3mHB0VBqNpG0JOhRfhm3BQXQM6GQMKACH
-----END RSA PRIVATE KEY-----
PEMEOF
    git add deploy-key.pem
    git commit -m "chore: add deployment key"
}

test_generic_api_key() {
    # gitleaks rule: generic-api-key — detects high-entropy strings assigned to key-like variables
    cat > config.yml << 'EOF'
service:
  api_key: "sk_live_4eC39HqLyjWDarjtT1zdp7dc8a9b7e3f2c1d0e4f5a6b7c8d9e0f1a2b3c4d5e6"
  endpoint: https://api.example.com
EOF
    git add config.yml
    git commit -m "chore: add service configuration"
}

test_slack_webhook() {
    # gitleaks rule: slack-webhook-url — detects Slack incoming webhook URLs
    cat > notifications.yml << 'EOF'
slack:
  webhook_url: "https://hooks.slack.com/services/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"
  channel: "#alerts"
EOF
    git add notifications.yml
    git commit -m "chore: add notification config"
}

# --- Run tests ---

echo "=========================================================="
echo "  PROXY: GITLEAKS SECRET SCANNING FAILURES"
echo "  Proxy URL: ${PROXY_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

run_test "FAIL: AWS access key in diff"         test_aws_access_key
run_test "FAIL: GitHub PAT in diff"             test_github_pat
run_test "FAIL: RSA private key in diff"        test_private_key_pem
run_test "FAIL: Generic API key in diff"        test_generic_api_key
run_test "FAIL: Slack webhook URL in diff"      test_slack_webhook

echo ""
echo "=========================================================="
echo "  RESULTS: ${PASS} passed, ${FAIL} unexpected"
echo "=========================================================="
