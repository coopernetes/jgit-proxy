# jgit-proxy — Claude context

Java rewrite of [FINOS git-proxy](https://github.com/finos/git-proxy) (Node.js). Proxies git push/fetch operations and enforces security/compliance checks.

## Repository layout

| Module | Purpose |
|--------|---------|
| `jgit-proxy-core` | Shared library: filter chain, JGit hooks, push store, provider model, approval abstraction |
| `jgit-proxy-server` | Standalone proxy-only server (`GitProxyJettyApplication`) — no dashboard, no Spring |
| `jgit-proxy-api` | Dashboard + REST API (`GitProxyWithDashboardApplication`) — Spring MVC, approval UI, depends on `jgit-proxy-server` |

## Architecture

Two proxy modes, both configurable per-provider:

- **Store-and-forward** (`/push/<provider>/<owner>/<repo>.git`) — JGit ReceivePack receives the push locally, runs a pre-receive hook chain (`AuthorEmailValidationHook` → `CommitMessageValidationHook` → `ValidationVerifierHook`), then `ForwardingPostReceiveHook` pushes upstream using the client's credentials.
- **Transparent proxy** (`/proxy/<provider>/<owner>/<repo>.git`) — Jetty's `ProxyServlet` forwards the request; a servlet filter chain (`ParseGitRequestFilter` → `EnrichPushCommitsFilter` → validation filters) inspects the pack data before it reaches the upstream.

## Reference implementation

The Node.js original lives at `/home/tom/repos/git-proxy`. Refer to it for the Action/Step model, Sink interface, and filter chain patterns when porting features.

## Build & test

```bash
./gradlew build              # compile + unit tests (no containers)
./gradlew test               # unit tests only (e2e excluded)
./gradlew e2eTest            # e2e tests — requires Docker/Podman
./gradlew spotlessApply      # fix formatting (palantir-java-format)
```

Unit tests live under each module's `src/test/`. E2e tests are in `jgit-proxy-server/src/test/java/org/finos/gitproxy/e2e/` and tagged `@Tag("e2e")`.

## Running the server locally

```bash
# Proxy only (no dashboard, no API):
./gradlew :jgit-proxy-server:run &
./gradlew :jgit-proxy-server:stop

# Proxy + dashboard + REST API (http://localhost:8080/):
./gradlew :jgit-proxy-api:run &
./gradlew :jgit-proxy-api:stop

# Logs: jgit-proxy-server/logs/application.log  (DEBUG for org.finos.gitproxy)
# Default DB: h2-file — persisted to jgit-proxy-server/.data/gitproxy.mv.db
```

## Integration testing (manual)

Use the shell scripts in the repo root against a running server:

```bash
test-push-pass.sh   # store-and-forward — valid commits
test-push-fail.sh   # store-and-forward — commits that should be rejected
test-proxy-pass.sh  # proxy mode — valid commits
test-proxy-fail.sh  # proxy mode — commits that should be rejected
```

To inspect the database, connect to `jgit-proxy-server/.data/gitproxy.mv.db` with the H2 console or the `h2` CLI. The file is created automatically on first run.

## Docker Compose

```bash
docker compose up -d          # jgit-proxy + Gitea (h2-mem database)
bash docker/setup.sh          # one-time: create admin user + test repo in Gitea

# Optional database backends:
docker compose --profile postgres up -d   # swap git-proxy-local.yml for git-proxy-postgres.yml
docker compose --profile mongo up -d      # swap git-proxy-local.yml for git-proxy-mongo.yml
```

Config override file mounted at `/app/conf/git-proxy-local.yml` inside the container. Templates in `docker/`.

## Configuration

`JettyConfigurationLoader` merges (lowest → highest priority):
1. `git-proxy.yml` (classpath, shipped with jar) — defaults + GitHub/GitLab/Bitbucket providers
2. `git-proxy-local.yml` (classpath, optional) — local overrides; put in `/app/conf/` for Docker
3. `GITPROXY_*` environment variables

Supported env vars: `GITPROXY_SERVER_PORT`, `GITPROXY_GITPROXY_BASEPATH`, `GITPROXY_PROVIDERS_<NAME>_ENABLED`.

## Database backends

Configured via `database.type` in YAML. Supported: `memory`, `h2-mem`, `h2-file`, `sqlite`, `postgres`, `mongo`.

## Podman notes

Always use fully qualified image names (e.g. `docker.io/eclipse-temurin:21-jre`). Podman on Fedora enforces short-name resolution and will error without a TTY if bare names are used.

## GitHub Actions workflows

All action steps must be pinned to a commit hash. After adding or updating any action reference in `.github/workflows/`, run:

```bash
ratchet pin .github/workflows/<file>.yml
```

This rewrites version tags (e.g. `@v5`) to their resolved commit SHA and adds a `# ratchet:` comment preserving the original tag for readability.

## Branch / PR target

Default branch for PRs: **`jetty`**

## Project vision

The high-level roadmap is captured in [this design gist](https://gist.github.com/coopernetes/d02d48efa759282ff8187da0d5dcae64). Key tracks in priority order:

1. **Sideband streaming UX** (coopernetes/jgit-proxy#5) — stream real-time `remote:` progress during push validation using `SideBandOutputStream`. Supports ANSI color (respect `NO_COLOR`) and emoji (toggleable via `GITPROXY_NO_EMOJI`). Heartbeat packets every 10s to defeat idle timeouts. Sideband channel semantics: `0x02` = progress, `0x03` = fatal error.
2. **Checkpoint-based resumption** — persist filter results (not packs) keyed by `commitFrom__commitTo + branch`. On re-push, skip already-completed steps. TTL ~15–30 min. The expensive work is external API calls (ServiceNow, scanners); packs are cheap to resend.
3. **Lifecycle hooks / dispatcher pattern** — distinguish blocking lifecycle hooks (need sideband access) from non-blocking observers (fire-and-forget notifications). Store-and-forward dispatcher: receive pack → ACK client → run chain async → push upstream → notify.
4. **Concurrent / DAG-based pipeline** — steps declare dependencies; independent steps run in parallel. Reduces wall-clock latency for pipelines with multiple external API calls. Design after extension points stabilize.
5. **SCM OAuth Apps & upstream handoff** (finos/git-proxy#1450) — git-proxy as an OSPO platform managing the full contribution lifecycle: push → compliance → create PR on upstream SCM via OAuth token. PRs linked to push records for audit trail.
6. **SSH protocol support** (stretch) — intercept SSH git traffic via custom SSH server (JGit + Apache MINA SSHD) or SOCKS5 proxy. Needs research; not a priority until HTTP story is solid.

Things to fix along the way: SPI/ServiceLoader for filter discovery, YAML config for custom filters, rename `onPull()` → `onFetch()`, contribute Entra ID OIDC (private key/JWKS) support to git-proxy upstream.
