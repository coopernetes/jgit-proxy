# Contributing to git-proxy-java

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
./gradlew :git-proxy-java-server:run
```

Listens on `http://localhost:8080`. Logs go to `git-proxy-java-server/logs/application.log`. Stop with:

```shell
./gradlew :git-proxy-java-server:stop
```

### Dashboard + REST API

```shell
./gradlew :git-proxy-java-dashboard:run
```

Opens the approval dashboard at `http://localhost:8080/`. Stop with:

```shell
./gradlew :git-proxy-java-dashboard:stop
```

The dashboard module always uses UI-mode approval (pushes block until manually approved). The standalone server defaults
to auto-approve.

### Local config override

Place overrides in `git-proxy-java-server/src/main/resources/git-proxy-local.yml`. The local file takes priority over
`git-proxy.yml`. At minimum, add an allow rule for your test repo and a permission entry for your proxy user:

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operations: [FETCH, PUSH]
      providers: [github]
      slugs:
        - /your-org/your-repo

permissions:
  - username: your-proxy-user
    provider: github
    path: /your-org/your-repo
    operations: PUSH
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

These start a containerised Gitea instance and a live Jetty proxy in-process. They are tagged `@Tag("e2e")` and live in
`git-proxy-java-server/src/test/java/org/finos/gitproxy/e2e/`.

### Manual integration test scripts (`test/`)

The `test/` directory contains bash scripts for exercising both proxy modes against a running server. They are the
fastest way to verify a feature end-to-end without writing Java.

Test scripts share a common library (`test/common.sh`) with setup, cleanup, and assertion helpers. Individual test cases
are organized into logical groupings by test outcome (pass/fail) and proxy mode (push/proxy).

#### Environment variables

All scripts share these variables:

| Variable           | Default                                           | Description                                        |
| ------------------ | ------------------------------------------------- | -------------------------------------------------- |
| `GIT_USERNAME`     | `me`                                              | HTTP Basic-auth username (arbitrary for the proxy) |
| `GIT_PASSWORD`     | _(read from PAT file, see below)_                 | Personal access token for the upstream SCM         |
| `GIT_REPO`         | `github.com/coopernetes/test-repo.git`            | Target repo for GitHub pass/fail scripts           |
| `GITHUB_REPO`      | `github.com/coopernetes/test-repo.git`            | Target repo for GitHub identity scripts            |
| `GITLAB_REPO`      | `gitlab.com/coopernetes/test-repo-gitlab.git`     | Target repo for GitLab identity scripts            |
| `CODEBERG_REPO`    | `codeberg.org/coopernetes/test-repo-codeberg.git` | Target repo for Codeberg identity scripts          |
| `GITPROXY_API_KEY` | `change-me-in-production`                         | API key used by approval scripts                   |

Scripts read the upstream PAT from a file if `GIT_PASSWORD` is not set:

| Script group     | PAT file          |
| ---------------- | ----------------- |
| GitHub scripts   | `~/.github-pat`   |
| GitLab scripts   | `~/.gitlab-pat`   |
| Codeberg scripts | `~/.codeberg-pat` |

#### Test entry points

Run tests by logical grouping. Each entry point orchestrates multiple related test cases:

**Store-and-forward (push):**

- `bash test/push-pass-all.sh` — golden-path pushes and tag pushes (should succeed)
- `bash test/push-fail-all.sh` — validation failures (should be rejected)

**Transparent proxy:**

- `bash test/proxy-pass-all.sh` — golden-path and tag pushes (require manual approval)
- `bash test/proxy-fail-all.sh` — validation failures (should be rejected)

**Identity verification:**

- `bash test/push-identity-all.sh` — SCM identity resolution across providers
- `bash test/proxy-identity-all.sh` — SCM identity resolution via proxy

#### Individual test scripts

If running a single test case by name:

**Store-and-forward (push):** | Script | Category | What it tests | | ---------------------- | -------- |
------------------------------------------------------ | | `push-pass.sh` | Pass | Golden-path push — should succeed and
forward upstream | | `push-pass-tag.sh` | Pass | Lightweight and annotated tags — should succeed | |
`push-pass-secrets.sh` | Pass | File patterns that look like secrets but pass gitleaks | | `push-fail-author.sh` | Fail
| Invalid author email domains (noreply, disallowed) | | `push-fail-message.sh` | Fail | Commit message validation (WIP,
fixup, DO NOT MERGE) | | `push-fail-diff.sh` | Fail | Diff content scanning (internal URLs, patterns) | |
`push-fail-secrets.sh` | Fail | Gitleaks detecting secrets in diff (AWS, GitHub, PEM) |

**Transparent proxy:** | Script | Category | What it tests | | ----------------------- | -------- |
---------------------------------------------------------------- | | `proxy-pass.sh` | Pass | Golden-path push — blocks
for approval, then auto-approves | | `proxy-pass-tag.sh` | Pass | Lightweight and annotated tags through proxy | |
`proxy-fail-author.sh` | Fail | Invalid author email domains (noreply, disallowed) | | `proxy-fail-message.sh` | Fail |
Commit message validation (WIP, fixup, DO NOT MERGE) | | `proxy-fail-diff.sh` | Fail | Diff content scanning (internal
URLs, patterns) | | `proxy-fail-secrets.sh` | Fail | Gitleaks detecting secrets in diff (AWS, GitHub, PEM) |

#### Running tests against your own repo

The scripts default to repos owned by the project maintainer. To run them against your own repos you need:

1. **A test repo** you can push to on GitHub (and optionally GitLab/Codeberg for identity tests).

2. **PAT files** for each provider you want to test:

   ```shell
   echo "ghp_yourtoken" > ~/.github-pat
   echo "glpat-yourtoken" > ~/.gitlab-pat   # optional
   echo "yourtoken" > ~/.codeberg-pat       # optional
   chmod 600 ~/.github-pat ~/.gitlab-pat ~/.codeberg-pat
   ```

3. **Allow rules and permissions** in `git-proxy-local.yml` — add your repo slug to the `rules.allow` slugs list and add
   `PUSH`/`REVIEW` permission entries for your proxy user. See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for the
   full reference.

4. **Run with your repo** — single scripts accept an inline override; orchestrators need an export:

   ```shell
   # Single script — inline is fine:
   GIT_REPO=github.com/your-org/your-repo.git bash test/push-pass.sh

   # Orchestrators (call subscripts via bash) — must export:
   export GIT_REPO=github.com/your-org/your-repo.git
   bash test/push-pass-all.sh
   bash test/proxy-pass-all.sh

   # Provider-specific identity tests use separate variables:
   export GITHUB_REPO=github.com/your-org/your-repo.git
   export GITLAB_REPO=gitlab.com/your-org/your-repo.git
   bash test/push-identity-all.sh
   ```

#### Running tests manually

Make sure the server is running first (see above), then:

```shell
# Run all passing push tests:
bash test/push-pass-all.sh

# Run all failure push tests:
bash test/push-fail-all.sh

# Run a single test case:
bash test/push-fail-secrets.sh
```

#### Full suite runners

Two scripts spin up a complete Docker Compose environment (git-proxy-java + Gitea + database), run all test groups, then
tear down:

```shell
bash test/run-postgres.sh             # PostgreSQL backend
bash test/run-mongo.sh                # MongoDB backend

# Leave the environment running after the suite (useful for debugging):
bash test/run-postgres.sh --no-teardown
```

These build the Docker image from source, so no pre-existing server is needed.

## Docker Compose (local Gitea)

The Compose setup runs git-proxy-java against a local Gitea instance. Overlay files are independent mixins — one for the
auth provider, one for the database backend. They can be combined freely.

### Overlay files

**Auth overlays** — each mounts a different `git-proxy-local.yml` config into the container:

| File                      | Auth provider                      | Default database |
| ------------------------- | ---------------------------------- | ---------------- |
| _(none)_                  | Static (password hashes in config) | H2 in-memory     |
| `docker-compose.ldap.yml` | OpenLDAP                           | H2 in-memory     |
| `docker-compose.oidc.yml` | OIDC (mock-oauth2-server)          | H2 in-memory     |

**Database overlays** — each sets `GITPROXY_DATABASE_*` environment variables; no config file swap needed:

| File                          | Backend      | Profile flag         | UI                     |
| ----------------------------- | ------------ | -------------------- | ---------------------- |
| _(none)_                      | H2 in-memory | —                    | —                      |
| `docker-compose.postgres.yml` | PostgreSQL   | `--profile postgres` | Adminer at :8082       |
| `docker-compose.mongo.yml`    | MongoDB      | `--profile mongo`    | Mongo Express at :8081 |

Any auth overlay can be combined with any database overlay (or none, to keep H2). The pattern is:

```bash
docker compose [--profile <db>] \
  -f docker-compose.yml \
  [-f docker-compose.<auth>.yml] \
  [-f docker-compose.<db>.yml] \
  up -d
```

### First-time setup

After starting any stack, run this once to create the Gitea admin user and test repository:

```shell
bash docker/gitea-setup.sh
```

### Common stacks

**Static auth + H2** (simplest — no external dependencies):

```shell
docker compose up -d
```

**LDAP + H2**:

```shell
docker compose -f docker-compose.yml -f docker-compose.ldap.yml up -d
```

**LDAP + PostgreSQL** (recommended for verifying IdP email locking and auto-provisioning):

```shell
docker compose --profile postgres \
  -f docker-compose.yml \
  -f docker-compose.ldap.yml \
  -f docker-compose.postgres.yml \
  up -d
```

**OIDC + PostgreSQL**:

```shell
docker compose --profile postgres \
  -f docker-compose.yml \
  -f docker-compose.oidc.yml \
  -f docker-compose.postgres.yml \
  up -d
```

**LDAP + MongoDB**:

```shell
docker compose --profile mongo \
  -f docker-compose.yml \
  -f docker-compose.ldap.yml \
  -f docker-compose.mongo.yml \
  up -d
```

### Auth provider details

#### Static auth

Log in at `http://localhost:8080` with `admin` / `admin` (defined in `docker/git-proxy-local.yml`).

#### LDAP auth

Test accounts are defined in `docker/ldap-bootstrap.ldif`:

| Username   | Password      | LDAP email             |
| ---------- | ------------- | ---------------------- |
| `testuser` | `testpass123` | `testuser@example.com` |
| `admin`    | `admin`       | `admin@example.com`    |

On first login the account is auto-provisioned and the LDAP `mail` attribute is stored as a locked email (not editable
from the profile UI). Inspect the `user_emails` table in Adminer or Mongo Express to see the `locked=true` row.

To add more users, edit `docker/ldap-bootstrap.ldif` and recreate the container:

```shell
docker compose -f docker-compose.yml -f docker-compose.ldap.yml rm -sf openldap
docker compose -f docker-compose.yml -f docker-compose.ldap.yml up -d openldap
```

#### OIDC auth

Uses [navikt/mock-oauth2-server](https://github.com/navikt/mock-oauth2-server), which accepts any username with no
password required.

**One-time `/etc/hosts` entry** — required so the OIDC issuer URL is the same from your browser and from git-proxy-java
inside Docker:

```text
127.0.0.1  mock-oauth2
```

Open `http://localhost:8080` and log in with any username.

### Proxy URLs

After `docker/gitea-setup.sh`, the test repository is reachable at:

```text
http://localhost:8080/push/gitea/test-owner/test-repo.git
http://localhost:8080/proxy/gitea/test-owner/test-repo.git
```

Clone example:

```shell
git clone http://gitproxyadmin:Admin1234!@localhost:8080/push/gitea/test-owner/test-repo.git
```

### Teardown

```bash
docker compose [same -f flags as start] down -v
```

## Code style

### Java

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless) using palantir-java-format:

```shell
./gradlew spotlessApply
```

### Frontend (React/TypeScript)

Formatting uses [Prettier](https://prettier.io/), lint checks use [ESLint](https://eslint.org/). Both are Gradle tasks
that use the same Node binary as the build:

```shell
./gradlew :git-proxy-java-dashboard:npmFormat   # auto-format src/ with Prettier
./gradlew :git-proxy-java-dashboard:npmLint     # ESLint check (fails on errors)
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

| Module                     | Purpose                                                                                    |
| -------------------------- | ------------------------------------------------------------------------------------------ |
| `git-proxy-java-core`      | Shared library: filter chain, JGit hooks, push store, provider model, approval abstraction |
| `git-proxy-java-server`    | Standalone proxy-only server — no dashboard, no Spring                                     |
| `git-proxy-java-dashboard` | Dashboard + REST API — Spring MVC, approval UI                                             |

See [docs/internals/JGIT_INFRASTRUCTURE.md](docs/internals/JGIT_INFRASTRUCTURE.md) for the store-and-forward
architecture and [docs/internals/GIT_INTERNALS.md](docs/internals/GIT_INTERNALS.md) for wire-protocol details.

## Issues and pull requests

The issue tracker is at [coopernetes/git-proxy-java](https://github.com/coopernetes/git-proxy-java/issues). Reference
the upstream Node.js implementation at [finos/git-proxy](https://github.com/finos/git-proxy) when porting features.
