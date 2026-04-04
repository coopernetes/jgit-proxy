# Internal PR Gate — Push Interception via Internal Fork Status Checks

> **Depends on / related to:** [`plan-mirror-sync.md`](plan-mirror-sync.md) — the internal fork must exist before this gate can do anything useful.

## Problem

Once an internal mirror of an upstream OSS repository exists (see `plan-mirror-sync.md`), teams naturally want to do collaborative work — code review, security scanning, licence compliance — on that internal fork _before_ the changes are pushed upstream through jgit-proxy. Today jgit-proxy has no way to verify that an internal review has happened and passed; it only validates the push itself (author email, commit message, GPG signature, etc.).

What is missing is a **push-time gate**: when a contributor pushes to jgit-proxy targeting an upstream repository, jgit-proxy should be able to look up the corresponding internal fork, find the PR that represents those changes, and block the push if the PR is not yet approved or if required status checks have not passed.

This is the pattern that git-proxy was extended with at some organisations — effectively a `GitHubPRStatusCheckFilter` that bridges jgit-proxy's push interception with the internal SCM's review workflow. The dashboard does not need to be the code-review platform; the internal SCM already is.

---

## Proposed Design

### `InternalPRGateCheck`

A new interface in `org.finos.gitproxy.git` (for the store-and-forward pre-receive hook path) and `org.finos.gitproxy.servlet.filter` (for the transparent-proxy filter path). Because the two execution paths have different signing contracts (hooks stream messages; filters accumulate a single response), the check logic itself is extracted into a shared service and thin adapters wrap it for each path.

```java
package org.finos.gitproxy.mirror.gate;

/**
 * Checks whether an internal SCM review is complete for the commits
 * being pushed through jgit-proxy.
 */
public interface InternalPRGateCheck {

    /**
     * @param push  details of the incoming push (destination URL, branch, commit SHAs)
     * @return      result describing whether the gate passed and, if not, why
     */
    PRGateResult check(PushDetails push);
}
```

```java
public record PRGateResult(boolean passed, String reason) {
    public static PRGateResult pass() { return new PRGateResult(true, null); }
    public static PRGateResult fail(String reason) { return new PRGateResult(false, reason); }
}
```

### Store-and-forward adapter: `InternalPRGateHook`

Implements `PreReceiveHook`. Calls `InternalPRGateCheck.check()` and, on failure, calls `rp.sendMessage()` with a human-readable explanation before rejecting the push. Streams the result live to the git client.

```
remote: [jgit-proxy] Internal PR gate: BLOCKED
remote: [jgit-proxy] No approved PR found for branch 'feature/my-change' in
remote: [jgit-proxy] internal fork example-oss-org/foo-bar.
remote: [jgit-proxy] Open a PR at https://ghe.internal.com/example-oss-org/foo-bar
remote: [jgit-proxy] and obtain required approvals before pushing upstream.
```

### Transparent-proxy adapter: `InternalPRGateFilter`

Implements `GitProxyFilter` at a low order (e.g. order 200, after `EnrichPushCommitsFilter` which resolves the commits but before heavier diff scanning). Accumulates the gate result and, on failure, calls `sendGitError` as per the existing filter contract.

---

## GitHub / GHE Implementation: `GitHubPRGateCheck`

Implements `InternalPRGateCheck` against the GitHub REST API (v3, compatible with GHE 3.x).

### Logic

1. **Resolve the internal repo.** Derive the internal fork coordinates from the push destination URL using the same naming convention as `GitHubMirrorRegistrar` (or a configured `internal-url-override`).
2. **Find the PR.** Query `GET /repos/{owner}/{repo}/pulls?head={org}:{branch}&state=open`. If no open PR is found, the gate fails immediately.
3. **Check approvals.** Query `GET /repos/{owner}/{repo}/pulls/{pull_number}/reviews`. Count reviews with `state: APPROVED`. Fail if the count is below the configured minimum (default: 1).
4. **Check required status checks.** Query `GET /repos/{owner}/{repo}/commits/{sha}/check-runs`. Inspect only check runs whose `app.slug` matches the configured `required-app-slug` (the internal GitHub App). Fail if any check run is not in `completed` state with `conclusion: success`.

All four lookups can be parallelised; the gate result is the conjunction of steps 2–4.

### Configuration

```yaml
git-proxy:
  mirror:
    pr-gate:
      enabled: true
      registrar-ref: github # reuse the MirrorRegistrar config block
      min-approvals: 1
      required-app-slug: my-internal-compliance-app # GitHub App whose checks must pass
      block-if-no-pr: true # fail the push if no open PR exists at all
      block-if-app-not-installed: false # if the App is not installed, warn but don't block
```

---

## Why This Is Better Than Dashboard-Only Review

jgit-proxy's built-in dashboard provides a diff view and an approval button, but it is not a full code-review platform. Internal SCMs (GHE, GitLab EE, Bitbucket Data Center) already have:

- structured PR review workflows with reviewer assignment and required approvals,
- CI/CD pipelines and status checks (SAST, SCA, licence scanning, secret detection),
- audit trails that satisfy compliance requirements without additional tooling,
- comment threads and review history that inform the contributor and the security team simultaneously.

By delegating the review gate to the internal SCM, jgit-proxy becomes a thin enforcement layer rather than a competing review platform. Any internal GitHub App or CI system can participate in the gate simply by posting a check run to the internal fork PR. This is significantly more extensible than adding new scanner integrations directly to jgit-proxy's filter chain.

---

## Relationship to the Hook / Filter Model

This feature slots naturally into jgit-proxy's existing extensibility model:

- **Store-and-forward**: `InternalPRGateHook` is inserted into the pre-receive hook chain (e.g. before `ValidationVerifierHook`). No changes to the hook chain contract are required.
- **Transparent proxy**: `InternalPRGateFilter` implements `GitProxyFilter` at a configurable order. No changes to the filter chain contract are required.

Custom deployments can subclass or compose `GitHubPRGateCheck` if they need non-standard lookup logic (e.g. matching PRs by commit SHA range rather than branch name, or consulting a second internal SCM in addition to GHE).

---

## Out of Scope

- **Creating or updating the internal PR.** The gate only reads PR state; it does not push to or manipulate the internal fork. That is covered by `plan-mirror-sync.md`.
- **Scheduling or triggering mirror syncs from within the gate.** If the internal fork is stale, the gate will fail and the contributor should wait for the next sync cycle (or trigger one manually).
- **Dashboard integration.** The existing approval flow in `jgit-proxy-dashboard` is unaffected. The PR gate is an additional pre-condition, not a replacement.

---

## TODO / Open Questions

- **Branch name mapping.** The push destination branch (e.g. `main`) may not map 1:1 to the internal PR head branch if the contributor worked on a feature branch. A configurable lookup strategy (by branch name, by commit SHA, or by a custom PR label) is needed.
- **Multiple internal forks.** An upstream repo could be mirrored to more than one internal org. Decide whether all mirrors must have an approved PR, or just one.
- **Caching API responses.** GitHub API rate limits are 5000 req/h for PATs. For high-traffic jgit-proxy instances a short TTL cache (10–30 s) on PR and check-run results is advisable.
- **GitLab / Bitbucket variants.** The `InternalPRGateCheck` interface is provider-agnostic. `GitLabPRGateCheck` (merge requests + pipelines) and `BitbucketPRGateCheck` (pull requests + build statuses) can be added without touching the adapter layer.
