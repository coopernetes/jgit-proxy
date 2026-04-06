#!/usr/bin/env bash
# Test fetch via store-and-forward path (/push/).
#
# The /push/ path uses JGit's GitServlet with StoreAndForwardRepositoryResolver,
# which syncs the repo from upstream then serves it locally via UploadPack.
# There is no filter chain on this path — no whitelist, no content validation.
set -euo pipefail

GIT_USERNAME=${GIT_USERNAME:-"me"}
GIT_REPO=${GIT_REPO:-"github.com/coopernetes/test-repo.git"}
PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"
REPO_DIR=$(mktemp -d /tmp/push-test-fetch-XXXX)

cleanup() { rm -rf "${REPO_DIR}"; }
trap cleanup EXIT INT TERM

echo "=========================================================="
echo "  STORE-AND-FORWARD: FETCH TEST"
echo "  URL: ${PUSH_URL//${GIT_PASSWORD}/***}"
echo "=========================================================="

git clone --depth 1 "${PUSH_URL}" "${REPO_DIR}"

echo ""
echo "PASSED"
