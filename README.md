[![CI](https://github.com/coopernetes/jgit-proxy/actions/workflows/ci.yml/badge.svg)](https://github.com/coopernetes/jgit-proxy/actions/workflows/ci.yml)
[![CVE Scanning](https://github.com/coopernetes/jgit-proxy/actions/workflows/cve.yml/badge.svg)](https://github.com/coopernetes/jgit-proxy/actions/workflows/cve.yml)

# jgit-proxy

A Java-based git proxy implementing the same compliance and security controls as [finos/git-proxy](https://github.com/finos/git-proxy). Designed for OSS contribution gateways (employees in regulated industries contributing code to public upstream repos) and private-to-private code exchange (M&A scenarios between two or more git providers). Built on [JGit](https://github.com/eclipse-jgit/jgit), [Jetty](https://github.com/jetty/jetty.project) and [Spring](https://spring.io/).

## Getting Started

### Prerequisites

- Java 21+
- Gradle (wrapper included)

### Clone and build

```shell
git clone https://github.com/coopernetes/jgit-proxy.git
cd jgit-proxy
./gradlew build
```

### Run the proxy server

The standalone proxy server (no dashboard, no management API) listens on port 8080 by default:

```shell
./gradlew :jgit-proxy-server:run
```

Logs are written to `jgit-proxy-server/logs/application.log`. Stop with:

```shell
./gradlew :jgit-proxy-server:stop
```

### Run the dashboard application

The dashboard module adds a Spring MVC web UI and REST API for reviewing and approving blocked pushes:

```shell
./gradlew :jgit-proxy-dashboard:run
```

Open `http://localhost:8080/` in a browser to access the approval dashboard. Stop with:

```shell
./gradlew :jgit-proxy-dashboard:stop
```

### Configure the proxy

Configuration is YAML-based. Copy `git-proxy.yml` from `jgit-proxy-server/src/main/resources/` and create `git-proxy-local.yml` in the same directory (or `/app/conf/` for Docker). The local file takes priority over the bundled defaults.

Minimal example — allow pushes to a specific GitHub repo:

```yaml
server:
  port: 8080

database:
  type: h2-mem   # default; data lost on restart

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

Environment variable overrides use the `GITPROXY_` prefix:
- `GITPROXY_SERVER_PORT=9090`
- `GITPROXY_PROVIDERS_GITHUB_ENABLED=false`

## Proxy Modes

### URLs

jgit-proxy is capable of proxying arbitrary & multiple upstream Git repositories over HTTPS. For each upstream provider (for example, https://github.com & https://gitlab.com), a distinct URL is mapped for proxying by hostname. The remainder of the URL is the specific git repository you wish to connect to. For example:

- Original repository: https://github.com/finos/git-proxy
- Proxy: http[s]://{jgit-proxy-server}/{proxy,push*}/github.com/finos/git-proxy

This makes it simple for a developer to simply add a new [git remote](https://git-scm.com/docs/git-remote) and start pushing code through jgit-proxy.

```
git clone https://github.com/finos/git-proxy && cd git-proxy
git remote add proxy http://localhost:8080/push/github.com/finos/git-proxy
```

> \*Note: the base URL determines which proxying mode is in use. See below for details.

### Transparent proxy (`/proxy/<host>/...`)

HTTP requests are forwarded to the upstream Git server via Jetty's `ProxyServlet`. A servlet filter chain validates commits inline and rejects the push with a git client error before it reaches the upstream. It is designed for simple proxying usage where immediate feedback is preferred and clients re-push upon resolving any validation failures.

```shell
git clone http://localhost:8080/proxy/github.com/owner/repo.git
git push http://localhost:8080/proxy/github.com/owner/repo.git
```

### Store-and-forward (`/push/<host>/...`)

Push objects are received locally using JGit's `ReceivePack`. A hook chain validates commits and streams real-time progress via git sideband (with real-time feedback and a nicer UX in the terminal with emoji & ANSI color support) before forwarding to the upstream. Each state transition is persisted as an event-log entry in the configured database.

```shell
git clone http://localhost:8080/push/github.com/owner/repo.git
git push http://localhost:8080/push/github.com/owner/repo.git
```

## Validation Features

Both proxy modes enforce the same set of configurable validation rules:

| Feature | Status |
|---------|--------|
| Repository allowlist (owner/slug) | Implemented |
| Author email domain allow/block list | Implemented |
| Commit message validation (literal + regex) | Implemented |
| Diff generation | Implemented |
| Diff content scanning | Implemented |
| Aggregate failure reporting (all errors at once) | Implemented |
| GPG/SSH commit signature verification | Implemented |
| Approval gate with full lifecycle (RECEIVED → APPROVED → FORWARDED) | Implemented |
| Real-time sideband progress with ANSI color | Implemented |

## Documentation

| Document | Description |
|----------|-------------|
| [Configuration Reference](docs/CONFIGURATION.md) | YAML config structure, environment variable overrides, provider settings, validation rules |
| [JGit Infrastructure](docs/JGIT_INFRASTRUCTURE.md) | Store-and-forward architecture: ReceivePackFactory, hook chain, forwarding, credential flow |
| [Git Internals](docs/GIT_INTERNALS.md) | Wire-protocol edge cases: tags, new branches, force pushes, pack parsing |

## Configuration

All validation and filtering is configurable via YAML. See the [Configuration Reference](docs/CONFIGURATION.md) for full details. The configuration system is still under active development.

## Push Audit Database

All pushes through the store-and-forward path are recorded as an event log. Each state transition (RECEIVED → APPROVED → FORWARDED, or BLOCKED/ERROR) is written as a separate row, enabling full push history and audit reporting.

### Supported backends

| Type | Config value | Notes |
|------|-------------|-------|
| In-memory | `memory` | No SQL schema, data lost on restart |
| H2 in-memory | `h2-mem` | SQL schema, data lost on restart. Default. |
| H2 file | `h2-file` | Persistent, zero external dependencies |
| SQLite | `sqlite` | Persistent, zero external dependencies |
| PostgreSQL | `postgres` | Production-grade |
| MongoDB | `mongo` | Compatible with finos/git-proxy data model |

### Database configuration

```yaml
# H2 in-memory (default)
database:
  type: h2-mem

# H2 file
database:
  type: h2-file
  path: ./.data/gitproxy   # H2 appends .mv.db

# SQLite
database:
  type: sqlite
  path: ./.data/gitproxy.db

# PostgreSQL
database:
  type: postgres
  host: localhost
  port: 5432
  name: gitproxy
  username: gitproxy
  password: gitproxy

# MongoDB
database:
  type: mongo
  url: mongodb://gitproxy:gitproxy@localhost:27017
  name: gitproxy
```

A `docker-compose.yml` is provided for local development with PostgreSQL and MongoDB (includes Adminer and Mongo Express web UIs):

```shell
docker compose up -d postgres   # port 5432, Adminer on 8082
docker compose up -d mongo      # port 27017, Mongo Express on 8081
```

## Project Structure

This is a multi-module Gradle project:

| Module | Purpose |
|--------|---------|
| `jgit-proxy-core` | Shared library: filter chain, JGit hooks, push store, provider model, approval abstraction |
| `jgit-proxy-server` | Standalone proxy-only server — no dashboard, no Spring |
| `jgit-proxy-dashboard` | Dashboard + REST API — Spring MVC, approval UI, depends on `jgit-proxy-server` |

## Roadmap

The following gists track the project's direction and open design questions:

| Document | Description |
|----------|-------------|
| [Project vision & design](https://gist.github.com/coopernetes/d02d48efa759282ff8187da0d5dcae64) | High-level goals and priority tracks: sideband streaming UX, checkpoint-based resumption, lifecycle hooks, DAG pipeline execution, SCM OAuth integration, SSH support |
| [Implementation progress](https://gist.github.com/coopernetes/3a6c83690164a8a60a10524ef24e35eb) | Feature-by-feature comparison against finos/git-proxy with current status (implemented / in-progress / gap) |
| [Framework rationale](https://gist.github.com/coopernetes/626541b83a148f4ae21ae2c62c57edea) | Why Java/Jetty + JGit over Node.js/Express: native git protocol handling, in-process pack inspection, sideband streaming |
| [JGit server-side abstractions](https://gist.github.com/coopernetes/96ce03ca5795ca9dc78367f064c20596) | Reference guide for `RepositoryResolver`, `ReceivePackFactory`, and pre/post-receive hooks — the building blocks of the store-and-forward pipeline |

## Development

### Build

```shell
./gradlew build              # compile + unit tests
./gradlew spotlessApply      # fix formatting (palantir-java-format)
```

### Integration tests

Test scripts in the repo root exercise both proxy modes end-to-end against a running server. They require a `~/.github-pat` file with a GitHub personal access token.

```shell
# Store-and-forward mode
bash test-push-pass.sh   # pushes that should succeed
bash test-push-fail.sh   # pushes that should be rejected

# Transparent proxy mode
bash test-proxy-pass.sh
bash test-proxy-fail.sh
```

### E2E tests (Docker/Podman required)

```shell
./gradlew e2eTest
```

### Docker Compose

```shell
docker compose up -d          # jgit-proxy + Gitea (h2-mem database)
bash docker/setup.sh          # one-time: create admin user + test repo in Gitea
```
