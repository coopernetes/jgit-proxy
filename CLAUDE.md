# jgit-proxy — Claude context

## Repository layout

| Module | Purpose |
|--------|---------|
| `jgit-proxy-core` | Shared library: filter chain, JGit hooks, push store, provider model, approval abstraction |
| `jgit-proxy-server` | Standalone proxy-only server (`GitProxyJettyApplication`) — no dashboard, no Spring |
| `jgit-proxy-dashboard` | Dashboard + REST API (`GitProxyWithDashboardApplication`) — Spring MVC, approval UI, depends on `jgit-proxy-server` |

## Architecture

Two proxy modes, both configurable per-provider:

- **Store-and-forward** (`/push/<provider>/<owner>/<repo>.git`) — JGit ReceivePack receives the push locally, runs a pre-receive hook chain (`AuthorEmailValidationHook` → `CommitMessageValidationHook` → `ValidationVerifierHook`), then `ForwardingPostReceiveHook` pushes upstream using the client's credentials.
- **Transparent proxy** (`/proxy/<provider>/<owner>/<repo>.git`) — Jetty's `ProxyServlet` forwards the request; a servlet filter chain (`ParseGitRequestFilter` → `EnrichPushCommitsFilter` → validation filters) inspects the pack data before it reaches the upstream.

## Client output — streaming constraint

**Store-and-forward** uses JGit `ReceivePack` pre-receive hooks. Each hook can call `rp.sendMessage()` at any point and the message streams to the git client immediately as a sideband progress packet (`remote: …`). This is how per-step progress lines are sent live.

**Transparent proxy** uses servlet filters. The HTTP response is a single buffered reply — there is no mechanism to stream partial output mid-filter-chain. Validation filters must _accumulate_ their result and return; `ValidationSummaryFilter` (order 4999) and `PushFinalizerFilter` (order 5000) collect everything and write one response at the end using `sendGitError`.

## Reference implementation

The Node.js original lives at [finos/git-proxy](https://github.com/finos/git-proxy). Refer to it for the Action/Step model, Sink interface, and filter chain patterns when porting features.

## Build & test

```bash
./gradlew spotlessApply      # fix formatting (palantir-java-format) — run this before build
./gradlew build              # compile + unit tests (no containers)
./gradlew test               # unit tests only (e2e excluded)
./gradlew e2eTest            # e2e tests — requires Docker/Podman
```

Unit tests live under each module's `src/test/`. E2e tests are in `jgit-proxy-server/src/test/java/org/finos/gitproxy/e2e/` and tagged `@Tag("e2e")`.

## Running the server locally

```bash
# Proxy only (no dashboard, no API):
./gradlew :jgit-proxy-server:run &
./gradlew :jgit-proxy-server:stop

# Proxy + dashboard + REST API (http://localhost:8080/):
./gradlew :jgit-proxy-dashboard:run &
./gradlew :jgit-proxy-dashboard:stop

# Logs: jgit-proxy-server/logs/application.log  (DEBUG for org.finos.gitproxy)
# Default DB: h2-file — persisted to jgit-proxy-server/.data/gitproxy.mv.db
```

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

Refer to [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for detailed docs on YAML config structure, environment variable overrides, and provider-specific settings.

## Roadmap & architecture

There are gists linked in the root README. Only look up these details as necessary for planning refactors or understanding design rationale. The code itself is the source of truth for how the system works ultimately.
