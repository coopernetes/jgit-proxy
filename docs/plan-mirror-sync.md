# Mirror Sync — Internal Fork Management

> **Related upstream issue:** [finos/git-proxy#1240 — Automate the sync of in-house forks with upstream repositories](https://github.com/finos/git-proxy/issues/1240)

## Problem

Enterprise users frequently maintain private mirrors of public OSS repositories so that internal collaboration, security scanning, licensing review, and code review can happen on a corporate SCM before any code is pushed back upstream through jgit-proxy. Today, keeping those internal mirrors consistent with their upstream counterparts is a manual or ad-hoc scripted task run outside of jgit-proxy. There is no standard way to:

- provision the internal mirror repo automatically,
- keep upstream refs (branches and tags) represented inside the internal copy in a predictable, non-conflicting namespace, or
- apply the existing jgit-proxy validation pipeline to changes as they ingress from upstream.

This plan describes a `MirrorSync` subsystem that automates all of the above in a provider-specific, configurable way.

---

## Proposed Interfaces

### `MirrorSyncTarget`

Represents a single upstream repository and the internal location it should be mirrored to. The interface is intentionally narrow — it owns only the repository coordinates and ref-mapping rules, not the SCM-specific API calls.

```java
package org.finos.gitproxy.mirror;

public interface MirrorSyncTarget {
    /** Upstream clone URL, e.g. https://github.com/foo/bar.git */
    String upstreamUrl();

    /** Internal push URL, e.g. https://internal-github.example.com/example-oss-org/foo-bar.git */
    String internalUrl();

    /**
     * Translate an upstream ref name before pushing internally.
     * Return the ref unchanged if no translation is required.
     * e.g. refs/heads/main → refs/heads/upstream/main
     */
    String translateRef(String upstreamRef);

    /**
     * Maximum number of refs to push in a single pack.
     * Set to Integer.MAX_VALUE to push everything in one shot.
     * Useful for large monorepos where a single git push --mirror
     * can time out the receiving server.
     */
    int refBatchSize();
}
```

### `MirrorRegistrar`

Responsible for the SCM-provider-specific side: creating the internal repository if it does not exist and resolving credentials. `MirrorRegistrar` is **not** responsible for the git protocol work itself — that belongs to `MirrorSyncService`.

```java
package org.finos.gitproxy.mirror;

public interface MirrorRegistrar {
    /**
     * Ensures the internal repository exists on the target SCM.
     * May be a no-op if auto-creation is disabled or the repo already exists.
     */
    void ensureRepository(MirrorSyncTarget target);

    /**
     * Return credentials (e.g. a bearer token or user/password) that
     * MirrorSyncService should use when pushing to target.internalUrl().
     */
    MirrorCredentials credentialsFor(MirrorSyncTarget target);
}
```

### `MirrorSyncService`

Orchestrates the actual clone → translate → push cycle. Does not know about SCM APIs — only about git.

```java
package org.finos.gitproxy.mirror;

public interface MirrorSyncService {
    /**
     * Synchronise one target: fetch all refs from upstream, translate
     * them, and push to the internal URL in batches per target.refBatchSize().
     */
    void sync(MirrorSyncTarget target, MirrorCredentials credentials) throws MirrorSyncException;
}
```

---

## GitHub / GHE Implementation

### `GitHubMirrorSyncTarget`

A value-object implementing `MirrorSyncTarget`. The ref-translation strategy is pluggable via a `RefTranslationStrategy` functional interface so it can be swapped without subclassing.

```java
public final class GitHubMirrorSyncTarget implements MirrorSyncTarget {

    private final String upstreamUrl;
    private final String internalUrl;
    private final RefTranslationStrategy refTranslation;
    private final int refBatchSize;

    // ... constructor / builder ...

    @Override
    public String translateRef(String upstreamRef) {
        return refTranslation.translate(upstreamRef);
    }
}

@FunctionalInterface
public interface RefTranslationStrategy {
    String translate(String ref);
}
```

Built-in strategies (each a static factory method on `RefTranslationStrategies`):

| Name                               | Input             | Output                     | Notes                                                                |
| ---------------------------------- | ----------------- | -------------------------- | -------------------------------------------------------------------- |
| `identity()`                       | `refs/heads/main` | `refs/heads/main`          | No rename; suitable when the internal fork has no local branches yet |
| `upstreamPrefix(String prefix)`    | `refs/heads/main` | `refs/heads/upstream/main` | Slash-prefixed namespace (common in corporate GHE setups)            |
| `upstreamSuffix(String separator)` | `refs/heads/main` | `refs/heads/upstream-main` | Hyphen-joined; avoids ambiguity with slash-delimited ref hierarchies |

The user configures exactly one strategy per target. The motivation for both slash and hyphen variants is that some corporate GHE installations interpret `refs/heads/upstream/main` as a folder path in the UI, while others reject slashes in branch names — the strategy can be chosen to match the target SCM's conventions.

### `GitHubMirrorRegistrar`

Implements `MirrorRegistrar` using the GitHub REST API (v3, compatible with GHE 3.x). Responsibilities:

- `ensureRepository`: calls `POST /orgs/{org}/repos` (or `POST /user/repos` for personal namespaces) with `private: true` and `auto_init: false`. Idempotent — treats a 422 "already exists" response as success.
- `credentialsFor`: returns a `BearerTokenCredentials` wrapping a configured PAT or GitHub App installation token. Tokens should be stored encrypted at rest (future: integrate with the credential store described in `plan-user-management.md`).

Configuration:

```yaml
git-proxy:
  mirror:
    registrar:
      type: github # github | gitlab | bitbucket | noop
      api-url: https://api.github.com # override for GHE: https://ghe.example.com/api/v3
      org: example-oss-org
      token: ${MIRROR_GITHUB_TOKEN} # PAT with repo scope
      auto-create-repos: true
```

---

## Repo Naming Convention

When `auto-create-repos: true`, the internal repo name is derived from the upstream owner and repo:

```
upstream:  https://github.com/foo/bar.git
internal:  https://<api-url-host>/<org>/foo-bar.git
```

The `{owner}-{repo}` pattern (hyphen-joined) is chosen because:

- it is unambiguous even when `owner` or `repo` contain hyphens (a double-hyphen would appear, which is visually distinct),
- it avoids GitHub's reserved character set for repo names (no slashes, no dots at start/end).

This is configurable via a `repoNameTemplate` expression. The default template is `{owner}-{repo}`.

---

## Sync Execution

### Clone and push cycle (per target)

```
1. git clone --mirror <upstreamUrl>              # or git fetch --prune if a local cache dir exists
2. for each fetched ref:
       rename via target.translateRef(ref)
3. split renamed refs into batches of target.refBatchSize()
4. for each batch:
       git push <internalUrl> <ref>... --force    # individual ref specs, not --mirror
```

Using explicit ref specs per batch instead of `--mirror` is intentional. `--mirror` would also push the `refs/pull/*` namespace and internal git state refs, which are not desirable on the mirror. Explicit ref specs give control over which namespaces are replicated.

**Batch motivation:** Large OSS monorepos can have thousands of branches and tags. Pushing all refs in a single packfile can hit server-side pack size limits or HTTP timeouts on corporate GHE instances. A default batch size of 100 refs is a safe baseline; teams with well-resourced GHE infrastructure can set it higher (or `Integer.MAX_VALUE` to disable batching).

### Local cache (optional)

`MirrorSyncService` can maintain a bare clone on local disk as a fetch cache. Subsequent syncs then only transfer deltas rather than re-cloning from scratch. The cache directory is controlled by:

```yaml
git-proxy:
  mirror:
    cache-dir: /var/lib/jgit-proxy/mirror-cache # omit to disable caching (always re-clone)
```

---

## Configuration — Full Example

```yaml
git-proxy:
  mirror:
    enabled: true
    cache-dir: /var/lib/jgit-proxy/mirror-cache

    registrar:
      type: github
      api-url: https://api.github.com
      org: example-oss-org
      token: ${MIRROR_GITHUB_TOKEN}
      auto-create-repos: true

    targets:
      - upstream-url: https://github.com/foo/bar.git
        ref-translation: upstream-prefix # refs/heads/main → refs/heads/upstream/main
        ref-batch-size: 100

      - upstream-url: https://github.com/acme/giant-monorepo.git
        ref-translation: upstream-suffix # refs/heads/main → refs/heads/upstream-main
        ref-batch-size: 50 # smaller batches for known-large repo

      - upstream-url: https://github.com/baz/lib.git
        ref-translation: identity # no renaming; internal fork has no local branches
        ref-batch-size: 200
        internal-url-override: https://ghe.internal.com/special-org/baz-lib.git
```

`internal-url-override` allows the per-target URL to bypass the global org + naming-convention derivation.

---

## Integration with the Provider Model

Each `MirrorSyncTarget` corresponds conceptually to a proxied repository entry. Once the `RepoRegistry` abstraction lands (see `plan-registry-abstraction.md`), a `MirrorSyncTarget` should be expressible as metadata on a `WhitelistEntry` so that the mirror is automatically provisioned when a new upstream repo is approved for proxying:

```
WhitelistEntry (approved repo)
  └─ optional: MirrorSyncConfig (upstream → internal mapping)
        └─ driven by: MirrorRegistrar (SCM API)
        └─ executed by: MirrorSyncService (git operations)
```

---

## TODO / Open Questions

- **Scheduling:** The sync cycle needs a trigger. Options: (a) cron-style scheduler inside jgit-proxy, (b) external call via a REST endpoint (e.g. `POST /api/mirror/sync/{target}`), or (c) webhook from the upstream SCM. Starting with (b) is easiest and keeps jgit-proxy stateless with respect to time.
- **Internal PR gate (out of scope here):** A natural follow-on is having jgit-proxy intercept an upstream-bound push and verify that the corresponding internal fork PR is approved and passes required status checks before allowing the push. This is intentionally out of scope for mirror-sync but is a direct consequence of having a mirror in place — see [`plan-internal-pr-gate.md`](plan-internal-pr-gate.md).
- **Credentials at rest:** For now, a PAT via environment variable is acceptable. Long-term, integration with a secret store (Vault, AWS Secrets Manager) is preferred. Track against `plan-user-management.md`.
- **GitLab / Bitbucket variants:** The `MirrorRegistrar` interface is designed to accommodate `GitLabMirrorRegistrar` and `BitbucketMirrorRegistrar` without changes to `MirrorSyncService`. These can be prioritised based on demand.
- **Conflict resolution:** If the internal organisation has pushed commits to a translated ref (e.g. `upstream/main`) and upstream has also advanced, a `--force` push will silently overwrite. A `--force-with-lease` strategy or an explicit divergence check should be considered for production use.
