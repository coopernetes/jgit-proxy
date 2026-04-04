# Git internals reference

Notes on git/JGit behaviour that inform how filters and hooks are written.
Add a section here when you hit a non-obvious edge case so the next person doesn't have to rediscover it.

---

## Tag objects

### Lightweight vs annotated tags

Git has two kinds of tags, and they behave very differently at the object level.

**Lightweight tag** — just a named pointer, stored as a ref file.
The ref value is the SHA of the commit it points to directly.
There is no tag object in the object store.

```
refs/tags/v1.0 → a3f9c1... (commit)
```

**Annotated tag** — a first-class git object of type `tag`.
The ref points to the tag object SHA, not the commit SHA.
The tag object contains metadata (tagger, date, message) and a pointer to the tagged commit.

```
refs/tags/v1.0 → b7d2e4... (tag object)
                     └─→ a3f9c1... (commit)
```

The key consequence: **`cmd.getNewId()` for an annotated tag push returns the tag object SHA, not a commit SHA.**
Any code that calls `RevWalk.parseCommit(cmd.getNewId())` directly will throw `IncorrectObjectTypeException` for annotated tags.

### The `^{commit}` dereference

Both git and JGit support a peeling suffix to follow any chain of tag objects to the final commit:

```java
// Safe for both lightweight and annotated tags:
ObjectId commitId = repository.resolve(sha + "^{commit}");
```

For a lightweight tag, `sha` is already a commit SHA — `^{commit}` is a no-op.
For an annotated tag, JGit follows the tag → commit chain.
For a chain of tags (a tag of a tag), it follows all the way down.

`^{tree}` works the same way but stops at a tree object instead of a commit.

`resolve()` returns `null` when the peel fails — for example when a tag points to a blob or tree
rather than a commit (legal but extremely rare). Always null-check the result.

### What git sends over the wire for a tag push

When you run `git push origin refs/tags/v1.0`:

- The packet line header is `<oldOid> <newOid> refs/tags/v1.0` (same format as a branch push).
- For a **lightweight tag** to an already-upstream commit, git sends a thin pack with **zero objects**
  because the commit already exists at the remote. Trying to read a pack entry from an empty pack
  produces garbage or a `DataFormatException` — this is normal, not a corruption.
- For an **annotated tag**, git sends a pack containing the tag object (type 4, `OBJ_TAG`).
  The tagged commit is not included if it already exists upstream.
- `commitFrom` (`oldOid`) is all-zeros for a new tag — the same value used for a new branch.
  Code that uses `commitFrom == zeros` as a signal for "new branch" must also account for new tags.

### How each hook/filter handles tags

#### S&F hooks (`CheckEmptyBranchHook`, `CheckHiddenCommitsHook`)

Tags push commits that already exist upstream.
`CommitInspectionService.getCommitRange()` returns an empty list — the commit at the tag tip is
already reachable from existing heads, so it is not "new".

**`CheckEmptyBranchHook`** — an empty commit range on a zero-oldId ref would normally mean the branch
has no new commits (a reject condition). For tags this is always the case and is legitimate, so the
hook skips any ref whose name starts with `refs/tags/`.

**`CheckHiddenCommitsHook`** — calls `walk.parseCommit()` on `cmd.getNewId()`.
For an annotated tag this throws. Fix: resolve through `^{commit}` first.

```java
ObjectId commitId = repo.resolve(cmd.getNewId().name() + "^{commit}");
if (commitId == null) continue;
walk.markStart(walk.parseCommit(commitId));
```

All other S&F hooks (`AuthorEmailValidationHook`, `CommitMessageValidationHook`, etc.) delegate to
`CommitInspectionService.getCommitDetails()` or `getCommitRange()`, both of which use `^{commit}`.
They are safe transitively.

#### Proxy-mode filters

The proxy pipeline sees the same two objects as the S&F hooks — the packet line SHAs and the pack data —
but runs as servlet filters without JGit's `ReceivePack` infrastructure.

**`ParseGitRequestFilter`** — extracts `branch`, `commitFrom`, `commitTo` from the packet line,
then tries to parse the first pack object as a commit.
For a tag push this fails (the pack contains a tag object or no new objects).
The parse exception is caught; `requestDetails.commit` is left null.
`requestDetails.branch` is set to the full ref name (e.g. `refs/tags/v1.0`),
so `GitRequestDetails.isTagPush()` works correctly downstream.

**`CheckUserPushPermissionFilter`** — uses the commit author email to identify the pushing user.
Null commit → null email → rejects with "Unknown User".
Fix: skip the email check for tag pushes; the user is already verified by HTTP basic auth.

**`CheckEmptyBranchFilter`** — empty `pushedCommits` + zero `commitFrom` looks like an empty branch push.
Fix: skip for tag refs, same reasoning as the S&F hook.

**`CheckHiddenCommitsFilter`** — calls `walk.parseCommit(repo.resolve(toCommit))` where `toCommit`
is the tag object SHA.
Fix: use `repo.resolve(toCommit + "^{commit}")`, consistent with all other `CommitInspectionService` callers.

**`EnrichPushCommitsFilter`** — unpacks the pack objects into the local repo clone using JGit's
`PackParser`, which handles tag objects fine. Then calls `CommitInspectionService.getCommitRange()`
(fixed via `^{commit}`), which returns empty for a tag on an existing commit. Normal behaviour.

**`ScanDiffFilter`** — calls `getDiff(repo, fromCommit, toCommit)`.
`toCommit + "^{tree}"` peels through the tag chain to the tree; this works correctly.
For a new tag (`fromCommit == zeros`) the diff base falls through to `findNewBranchBase()`,
which also uses `^{commit}` and returns null (no new commits), so the diff is against the empty tree.
This produces a full-snapshot diff of the tagged commit, which is harmless for typical content checks.

**`SecretScanningFilter`** — passes `commitFrom`/`commitTo` to `gitleaks git`.
Gitleaks calls native `git log`, which peels tags natively. No special handling needed.
