# JGit infrastructure

How git-proxy-java uses [Eclipse JGit](https://github.com/eclipse-jgit/jgit) to implement the store-and-forward proxy mode
and to support commit inspection in the transparent proxy mode.

For low-level details on git wire-protocol behaviour and how individual hooks/filters handle edge cases (tags, new
branches, force pushes, deletions, etc.), see [GIT_INTERNALS.md](GIT_INTERNALS.md).

---

## Store-and-forward architecture

The store-and-forward path (`/push/<host>/owner/repo.git`) uses JGit's built-in HTTP git server. Three JGit SPIs are
plugged in to turn a vanilla `GitServlet` into a validating, forwarding proxy:

| SPI                  | Implementation                      | Role                                                                            |
| -------------------- | ----------------------------------- | ------------------------------------------------------------------------------- |
| `RepositoryResolver` | `StoreAndForwardRepositoryResolver` | Resolves the upstream repo into a local bare clone; extracts client credentials |
| `ReceivePackFactory` | `StoreAndForwardReceivePackFactory` | Creates a `ReceivePack` per request; assembles the pre/post-receive hook chain  |
| `UploadPackFactory`  | `StoreAndForwardUploadPackFactory`  | Creates `UploadPack` for fetch requests                                         |

Registration happens in `GitProxyServletRegistrar`:

```java
var gitServlet = new GitServlet();
gitServlet.setRepositoryResolver(new StoreAndForwardRepositoryResolver(cache, provider));
gitServlet.setReceivePackFactory(new StoreAndForwardReceivePackFactory(...));
gitServlet.setUploadPackFactory(new StoreAndForwardUploadPackFactory());

context.addServlet(new ServletHolder(gitServlet), "/push/" + provider.servletPath() + "/*");
```

---

## Push lifecycle

```text
Client: git push http://proxy:8080/push/github.com/owner/repo.git
    |
    v
StoreAndForwardRepositoryResolver.open()
    - Extract credentials from Authorization header (in-memory only)
    - Clone/fetch upstream WITHOUT credentials (public repos only)
    - Store upstream URL in repo config (gitproxy.upstreamUrl)
    |
    v
JGit GitServlet receives pack data
    - Parses pack, writes objects to local bare repository
    - Creates ReceiveCommand entries for each ref update
    |
    v
StoreAndForwardReceivePackFactory.create()
    - Retrieve credentials from request attribute
    - Create ValidationContext + PushContext (shared across hooks)
    - Assemble and sort the hook chain
    |
    v
PRE-RECEIVE HOOK CHAIN (with heartbeat keepalive)
    |
    +-- PushStorePersistenceHook.preReceiveHook()    [pinned: first]
    |     Save initial RECEIVED record to database
    |
    +-- RepositoryWhitelistHook                      [order 100]
    +-- CheckUserPushPermissionHook                  [order 150]
    +-- CheckEmptyBranchHook                         [order 210]
    +-- CheckHiddenCommitsHook                       [order 220]
    +-- AuthorEmailValidationHook                    [order 250]
    +-- CommitMessageValidationHook                  [order 260]
    +-- ProxyPreReceiveHook                          [order 270]
    +-- DiffGenerationHook                           [order 280]
    +-- DiffScanningHook                             [order 300]
    +-- GpgSignatureHook                             [order 320]
    +-- SecretScanningHook                           [order 340]
    |
    +-- PushStorePersistenceHook.validationResultHook()  [pinned: after validation]
    |     Collect all issues from ValidationContext
    |     Record REJECTED (failed) or BLOCKED (clean, pending review)
    |     Send validation summary to client via sideband
    |
    +-- ApprovalPreReceiveHook                           [pinned: last]
          If clean: poll ApprovalGateway (auto-approve or wait for human)
          If failed: reject all commands immediately
    |
    v
JGit updates local refs (only if all pre-receive hooks passed)
    |
    v
POST-RECEIVE HOOKS
    |
    +-- ForwardingPostReceiveHook
    |     Open JGit Transport to upstream
    |     Build RemoteRefUpdate for each accepted command
    |     Push with client's credentials
    |     Stream coloured status to client via sideband
    |
    +-- PushStorePersistenceHook.postReceiveHook()
          Record FORWARDED or ERROR
    |
    v
Client receives result
```

---

## RepositoryResolver

`StoreAndForwardRepositoryResolver` does two things per request:

1. **Local mirror** — calls `LocalRepositoryCache.getOrClone(upstreamUrl)` to maintain a bare clone. First access
   triggers `git clone --bare --depth 100`; subsequent requests do `git fetch --depth 100`. The clone uses **no
   credentials** so this only works for public repositories. Private repos fail with a clear error directing the user to
   the `/proxy/` path.

2. **Credential extraction** — reads HTTP Basic auth (or URL userinfo) and stores it as a request attribute
   (`org.finos.gitproxy.credentials`). Credentials live in memory only for the duration of the request and are never
   written to disk or repo config.

The upstream URL is stored in the repository's git config (`gitproxy.upstreamUrl`) so downstream hooks can read it
without access to the HTTP request.

---

## ReceivePackFactory

`StoreAndForwardReceivePackFactory` creates a fresh `ReceivePack` for each push request. Its main job is assembling the
hook chain:

- **Orderable validation hooks** implement `GitProxyHook` and are sorted by `getOrder()`. Two ranges are used:
  - Authorization (0–199): whitelist check, user permission
  - Content filtering (200–399): empty branch, hidden commits, email/message validation, diffs, GPG, secret scanning
- **Lifecycle hooks** are pinned at fixed positions around the validation hooks: persistence (before/after) and approval
  (after).

The factory also:

- Extracts credentials from the request attribute (set by the resolver) or falls back to re-reading the Authorization
  header
- Creates per-request `ValidationContext` and `PushContext` instances shared across all hooks
- Sets `setBiDirectionalPipe(false)` since this is HTTP, not SSH

---

## Pre-receive hook chain

All pre-receive hooks are chained by `chainPreReceiveHooks()`:

```java
try (HeartbeatSender hb = new HeartbeatSender(rp, heartbeatInterval)) {
    hb.start();
    for (PreReceiveHook hook : hooks) {
        hook.onPreReceive(rp, commands);
        rp.getMessageOutputStream().flush();   // stream sideband in real time
        if (anyCommandRejected) return;        // stop on first rejection
    }
}
```

Key points:

- After each hook, the sideband stream is flushed so messages appear immediately in the client terminal (JGit's
  `sendMessage()` doesn't auto-flush).
- The chain short-circuits as soon as any `ReceiveCommand` result is set to anything other than `NOT_ATTEMPTED`.
- A `HeartbeatSender` runs on a background daemon thread, sending `"."` on sideband every N seconds (default 10) to
  prevent idle-timeout disconnects during long steps like secret scanning or approval polling.

### Hook inventory

| Order | Hook                                        | Purpose                                                      |
| ----- | ------------------------------------------- | ------------------------------------------------------------ |
| —     | `PushStorePersistenceHook.preReceive`       | Record initial RECEIVED state in database                    |
| 100   | `RepositoryWhitelistHook`                   | Record whitelist pass (resolver already validated)           |
| 150   | `CheckUserPushPermissionHook`               | Validate push user via `UserAuthorizationService`            |
| 210   | `CheckEmptyBranchHook`                      | Reject if push range has no commits (skips tags)             |
| 220   | `CheckHiddenCommitsHook`                    | Detect unreferenced commits smuggled in via pack             |
| 250   | `AuthorEmailValidationHook`                 | Check author emails against allow/block patterns             |
| 260   | `CommitMessageValidationHook`               | Check commit messages against blocked literals/patterns      |
| 270   | `ProxyPreReceiveHook`                       | Log commit inspection details (sha, author, message snippet) |
| 280   | `DiffGenerationHook`                        | Generate unified diffs (push diff + default-branch diff)     |
| 300   | `DiffScanningHook`                          | Scan diff added-lines for blocked content patterns           |
| 320   | `GpgSignatureHook`                          | Validate GPG signatures via BouncyCastle PGP                 |
| 340   | `SecretScanningHook`                        | Pipe diff to gitleaks CLI for secret detection               |
| —     | `PushStorePersistenceHook.validationResult` | Collect issues; record REJECTED or BLOCKED                   |
| —     | `ApprovalPreReceiveHook`                    | Gate: auto-approve or poll for human approval                |

---

## Post-receive hooks

Post-receive hooks run only for commands with `Result.OK` (refs that were successfully updated locally).

### ForwardingPostReceiveHook

The "forward" half of store-and-forward. For each accepted `ReceiveCommand`:

1. Opens a JGit `Transport` to the upstream URL (read from `gitproxy.upstreamUrl` in repo config)
2. Sets the `CredentialsProvider` extracted from the original client request
3. Builds `RemoteRefUpdate` objects:
   - `CREATE` / `UPDATE` — same source and destination ref
   - `UPDATE_NONFASTFORWARD` — same, but with `force=true`
   - `DELETE` — null source ref (JGit translates this to a ref deletion)
4. Calls `transport.push()` and streams per-ref status to the client with colour-coded sideband messages

### PushStorePersistenceHook.postReceiveHook

Records the final outcome: `FORWARDED` if all refs pushed successfully, `ERROR` otherwise.

---

## Shared contexts

Two objects are created per request and threaded through all hooks:

### ValidationContext

Collects validation issues without rejecting commands directly. This allows the user to see **all** problems in a single
push attempt rather than fixing them one at a time.

```java
validationContext.addIssue("hookName", "summary", "detail");
```

Issues are collected and reported together by `PushStorePersistenceHook.validationResultHook()`.

### PushContext

Accumulates `PushStep` records (diffs, scan results, forwarding status) that are persisted to the database as part of
the push audit trail.

---

## Credential flow

Credentials are handled carefully to avoid writing secrets to disk:

1. **`StoreAndForwardRepositoryResolver.open()`** — extracts from Authorization header or URL userinfo. Stores as
   request attribute `org.finos.gitproxy.credentials`. Never used for cloning.
2. **`StoreAndForwardReceivePackFactory.create()`** — reads from request attribute (or re-extracts from header). Creates
   `UsernamePasswordCredentialsProvider`.
3. **`ForwardingPostReceiveHook.pushToUpstream()`** — sets the `CredentialsProvider` on JGit's `Transport` before
   calling `push()`.

The local clone/fetch is always unauthenticated. Credentials exist only in memory for the request duration.

---

## HeartbeatSender

Prevents idle-timeout disconnects during long-running hooks (approval polling, secret scanning subprocess waits).

- Single daemon thread via `ScheduledExecutorService`
- Fires every N seconds (default 10, configurable via `server.heartbeat-interval-seconds`)
- Sends `"."` on sideband-2 and flushes
- No-op if interval is zero or negative
- Implements `AutoCloseable` — used in try-with-resources around the hook chain

Thread safety: JGit's sideband stream is not thread-safe. The race window between heartbeat and hook writes is benign
because heartbeat fires only during silent gaps.

---

## LocalRepositoryCache

Manages bare clones used by both proxy modes:

- **S&F mode**: `StoreAndForwardRepositoryResolver` calls `getOrClone()` on each push. Objects from the client's pack
  are already in the repo (JGit's `ReceivePack` unpacked them), so hooks can use `RevWalk`, `DiffFormatter`, etc.
  directly.
- **Proxy mode**: `EnrichPushCommitsFilter` calls `getOrClone()` to get a local repo, then feeds the push's pack data
  through JGit's `PackParser` to insert objects. This bridges the gap between raw HTTP bytes and the JGit API.

Cache characteristics:

- Keyed by `owner_reponame` derived from the URL
- First access: `git clone --bare --depth 100`
- Subsequent: `git fetch --depth 100`
- Stored in temp directory, cleaned up on JVM shutdown
- Thread-safe with synchronized cloning

See [GIT_INTERNALS.md — Shallow clone implications](GIT_INTERNALS.md#shallow-clone-implications) for how the depth limit
affects commit walks and diffs.

---

## CommitInspectionService

Utility class used by both modes for extracting commit data via JGit:

| Method                             | What it does                                                  | JGit API                                                                         |
| ---------------------------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `getCommitDetails(repo, sha)`      | Single commit metadata (author, message, signature, trailers) | `RevWalk.parseCommit()`                                                          |
| `getCommitRange(repo, from, to)`   | Commits introduced by a push                                  | `git.log().addRange()` or `git.log().add(to).not(existingRefs)` for new branches |
| `getDiff(repo, from, to)`          | Diff entries between two commits                              | `git.diff()` with tree iterators                                                 |
| `getFormattedDiff(repo, from, to)` | Unified diff as string                                        | `DiffFormatter` writing to `ByteArrayOutputStream`                               |
| `findNewBranchBase(repo, to)`      | Oldest new commit's parent tree (for diffing new branches)    | `RevWalk` excluding existing refs                                                |

All methods use `^{commit}` peeling to handle annotated tags transparently. See
[GIT_INTERNALS.md — Tag objects](GIT_INTERNALS.md#tag-objects) for details.

---

## Key JGit APIs used

| API                    | Where                                               | Purpose                                             |
| ---------------------- | --------------------------------------------------- | --------------------------------------------------- |
| `GitServlet`           | `GitProxyServletRegistrar`                          | HTTP git server implementation                      |
| `ReceivePack`          | `StoreAndForwardReceivePackFactory`                 | Receives pack data, runs hook chain                 |
| `Transport`            | `ForwardingPostReceiveHook`                         | Pushes to upstream with credentials                 |
| `RevWalk`              | `CommitInspectionService`, `CheckHiddenCommitsHook` | Walks commit graph                                  |
| `DiffFormatter`        | `CommitInspectionService`, `DiffGenerationHook`     | Generates unified diffs                             |
| `PackParser`           | `EnrichPushCommitsFilter` (proxy mode)              | Inserts pack objects into local repo                |
| `PacketLineIn`         | `GitReceivePackParser` (proxy mode)                 | Reads packet-line protocol from raw bytes           |
| `Repository.resolve()` | Throughout                                          | SHA resolution with `^{commit}` / `^{tree}` peeling |
| `CredentialsProvider`  | Factory, resolver, forwarding hook                  | In-memory credential transport                      |
