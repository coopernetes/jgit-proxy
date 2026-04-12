[![CI](https://github.com/coopernetes/git-proxy-java/actions/workflows/ci.yml/badge.svg)](https://github.com/coopernetes/git-proxy-java/actions/workflows/ci.yml)
[![CVE Scanning](https://github.com/coopernetes/git-proxy-java/actions/workflows/cve.yml/badge.svg)](https://github.com/coopernetes/git-proxy-java/actions/workflows/cve.yml)
[![License](https://img.shields.io/github/license/coopernetes/git-proxy-java)](https://github.com/coopernetes/git-proxy-java/blob/main/LICENSE)

# git-proxy-java

Enterprises in regulated industries need their developers to contribute to open-source — but every outbound push must be
audited, validated, and approved before it leaves the building. git-proxy-java sits between the developer's `git push`
and the upstream host, enforcing commit policies, scanning for secrets, verifying identities, and gating pushes behind a
review workflow.

This project is a Java reimplementation of [FINOS git-proxy](https://github.com/finos/git-proxy) — a Node.js proxy that
pioneered the concept of an enterprise-grade, policy-enforcing git push gateway. git-proxy-java builds on the same core
ideas (validation pipeline, push approval lifecycle, multi-provider support) while targeting JVM-based environments,
using [JGit](https://github.com/eclipse-jgit/jgit) for native git protocol handling,
[Jetty](https://github.com/jetty/jetty.project) for the HTTP layer, and [Spring](https://spring.io/) for the dashboard.

![Store-and-forward — validation failure and fix](https://github.com/coopernetes/git-proxy-java/releases/download/demo-assets/demo-push-fix-message.gif)

## Getting Started

### Prerequisites

- Java 21+
- Node 24+ (for the dashboard frontend)
- Gradle (wrapper included)

The easiest way to get the right versions is [mise](https://mise.jdx.dev/):

```shell
mise install   # installs Java 21 (Temurin) and Node 24 from mise.toml
```

### Clone and build

```shell
git clone https://github.com/coopernetes/git-proxy-java.git
cd git-proxy-java
./gradlew build
```

### Run the main application (UI + proxy)

The full application includes the proxy, approval dashboard, and REST API:

```shell
./gradlew :git-proxy-java-dashboard:run
```

Open `http://localhost:8080/` in a browser to access the dashboard. Stop with:

```shell
./gradlew :git-proxy-java-dashboard:stop
```

### Run the proxy server (standalone, no UI or review gates)

If you only need the proxy without the dashboard or management API:

```shell
./gradlew :git-proxy-java-server:run
```

Logs are written to `git-proxy-java-server/logs/application.log`. Stop with:

```shell
./gradlew :git-proxy-java-server:stop
```

### Configure and test

Configuration is YAML-based. See the [Configuration Reference](docs/CONFIGURATION.md) for the full schema, environment
variable overrides, and provider settings.

To verify the proxy end-to-end against your own repo, follow the
[Running tests against your own repo](CONTRIBUTING.md#running-tests-against-your-own-repo) guide in CONTRIBUTING.md — it
walks through PAT setup, allow rules, permissions, and running the smoke test scripts.

## Proxy Modes

### URLs

git-proxy-java proxies arbitrary upstream Git repositories over HTTPS. For each upstream provider (e.g. `github.com`,
`gitlab.com`), a distinct URL is mapped by hostname. The remainder of the URL is the repository path:

- Original repository: `https://github.com/finos/git-proxy`
- Proxy: `http[s]://{git-proxy-java-server}/{proxy,push*}/github.com/finos/git-proxy`

This makes it simple for a developer to add a new [git remote](https://git-scm.com/docs/git-remote) and start pushing
through the proxy:

```shell
git clone https://github.com/finos/git-proxy && cd git-proxy
git remote add proxy http://localhost:8080/push/github.com/finos/git-proxy
```

> \*Note: the base URL determines which proxying mode is in use. See below for details.

### Transparent proxy (`/proxy/<host>/...`)

HTTP requests are forwarded to the upstream Git server via Jetty's `ProxyServlet`. A servlet filter chain validates
commits inline and rejects the push with a git client error before it reaches the upstream. It is designed for simple
proxying usage where immediate feedback is preferred and clients re-push upon resolving any validation failures.

```shell
git clone http://localhost:8080/proxy/github.com/owner/repo.git
git push http://localhost:8080/proxy/github.com/owner/repo.git
```

### Store-and-forward (`/push/<host>/...`)

Push objects are received locally using JGit's `ReceivePack`. A hook chain validates commits and streams real-time
progress via git sideband before forwarding to the upstream. Because the proxy owns the full push lifecycle, each step
can be extended — custom validation, external approval workflows, third-party integrations — without touching the
upstream. Each state transition is persisted as an event-log entry in the configured database.

```shell
git clone http://localhost:8080/push/github.com/owner/repo.git
git push http://localhost:8080/push/github.com/owner/repo.git
```

## Validation Features

Both proxy modes enforce the same set of configurable validation rules:

- 🔒 Repository URL allow/deny rules (literal, glob, and regex)
- ✉️ Author email domain allow/block list
- 📝 Commit message validation (literal + regex)
- 🔍 Diff generation and content scanning
- 🔑 Secret scanning ([gitleaks](https://github.com/gitleaks/gitleaks))
- 🪪 SCM identity verification (resolve token → SCM user)
- 🛡️ User push permissions (per-repo RBAC)
- 🕵️ Hidden commit detection (force-push / history rewrite guard)
- 🌿 Empty branch protection
- ✍️ GPG/SSH commit signature verification
- ✅ Approval gate with full lifecycle (RECEIVED → APPROVED → FORWARDED)
- 📋 Aggregate failure reporting (all errors surfaced at once)
- 📡 Real-time sideband progress (store-and-forward)
- 📊 Fetch auditing

## Supported Providers

| Provider        | Identity resolution | Notes                                         |
| --------------- | ------------------- | --------------------------------------------- |
| GitHub          | Token → user        | github.com and GitHub Enterprise (custom URI) |
| GitLab          | Token → user        | gitlab.com and self-hosted instances          |
| Bitbucket       | Token → user        | bitbucket.org and Bitbucket Data Center       |
| Forgejo / Gitea | Token → user        | Any Forgejo or Gitea instance                 |

Each provider can be pointed at a self-hosted instance via the `uri` config property. Multiple instances of the same
provider type are supported.

## Authentication

The dashboard supports multiple authentication backends:

| Provider         | Description                                                       |
| ---------------- | ----------------------------------------------------------------- |
| Static (default) | Usernames and password hashes defined in YAML config              |
| LDAP             | Standard LDAP bind + optional group search                        |
| Active Directory | UPN bind via Spring's `ActiveDirectoryLdapAuthenticationProvider` |
| OIDC             | OpenID Connect authorization code flow                            |

See the [Configuration Reference](docs/CONFIGURATION.md#authentication) for setup details. Docker Compose overlays are
provided for [LDAP](docker-compose.ldap.yml) and [OIDC](docker-compose.oidc.yml).

## Push Audit Database

All pushes through the store-and-forward path are recorded as an event log. Each state transition (RECEIVED → APPROVED →
FORWARDED, or BLOCKED/ERROR) is written as a separate row, enabling full push history and audit reporting.

| Type         | Config value | Notes                                      |
| ------------ | ------------ | ------------------------------------------ |
| H2 in-memory | `h2-mem`     | SQL schema, data lost on restart. Default. |
| H2 file      | `h2-file`    | Persistent, zero external dependencies     |
| PostgreSQL   | `postgres`   | Production-grade                           |
| MongoDB      | `mongo`      | Compatible with finos/git-proxy data model |

See the [Configuration Reference](docs/CONFIGURATION.md#database) for connection settings and Docker Compose profiles.

## Project Structure

This is a multi-module Gradle project:

| Module                     | Purpose                                                                                    |
| -------------------------- | ------------------------------------------------------------------------------------------ |
| `git-proxy-java-core`      | Shared library: filter chain, JGit hooks, push store, provider model, approval abstraction |
| `git-proxy-java-server`    | Standalone proxy-only server — no dashboard, no Spring                                     |
| `git-proxy-java-dashboard` | Dashboard + REST API — Spring MVC, approval UI, depends on `git-proxy-java-server`         |

## Documentation

| Document                                                     | Description                                                                                                      |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| [User Guide](docs/USER_GUIDE.md)                             | For developers pushing code through the proxy: remote setup, push modes, blocked pushes, approval workflow       |
| [Administrator Guide](docs/ADMIN_GUIDE.md)                   | For operators: RBAC vs permissions, approval modes, logging, JGit filesystem requirements, production checklist  |
| [Configuration Reference](docs/CONFIGURATION.md)             | YAML config structure, environment variable overrides, provider settings, validation rules                       |
| [Architecture](docs/ARCHITECTURE.md)                         | How the proxy works: two proxy modes, validation pipeline, core abstractions, advanced use cases                 |
| [Demo Gallery](DEMO.md)                                      | Animated demos and screenshots of both proxy modes and the dashboard UI                                          |
| [JGit Infrastructure](docs/internals/JGIT_INFRASTRUCTURE.md) | Store-and-forward internals: ReceivePackFactory, hook chain, forwarding, credential flow (contributor reference) |
| [Git Internals](docs/internals/GIT_INTERNALS.md)             | Wire-protocol edge cases: tags, new branches, force pushes, pack parsing (contributor reference)                 |

## Roadmap

The backlog is tracked in [GitHub Issues](https://github.com/coopernetes/git-proxy-java/issues). The following gists
cover design rationale and reference material:

| Document                                                                                        | Description                                                                                                                                                           |
| ----------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Project vision & design](https://gist.github.com/coopernetes/d02d48efa759282ff8187da0d5dcae64) | High-level goals and priority tracks: sideband streaming UX, checkpoint-based resumption, lifecycle hooks, DAG pipeline execution, SCM OAuth integration, SSH support |
| [Framework rationale](https://gist.github.com/coopernetes/626541b83a148f4ae21ae2c62c57edea)     | Why Java/Jetty + JGit over Node.js/Express: native git protocol handling, in-process pack inspection, sideband streaming                                              |

## Acknowledgments

This project would not exist without [FINOS git-proxy](https://github.com/finos/git-proxy) and its contributors, who
designed the original push validation model, approval lifecycle, and multi-provider architecture. The Node.js
implementation remains the reference for the Action/Step pipeline, Sink interface, and filter chain patterns that
git-proxy-java builds on. If you're in a Node.js environment, check out the original.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, run tests, use the manual test scripts in `test/`, and set up
the Docker Compose environment.
