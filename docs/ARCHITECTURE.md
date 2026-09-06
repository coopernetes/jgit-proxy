# Architecture

fogwall is a Git push proxy that sits between developers and upstream Git hosting providers (GitHub, GitLab, Bitbucket,
Forgejo, etc.). Every push travels through a validation and approval pipeline before reaching the upstream remote.
Fetch/clone traffic is audited but not blocked.

If you're familiar with [finos/git-proxy](https://github.com/finos/git-proxy), the Java rewrite shares the same
conceptual model: an ordered chain of steps that inspect and act on each push, a push store for audit and approval
state, and pluggable providers for different Git hosts. The main structural difference is that fogwall offers two
distinct proxy modes with different tradeoffs.

---

## Project structure

The codebase is a multi-module Gradle build. Dependencies flow upward — `core` is depended on by `server`, `server` is
depended on by `dashboard`.

```
fogwall-core
  Shared library. Contains all validation logic (hooks + filters), the push store, provider
  model, identity resolution, approval abstraction, and database migrations (Flyway). Both
  proxy modes are implemented here. No application entry point — this is a library.

fogwall-server
  Standalone Jetty application (FogwallJettyApplication). Registers both proxy modes for
  every configured provider, loads YAML config via Gestalt, and starts a plain Jetty server.
  No Spring, no dashboard, no REST API. This module also owns the shared servlet registrar
  (FogwallServletRegistrar) and configuration builder (JettyConfigurationBuilder) used by
  the dashboard module.

fogwall-dashboard
  Full application (FogwallDashboardApplication). Depends on both core and server.
  Adds Spring MVC (DispatcherServlet at /*), Spring Security, a REST API (/api/*), and a
  React SPA (built with Vite, bundled into the JAR as static resources). Approval workflow
  is always UI-driven in this mode.
```

The server module defines a `FogwallContext` record that bundles all runtime singletons (push store, user store,
approval gateway, identity resolver, repository caches, TLS config). Both application entry points build this context
from config and pass it to `FogwallServletRegistrar`, which registers the same servlets and filters regardless of
whether the dashboard is present.

---

## Two proxy modes

### Server mode (`/server/<provider>/<owner>/<repo>.git`)

The upstream repository is cloned locally on first access. When a developer pushes, JGit's `ReceivePack` receives the
entire pack locally before anything is forwarded. Pre-receive hooks validate the push; if it passes (and any required
approval is granted), a post-receive hook forwards it to upstream using the developer's credentials.

This mode can stream progress messages to the git client in real time via JGit sideband packets — so the developer sees
`remote: [step] author email OK` lines as each validation step completes.

> **Naming:** this mode was formerly called _store-and-forward_. It is served under the canonical `/server/…` prefix as
> of 1.4.0; the legacy `/push/…` prefix still routes to it as a deprecated alias, so existing git remotes keep working.

### Transparent proxy (`/proxy/<provider>/<owner>/<repo>.git`)

An HTTP reverse proxy forwards the git protocol directly to upstream. A servlet filter chain inspects the pack data
before it reaches upstream. Validation results are collected and, if anything fails, a single error response is sent.
The developer's git client is talking to a forwarding proxy, not a JGit endpoint, through a single HTTP request/response
cycle. A temporary local clone is still used to unpack the pack data and walk the commit range for validation, but the
push is forwarded via HTTP proxy rather than a JGit `push` command.

This mode cannot stream incremental feedback. The reason is structural: an HTTP response is a single buffered reply. The
filter chain runs to completion inside one request/response cycle — there is no mechanism to flush partial output to the
git client mid-chain. Validation filters accumulate their results; `ValidationSummaryFilter` and `PushFinalizerFilter`
collect everything and write one response at the end. Server mode avoids this constraint entirely because JGit's
`ReceivePack` owns the connection and can call `sendMessage()` at any point, streaming sideband packets to the client as
each hook completes.

### Choosing a mode

| Concern                       | Server mode                                | Transparent proxy                                                         |
| ----------------------------- | ------------------------------------------ | ------------------------------------------------------------------------- |
| Live progress feedback        | Yes — per-step sideband messages           | No — single terminal response                                             |
| Local storage required        | Yes — receives the push into a local clone | Yes — clone needed for pack inspection                                    |
| Approval workflow             | Blocks git session until approved          | Records push, polls for approval (requires second push)                   |
| Pack inspection               | Via JGit `ReceivePack` APIs                | Pack unpacked into local clone for inspection, then HTTP-proxied upstream |
| Resumable push after approval | Same session                               | New push to `/proxy/` re-run detects prior approval                       |

Both modes share the same validation logic and push store. Both are always active for every configured provider — there
is currently no per-provider toggle to disable one mode.

### Proposals (a dedicated listener per provider)

_Available since v1.4.0, opt-in per provider — see [docs/internals/SCM_API_PROXY.md](internals/SCM_API_PROXY.md)._

A third HTTP surface, opt-in per provider (`providers.<name>.proposals.enabled`), for SCM CLI tools rather than git
itself — proxying `gh`'s issue/PR, `glab`'s issue/MR, and `tea`/`fj`'s issue/PR create-edit-comment-review traffic
instead of a git push. Unlike the two modes above, it does not touch a local repository clone; it inspects and relays a
small request/response pair.

Unlike server mode and the transparent proxy, which share the main port under path prefixes, **each enabled provider
gets its own listener** (`providers.<name>.proposals.port`) with its dialect mounted at that listener's root —
`/api/graphql`, `/api/v4/*`, `/api/v1/*`. This is forced by the clients: `gh` and `fj` address the API from the host
root and discard any path prefix, and a single shared root listener would collide between two instances of the same
platform. `registerScmApiListeners` binds each context to its connector using Jetty's `"@connectorName"` virtual-host
form, and relaxes URI compliance on the GitLab and Gitea/Forgejo connectors so an encoded separator isn't rejected as an
ambiguous path separator — GitLab names a project as one `owner%2Frepo` segment, and Gitea encodes a repository-relative
file path into one segment of its blob endpoints. The GitHub connector keeps the strict default. The relaxation only
gets those requests past the parser; `ScmApiRestPathPolicy` decides where a `%2F` is actually permitted, per dialect.

The three platforms use genuinely different wire formats, so each gets its own filter chain rather than one shared
pipeline forced to fit all — the chains are plain `jakarta.servlet.Filter`s (not `FogwallFilter`s — the git-specific
`GitRequestDetails`/`PushStep` request model doesn't apply here), registered by `FogwallServletRegistrar`:

```
GitHub (GraphQL), via registerScmApiProxy:
ScmApiAuditFilter (outermost, try/finally — one record per mutation, plus refusals of authenticated callers)
  └─ ScmApiAuthenticateFilter (token → fogwall identity, via the same PushIdentityResolver git push uses)
       └─ ScmApiGitHubGateFilter (parse → allowlist/resolve/authorize a mutation, or provider-level-gate a read)
            └─ ScmApiContentInspectionFilter (blocked terms, secrets, PII bundles over the whole payload)
                 └─ ScmApiGraphQlForwardServlet (relays to the GraphQL endpoint with the caller's own token)

GitLab (REST), via registerScmApiProxyGitLab:
ScmApiAuditFilter (same as above)
  └─ ScmApiAuthenticateFilter (same as above)
       └─ ScmApiGitLabGateFilter (allowlist method+path → authorize using owner/repo straight from the URL)
            └─ ScmApiContentInspectionFilter (same as above)
                 └─ ScmApiRestForwardServlet (relays the sub-path/query/body to the provider's REST API base URL)

Gitea/Forgejo (REST), via registerScmApiProxyForgejo:
ScmApiAuditFilter (same as above)
  └─ ScmApiAuthenticateFilter (same as above)
       └─ ScmApiUserAgentFilter (classify + audit the client; optionally refuse non-CLI callers)
            └─ ScmApiForgejoGateFilter (same shape as GitLab's, different allowlist table)
                 └─ ScmApiContentInspectionFilter (same as above)
                      └─ ScmApiRestForwardServlet (shared with the GitLab dialect)
```

(`ScmApiUserAgentFilter` sits in all three chains; it is shown once, above, to keep the other two readable.)

Mechanics that carry the actual security decisions:

- **AST-based mutation allowlisting (GitHub).** The GraphQL request body is parsed (`graphql-java`) into a real AST; the
  allowlist matches on the parsed mutation's schema field name, never a substring of the raw query text — a client alias
  or a string literal containing a mutation name can't spoof the check.
- **Opaque node-ID resolution (GitHub only).** A GraphQL mutation references its target only by an opaque node ID, never
  `owner/repo` — `GitHubNodeIdResolver` resolves it (cached, with a TTL that is a security parameter, not just a perf
  knob: a node ID can outlive a repo rename/transfer) before `RepoPermissionService.isAllowedToPropose` can run. GitLab
  has no equivalent step: its REST calls carry `owner/repo` directly in the URL (verified from live `glab` captures —
  see docs/internals/SCM_API_PROXY.md), so `ScmApiGitLabGateFilter` reads the authorization target straight off the
  matched path via `GitLabRestAllowlist`.
- **One dialect for `tea` and `fj`.** The two Gitea/Forgejo CLIs speak the same server API and differ only in which
  subset they use, so `ForgejoRestAllowlist` is the union of both. The union is load-bearing: `tea pr close` sends
  `PATCH /pulls/{n}` while `fj pr close` sends `PATCH /issues/{n}`, so allowlisting one form silently breaks the other
  CLI. They are deliberately **not** told apart by `User-Agent` — that header is caller-controlled, so branching
  authorization on it would let a caller select the looser rule set.
- **`User-Agent` is evidence, never an input to a decision.** `ScmApiUserAgentFilter` records the raw header (each CLI
  advertises its version, the anchor for spotting a wire-format change after an upgrade) and can optionally refuse
  unrecognised client types. It is strictly subtractive: enabling it only ever denies more, so a forged header buys
  nothing beyond the baseline the allowlist and permission engine already enforce.
- **Allowlists match the raw, undecoded URI.** `getPathInfo()` is decoded by the container, which would split GitLab's
  `acme%2Fwidgets` into two segments — turning every `glab` mutation into a fail-closed denial, and in principle letting
  an encoded slash shift which repository is authorized. `ScmApiRestPath` reads `getRequestURI()` instead.
- **Content inspection covers the payload, not a field list.** `ProposalContentInspector` runs `proposals.block`,
  gitleaks and the content-pattern bundles over the raw bytes, every JSON key and scalar at any depth, the query string
  in both forms, and — for GitHub — the GraphQL query's own literals. It **fails closed**: unlike the push path, a
  proposal that cannot be scanned is refused, because a forwarded one has already published its text upstream. For the
  same reason the PII bundles block here rather than warning as they do on a push — a warning needs a reviewer, and this
  path holds nothing for one to look at.
- **Reads stay cheap in all dialects.** No dialect resolves or permission-checks reads individually — an authenticated
  caller's reads are forwarded, keeping the default read cost near pass-through.
- **No URL rule layer.** `UrlRuleRegistry` gates the git path because a fetch of a public repository arrives with no
  credential, leaving the URL as the only thing to match on. Every request here is authenticated —
  `ScmApiAuthenticateFilter` refuses a missing token and one that resolves to no fogwall user — so authorization runs
  against the caller directly, through `RepoPermissionService`.

---

## Request flow

### Server mode push

```
git push → /server/<provider>/<owner>/<repo>.git
             │
             ▼
     ServerRepositoryResolver
       • clone/fetch upstream repo locally
       • extract credentials from Authorization header
             │
             ▼
     ServerReceivePackFactory
       • assemble hook chain (see below)
             │
       ┌─────┴──────────────────────────────────┐
       │  Pre-receive hooks (ordered)            │
       │  1. PushStorePersistenceHook            │  record RECEIVED
       │  2. Validation hooks (see below)        │  emit per-step sideband messages
       │  3. PushStorePersistenceHook            │  record PENDING or BLOCKED
       │  4. ApprovalPreReceiveHook              │  block until approved / auto-approve
       └─────┬──────────────────────────────────┘
             │  (if approved)
       ┌─────┴──────────────────────────────────┐
       │  Post-receive hooks                     │
       │  1. ForwardingPostReceiveHook           │  push to upstream with dev's credentials
       │  2. PushStorePersistenceHook            │  record FORWARDED or ERROR
       └─────────────────────────────────────────┘
```

### Authenticating a server mode request

Server mode cannot forward a request it has no credentials for, and a git client only sends credentials after a 401
challenge — so `BasicAuthChallengeFilter` has to decide, before the servlet runs, whether this request needs one.

Push is unambiguous: the push is forwarded upstream using the developer's own token, so it is always challenged.

Fetch is not. The mirror is cloned from upstream on every open, so a fetch of a private repository must be able to carry
credentials — but challenging every fetch makes public repositories unclonable by anyone who has no credential to offer,
and a client that answers the challenge with an unrelated or expired token is rejected by providers such as GitHub even
on a repository they would have served anonymously. Guessing in either direction breaks a real workflow.

So fogwall asks upstream instead. `UpstreamAuthProbe` issues the git advertisement itself —
`GET <repo>/info/refs?service=git-upload-pack`, no credentials — and reads the answer: `200` means anonymous reads are
served, anything else means they are not. That is provider-agnostic, needs no REST API and no per-provider visibility
field, and follows the repository's real visibility rather than a configured assumption.

Two properties keep it cheap and safe. Only an _unauthenticated_ fetch probes at all — a request already carrying an
`Authorization` header is passed straight through — and verdicts are cached per repository, so a burst of anonymous
clones costs one upstream round trip. Any unclear answer (timeout, 404, 5xx) is treated as "credentials required",
because a probe that cannot reach upstream must never be the reason a repository becomes anonymously readable.

The transparent proxy needs none of this: it forwards to upstream directly, so upstream issues its own challenge. SSH
authenticates by key before any git command runs.

### Transparent proxy push

```
git push → /proxy/<provider>/<owner>/<repo>.git
             │
             ▼
     Servlet filter chain (ordered)
       ParseGitRequestFilter      extract pack metadata from packet lines
       EnrichPushCommitsFilter    clone/fetch upstream repo; unpack inflight pack into a per-request quarantine; walk commit range
       AllowApprovedPushFilter    prior-approved? skip validation, proxy directly
       UrlRuleAggregateFilter     evaluate ALLOW/DENY rules
       CheckUserPushPermissionFilter   resolve identity; check repo permissions
       CommitAttributionPolicyFilter  verify commit author/committer email
       [content validation filters — see below]
       ValidationSummaryFilter    collect all issues
       PushFinalizerFilter        save push record; wait for approval if required
             │
             ▼
     FogwallServlet (Jetty AsyncProxyServlet)
       • HTTP proxy pass-through to upstream
       • on response: update push record → FORWARDED or ERROR
```

#### Per-request object quarantine

Validation has to read a push's objects before it can decide anything about them, but the mirror behind
`/proxy/<provider>/...` is shared by every request for that repository. Unpacking straight into it means a rejected push
leaves its content there permanently — including the content policy just refused.

`QuarantineObjectStore` gives each push its own scratch object store instead:

- the quarantine `Repository` shares the mirror's **git directory**, so it sees the mirror's refs and can still answer
  "what does this push actually introduce";
- its **object directory** is a temporary directory, so every write lands there;
- the mirror's object directory is registered as an **alternate**, which is what lets thin-pack deltas resolve against
  objects the mirror already has.

Downstream filters receive the quarantine as `GitRequestDetails.localRepository`, so they see the union: mirror contents
plus this push. `EnrichPushCommitsFilter` wraps the rest of the chain in try-finally and deletes the quarantine when the
request ends.

In this mode nothing is ever promoted back into the mirror. An accepted push's objects reach it the same way everything
else does — by being fetched from upstream once they exist there — which keeps the mirror a reflection of upstream
rather than an accumulation of everything anyone attempted. (Server mode differs; see below.) If a quarantine cannot be
created the filter logs a warning and falls back to the mirror: the loss is disk hygiene, not a validation result, so it
is not worth failing a push over.

This is the same shape as git's own `receive-pack` quarantine (`tmp_objdir`, exposed to hooks as `GIT_QUARANTINE_PATH`):
temporary object directory, real one as an alternate, hooks run against that view. JGit has no equivalent, hence the
local implementation. Note it is roughly the _inverse_ of a worktree — a worktree shares the object database and
isolates the index and HEAD, whereas this shares refs and isolates objects, so a worktree would not help here.

The one deliberate departure from git's version is the last step: git migrates objects into the real store on success,
because for git the receive is authoritative and those objects have nowhere else to come from. Here the mirror is a
cache of upstream, so not promoting is both simpler and a stronger guarantee.

Server mode quarantines too, with one difference. There JGit's `ReceivePack` applies the ref updates to the shared git
directory once the pre-receive hooks pass, so the objects those refs name have to be in the mirror by then — discarding
them would leave the mirror pointing at objects that no longer exist. `QuarantinePromotionHook` runs as the last
pre-receive hook and moves the objects across only when nothing has been rejected; if it fails, it rejects the push,
because a half-promoted push is worse than a refused one. The HTTP path's quarantine is torn down by
`QuarantineCleanupFilter` on the server-mode mapping; the SSH path scopes it to `SshGitReceiveCommand`, which has no
servlet request to hang it off.

So both modes discard a rejected push's objects. The transparent proxy additionally never promotes, because it never
applies ref updates. There, JGit's `ReceivePack` owns the inserter and writes into the mirror before the pre-receive
hooks run, so the same guarantee needs a different mechanism.

Both mirrors — server mode's and the transparent proxy's — are held by a `LocalRepositoryCache`, and the dashboard
exposes each one for operator inspection and manual invalidation over `/api/admin/cache` (`ROLE_ADMIN`). The cache is
in-memory and per-pod, so this is a per-pod operational view, not distributed state; invalidating an entry deletes its
local clone (keeping the cache root) so the next access re-clones from upstream — the recovery path for a stale or
poisoned mirror without a restart.

**Concurrency.** A mirror is shared across concurrent requests, so the cache coordinates access at three points. First
clones are serialized on a **per-repository lock** (keyed on the cache key): threads racing on the first access to the
same repo dedupe to one clone, while first clones of _different_ repos run in parallel. Upstream refreshes for a repo
are serialized on that repo's own lock so two fetches never write the same bare repo at once. The **serve path is
deliberately left lock-free**, though: a refresh (writer) may run while an upload/receive or content inspection (reader)
uses the same mirror, and readers are not blocked. This is safe because a `git fetch` is additive — it never deletes the
objects a concurrent reader is serving — so the worst normal outcome is that the reader sees a slightly stale snapshot
and the client re-fetches. A read/write lock was rejected because, to be safe, it would either starve refreshes under
sustained fetch traffic or tax the hot serve path; the full rationale (and the shallow-mirror `cloneDepth=0` escape
hatch for the one transient edge case) lives on `LocalRepositoryCache`. Separately, JGit's process-global pack-window
cache is tuned once at startup for a many-mirror server (larger `packedGitLimit`/open-file budget, mmap off) rather than
its desktop-git defaults; these are engine internals, intentionally not fogwall config keys.

---

## Validation pipeline

Both modes run equivalent validation logic. The filter/hook names differ, but they check the same things in the same
order.

| Order   | What it checks                                                                        |
| ------- | ------------------------------------------------------------------------------------- |
| 50–199  | URL allow/deny rules (config + DB-sourced)                                            |
| 150     | User identity — developer must have a proxy account and push permission for this repo |
| 160     | Author attribution — git commit author must match the authenticated proxy user        |
| 210     | Non-empty push — at least one new commit                                              |
| 220     | Hidden commits — pack must not contain commits outside the declared push range        |
| 250–260 | Author email and commit message patterns (allow/block regex)                          |
| 265     | Content pattern scan — commit messages (national ID/PII bundles) — **WARN-only**      |
| 290     | Binary blob detection — magic-byte signature sniffing, with MIME-type allow/deny      |
| 300     | Diff content scan (blocked literals and patterns)                                     |
| 320     | GPG commit signature validation                                                       |
| 340     | Secret scanning (gitleaks)                                                            |
| 345     | Content pattern scan — diff (national ID/PII bundles) — **WARN-only**                 |

Each step records a `PushStep` in the push record with a `StepStatus` of `PASS`, `WARN`, `FAIL`, `BLOCKED`, or
`SKIPPED`. `WARN` is a first-class outcome, not a lesser form of `FAIL` — a WARN step never blocks the push, it only
surfaces a finding on the push record for the reviewer's attention. The content-pattern (PII/national-ID) filters are
WARN-only by design on this pipeline, where a reviewer sees the finding; the proposals surface runs the same bundles as
a blocking check, having no reviewer to show a warning to. `CommitAttributionPolicyFilter` (order 160, commit-email
attribution) can also run in `warn` mode via `commit.attribution-policy`. All steps always run (fail-fast is
configurable); issues accumulate and are reported together.

---

## Core abstractions

### Provider (`FogwallProvider`)

A provider represents one upstream Git hosting service. It carries the upstream HTTP base URI, the URL path prefix the
proxy listens on, and optional API calls for identity resolution.

Built-in providers: `github`, `gitlab`, `bitbucket`, `forgejo`/`gitea`, `codeberg`. Custom generic providers can be
declared in config with an arbitrary name and URI.

**Transport is a property of the provider, not a separate entry.** A single provider entry can serve HTTP (its `uri`),
SSH (an `ssh:` sub-block exposing `getSshUri()`), or both — the `FogwallServletRegistrar` registers the HTTP servlets
for any provider with an HTTP URI, and the `SshServerRegistrar` registers an SSH route for any provider whose
`getSshUri()` is present, both keyed by the same `servletPath()`. Because both transports resolve to one provider name,
identity resolution, permissions, and OAuth links apply uniformly across them — there is no `github` / `github-ssh`
duplication.

Providers that implement `TokenIdentityProvider` can resolve an SCM username from a push token by calling the hosting
service's API (e.g. `GET /user` for GitHub). This is how the proxy maps a credential to a known identity without
requiring the developer to use their SCM username as the HTTP Basic username. These mappings are cached in the database
for performance & to avoid excess API calls to respect rate limits. The cache expires entries on the order of 7 days by
default - PAT tokens have a configurable lifespan, so this strikes a balance between keeping up with token changes and
minimizing API calls.

### Push store (`PushStore`)

Every push attempt produces a `PushRecord`. The record tracks the full lifecycle:
`RECEIVED → PENDING → APPROVED → FORWARDED`, or `RECEIVED → BLOCKED`, or `RECEIVED → PENDING → REJECTED`. It embeds an
ordered list of `PushStep` entries (one per validation step) and a list of commits.

The push store is the integration point for the approval workflow: the dashboard reads push records from it, writes
approvals/rejections to it, and the proxy polls it.

Backends: H2 (dev), PostgreSQL, MySQL, MariaDB, MongoDB, in-memory (testing).

### Approval gateway (`ApprovalGateway`)

Decouples the proxy from the approval mechanism. Two implementations today, with the interface designed for external
integrations:

- **`AutoApprovalGateway`** — clean pushes are approved immediately (no human review)
- **`UiApprovalGateway`** — proxy writes the push record and polls the store; a reviewer approves or rejects via the
  dashboard REST API

The `ApprovalGateway` interface is the extension point for external approval workflows — for example, a
`ServiceNowApprovalGateway` (planned) that would create a request ticket and wait for external approval before
forwarding the push.

### User store and identity

The proxy maintains its own user registry, separate from any upstream SCM accounts.

```
UserEntry (proxy user)
  ├── username + password hash (BCrypt / {noop} in dev/local auth modes)
  ├── emails[]          claimed email addresses (used for author attribution)
  ├── scmIdentities[]   links to upstream SCM accounts
  │     ├── provider    e.g. "github", "gitlab"
  │     └── username    the developer's SCM login
  └── roles[]           USER, ADMIN
```

When a developer pushes with `Authorization: Basic <token>`, the proxy:

1. Calls the provider API with the token to get the developer's SCM username.
2. Looks up a proxy user whose `scmIdentities` has a matching `(provider, scmUsername)` entry.
3. Uses the resolved `UserEntry` for permission checks and author attribution.

Resolution results are cached in the database (7-day TTL by default).

Backends: static YAML list, JDBC (H2/Postgres), MongoDB, or a composite that checks both.

---

## Deployment modes

### Proxy only (`fogwall-server`)

`FogwallJettyApplication` boots a plain Jetty server. It loads YAML config (base `fogwall.yml` + profile overlays +
environment variable overrides), builds the `FogwallContext`, and registers both proxy modes for every provider. There
is no Spring context, no dashboard, and no REST API — just the git servlets on `/server/*` and `/proxy/*`.

The approval gateway defaults to `AutoApprovalGateway` — clean pushes go straight through with no human review. A
`LiveConfigLoader` watches the config file and hot-reloads commit validation rules (email patterns, message patterns,
diff scan rules) without restarting the server.

Everything is configured upfront in YAML: users, permissions, URL allow/deny rules, and validation settings. The
standalone server has no REST API, so there is no way to create or modify users, permissions, or rules at runtime. This
makes it well-suited for enforcement-only deployments where configuration is managed as code — CI pipelines, automated
environments, or setups where an external system like ServiceNow handles approval.

```
./gradlew :fogwall-server:run     # start (FOGWALL_CONFIG_PROFILES=local by default)
./gradlew :fogwall-server:stop    # stop via PID file
```

### Proxy + dashboard (`fogwall-dashboard`)

`FogwallDashboardApplication` builds the same `FogwallContext` and calls the same `FogwallServletRegistrar`, then layers
on a Spring MVC `DispatcherServlet` at `/*`. Jetty's servlet path-matching rules give the more-specific git paths
(`/server/*`, `/proxy/*`) precedence, so the Spring servlet only handles `/api/*`, `/dashboard/*`, `/login`, and static
assets.

This is Spring MVC and Spring Security directly on a Jetty `Server` we construct and configure ourselves — not Spring
Boot. Boot's auto-configuration assumes it owns the embedded servlet container: it wants to build the `Server`, wire the
connectors, and register its own default servlet mappings. fogwall needs the opposite — the JGit `ReceivePack` servlets
and the git-protocol filter chain must be registered on that same Jetty instance with precise path and order control
(see [Two proxy modes](#two-proxy-modes) above), and `fogwall-server` needs to run the identical servlet setup with zero
Spring on the classpath at all. Wiring Spring MVC onto a Jetty server we already built is straightforward; carving a
Boot application apart to let something else own the container is fighting the framework. So the dashboard module adds
Spring as a set of servlets/filters registered onto fogwall's Jetty server, not the other way around.

Spring Security is registered as a filter chain on a narrow set of paths (`/api/**`, `/login`, `/logout`, `/`,
`/oauth2/**`) — deliberately not on git paths, to avoid interfering with async streaming. Four auth providers are
supported: local (BCrypt from YAML), LDAP, Active Directory, and OIDC (authorization code flow). When using an IdP
(LDAP/AD/OIDC), users are automatically provisioned in the database on first login.

The approval gateway is always `UiApprovalGateway` in this mode, regardless of config. Pushes that pass validation land
in `PENDING` status; a reviewer approves or rejects via the dashboard UI, and the proxy polls the push store for the
decision.

The dashboard adds runtime management that the standalone server does not have: user and permission CRUD, URL rule
management, push history queries, and the approval workflow UI. This is the recommended mode for operational deployments
where administrators need to manage users, review pushes, and adjust policies without redeploying.

The React frontend is built by Vite at Gradle build time and copied into the JAR as static resources. For local
development, Vite's dev server can run separately and proxy `/api` calls to the backend.

```
./gradlew :fogwall-dashboard:run  # start (dashboard at http://localhost:8080/)
./gradlew :fogwall-dashboard:stop # stop via PID file
```

### Docker

The primary production distribution is a Docker image. The Dockerfile builds the dashboard module's distribution
(including the frontend), producing a self-contained image with a Temurin JRE. Config overrides are mounted at
`/app/conf/fogwall-local.yml`.

---

## Advanced use cases

### Private-to-private proxying

The provider `uri` does not have to be a public SaaS host. Any Git HTTP server works:

```yaml
providers:
  internal-github:
    type: github
    uri: https://github.mycompany.com
  acquired-gitlab:
    type: gitlab
    uri: https://git.acquiredco.internal
```

Pushes to `/server/internal-github/...` and `/server/acquired-gitlab/...` go through the same validation pipeline. The
proxy validates identity, author email, commit messages, and diff content before forwarding to the appropriate internal
host. This is useful for enforcing consistent push policy across multiple internally-hosted Git services.

### Credential rewriting (planned)

A planned extension is proxy-level credential substitution: the developer authenticates to the proxy with their own
identity, but the forwarded push uses a proxy-managed service account credential for the upstream.

Motivating scenario: an acquired company (Org A) has developers with credentials for Org A's Git host, but they need to
push to shared repositories on the acquiring company's Git host (Org B). Org A developers don't have Org B credentials.
The proxy can:

1. Accept the Org A developer's push (authenticated against their proxy user record).
2. Validate author attribution, commit messages, and diff content normally — the developer's identity is still enforced.
3. Forward the push to Org B's Git host using a proxy-managed service account that has write access there.

This separates authentication (who you are, proven by your token against Org A's API) from forwarding credentials (what
gets sent upstream). All existing validation steps remain active — the credential rewrite only changes what appears in
the `Authorization` header on the forwarded request.

---

## What this architecture enables

The transparent proxy mode replicates what finos/git-proxy does today: intercept, inspect, and forward. The server mode
— where the proxy owns the full pack lifecycle via JGit — opens up use cases that are not possible with a pass-through
HTTP proxy:

- **Deferred forwarding** — the developer's push is received and acknowledged immediately. The pack is stored locally
  while an approval process runs (hours, days); forwarding happens asynchronously once approved. This eliminates the
  problem of holding a git client session open during a long review window. Note: the current implementation forwards
  within the same session using the client's in-memory credentials (see
  [Credential flow](internals/JGIT_INFRASTRUCTURE.md#credential-flow)); true async deferred forwarding would require a
  separate credential design and is tracked as a backlog item.

- **Multi-upstream push** — a single received pack can be forwarded to more than one upstream remote, keeping shared
  repositories (CI workflows, shared libraries) in sync across separate Git hosts without requiring the developer to
  push to each one individually.

- **Upstream buffering** — when an upstream SCM is slow or unavailable, the proxy can hold received packs and retry with
  backoff rather than failing the developer's push immediately.

- **Checkpoint resumption** — because each validation step is persisted as a `PushStep`, a re-push of the same commits
  can skip steps that already passed. This matters most when the chain includes expensive external calls (secret
  scanning, external policy engines) — the developer gets credit for work already done rather than waiting through the
  full chain again.

- **Streaming LLM analysis** — the sideband channel in server mode can stream an LLM's advisory review of the diff back
  to the developer's terminal in real time, giving immediate feedback alongside the existing rule-based checks.

These are tracked as individual issues in the backlog; the architecture is designed to support them incrementally.
