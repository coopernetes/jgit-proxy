# Secret scanning: migrating both proxy modes to `gitleaks git`

## Problem

The current `--pipe` (stdin) approach feeds a raw unified diff to gitleaks.
Gitleaks has no file-path context in this mode, so path-based allowlists in its
built-in ruleset never fire — rules that would normally skip
`**/package-lock.json` (e.g. to suppress `sha512-` integrity hashes) are
silently ignored, producing false positives that will only grow as more
dependency lockfiles and generated files appear in pushes.

## Why `gitleaks git` is the right fix

`gitleaks git --source=<repoDir> --log-opts=<range>` runs natively against a
git repository. Gitleaks walks the commit graph, retrieves diffs via `git show`,
and populates the `File`, `StartLine`, and `Commit` fields on every finding.
Path-based allowlists in the gitleaks ruleset and any custom `.gitleaks.toml`
fire correctly because gitleaks has the actual file paths.

## Store-and-forward mode — DONE

`SecretScanningHook` was updated to call `GitleaksRunner.scanGit()`.
`rp.getRepository()` provides a bare JGit repo that already has the pushed
objects in its object store before pre-receive hooks fire. Gitleaks traverses
it cleanly. No further work needed here.

## Transparent proxy mode — what's needed

### Why the objects are already there (and my initial analysis was wrong)

`EnrichPushCommitsFilter.unpackPushData()` uses JGit `PackParser` to insert the
pushed objects directly into the `LocalRepositoryCache` bare clone's object
store. After this call the objects exist as loose objects in the repo.
`git log` and `git show` work on explicit SHAs regardless of whether a ref
points at the object — so `gitleaks git --log-opts=commitFrom..commitTo` can
traverse the commit graph without any ref creation.

### Work items (can be done independently)

---

#### 1. Wire `LocalRepositoryCache` into `SecretScanningFilter`

**File:** `SecretScanningFilter.java`

Currently the filter reads a pre-generated diff string from a `PushStep`. It
needs to instead call `GitleaksRunner.scanGit()` with the repo directory and
commit range.

Changes:
- Add `LocalRepositoryCache repositoryCache` constructor parameter
- Remove the diff-string read from `PushStep`
- Read `commitFrom` / `commitTo` from `GitRequestDetails` (already present)
- Construct `remoteUrl` the same way `EnrichPushCommitsFilter` does
- Call `repositoryCache.getCached(remoteUrl)` (not `getOrClone` — by the time
  this filter runs at order 2500, `EnrichPushCommitsFilter` at
  `Integer.MIN_VALUE + 2` has already cloned it)
- Get `repoDir` via `repository.getDirectory().toPath()`
- Call `runner.scanGit(repoDir, commitFrom, commitTo, config)`

`GitRequestDetails` already exposes `getCommitFrom()` and `getCommitTo()`.

**Note:** `SecretScanCheck` and the `--pipe` path in `GitleaksRunner` can be
removed once proxy mode is migrated. `ScanDiffFilter` still generates the diff
for the blocked-terms/patterns check so that filter chain is unaffected.

---

#### 2. Handle ZERO_OID (new branch push) in proxy mode

**Already handled in `GitleaksRunner.scanGit()`** — the method checks
`ZERO_OID.equals(commitFrom)` and switches to `commitTo --not --all` for
new-branch pushes. No additional work needed as long as `GitRequestDetails`
correctly exposes `commitFrom` as the zero OID for new branches.

Verify: check that `ParseGitRequestFilter` populates `commitFrom` from the
packet-line old-tip, which will be `0000000000000000000000000000000000000000`
for a new branch.

---

#### 3. Concurrent push isolation (shallow concern)

**File:** `LocalRepositoryCache.java`

The shared bare clone is not isolated per push. Two concurrent pushes to the
same repo both call `PackParser` on the same `Repository` instance. JGit's
`ObjectInserter` is safe to use concurrently (each call gets its own inserter),
but gitleaks running `git log` concurrently against the same bare dir while
another push is inserting objects is untested and could return inconsistent
results (e.g. seeing commits from the other push).

**Mitigation options (pick one):**

A. **Per-push temp repo** (cleanest, highest overhead): After `getOrClone`, do
   a local `git clone --local --no-hardlinks <cachedRepo> <tempDir>` to get a
   snapshot of the repo at the time of the push, then unpack objects into the
   temp copy. Delete the temp copy after the filter chain completes. No shared
   mutable state.

B. **Ref-per-push** (lower overhead): After `PackParser`, create a temporary
   ref `refs/gitproxy/scan/<commitTo>` pointing at `commitTo`. This makes the
   new-tip durable and queryable in isolation. Clean up the ref after scanning.
   Still shares the same repo dir but gitleaks can be given `--log-opts=<ref>`
   instead of a bare SHA — more robust to git's ref-resolution rules.

C. **Accept the race** (pragmatic): For a single-instance proxy with low push
   volume, concurrent pushes to the same repo are rare. Log a warning when
   `getCached` returns a repo that is already being written to (trackable via an
   in-flight set in `LocalRepositoryCache`). Document as a known limitation.

Recommendation: start with **C** (log + document), implement **B** if the
shared-state issue is observed in practice, escalate to **A** only if needed.

---

#### 4. Shallow clone depth

`LocalRepositoryCache` defaults to `cloneDepth = 100`. A push that includes a
commit whose parent is older than 100 commits back in the upstream history will
succeed at object-insertion time (PackParser resolves thin pack deltas against
the shallow boundary) but `git log` may be missing parent context beyond the
shallow boundary.

**Impact on gitleaks:** The diff for the outermost commit in the push is still
generated correctly — gitleaks diffs each pushed commit against its parent. If
the parent is beyond the shallow boundary, `git show` will fall back to a full
tree diff, which is safe (more output, no false negatives).

**Low priority.** Document as known behaviour. If a project does 100+ commit
pushes regularly (force-push with full branch history), consider making
`cloneDepth` configurable per provider.

---

## Migration path

| Step | Session effort | Risk |
|------|---------------|------|
| 1 — Wire `LocalRepositoryCache` into `SecretScanningFilter` | ~1 session | Low |
| 2 — ZERO_OID verification in `ParseGitRequestFilter` | ~30 min | Low |
| 3C — Log concurrent-push warning | ~30 min | None |
| 3B — Temp-ref per push | ~1 session | Medium |
| 4 — Document shallow clone behaviour | 10 min | None |
| Remove `SecretScanCheck` / `--pipe` path | ~30 min after 1+2 | Low |

## Files affected

```
jgit-proxy-core/src/main/java/org/finos/gitproxy/servlet/filter/SecretScanningFilter.java
jgit-proxy-core/src/main/java/org/finos/gitproxy/git/GitleaksRunner.java         (scanGit already added)
jgit-proxy-core/src/main/java/org/finos/gitproxy/git/LocalRepositoryCache.java   (step 3 only)
jgit-proxy-core/src/main/java/org/finos/gitproxy/validation/SecretScanCheck.java  (delete when done)
jgit-proxy-server/src/main/java/org/finos/gitproxy/jetty/GitProxyServletRegistrar.java  (update wiring)
jgit-proxy-dashboard/ (same wiring update if it registers filters independently)
```
