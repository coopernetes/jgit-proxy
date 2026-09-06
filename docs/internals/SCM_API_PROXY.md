# SCM API Proxy — wire formats and capture notes

How each SCM CLI talks to its provider, and what fogwall has to match to sit in the middle. This is the
reverse-engineering record: request shapes, endpoint maps, where each dialect hides its authorization target, and the
per-CLI quirks that constrain the implementation.

For how the proxy is built — listeners, filter chains, where each decision is made — see
[ARCHITECTURE.md](../ARCHITECTURE.md#proposals-a-dedicated-listener-per-provider).

Everything below is from live traffic unless marked otherwise. Versions captured: `gh` 2.98.0 (`GH_DEBUG=api`), `glab`
v1.116.0 (`GLAB_DEBUG_HTTP=true`), `tea` 0.15.1 (`tea --debug`, `gitea.dev/sdk` v1.2.0). `fj` v0.6.0 emits no HTTP debug
output at all, so its rows come from reading `forgejo-cli` and the generated `forgejo-api` 0.11.0 crate.

---

## What the CLIs constrain

### None of them accepts a path

Each binary was pointed at a local listener configured as `http://127.0.0.1:8099/scm-api/<provider>`, and the request
that actually arrived was recorded:

| CLI    | sub-path mount | request observed                                                          |
| ------ | -------------- | ------------------------------------------------------------------------- |
| `gh`   | **discarded**  | `POST /api/graphql` — `GH_HOST` is a hostname; a path cannot be expressed |
| `glab` | preserved      | `GET /scm-api/gitlab/api/v4/projects/foo%2Fbar/issues`                    |
| `tea`  | preserved      | `GET /scm-api/gitea/api/v1/user`                                          |
| `fj`   | **discarded**  | `GET /api/v1/user`                                                        |

`tea` concatenates (`c.url + "/api/v1" + path`), so a base path survives. `fj` resolves `base.join("/api/v1/...")`, and
RFC 3986 makes an absolute reference replace the entire base path — silently, with no error. `gh` never had a path to
begin with: `GH_HOST` holds a hostname, optionally with a port.

This is what forces a listener per provider rather than a shared prefix.

### Each sends a different credential header

| CLI    | header                                                              |
| ------ | ------------------------------------------------------------------- |
| `gh`   | `Authorization: token <pat>`                                        |
| `glab` | `PRIVATE-TOKEN: <pat>` for a PAT; `Authorization: Bearer` for OAuth |
| `tea`  | `Authorization: token <pat>`                                        |
| `fj`   | `Authorization: token <pat>`                                        |

**`glab` is the awkward one: which header it sends depends on how the developer authenticated.** A personal access token
goes in `PRIVATE-TOKEN` with no `Authorization` header at all; a token obtained through OAuth login goes in
`Authorization: Bearer`. The two are not interchangeable — GitLab rejects a PAT presented as a bearer token.

So fogwall has to recognise both headers to resolve identity, and forward whichever the caller actually sent, unchanged.
Reading only `Authorization` rejects every PAT-authenticated `glab` request with a 401 from fogwall rather than from the
upstream. Rewriting one into the other makes fogwall's answer differ from what the CLI would have got talking to the
provider directly.

### Each advertises its version

`GitHub CLI 2.98.0 ...`, `glab/v1.116.0 (linux, amd64)`, `tea/0.15.1 (linux/amd64) go-sdk/v1.2.0`,
`forgejo-cli/0.6.0 (https://codeberg.org/forgejo-contrib/forgejo-cli/)`. A CLI release that changes its wire format
shows up here first.

### Encoded separators appear inside single path segments

GitLab addresses a project as one `owner%2Frepo` segment. Gitea encodes a repository-relative file path into one segment
of its blob endpoints — `fj` reads a pull request template from `/repos/{o}/{r}/raw/.forgejo%2Fpull_request_template.md`
before creating a pull request. Both must survive to fogwall undecoded, or the segment splits and the repository the
request names changes.

---

## GitHub (`gh`)

### Transport

Issue and PR CRUD is entirely GraphQL — every create, edit, comment, review and close is a `POST` to the GraphQL
endpoint (`/graphql` on github.com, `/api/graphql` on GHES). The one REST call found anywhere in this matrix is
`pr close --delete-branch`, which sends `DELETE /repos/{o}/{r}/git/refs/heads%2F{branch}` — see "What the flags reach"
below.

Every command is a 2–3 request fan-out: one or more read `query`s, then one `mutation`.

**The proxied path needs a classic PAT with the `repo` scope.** GitHub has no classic scope covering issues or pull
requests alone, so `repo` is the minimum, and it grants full read/write across every repository the user can reach. That
breadth is a property of GitHub's scopes, not of anything fogwall does — the permission engine, not the token, is what
bounds a caller here.

Request headers seen include `X-Github-Api-Version: 2022-11-28` and `Graphql-Features: merge_queue`.

### Mutation → node-ID map

Each mutation references its target by an opaque global node ID, and **the input key holding that ID differs per
mutation** — there is no single field name to look for:

| `gh` command       | schema mutation field  | gh `operationName`     | input node-ID key     | node type           |
| ------------------ | ---------------------- | ---------------------- | --------------------- | ------------------- |
| `issue create`     | `createIssue`          | `IssueCreate`          | `input.repositoryId`  | Repository (`R_`)   |
| `pr create`        | `createPullRequest`    | `PullRequestCreate`    | `input.repositoryId`  | Repository (`R_`)   |
| `issue edit`       | `updateIssue`          | `IssueUpdate`          | `input.id`            | Issue (`I_`)        |
| `issue close`      | `closeIssue`           | `IssueClose`           | `input.issueId`       | Issue (`I_`)        |
| `issue/pr comment` | `addComment`           | `CommentCreate`        | `input.subjectId`     | Issue or PR         |
| `pr edit`          | `updatePullRequest`    | `PullRequestUpdate`    | `input.pullRequestId` | PullRequest (`PR_`) |
| `pr review`†       | `addPullRequestReview` | `PullRequestReviewAdd` | `input.pullRequestId` | PullRequest (`PR_`) |
| `pr close`         | `closePullRequest`     | `PullRequestClose`     | `input.pullRequestId` | PullRequest (`PR_`) |

Attribute changes are their own mutations, not fields on the ones above, and each names its target through the generic
capability it acts on rather than a concrete type:

| flag                                                | schema mutation field        | gh `operationName`           | input node-ID key     | node type   |
| --------------------------------------------------- | ---------------------------- | ---------------------------- | --------------------- | ----------- |
| `--assignee`, `--add-assignee`, `--remove-assignee` | `replaceActorsForAssignable` | `ReplaceActorsForAssignable` | `input.assignableId`  | Issue or PR |
| `--add-label`                                       | `addLabelsToLabelable`       | `LabelAdd`                   | `input.labelableId`   | Issue or PR |
| `--remove-label`                                    | `removeLabelsFromLabelable`  | `LabelRemove`                | `input.labelableId`   | Issue or PR |
| `--reviewer`, `--add-reviewer`, `--remove-reviewer` | `requestReviewsByLogin`      | `RequestReviewsByLogin`      | `input.pullRequestId` | PullRequest |

† Recorded for completeness; not allowlisted.

Three things follow:

1. Allowlisting matches the **schema mutation field** parsed from the AST, not `gh`'s `operationName` (which is
   `gh`-specific and can change) and not a substring of the query text.
2. The mutation carries only the node ID. Resolution to `owner/repo` is mandatory before any permission check.
3. The resolver handles three node types — `Repository` (`R_…`), `Issue` (`I_…`), `PullRequest` (`PR_…`):

```graphql
node(id: $id) {
  ... on Repository  { name owner { login } }
  ... on Issue       { repository { name owner { login } } }
  ... on PullRequest { repository { name owner { login } } }
}
```

### Fork PRs address the upstream

`input.repositoryId` names the base repository — the one the request is opened on, and the one to authorize against.
Captured from a real fork PR (fork `RBC/coopernetes-test-repo` → upstream `coopernetes/test-repo`):

```json
{
  "input": {
    "repositoryId": "R_kgDOKPRwrA",
    "headRefName": "RBC:test/fork-pr-1788671018",
    "baseRefName": "main",
    "title": "…",
    "body": "…"
  }
}
```

`R_kgDOKPRwrA` is the upstream; the fork's own ID (`R_kgDON5qHaA`) appears nowhere. The fork is named only inside
`headRefName`, in the `owner:branch` form — the same shape Gitea uses. So the resolver reads the correct repository with
no extra work, and none of GitLab's `target_project_id` handling is needed.

The schema has a separate `input.headRepositoryId` for the head repository, but `gh` does not send it, relying on the
namespaced `headRefName` instead. It is worth knowing it exists: reading it as the target would authorize the repository
the contributor already owns.

Subject IDs are safer to cache than repository IDs. A GitHub issue transfer mints a new node ID in the destination and
leaves the old one as a redirect, so `issueId → repo` has no rename staleness. `repositoryId → owner/name` is the
mapping that needs a conservative TTL.

### Fan-out, and what could seed the cache

Each mutation is preceded by a read `query` carrying `owner`/`repo`(/`number`) in its variables and returning the target
node ID in its response — so the cache can be seeded from the caller's own traffic before the mutation arrives:

- `issue create` → `query IssueRepositoryInfo($owner,$name){ repository{ id … } }`. The response's `data.repository.id`
  equals the `createIssue` mutation's `input.repositoryId`.
- `issue edit/comment/close` → `query IssueByNumber($owner,$repo,$number){ … issue{ id } }`.
- `pr edit/comment/review/close` → `query PullRequestByNumber($owner,$repo,$pr_number){ … pullRequest{ id } }`.
- `pr create` additionally fires `query RepositoryInfo` and `query PullRequestForBranch` (an existing-PR check).

All lookups are `query` type. Some commands fire extra reads (`PullRequestProjectItems`), also queries.

### What the flags reach

Every table above was captured from the bare command. The flagged variants split three ways, and the split is not where
it looks:

- **Inlined into the create or update.** `issue create --label` (as `labelIds`), `pr create --draft`, and
  `pr edit --base --milestone --title --body` — all four of the last land in one `updatePullRequest` input. `gh` also
  fires `updatePullRequest` under the operation name `PullRequestCreateMetadata` immediately after `createPullRequest`,
  to attach labels named on `pr create`.
- **A separate mutation.** Assignees in every command, labels on an edit, reviewers even on create. These are the four
  rows in the second table above.
- **REST.** `pr close --delete-branch` sends `DELETE /repos/{o}/{r}/git/refs/heads%2F{branch}`. The dialect carries
  GraphQL only, so this has nowhere to go. Ref deletion is a git operation with a git path through fogwall; it is not
  proposal content, and it stays out.

The follow-ups run **after** the create or update has already succeeded, so denying one leaves the issue or PR created
and the attribute unset — half-applied rather than refused. That asymmetry is the reason they are allowlisted rather
than left out as "metadata".

### Allowlist

```
createIssue, updateIssue, closeIssue,
createPullRequest, updatePullRequest, closePullRequest,
addComment,
replaceActorsForAssignable, addLabelsToLabelable, removeLabelsFromLabelable,
requestReviewsByLogin
```

`requestReviewsByLogin` requests a review; `addPullRequestReview` submits one and is absent. Asking a colleague to look
is part of proposing a change — the verdict is not.

---

## GitLab (`glab`)

### Transport

Issue and MR CRUD is entirely REST v4 — plain `GET`/`POST`/`PUT` against `/api/v4/...` with a JSON body. GitLab has a
GraphQL surface; `glab` does not use it for this command set.

The project is addressed by URL-encoded `owner/repo` path rather than a numeric ID, so the authorization target is read
straight off the URL and none of GitHub's node-ID machinery applies. Every mutating command is preceded by a `GET` on
the same path, so the path is self-describing whether the cache is warm or cold.

### Operation → REST endpoint map

| `glab` command | Method | Path                                          | Target ID source                                                             |
| -------------- | ------ | --------------------------------------------- | ---------------------------------------------------------------------------- |
| `issue create` | POST   | `/projects/:path/issues`                      | path only                                                                    |
| `issue update` | PUT    | `/projects/:path/issues/:iid`                 | path + `iid` (from CLI arg, not a preceding lookup)                          |
| `issue note`   | POST   | `/projects/:path/issues/:iid/notes`           | path + `iid`                                                                 |
| `issue close`  | PUT    | `/projects/:path/issues/:iid`                 | body `{"state_event":"close"}`                                               |
| `mr create`    | POST   | `/projects/:path/merge_requests`              | path (+ numeric `target_project_id`, from a preceding `GET /projects/:path`) |
| `mr update`    | PUT    | `/projects/:path/merge_requests/:iid`         | path + `iid`                                                                 |
| `mr note`      | POST   | `/projects/:path/merge_requests/:iid/notes`   | path + `iid`                                                                 |
| `mr approve`†  | POST   | `/projects/:path/merge_requests/:iid/approve` | path + `iid`                                                                 |
| `mr close`     | PUT    | `/projects/:path/merge_requests/:iid`         | body `{"state_event":"close"}`                                               |

`:path` is the URL-encoded `owner%2Frepo` segment; `:iid` is the project-scoped issue/MR number (not a global ID),
always supplied by the CLI caller from the command-line argument or a preceding `GET`.

† Recorded for completeness; approval is a review operation and is not allowlisted.

### Flags need no extra endpoints, but do need extra reads

GitLab is the one dialect where every flag lands inline.
`mr create --assignee --reviewer --label --squash-before-merge --remove-source-branch` is a single `POST`, and
`mr update --label --assignee --reviewer --ready --lock-discussion` a single `PUT`, because GitLab accepts
`assignee_ids`, `reviewer_ids`, `labels` and `milestone_id` as ordinary fields. There is no follow-up call to allowlist.

What the flags do add is **reads**, and those carry query parameters the write path never does:

```
GET /projects/:path?license=true&with_custom_attributes=true   before every create
GET /users?per_page=30&username=<login>                        once per --assignee/--reviewer login
```

`glab` resolves each login to a numeric ID before it can build the mutation, so refusing `username` stops the command
before the write is ever attempted. The query-parameter allowlist has to carry all three names or the mutation allowlist
never gets a say.

### Fork MRs address the source project

`mr create` is the one operation whose URL does not name the repository fogwall must authorize against. Captured from a
real fork MR (fork `id 86130652` → upstream `id 53539888`, same namespace):

```
POST /api/v4/projects/coopernetes%2Ftest-repo-gitlab-fork/merge_requests
{"title":"…","source_branch":"fork-feature","target_branch":"main","target_project_id":53539888}
```

The URL segment is the fork. The upstream appears only as the numeric `target_project_id` in the body, and the response
confirms the split — `source_project_id: 86130652`, `target_project_id: 53539888`, MR created on the upstream.

Since authorization targets the repository the MR is opened on, a path-only matcher reads the wrong project here:

- Authorize on `target_project_id` when the body carries it.
- Fall back to the URL's project when it does not — a same-project MR, where the two are identical.
- If `target_project_id` is present but cannot be resolved to a path, deny.

Every other GitLab operation in scope is unaffected: `mr update` and `mr note` address the MR by `iid`, which is scoped
to the target project, so the URL already names the upstream.

Resolution is cheap. `glab` fires `GET /projects/:path` for both projects immediately before the POST, and each response
carries `id` alongside `path_with_namespace` — the same seeding opportunity as GitHub's node IDs.

> **Capture hazard.** `GLAB_DEBUG_HTTP` redacts `Authorization` but not response bodies, and `GET /projects/:path`
> returns `runners_token` in plaintext for a project owner. Scrub captures before sharing them.

---

## Forgejo and Gitea — `fj` and `tea`

Gitea and Forgejo share one REST API — Forgejo forked Gitea's — and `tea` and `fj` are two CLIs against it.

### Transport

100% REST v1, no GraphQL in either CLI. The repository is two ordinary path segments,
`/api/v1/repos/{owner}/{repo}/...`, each URL-encoded independently, so the authorization target comes off the path as it
does for GitLab. The issue/PR index is a plain project-scoped integer supplied by the caller.

### The two CLIs reach the same operations by different endpoints

| operation       | `tea`                                     | `fj`                                   |
| --------------- | ----------------------------------------- | -------------------------------------- |
| list PRs        | `GET /repos/{o}/{r}/pulls`                | `GET /repos/{o}/{r}/issues?type=pulls` |
| close PR        | `PATCH /repos/{o}/{r}/pulls/{n}`          | `PATCH /repos/{o}/{r}/issues/{n}`      |
| comment on a PR | `POST /repos/{o}/{r}/issues/{n}/comments` | same                                   |

Forgejo models a pull request as an issue, and `fj` routes through that model (`fj pr close` calls
`crate::issues::close_issue`). Allowlisting only the `/pulls` form silently breaks `fj`; only the `/issues` form
silently breaks `tea`. One dialect covers both, with the allowlist as the union.

### Operation → REST endpoint map

Paths are shown below the `/api/v1` mount point. `{n}` is the project-scoped index.

| operation            | Method | Path                                  | `tea` | `fj`                  |
| -------------------- | ------ | ------------------------------------- | ----- | --------------------- |
| issue create         | POST   | `/repos/{o}/{r}/issues`               | yes   | yes                   |
| issue update/close   | PATCH  | `/repos/{o}/{r}/issues/{n}`           | yes   | yes (also `pr close`) |
| issue/PR comment     | POST   | `/repos/{o}/{r}/issues/{n}/comments`  | yes   | yes                   |
| comment update       | PATCH  | `/repos/{o}/{r}/issues/comments/{n}`  | yes   | yes                   |
| PR create            | POST   | `/repos/{o}/{r}/pulls`                | yes   | yes                   |
| PR update/close      | PATCH  | `/repos/{o}/{r}/pulls/{n}`            | yes   | no                    |
| PR merge†            | POST   | `/repos/{o}/{r}/pulls/{n}/merge`      | yes   | yes                   |
| PR review (approve)† | POST   | `/repos/{o}/{r}/pulls/{n}/reviews`    | yes   | **no**                |
| add labels           | POST   | `/repos/{o}/{r}/issues/{n}/labels`    | yes   | —                     |
| add assignees        | POST   | `/repos/{o}/{r}/issues/{n}/assignees` | yes   | —                     |
| remove assignees     | DELETE | `/repos/{o}/{r}/issues/{n}/assignees` | yes   | —                     |

† Recorded for completeness; not allowlisted.

The last three are what `tea issue edit` reaches for `--add-labels`, `--add-assignees`/`--set-assignees` and
`--remove-assignees`; a create sets both inline on `POST /issues` instead. The remove is a **DELETE carrying a body** —
the logins to drop are in the entity, so forwarding the method without it asks the upstream to remove nobody.
`--remove-labels` fires no HTTP request at all: `tea` resolves the label list and then no-ops, so there is nothing to
allowlist.

Two quirks constrain what the allowlist can express:

- **`fj` cannot approve a pull request.** `repo_create_pull_review` exists in `forgejo-api` 0.11.0, but `fj` never calls
  it — `fj pr review` only lists. A capability gap in the CLI, not a gap in the capture.
- **`tea` sends a full-object PATCH.** `tea pr close` emits every field alongside `"state":"closed"`
  (`{"title":"","base":"", … ,"state":"closed", …}`), so on the wire it is indistinguishable from `tea pr edit`. Rule
  granularity is method plus path, never intent.

Endpoints the CLIs can also reach — tracked time, dependencies, blocking, releases — are absent from the allowlist and
therefore denied.

### Fork PRs address the upstream

Unlike GitLab, Gitea and Forgejo name the repository fogwall must authorize against directly in the URL, even for a PR
opened from a fork. Captured with `tea --debug`:

```
POST https://gitea.com/api/v1/repos/coopernetes/test-repo/pulls
{"head":"someotheruser:some-fork-branch","base":"main","title":"…","body":"…"}
```

The path segment is the upstream — whatever `--repo` names — and the fork appears only in the body as
`head: "<user>:<branch>"`, the same shape GitHub uses. No `target_project_id` handling is needed.

`fj` behaves the same way by construction: `--head` is forwarded verbatim (`prs.rs`, `Some(head) => Some(head)`) and the
repo comes from `-r/--repo` into `repo_create_pull_request(owner, repo, …)`.

`tea` also fires non-repo-scoped reads around the create (`GET /orgs/{name}`, `GET /repos/{o}/{r}/labels`), so a
path-based matcher has to tolerate paths carrying no `owner/repo`.

---

## Credential model

ID resolution uses the caller's own token, never an app-level one. fogwall resolves only what the caller can already
see, so an opaque ID they cannot read is a denial rather than a lookup. That never wrongly blocks: the caller is about
to operate on that target, and a token that cannot read it cannot operate on it either.

The `id → owner/repo` mapping is an objective fact rather than a per-user one, so the cache is shared across users. Only
the authorization decision is per-user, and it is never cached.

A developer's token is always far broader than the operations fogwall proxies with it. Neither provider has a scope that
means "may open issues and pull requests" — GitHub's `repo` and GitLab's `api` are both full read/write to everything
the user can already reach. GitLab has granular scopes for runners, the registry and observability, but nothing for core
API resources.

The token stays bounded by the user's own role on the provider, so this is an over-broad credential at rest rather than
an escalation. Narrowing it to specific repositories and operations is the permission engine's job, not the token's.

---

## Appendix: capturing a new provider

The goal is a per-command log of every request and response, bodies included, for the full CRUD matrix, with credentials
scrubbed.

1. **Use a throwaway repo and a rotatable token.** Never capture against production data.
2. **Enable the CLI's API debug output** and tee each command to its own file:
   - `gh`: `GH_DEBUG=api gh <cmd> … > cmd.log 2>&1` — prints the request and response details.
   - `glab`: `GLAB_DEBUG_HTTP=true glab <cmd> … > cmd.log 2>&1` — prints the request and response details.
   - `tea`: `tea <cmd> … --debug` — prints method, URL, the headers `tea` sets, and the request body. Response bodies
     show only a Go pointer, and `Authorization`/`User-Agent` are added lower in the SDK so they never appear.
   - `fj`: no HTTP debug exists. Read the CLI source and its generated API crate instead — that is how the Gitea/Forgejo
     tables above were produced, and it enumerates every endpoint the CLI can reach rather than only the ones one
     session exercised. `fj` honours `HTTPS_PROXY` and links OpenSSL, so mitmproxy with its CA in the system trust store
     works if raw bytes are genuinely needed.
3. **Exercise the full matrix**, recording the ordered fan-out per command: issue create → edit → comment; pr/mr create
   → edit → comment → review; then close both. Reuse an existing branch for the PR/MR head so nothing needs pushing.
4. **Scrub tokens** from every log before analysis — `Authorization:` lines and any `gh[posur]_…`, `github_pat_…` or
   provider token patterns. CLI debug output usually redacts `Authorization`; scrub anyway.
5. **Extract** per command: method and path (REST) or mutation field and variables (GraphQL), where the target ID
   appears, and whether a preceding lookup already carries `owner/repo`.
6. **Check what the client does with a base path.** Point the CLI at a local listener configured with a sub-path and see
   whether the prefix survives. A `BaseHTTPRequestHandler` that logs the request line is enough, and needs no valid
   credential.

Read the CLI's source alongside any capture. A capture proves what one session did; the source enumerates everything the
CLI can send, which is what an allowlist has to cover.
