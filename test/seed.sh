#!/usr/bin/env bash
# Seed script — runs all proxy-mode smoke tests to populate the dashboard with
# representative push records (passes, failures, identity scenarios).
#
# Required PATs (env var or ~/.xxx-pat file):
#   GITHUB_PAT   — github.com/coopernetes/test-repo.git
#   GITLAB_PAT   — gitlab.com/coopernetes/test-repo-gitlab.git
#   CODEBERG_PAT — codeberg.org/coopernetes/test-repo-codeberg.git
#
# Optional:
#   GITPROXY_API_KEY  (default: foobarbaz)
#   PROXY_HOST        (default: localhost:8080)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GITPROXY_API_KEY="${GITPROXY_API_KEY:-change-me-in-production}"
PROXY_HOST="${PROXY_HOST:-localhost:8080}"

# ── PAT resolution ───────────────────────────────────────────────────────────

read_pat() {
    local var="$1" file="$2"
    local val="${!var:-}"
    if [ -z "${val}" ] && [ -f "${file}" ]; then
        val="$(cat "${file}")"
    fi
    if [ -z "${val}" ]; then
        echo "ERROR: ${var} is not set and ${file} does not exist" >&2
        exit 1
    fi
    echo "${val}"
}

GITHUB_PAT="$(read_pat GITHUB_PAT ~/.github-pat)"
GITLAB_PAT="$(read_pat GITLAB_PAT ~/.gitlab-pat)"
CODEBERG_PAT="$(read_pat CODEBERG_PAT ~/.codeberg-pat)"

# ── Helpers ──────────────────────────────────────────────────────────────────

PASS=0
FAIL=0

run() {
    local name="$1"; shift
    echo ""
    echo "━━━ ${name} ━━━"
    if "$@"; then
        echo "✓  ${name}"
        ((++PASS))
    else
        echo "✗  ${name}"
        ((++FAIL))
    fi
}

# ── Tests ────────────────────────────────────────────────────────────────────

# ── Store-and-forward failures (sideband rejection, no approval needed) ──────

# 1. Author email validation failure
run "push-fail-author" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITHUB_PAT}" \
        GIT_REPO="github.com/coopernetes/test-repo.git" \
        bash "${SCRIPT_DIR}/push-fail-author.sh"

# 2. Commit message validation failure
run "push-fail-message" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITHUB_PAT}" \
        GIT_REPO="github.com/coopernetes/test-repo.git" \
        bash "${SCRIPT_DIR}/push-fail-message.sh"

# 3. Secret scanning failure
run "push-fail-secrets" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITHUB_PAT}" \
        GIT_REPO="github.com/coopernetes/test-repo.git" \
        bash "${SCRIPT_DIR}/push-fail-secrets.sh"

# 4. Diff content failure
run "push-fail-diff" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITHUB_PAT}" \
        GIT_REPO="github.com/coopernetes/test-repo.git" \
        bash "${SCRIPT_DIR}/push-fail-diff.sh"

# ── Transparent proxy passes (commit + approve + re-push) ────────────────────

# 1. Golden-path pass (commit + approve + re-push)
run "proxy-pass" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITHUB_PAT}" \
        GIT_REPO="github.com/coopernetes/test-repo.git" \
        GITPROXY_API_KEY="${GITPROXY_API_KEY}" \
        bash "${SCRIPT_DIR}/proxy-pass.sh"

# 2. Tag push (lightweight + annotated)
run "proxy-pass-tag" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITHUB_PAT}" \
        GIT_REPO="github.com/coopernetes/test-repo.git" \
        GITPROXY_API_KEY="${GITPROXY_API_KEY}" \
        bash "${SCRIPT_DIR}/proxy-pass-tag.sh"

# 3. Combined validation failures (author email / commit msg / secrets / diff content)
run "proxy-fail-all" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITHUB_PAT}" \
        GIT_REPO="github.com/coopernetes/test-repo.git" \
        bash "${SCRIPT_DIR}/proxy-fail-all.sh"

# 4. Identity: GitHub — fully resolved
run "proxy-identity-github" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITHUB_PAT}" \
        GIT_REPO="github.com/coopernetes/test-repo.git" \
        GITPROXY_API_KEY="${GITPROXY_API_KEY}" \
        bash "${SCRIPT_DIR}/proxy-identity-github.sh"

# 5. Identity: GitLab — resolved, email warning
run "proxy-identity-gitlab" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${GITLAB_PAT}" \
        GIT_REPO="gitlab.com/coopernetes/test-repo-gitlab.git" \
        GITPROXY_API_KEY="${GITPROXY_API_KEY}" \
        bash "${SCRIPT_DIR}/proxy-identity-gitlab.sh"

# 6. Identity: Codeberg — unresolved, hard block
run "proxy-identity-codeberg" \
    env GIT_USERNAME="me" \
        GIT_PASSWORD="${CODEBERG_PAT}" \
        GIT_REPO="codeberg.org/coopernetes/test-repo-codeberg.git" \
        bash "${SCRIPT_DIR}/proxy-identity-codeberg.sh"

# ── Summary ──────────────────────────────────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Results: ${PASS} passed, ${FAIL} failed"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
[ "${FAIL}" -eq 0 ]
