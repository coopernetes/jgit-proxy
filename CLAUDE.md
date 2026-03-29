# jgit-proxy — Claude context

Java rewrite of [FINOS git-proxy](https://github.com/finos/git-proxy) (Node.js). Proxies git push/fetch operations and enforces security/compliance checks.

## Repository layout

| Module | Purpose |
|--------|---------|
| `jgit-proxy-core` | Shared library: filter chain, JGit hooks, push store, provider model |
| `jgit-proxy-server` | Standalone Jetty server (`GitProxyJettyApplication`) |

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
# Start (background — writes PID to jgit-proxy-server/build/jgit-proxy.pid)
./gradlew :jgit-proxy-server:run &

# Stop via Gradle task (reads PID file)
./gradlew :jgit-proxy-server:stop

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

## Branch / PR target

Default branch for PRs: **`jetty`**
