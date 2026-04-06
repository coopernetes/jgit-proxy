#!/usr/bin/env bash
# Initialize Gitea with an admin user and a test repository.
# Run this ONCE after: docker compose up -d
#
# Usage:
#   bash docker/gitea-setup.sh                     # Docker Compose v2
#   COMPOSE="podman-compose" bash docker/gitea-setup.sh
set -euo pipefail

COMPOSE="${COMPOSE:-docker compose}"
GITEA_URL="http://localhost:3000"
ADMIN_USER="gitproxyadmin"
ADMIN_PASSWORD="Admin1234!"
ADMIN_EMAIL="admin@example.com"
TEST_USER="test-user"
TEST_PASSWORD="Test1234!"
TEST_EMAIL="testuser@example.com"
TEST_ORG="test-owner"
TEST_REPO="test-repo"
TEST_ORG2="otherorg"
TEST_REPO2="other-foo"
TEST_REPO3="other-bar"

echo "==> Waiting for Gitea to be ready..."
until curl -sf "${GITEA_URL}/api/healthz" -o /dev/null 2>&1; do
    sleep 2
done
echo "    Gitea is up."

echo "==> Creating admin user '${ADMIN_USER}'..."
$COMPOSE exec gitea /sbin/su-exec git gitea admin user create \
    --admin \
    --username "${ADMIN_USER}" \
    --password "${ADMIN_PASSWORD}" \
    --email "${ADMIN_EMAIL}" \
    --must-change-password=false 2>&1 || echo "    (user already exists, continuing)"

echo "==> Creating test user '${TEST_USER}'..."
$COMPOSE exec gitea /sbin/su-exec git gitea admin user create \
    --username "${TEST_USER}" \
    --password "${TEST_PASSWORD}" \
    --email "${TEST_EMAIL}" \
    --must-change-password=false 2>&1 || echo "    (user already exists, continuing)"

echo "==> Generating user token for '${TEST_USER}'..."
_token_create_out=$("$COMPOSE exec gitea /sbin/su-exec git gitea admin user generate-access-token \
  --username testuser \
  --token-name gituse \
  --scopes read:user,write:repository")

_token=$(echo "${_token_create_out}" | grep -oP '(?<=Access token was successfully created\: )[0-9a-f-]{40}' | head -1)
if [ -z "${_token}" ]; then
    echo "ERROR: Could not extract token from output"
    exit 1
fi

echo "==> Creating organisations ('${TEST_ORG}')..."
curl -sf -X POST "${GITEA_URL}/api/v1/orgs" \
    -u "${ADMIN_USER}:${ADMIN_PASSWORD}" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${TEST_ORG}\",\"visibility\":\"public\"}" > /dev/null || true

echo "==>                        ('${TEST_ORG2}')..."
curl -sf -X POST "${GITEA_URL}/api/v1/orgs" \
    -u "${ADMIN_USER}:${ADMIN_PASSWORD}" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${TEST_ORG2}\",\"visibility\":\"public\"}" > /dev/null || true

echo "==> Creating repositories ('${TEST_ORG}/${TEST_REPO}')..."
curl -sf -X POST "${GITEA_URL}/api/v1/orgs/${TEST_ORG}/repos" \
    -u "${ADMIN_USER}:${ADMIN_PASSWORD}" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"${TEST_REPO}\",\"private\":false,\"auto_init\":true,\"default_branch\":\"main\"}" > /dev/null || true

echo "==>                       ('${TEST_ORG2}/${TEST_REPO2}')..."
curl -sf -X POST "${GITEA_URL}/api/v1/orgs/${TEST_ORG2}/repos" \
    -u "${ADMIN_USER}:${ADMIN_PASSWORD}" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"${TEST_REPO2}\",\"private\":false,\"auto_init\":true,\"default_branch\":\"main\"}" > /dev/null || true

echo "==>                       ('${TEST_ORG2}/${TEST_REPO3}')..."
curl -sf -X POST "${GITEA_URL}/api/v1/orgs/${TEST_ORG2}/repos" \
    -u "${ADMIN_USER}:${ADMIN_PASSWORD}" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"${TEST_REPO3}\",\"private\":false,\"auto_init\":true,\"default_branch\":\"main\"}" > /dev/null || true

echo ""
echo "==> Setup complete!"
echo ""
echo "    Gitea:    ${GITEA_URL}  (${ADMIN_USER} / ${ADMIN_PASSWORD})"
echo "    Test user: ${TEST_USER} / ${TEST_PASSWORD}"
echo "             (token: ${_token})"
echo ""
echo "    jgit-proxy push path:   http://localhost:8080/push/gitea/${TEST_ORG}/${TEST_REPO}.git"
echo "    jgit-proxy proxy path:  http://localhost:8080/proxy/gitea/${TEST_ORG}/${TEST_REPO}.git"
echo ""
echo "    Clone example:"
echo "      git clone http://${TEST_USER}:${_token}@localhost:8080/push/gitea/${TEST_ORG}/${TEST_REPO}.git"
