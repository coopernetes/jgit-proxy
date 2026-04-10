# Architecture

git-proxy-java is a Git push proxy that sits between developers and upstream Git hosting providers (GitHub, GitLab,
Bitbucket, Forgejo, etc.). Every push travels through a validation and approval pipeline before reaching the upstream
remote. Fetch/clone traffic is audited but not blocked.

If you're familiar with [finos/git-proxy](https://github.com/finos/git-proxy), the Java rewrite shares the same
conceptual model: an ordered chain of steps that inspect and act on each push, a push store for audit and approval
state, and pluggable providers for different Git hosts. The main structural difference is that git-proxy-java offers two
distinct proxy modes with different tradeoffs.

---

## Project structure

The codebase is a multi-module Gradle build. Dependencies flow upward — `core` is depended on by `server`, `server` is
depended on by `dashboard`.

```
git-proxy-java-core
  Shared library. Contains all validation logic (hooks + filters), the push store, provider
  model, identity resolution, approval abstraction, and database migrations (Flyway). Both
  proxy modes are implemented here. No application entry point — this is a library.

git-proxy-java-server
  Standalone Jetty application (GitProxyJettyApplication). Registers both proxy modes for
  every configured provider, loads YAML config via Gestalt, and starts a plain Jetty server.
  No Spring, no dashboard, no REST API. This module also owns the shared servlet registrar
  (GitProxyServletRegistrar) and configuration builder (JettyConfigurationBuilder) used by
  the dashboard module.

git-proxy-java-dashboard
  Full application (GitProxyWithDashboardApplication). Depends on both core and server.
  Adds Spring MVC (DispatcherServlet at /*), Spring Security, a REST API (/api/*), and a
  React SPA (built with Vite, bundled into the JAR as static resources). Approval workflow
  is always UI-driven in this mode.
```

The server module defines a `GitProxyContext` record that bundles all runtime singletons (push store, user store,
approval gateway, identity resolver, repository caches, TLS config). Both application entry points build this context
from config and pass it to `GitProxyServletRegistrar`, which registers the same servlets and filters regardless of
whether the dashboard is present.

---

## Two proxy modes

### Store-and-forward (`/push/<provider>/<owner>/<repo>.git`)

The upstream repository is cloned locally on first access. When a developer pushes, JGit's `ReceivePack` receives the
entire pack locally before anything is forwarded. Pre-receive hooks validate the push; if it passes (and any required
approval is granted), a post-receive hook forwards it to upstream using the developer's credentials.

This mode can stream progress messages to the git client in real time via JGit sideband packets — so the developer sees
`remote: [step] author email OK` lines as each validation step completes.

### Transparent proxy (`/proxy/<provider>/<owner>/<repo>.git`)

An HTTP reverse proxy forwards the git protocol directly to upstream. A servlet filter chain inspects the pack data
before it reaches upstream. Validation results are collected and, if anything fails, a single error response is sent.
The developer's git client is talking to a forwarding proxy, not a JGit endpoint, through a single HTTP request/response
cycle. A temporary local clone is still used to unpack the pack data and walk the commit range for validation, but the
push is forwarded via HTTP proxy rather than a JGit `push` command.

This mode cannot stream incremental feedback, but it does still clone the upstream repo locally for pack inspection — 
see below.

### Choosing a mode

| Concern | Store-and-forward | Transparent proxy                                                         |
|---|---|---------------------------------------------------------------------------|
| Live progress feedback | Yes — per-step sideband messages | No — single terminal response                                             |
| Local storage required | Yes — receives the push into a local clone | Yes — clone needed for pack inspection                                    |
| Approval workflow | Blocks git session until approved | Records push, polls for approval (requires second push)                   |
| Pack inspection | Via JGit `ReceivePack` APIs | Pack unpacked into local clone for inspection, then HTTP-proxied upstream |
| Resumable push after approval | Same session | New push to `/proxy/` re-run detects prior approval                       |

  Both modes share the same validation logic and push store. Both are always active for every configured provider —
  there is currently no per-provider toggle to disable one mode.

---

## Request flow

### Store-and-forward push

```
git push → /push/<provider>/<owner>/<repo>.git
             │
             ▼
     StoreAndForwardRepositoryResolver
       • clone/fetch upstream repo locally
       • extract credentials from Authorization header
             │
             ▼
     StoreAndForwardReceivePackFactory
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

### Transparent proxy push

```
git push → /proxy/<provider>/<owner>/<repo>.git
             │
             ▼
     Servlet filter chain (ordered)
       ParseGitRequestFilter      extract pack metadata from packet lines
       EnrichPushCommitsFilter    clone/fetch upstream repo; unpack inflight pack data into it; walk commit range
       AllowApprovedPushFilter    prior-approved? skip validation, proxy directly
       UrlRuleAggregateFilter     evaluate ALLOW/DENY rules
       CheckUserPushPermissionFilter   resolve identity; check repo permissions
       IdentityVerificationFilter verify git author matches proxy user
       [content validation filters — see below]
       ValidationSummaryFilter    collect all issues
       PushFinalizerFilter        save push record; wait for approval if required
             │
             ▼
     GitProxyServlet (Jetty AsyncProxyServlet)
       • HTTP proxy pass-through to upstream
       • on response: update push record → FORWARDED or ERROR
```

---

## Validation pipeline

Both modes run equivalent validation logic. The filter/hook names differ, but they check the same things in the same
order.

| Order | What it checks |
|---|---|
| 100 | URL allow/deny rules (config + DB-sourced) |
| 150 | User identity — developer must have a proxy account and push permission for this repo |
| 160 | Author attribution — git commit author must match the authenticated proxy user |
| 210 | Non-empty push — at least one new commit |
| 220 | Hidden commits — pack must not contain commits outside the declared push range |
| 240–260 | Author email and commit message patterns (allow/block regex) |
| 280 | Diff content scan (blocked literals and patterns) |
| 290 | Secret scanning (gitleaks) |
| 310 | GPG signature validation |

Each step records a `PushStep` in the push record. All steps always run (fail-fast is configurable); issues accumulate
and are reported together.

---

## Core abstractions

### Provider (`GitProxyProvider`)

A provider represents one upstream Git hosting service. It carries the upstream base URI, the URL path prefix the proxy
listens on, and optional API calls for identity resolution.

Built-in providers: `github`, `gitlab`, `bitbucket`, `forgejo`/`gitea`, `codeberg`. Custom generic providers can be
declared in config with an arbitrary name and URI.

Providers that implement `TokenIdentityProvider` can resolve an SCM username from a push token by calling the hosting
service's API (e.g. `GET /user` for GitHub). This is how the proxy maps a credential to a known identity without
requiring the developer to use their SCM username as the HTTP Basic username. These mappings are cached in the database
for performance & to avoid excess API calls to respect rate limits. The cache expires entries on the order of 7 days by
default - PAT tokens have a configurable lifespan, so this strikes a balance between keeping up with token changes
and minimizing API calls.

### Push store (`PushStore`)

Every push attempt produces a `PushRecord`. The record tracks the full lifecycle: `RECEIVED → PENDING → APPROVED →
FORWARDED`, or `RECEIVED → BLOCKED`, or `RECEIVED → PENDING → REJECTED`. It embeds an ordered list of `PushStep`
entries (one per validation step) and a list of commits.

The push store is the integration point for the approval workflow: the dashboard reads push records from it, writes
approvals/rejections to it, and the proxy polls it.

Backends: H2 (dev), PostgreSQL, MongoDB, in-memory (testing).

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

### Proxy only (`git-proxy-java-server`)

`GitProxyJettyApplication` boots a plain Jetty server. It loads YAML config (base `git-proxy.yml` + profile overlays +
environment variable overrides), builds the `GitProxyContext`, and registers both proxy modes for every provider. There
is no Spring context, no dashboard, and no REST API — just the git servlets on `/push/*` and `/proxy/*`.

The approval gateway defaults to `AutoApprovalGateway` — clean pushes go straight through with no human review. A
`LiveConfigLoader` watches the config file and hot-reloads commit validation rules (email patterns, message patterns,
diff scan rules) without restarting the server.

Everything is configured upfront in YAML: users, permissions, URL allow/deny rules, and validation settings. The
standalone server has no REST API, so there is no way to create or modify users, permissions, or rules at runtime. This
makes it well-suited for enforcement-only deployments where configuration is managed as code — CI pipelines, automated
environments, or setups where an external system like ServiceNow handles approval.

```
./gradlew :git-proxy-java-server:run     # start (GITPROXY_CONFIG_PROFILES=local by default)
./gradlew :git-proxy-java-server:stop    # stop via PID file
```

### Proxy + dashboard (`git-proxy-java-dashboard`)

`GitProxyWithDashboardApplication` builds the same `GitProxyContext` and calls the same `GitProxyServletRegistrar`, then
layers on a Spring MVC `DispatcherServlet` at `/*`. Jetty's servlet path-matching rules give the more-specific git paths
(`/push/*`, `/proxy/*`) precedence, so the Spring servlet only handles `/api/*`, `/dashboard/*`, `/login`, and static
assets.

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
./gradlew :git-proxy-java-dashboard:run  # start (dashboard at http://localhost:8080/)
./gradlew :git-proxy-java-dashboard:stop # stop via PID file
```

### Docker

The primary production distribution is a Docker image. The Dockerfile builds the dashboard module's distribution
(including the frontend), producing a self-contained image with a Temurin JRE. Config overrides are mounted at
`/app/conf/git-proxy-local.yml`.

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

Pushes to `/push/internal-github/...` and `/push/acquired-gitlab/...` go through the same validation pipeline. The
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

The transparent proxy mode replicates what finos/git-proxy does today: intercept, inspect, and forward. The
store-and-forward mode — where the proxy owns the full pack lifecycle via JGit — opens up use cases that are not
possible with a pass-through HTTP proxy:

- **Deferred forwarding** — the developer's push is received and acknowledged immediately. The pack and credentials are
  parked locally while an approval process runs (hours, days). Forwarding happens asynchronously once approved. This
  eliminates the problem of holding a git client session open during a long review window.

- **Multi-upstream push** — a single received pack can be forwarded to more than one upstream remote, keeping shared
  repositories (CI workflows, shared libraries) in sync across separate Git hosts without requiring the developer to
  push to each one individually.

- **Upstream buffering** — when an upstream SCM is slow or unavailable, the proxy can hold received packs and retry
  with backoff rather than failing the developer's push immediately.

- **Checkpoint resumption** — because each validation step is persisted as a `PushStep`, a re-push of the same commits
  can skip steps that already passed. This matters most when the chain includes expensive external calls (secret
  scanning, external policy engines) — the developer gets credit for work already done rather than waiting through the
  full chain again.

- **Streaming LLM analysis** — the sideband channel in store-and-forward mode can stream an LLM's advisory review of
  the diff back to the developer's terminal in real time, giving immediate feedback alongside the existing rule-based
  checks.

These are tracked as individual issues in the backlog; the architecture is designed to support them incrementally.
