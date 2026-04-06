#!/usr/bin/env bash
# Spin up jgit-proxy + Gitea + MongoDB, run the full test/... suite, then tear down.
#
# Usage:
#   bash test/run-mongo.sh               # build, test, tear down
#   bash test/run-mongo.sh --no-teardown # leave the environment running afterwards
set -euo pipefail

NO_TEARDOWN=false
for arg in "$@"; do
    case "$arg" in
        --no-teardown) NO_TEARDOWN=true ;;
        *) echo "Unknown argument: $arg"; exit 1 ;;
    esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="docker compose --profile mongo -f ${REPO_ROOT}/docker-compose.yml -f ${REPO_ROOT}/docker-compose.mongo.yml"

# Credentials set by docker/gitea-setup.sh
ADMIN_USER="gitproxyadmin"
ADMIN_PASS="Admin1234!"
TEST_ORG="test-owner"
TEST_REPO="test-repo"

teardown() {
    if [ "$NO_TEARDOWN" = "true" ]; then
        echo ""
        echo "==> --no-teardown: environment left running."
        echo "    GIT_USERNAME=${ADMIN_USER}  GIT_PASSWORD=${ADMIN_PASS}"
        echo "    GIT_REPO=gitea/${TEST_ORG}/${TEST_REPO}.git  GITPROXY_API_KEY=change-me-in-production"
        echo "    Stop with: $COMPOSE down -v"
    else
        echo "==> Tearing down..."
        $COMPOSE down -v
    fi
}
trap teardown EXIT

echo "==> Cleaning up any previous run..."
$COMPOSE down -v --remove-orphans 2>/dev/null || true

echo "==> Building image..."
$COMPOSE build jgit-proxy

echo "==> Starting services..."
$COMPOSE up -d

echo "==> Running Gitea setup..."
bash "${REPO_ROOT}/docker/gitea-setup.sh"

echo "==> Waiting for jgit-proxy to be healthy..."
for i in $(seq 1 30); do
    if curl -sf "http://localhost:8080/login.html" -o /dev/null 2>&1; then
        echo "    jgit-proxy is up."
        break
    fi
    [ "$i" -eq 30 ] && { echo "ERROR: jgit-proxy did not become healthy"; exit 1; }
    sleep 3
done

# Env vars consumed by all test/*.sh scripts
export GIT_USERNAME="$ADMIN_USER"
export GIT_PASSWORD="$ADMIN_PASS"
export GIT_REPO="gitea/${TEST_ORG}/${TEST_REPO}.git"
export GITPROXY_API_KEY="change-me-in-production"

# push-pass* scripts expect store-and-forward to forward immediately, but the dashboard
# always uses UiApprovalGateway so they would hang waiting for approval. Skip them here.
SKIP_PATTERN="push-pass"

echo ""
echo "========================================================"
echo "  Running test suite (MongoDB backend)"
echo "========================================================"

PASS=0
FAIL=0
SKIPPED=0

for script in "${REPO_ROOT}"/test/*.sh; do
    name="$(basename "$script")"
    [[ "$name" == run-*.sh ]] && continue
    if [[ "$name" == *${SKIP_PATTERN}* ]]; then
        echo ""
        echo "  SKIP: $name  (S&F pass scripts require non-dashboard server)"
        ((++SKIPPED))
        continue
    fi

    echo ""
    echo "--------------------------------------------------------"
    echo "  $name"
    echo "--------------------------------------------------------"
    if bash "$script"; then
        echo ">>> PASSED: $name"
        ((++PASS))
    else
        echo ">>> FAILED: $name"
        ((++FAIL))
    fi
done

echo ""
echo "========================================================"
echo "  Suite done: ${PASS} passed, ${FAIL} failed, ${SKIPPED} skipped"
echo "========================================================"

echo ""
echo "==> Verifying push records exist in MongoDB..."
PUSHES=$(curl -sf -H "x-api-key: change-me-in-production" "http://localhost:8080/api/push")
COUNT=$(echo "$PUSHES" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
echo "    Push records in DB: $COUNT"
if [ "$COUNT" -eq 0 ]; then
    echo "ERROR: No push records found — DB write may have failed."
    exit 1
fi
echo "    DB check PASSED"

[ "$FAIL" -gt 0 ] && exit 1
exit 0
