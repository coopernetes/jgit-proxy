#!/usr/bin/env bash
# Commit message validation failures via Gitea store-and-forward.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

PUSH_URL="http://${GIT_USERNAME}:${GIT_PASSWORD}@localhost:8080/push/${GIT_REPO}"

test_wip_message() {
    echo "wip - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "WIP: still working on this"
}

test_fixup_message() {
    echo "fixup - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "fixup! previous commit"
}

test_do_not_merge() {
    echo "dnm - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "DO NOT MERGE - experimental"
}

test_secret_in_message() {
    echo "secret - $(date)" >> test-file.txt
    git add test-file.txt
    git commit -m "fix: rotate password=hunter2 in config"
}

print_header "STORE-AND-FORWARD: COMMIT MESSAGE FAILURES" "${PUSH_URL}"

run_test() {
    local test_name="$1"; shift
    setup_repo "${PUSH_URL}" "gitea-msg"
    "$@"
    run_test_expect_failure "${test_name}"
}

run_test "FAIL: WIP message"            test_wip_message
run_test "FAIL: fixup! message"         test_fixup_message
run_test "FAIL: DO NOT MERGE message"   test_do_not_merge
run_test "FAIL: secret in message"      test_secret_in_message

print_results
[[ ${FAIL} -eq 0 ]]
