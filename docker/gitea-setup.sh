#!/usr/bin/env bash
# Initialize Gitea with admin, test users, orgs, and repos for smoke testing.
# Run this ONCE after: docker compose up -d
#
# Tokens are written to test/gitea/tokens.env (gitignored) so test scripts
# can source them without embedding secrets in the repo.
#
# Usage:
#   bash docker/gitea-setup.sh                     # Docker Compose v2
#   COMPOSE="podman-compose" bash docker/gitea-setup.sh
set -euo pipefail

COMPOSE="${COMPOSE:-docker compose}"
# Always pass the compose file explicitly so podman-compose finds it when the
# script is run from any directory (podman-compose doesn't auto-discover like
# Docker Compose v2 does).
COMPOSE_FILE="$(dirname "${BASH_SOURCE[0]}")/../docker-compose.yml"
GITEA_URL="http://localhost:3000"

# Gitea admin — owns orgs/repos; NOT mapped in jgit-proxy (tests identity-not-linked)
ADMIN_USER="gitproxyadmin"
ADMIN_PASSWORD="Admin1234!"
ADMIN_EMAIL="admin@example.com"

# Test users — each mapped to a distinct proxy user for permission testing:
#
#   test-user  →  proxy:test-user  →  LITERAL permission on /test-owner/test-repo
#   user2      →  proxy:user2      →  GLOB    permission on /otherorg/*
#   user3      →  proxy:user3      →  REGEX   permission on /test-owner/test-repo.*
#
TEST_USER="test-user"
TEST_USER_EMAIL="testuser@example.com"
USER2="user2"
USER2_EMAIL="user2@example.com"
USER3="user3"
USER3_EMAIL="user3@example.com"
USER_PASSWORD="Test1234!"

# Repos
ORG1="test-owner"
REPO1="test-repo"      # test-user (literal) and user3 (regex) can push
REPO2="test-repo-2"    # user3 (regex) can push; test-user cannot (literal too narrow)
ORG2="otherorg"
REPO3="other-foo"      # user2 (glob) can push
REPO4="other-bar"      # user2 (glob) can push

TOKENS_FILE="$(dirname "${BASH_SOURCE[0]}")/../test/gitea/tokens.env"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

gitea_api() {
    curl -sf -u "${ADMIN_USER}:${ADMIN_PASSWORD}" \
        -H "Content-Type: application/json" \
        "${GITEA_URL}/api/v1/$@"
}

create_user() {
    local username="$1" password="$2" email="$3"
    echo "==> Creating user '${username}'..."
    $COMPOSE -f "${COMPOSE_FILE}" exec gitea gitea admin user create \
        --username "${username}" \
        --password "${password}" \
        --email "${email}" \
        --must-change-password=false 2>&1 || echo "    (user already exists, continuing)"
}

generate_token() {
    local username="$1" token_name="$2"
    local out
    out=$($COMPOSE -f "${COMPOSE_FILE}" exec gitea gitea admin user generate-access-token \
        --username "${username}" \
        --token-name "${token_name}" \
        --scopes "read:user,write:repository" 2>&1) || true
    # Output format: "Access token was successfully created: <token>"
    echo "${out}" | grep -oE '[0-9a-f]{40}' | head -1
}

create_org() {
    local org="$1"
    echo "==> Creating org '${org}'..."
    gitea_api orgs \
        -X POST -d "{\"username\":\"${org}\",\"visibility\":\"public\"}" > /dev/null || true
}

create_repo() {
    local org="$1" repo="$2"
    echo "==> Creating repo '${org}/${repo}'..."
    gitea_api "orgs/${org}/repos" \
        -X POST -d "{\"name\":\"${repo}\",\"private\":false,\"auto_init\":true,\"default_branch\":\"main\"}" > /dev/null || true
}

add_collaborator() {
    local owner="$1" repo="$2" user="$3"
    gitea_api "repos/${owner}/${repo}/collaborators/${user}" \
        -X PUT -d '{"permission":"write"}' > /dev/null || true
}

# ---------------------------------------------------------------------------
# Wait for Gitea
# ---------------------------------------------------------------------------

echo "==> Waiting for Gitea to be ready..."
until curl -sf "${GITEA_URL}/api/healthz" -o /dev/null 2>&1; do sleep 2; done
echo "    Gitea is up."

# ---------------------------------------------------------------------------
# Users
# ---------------------------------------------------------------------------

echo "==> Creating admin user '${ADMIN_USER}'..."
$COMPOSE -f "${COMPOSE_FILE}" exec gitea gitea admin user create \
    --admin \
    --username "${ADMIN_USER}" \
    --password "${ADMIN_PASSWORD}" \
    --email "${ADMIN_EMAIL}" \
    --must-change-password=false 2>&1 || echo "    (user already exists, continuing)"

create_user "${TEST_USER}" "${USER_PASSWORD}" "${TEST_USER_EMAIL}"
create_user "${USER2}"     "${USER_PASSWORD}" "${USER2_EMAIL}"
create_user "${USER3}"     "${USER_PASSWORD}" "${USER3_EMAIL}"

# ---------------------------------------------------------------------------
# Tokens
# ---------------------------------------------------------------------------

echo "==> Generating tokens..."
TOKEN_TESTUSER=$(generate_token "${TEST_USER}" "gitproxy-smoke")
TOKEN_USER2=$(generate_token "${USER2}" "gitproxy-smoke")
TOKEN_USER3=$(generate_token "${USER3}" "gitproxy-smoke")
TOKEN_ADMIN=$(generate_token "${ADMIN_USER}" "gitproxy-smoke")

for var in TOKEN_TESTUSER TOKEN_USER2 TOKEN_USER3 TOKEN_ADMIN; do
    eval "val=\$$var"
    if [ -z "${val}" ]; then
        echo "WARNING: could not extract token for ${var} (may already exist — delete and re-run to regenerate)"
    fi
done

# ---------------------------------------------------------------------------
# Orgs and repos
# ---------------------------------------------------------------------------

create_org "${ORG1}"
create_org "${ORG2}"

create_repo "${ORG1}" "${REPO1}"
create_repo "${ORG1}" "${REPO2}"
create_repo "${ORG2}" "${REPO3}"
create_repo "${ORG2}" "${REPO4}"

# ---------------------------------------------------------------------------
# Collaborator access — each user gets write on the repos their permissions cover
# ---------------------------------------------------------------------------

echo "==> Granting collaborator access..."
# test-user: literal permission on /test-owner/test-repo only
add_collaborator "${ORG1}" "${REPO1}" "${TEST_USER}"

# user2: glob on /otherorg/* — needs write on both otherorg repos
add_collaborator "${ORG2}" "${REPO3}" "${USER2}"
add_collaborator "${ORG2}" "${REPO4}" "${USER2}"

# user3: regex /test-owner/test-repo.* — matches repo1 and repo2
add_collaborator "${ORG1}" "${REPO1}" "${USER3}"
add_collaborator "${ORG1}" "${REPO2}" "${USER3}"

# ---------------------------------------------------------------------------
# Write tokens file
# ---------------------------------------------------------------------------

mkdir -p "$(dirname "${TOKENS_FILE}")"
cat > "${TOKENS_FILE}" <<EOF
# Generated by docker/gitea-setup.sh — do not commit (gitignored)
# Source this file before running Gitea smoke tests:
#   source test/gitea/tokens.env
#
# test-user  → proxy:test-user  → LITERAL /test-owner/test-repo (PUSH)
# user2      → proxy:user2      → GLOB    /otherorg/*            (PUSH)
# user3      → proxy:user3      → REGEX   /test-owner/test-repo.*(PUSH)
# gitproxyadmin → no proxy mapping → identity-not-linked test
export GITEA_TESTUSER_TOKEN="${TOKEN_TESTUSER}"
export GITEA_USER2_TOKEN="${TOKEN_USER2}"
export GITEA_USER3_TOKEN="${TOKEN_USER3}"
export GITEA_ADMIN_TOKEN="${TOKEN_ADMIN}"
EOF

echo "==> Tokens written to ${TOKENS_FILE}"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "==> Setup complete!"
echo ""
echo "    Gitea UI:  ${GITEA_URL}  (${ADMIN_USER} / ${ADMIN_PASSWORD})"
echo ""
echo "    Users:"
printf "      %-16s  password: %s\n" "${TEST_USER}" "${USER_PASSWORD}"
printf "      %-16s  password: %s\n" "${USER2}"     "${USER_PASSWORD}"
printf "      %-16s  password: %s\n" "${USER3}"     "${USER_PASSWORD}"
echo ""
echo "    Repos:"
echo "      ${ORG1}/${REPO1}   — test-user (LITERAL) + user3 (REGEX)"
echo "      ${ORG1}/${REPO2}   — user3 (REGEX) only"
echo "      ${ORG2}/${REPO3}     — user2 (GLOB)"
echo "      ${ORG2}/${REPO4}     — user2 (GLOB)"
echo ""
echo "    Tokens written to: ${TOKENS_FILE}"
echo "    Source before running smoke tests:"
echo "      source test/gitea/tokens.env"
echo ""
echo "    jgit-proxy push path:  http://localhost:8080/push/gitea/${ORG1}/${REPO1}.git"
echo "    jgit-proxy proxy path: http://localhost:8080/proxy/gitea/${ORG1}/${REPO1}.git"
