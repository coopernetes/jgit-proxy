# Contributing to jgit-proxy

## Prerequisites

The easiest way to get the right toolchain versions is [mise](https://mise.jdx.dev/):

```shell
mise install   # installs Java 21 (Temurin) and Node 24 as defined in mise.toml
```

If you prefer to manage tools yourself, you need:

- Java 21+
- Node 24+
- Docker or Podman (for e2e tests and Docker Compose workflows)

Gradle itself is included via the wrapper — no separate installation needed.

## Build

```shell
./gradlew spotlessApply      # fix formatting (palantir-java-format) — run before build
./gradlew build              # compile + unit tests
```

Formatting is enforced in CI. Always run `spotlessApply` before pushing.

## Running the server locally

### Proxy only (no dashboard)

```shell
./gradlew :jgit-proxy-server:run
```

Listens on `http://localhost:8080`. Logs go to `jgit-proxy-server/logs/application.log`. Stop with:

```shell
./gradlew :jgit-proxy-server:stop
```

### Dashboard + REST API

```shell
./gradlew :jgit-proxy-dashboard:run
```

Opens the approval dashboard at `http://localhost:8080/`. Stop with:

```shell
./gradlew :jgit-proxy-dashboard:stop
```

The dashboard module always uses UI-mode approval (pushes block until manually approved). The standalone server defaults to auto-approve.

### Local config override

Copy `jgit-proxy-server/src/main/resources/git-proxy.yml` to `git-proxy-local.yml` in the same directory. The local file takes priority. At minimum, add a provider and an allowlisted repo slug:

```yaml
git-proxy:
  providers:
    github:
      enabled: true
  filters:
    whitelists:
      - enabled: true
        order: 1100
        operations: [FETCH, PUSH]
        providers: [github]
        slugs:
          - owner/repo
```

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for the full reference.

## Tests

### Unit tests

```shell
./gradlew test
```

Unit tests live under each module's `src/test/`. They run without containers.

### E2E tests (JUnit, requires Docker/Podman)

```shell
./gradlew e2eTest
```

These start a containerised Gitea instance and a live Jetty proxy in-process. They are tagged `@Tag("e2e")` and live in `jgit-proxy-server/src/test/java/org/finos/gitproxy/e2e/`.

### Manual integration test scripts (`test/`)

The `test/` directory contains bash scripts for exercising both proxy modes against a running server. They are the fastest way to verify a feature end-to-end without writing Java.

#### Environment variables

All scripts share these variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `GIT_USERNAME` | `me` | Git credential username |
| `GIT_PASSWORD` | _(required)_ | Git credential password |
| `GIT_REPO` | `github.com/coopernetes/test-repo.git` | `<provider>/<owner>/<repo>.git` path |
| `GITPROXY_API_KEY` | _(optional)_ | API key for approval scripts |

#### Store-and-forward scripts (`push-*`)

These use the `/push/...` path (JGit ReceivePack + sideband).

| Script | What it tests |
|--------|--------------|
| `push-pass.sh` | Golden-path push — should succeed and forward upstream |
| `push-pass-tag.sh` | Pushing a tag — should succeed |
| `push-pass-secrets.sh` | Push with a file that looks secret but passes gitleaks |
| `push-fail-author.sh` | Blocked due to invalid author email domains |
| `push-fail-message.sh` | Blocked due to commit message validation failures |
| `push-fail-diff.sh` | Blocked due to diff content rule violations |
| `push-fail-secrets.sh` | Blocked due to gitleaks detecting secrets in the diff |

#### Transparent proxy scripts (`proxy-*`)

These use the `/proxy/...` path (Jetty ProxyServlet + servlet filter chain).

| Script | What it tests |
|--------|--------------|
| `proxy-pass.sh` | Golden-path push — blocks for approval, then the script approves and re-pushes |
| `proxy-pass-tag.sh` | Pushing a tag through the proxy |
| `proxy-fail-author.sh` | Blocked due to invalid author email domains |
| `proxy-fail-message.sh` | Blocked due to commit message validation failures |
| `proxy-fail-diff.sh` | Blocked due to diff content rule violations |
| `proxy-fail-secrets.sh` | Blocked due to gitleaks detecting secrets in the diff |

#### Running scripts manually

Make sure the server is running first (see above), then:

```shell
export GIT_USERNAME=your-github-username
export GIT_PASSWORD=your-github-pat
export GIT_REPO=github.com/your-org/your-repo.git

bash test/push-pass.sh
bash test/push-fail-secrets.sh
```

#### Full suite runners

Two scripts spin up a complete Docker Compose environment (jgit-proxy + Gitea + database), run all `test/*.sh` scripts, then tear down:

```shell
bash test/run-postgres.sh             # PostgreSQL backend
bash test/run-mongo.sh                # MongoDB backend

# Leave the environment running after the suite (useful for debugging):
bash test/run-postgres.sh --no-teardown
```

These build the Docker image from source, so no pre-existing server is needed.

## Docker Compose (local Gitea)

The default Compose setup runs jgit-proxy against a local Gitea instance, which is the easiest way to test without touching a real GitHub repo.

```shell
docker compose up -d        # start jgit-proxy + Gitea
bash docker/setup.sh        # one-time: create admin user and test repo in Gitea
```

After setup, the proxy URLs are:

```
http://localhost:8080/push/gitea/test-owner/test-repo.git
http://localhost:8080/proxy/gitea/test-owner/test-repo.git
```

Clone example with credentials embedded:

```shell
git clone http://gitproxyadmin:Admin1234!@localhost:8080/push/gitea/test-owner/test-repo.git
```

### Optional database backends

```shell
docker compose --profile postgres up -d   # PostgreSQL + Adminer (port 8082)
docker compose --profile mongo up -d      # MongoDB + Mongo Express (port 8081)
```

Stop and remove volumes:

```shell
docker compose down -v
```

## Code style

### Java

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless) using palantir-java-format:

```shell
./gradlew spotlessApply
```

### Frontend (React/TypeScript)

Formatting uses [Prettier](https://prettier.io/), lint checks use [ESLint](https://eslint.org/). Both are Gradle tasks that use the same Node binary as the build:

```shell
./gradlew :jgit-proxy-dashboard:npmFormat   # auto-format src/ with Prettier
./gradlew :jgit-proxy-dashboard:npmLint     # ESLint check (fails on errors)
```

### Pre-commit hook

Install once after cloning:

```shell
./gradlew installGitHooks
```

This sets `core.hooksPath` to `.githooks/`. The hook runs on every `git commit`:
1. `spotlessApply` — auto-formats Java and re-stages changed files
2. `npmFormat` — auto-formats frontend source with Prettier and re-stages changed files
3. `npmLint` — ESLint check; fails the commit if there are errors (no auto-fix)

## Project layout

| Module | Purpose |
|--------|---------|
| `jgit-proxy-core` | Shared library: filter chain, JGit hooks, push store, provider model, approval abstraction |
| `jgit-proxy-server` | Standalone proxy-only server — no dashboard, no Spring |
| `jgit-proxy-dashboard` | Dashboard + REST API — Spring MVC, approval UI |

See [docs/JGIT_INFRASTRUCTURE.md](docs/JGIT_INFRASTRUCTURE.md) for the store-and-forward architecture and [docs/GIT_INTERNALS.md](docs/GIT_INTERNALS.md) for wire-protocol details.

## Issues and pull requests

The issue tracker is at [coopernetes/jgit-proxy](https://github.com/coopernetes/jgit-proxy/issues). Reference the upstream Node.js implementation at [finos/git-proxy](https://github.com/finos/git-proxy) when porting features.
