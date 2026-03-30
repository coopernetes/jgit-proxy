[![CI](https://github.com/coopernetes/jgit-proxy/actions/workflows/ci.yml/badge.svg)](https://github.com/coopernetes/jgit-proxy/actions/workflows/ci.yml)
[![CVE Scanning](https://github.com/coopernetes/jgit-proxy/actions/workflows/cve.yml/badge.svg)](https://github.com/coopernetes/jgit-proxy/actions/workflows/cve.yml)

# git-proxy in Java
This is a simple implementation of a git proxy in Java. This is a possible successor to [finos/git-proxy](https://github.com/finos/git-proxy) which is written in Node.

## Project Structure

This project is a multi-module Gradle project:

### jgit-proxy-core
Shared library containing all proxy logic:
- Servlet filters for the transparent proxy path (`/proxy/...`)
- Store-and-forward push pipeline using JGit ReceivePack hooks (`/push/...`)
- Provider interfaces and implementations (GitHub, GitLab, Bitbucket)
- Push audit store abstraction with JDBC and MongoDB backends
- Git protocol utilities and commit inspection

### jgit-proxy-jetty
Standalone Jetty server — the primary runnable module. No Spring dependency.

```shell
./gradlew :jgit-proxy-jetty:run
./gradlew :jgit-proxy-jetty:stop
```

### jgit-proxy-spring
Placeholder for a future Spring Boot variant.

## Proxy Modes

### Transparent proxy (`/proxy/<host>/...`)
HTTP request is forwarded to the upstream Git server. Servlet filters validate commits inline and reject the push before it reaches the upstream. Client receives a git sideband error message.

```shell
git clone http://localhost:8080/proxy/github.com/owner/repo.git
git push http://localhost:8080/proxy/github.com/owner/repo.git
```

### Store-and-forward push (`/push/<host>/...`)
Push objects are received locally using JGit's `ReceivePack`. A hook chain validates the commits and streams real-time feedback via git sideband before forwarding to the upstream. Each state transition is persisted as an event-log entry.

```shell
git clone http://localhost:8080/push/github.com/owner/repo.git
git push http://localhost:8080/push/github.com/owner/repo.git
```

## Push Audit Database

All pushes through the store-and-forward path are recorded as an event log. Each state transition (RECEIVED → APPROVED → FORWARDED, or BLOCKED/ERROR) is written as a separate row, enabling full push history and reporting.

### Supported backends

| Type | Config value | Notes |
|------|-------------|-------|
| In-memory (simple) | `memory` | No SQL schema, data lost on restart |
| H2 in-memory | `h2-mem` | SQL schema, data lost on restart. Default. |
| H2 file | `h2-file` | Persistent, zero external dependencies |
| SQLite | `sqlite` | Persistent, zero external dependencies |
| PostgreSQL | `postgres` | Production-grade |
| MongoDB | `mongo` | Compatible with git-proxy Node.js data model |

### Database configuration

Set in `git-proxy.yml` (or override in `git-proxy-local.yml`):

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

## Configuration

### Jetty Module
YAML-based configuration loaded from `git-proxy.yml` and `git-proxy-local.yml` (local file takes priority):

```yaml
server:
  port: 8080

database:
  type: h2-mem

git-proxy:
  providers:
    github:
      enabled: true
    gitlab:
      enabled: true
    bitbucket:
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

See [`jgit-proxy-jetty/CONFIGURATION.md`](jgit-proxy-jetty/CONFIGURATION.md) for full configuration reference.

## Development

### Building
```shell
./gradlew build
./gradlew :jgit-proxy-jetty:build
```

### Running
```shell
./gradlew :jgit-proxy-jetty:run    # starts server (PID tracked for stop)
./gradlew :jgit-proxy-jetty:stop   # graceful stop via PID file
```

### Integration tests

Test scripts in the repo root exercise both proxy modes end-to-end. They require a running server and a `~/.github-pat` file with a GitHub personal access token.

```shell
# Store-and-forward mode
bash test-push-pass.sh   # pushes that should succeed
bash test-push-fail.sh   # pushes that should be rejected by validation

# Transparent proxy mode
bash test-proxy-pass.sh
bash test-proxy-fail.sh
```

## Demo

![demo 1](./static/jgit-proxy-demo.gif)

![demo 2](./static/jgit-proxy-demo2.gif)

Running the server
![demo 3](./static/jgit-proxy-demo3.gif)
