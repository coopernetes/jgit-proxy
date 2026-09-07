# Contributing to fogwall

## Prerequisites

The easiest way to get the right toolchain versions is [mise](https://mise.jdx.dev/):

```shell
mise install   # installs Java 25 (Temurin) and Node 26 as defined in mise.toml
```

Optional CLI tools (e.g. `glab`, for testing the GitLab SCM API proxy dialect) live in `mise.cli.toml` rather than
`mise.toml`, since they're not needed by every contributor. Install them with:

```shell
MISE_ENVIRONMENT=cli mise install
```

If you prefer to manage tools yourself, you need:

- Java 25+
- Node 26+
- Docker or Podman (for e2e tests and Docker Compose workflows)

Gradle itself is included via the wrapper — no separate installation needed.

## Project structure

Multi-module Gradle project; dependencies flow upward (`core` → `server` → `dashboard`):

| Module              | Purpose                                                                                    |
| ------------------- | ------------------------------------------------------------------------------------------ |
| `fogwall-core`      | Shared library: filter chain, JGit hooks, push store, provider model, approval abstraction |
| `fogwall-server`    | Standalone proxy-only server — no dashboard, no Spring                                     |
| `fogwall-dashboard` | Dashboard + REST API — Spring MVC, approval UI, depends on `fogwall-server`                |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the modules fit together at runtime.

## Build

```shell
./gradlew spotlessApply      # fix formatting (palantir-java-format) — run before build
./gradlew build              # compile + unit tests
```

Formatting is enforced in CI. Always run `spotlessApply` before pushing.

When working on Java-only changes in the dashboard module, pass `-PskipFrontend` to skip the Node/npm frontend build
steps (requires Node to be available otherwise):

```shell
./gradlew :fogwall-dashboard:compileJava -PskipFrontend
./gradlew :fogwall-dashboard:build -PskipFrontend
```

## Running the server locally

### Proxy only (no dashboard)

```shell
./gradlew :fogwall-server:run
```

Listens on `http://localhost:8080`. Logs go to `fogwall-server/logs/application.log`. Stop with:

```shell
./gradlew :fogwall-server:stop
```

### Dashboard + REST API

```shell
./gradlew :fogwall-dashboard:run
```

Opens the approval dashboard at `http://localhost:8080/`. Stop with:

```shell
./gradlew :fogwall-dashboard:stop
```

The dashboard module always uses UI-mode approval (pushes block until manually approved). The standalone server defaults
to auto-approve.

### Local config override

Place overrides in `fogwall-server/src/main/resources/fogwall-local.yml`. The local file takes priority over
`fogwall.yml`. At minimum, add an allow rule for your test repo and a permission entry for your proxy user:

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operations: BOTH
      provider: github
      match:
        target: SLUG
        value: /your-org/your-repo
        type: LITERAL

permissions:
  - username: your-proxy-user
    provider: github
    match:
      target: SLUG
      value: /your-org/your-repo
      type: LITERAL
    operations: PUSH
```

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for the full reference.

### Testing the proposals listeners locally (TLS)

The proposals listeners (`gh`, `glab`, `tea`, `fj`) can't be exercised over plain HTTP: every one of those CLIs
addresses a custom host over HTTPS with no way to ask otherwise. So fogwall has to terminate TLS, which locally means a
self-signed certificate the CLIs will trust.

Generate a small CA and a leaf signed by it. A bare `openssl req -x509` self-signed certificate is **not** enough: it is
a CA certificate, and `fj` (rustls) refuses one presented as a server certificate — `CaUsedAsEndEntity`. `gh` and `glab`
(Go) accept it, so the shortcut appears to work until you try Forgejo.

```shell
cd /tmp
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout ca-key.pem -out ca.pem \
  -subj "/CN=fogwall local dev CA" -addext "basicConstraints=critical,CA:TRUE"

openssl req -newkey rsa:2048 -nodes -keyout fogwall-key.pem -out leaf.csr -subj "/CN=localhost"
openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -days 30 -out leaf.pem \
  -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\n")
cat leaf.pem ca.pem > fogwall-cert.pem
```

The SAN matters too — Go rejects a certificate carrying only a CN. `-nodes` already emits a PKCS8 key, so there is no
conversion step. Run with `server.tls` pointed at the chain; every enabled proposals listener inherits it and logs
`(https, inherited from server.tls)`:

```shell
FOGWALL_SERVER_TLS_CERTIFICATE=/tmp/fogwall-cert.pem \
FOGWALL_SERVER_TLS_KEY=/tmp/fogwall-key.pem \
  ./gradlew :fogwall-dashboard:run
```

Clients then trust `ca.pem` (not the leaf) via `SSL_CERT_FILE`.

Then point a CLI at it, trusting the CA per-invocation with `SSL_CERT_FILE` (`gh` and `glab` are Go, which reads it for
the file portion of the trust store while still loading the system CA directory — so public hosts keep working):

```shell
export SSL_CERT_FILE=/tmp/ca.pem

# gh — GH_ENTERPRISE_TOKEN, not GH_TOKEN, which gh only applies to github.com.
# `gh auth login` can't be used here: it validates against the REST API, which fogwall doesn't proxy.
GH_HOST=localhost:9443 GH_ENTERPRISE_TOKEN="$(gh auth token)" \
  gh pr create -R localhost:9443/<owner>/<repo> --base main --head <branch> --title t --body b

# glab — authenticate against the fogwall host in a throwaway config dir so your real one is untouched
export GLAB_CONFIG_DIR=/tmp/fogwall-glab
glab auth login --hostname localhost:9444 --api-protocol https --insecure-storage --stdin < /path/to/pat
GITLAB_HOST=localhost:9444 glab mr create -R <owner>/<repo> \
  --source-branch <branch> --target-branch main --title t --description b --no-editor --yes
```

For Gitea/Forgejo, bring up the container (`bash compose.sh -- up -d gitea`) and point both CLIs at the Gitea listener.
Each keeps its config under `$XDG_CONFIG_HOME`, so a throwaway directory isolates them from your real logins:

```shell
XDG_CONFIG_HOME=/tmp/fogwall-tea tea login add --name fogwall --url https://localhost:9445 --token "$GITEA_PAT"
XDG_CONFIG_HOME=/tmp/fogwall-tea tea pr create --login fogwall --repo <owner>/<repo> --head <branch> --base main --title t

echo "$GITEA_PAT" | XDG_CONFIG_HOME=/tmp/fogwall-fj fj auth add-token -H https://localhost:9445
XDG_CONFIG_HOME=/tmp/fogwall-fj fj -H https://localhost:9445 pr create "t" --body b --head <branch> --base main --repo <owner>/<repo>
```

Client quirks that cost time if you don't know them:

- `glab mr create` refuses to run unless one of the repo's git remotes points at `GITLAB_HOST` — add a dummy
  `glab-proxy-do-not-use` remote, which is never used for git.
- `glab` sends a PAT in `PRIVATE-TOKEN` but an OAuth token in `Authorization: Bearer`; the two are not interchangeable.
- `fj` must run inside a clone and needs `--repo` for most commands; `fj pr close` takes the number only.
- gitleaks discards low-entropy matches, so a made-up token like `ghp_ABCDEF…0123456789` is a false negative by design.
  Use a random one when testing content inspection.

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
`fogwall-server/src/test/java/com/rbc/fogwall/e2e/`.

### UI regression tests (Playwright, no Docker)

```shell
cd fogwall-dashboard/frontend && npx playwright install chromium && npx playwright test
```

Specs live in `fogwall-dashboard/frontend/tests/`. The Playwright web server boots the dashboard against a **real,
pre-populated H2 database** — `tests/fixtures/fogwall.sql`, restored fresh into `build/playwright-db/` before every run
by `:fogwall-dashboard:restoreFixtureDb` — with the matching profile `tests/fixtures/fogwall-playwright.yml` put on the
classpath via `-PconfigDir`. Four local users exist (`admin`, `dev`, `reviewer`, `observer`); the `asRole` fixture in
`tests/fixtures.ts` opens a page as any of them. No credentials or network are needed on replay.

The database is produced by `test/capture/capture.py` from **real pushes through real providers** with your own tokens,
then scrubbed to stable placeholders (`fixture-dev`, `fixture-dev@example.com`, `fogwall-fixture`, …) and committed.
Push-detail specs read `tests/fixtures/manifest.json` (scenario → push id). Locally a missing scenario skips its spec,
so a partial capture is workable; **on CI a missing scenario fails the job** (`process.env.CI`), because there it means
someone dropped a scenario or forgot to re-capture.

Specs that change the status of captured push records (approve / reject / cancel through the UI) are named
`*.mutation.spec.ts` and run in the `mutations` project, which Playwright starts only after every read-only spec has
passed; the same goes for CRUD specs that add grants to fixture users. Everything else may run in parallel.

The suite runs on every PR in the `Playwright UI Tests` job; it needs no Docker, credentials, or network and takes under
a minute on top of the app boot. **Re-capture** when a hook or filter changes what it records (step names, messages,
content), when a scenario is added, or when the fixture profile changes push outcomes — commit the new `fogwall.sql` +
`manifest.json` in the same PR as the change. Config-page changes (rules, providers, groups) only need the profile
edited. See [test/capture/README.md](test/capture/README.md).

### Manual integration test scripts (`test/`)

The `test/` directory contains bash scripts for exercising both proxy modes against a running server. They are the
fastest way to verify a feature end-to-end without writing Java.

Test scripts share a common library (`test/common.sh`) with setup, cleanup, and assertion helpers. Individual test cases
are organized into logical groupings by test outcome (pass/fail) and proxy mode (push/proxy).

#### Environment variables

All scripts share these variables:

| Variable          | Default                                           | Description                                        |
| ----------------- | ------------------------------------------------- | -------------------------------------------------- |
| `GIT_USERNAME`    | `me`                                              | HTTP Basic-auth username (arbitrary for the proxy) |
| `GIT_PASSWORD`    | _(read from PAT file, see below)_                 | Personal access token for the upstream SCM         |
| `GIT_REPO`        | `github.com/coopernetes/test-repo.git`            | Target repo for GitHub pass/fail scripts           |
| `GITHUB_REPO`     | `github.com/coopernetes/test-repo.git`            | Target repo for GitHub identity scripts            |
| `GITLAB_REPO`     | `gitlab.com/coopernetes/test-repo-gitlab.git`     | Target repo for GitLab identity scripts            |
| `CODEBERG_REPO`   | `codeberg.org/coopernetes/test-repo-codeberg.git` | Target repo for Codeberg identity scripts          |
| `FOGWALL_API_KEY` | `change-me-in-production`                         | API key used by approval scripts                   |

Scripts read the upstream PAT from a file if `GIT_PASSWORD` is not set:

| Script group     | PAT file          |
| ---------------- | ----------------- |
| GitHub scripts   | `~/.github-pat`   |
| GitLab scripts   | `~/.gitlab-pat`   |
| Codeberg scripts | `~/.codeberg-pat` |

#### Test entry points

Run tests by logical grouping. Each entry point orchestrates multiple related test cases:

**Server mode (push):**

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

**Server mode (push):**

| Script                 | Category | What it tests                                          |
| ---------------------- | -------- | ------------------------------------------------------ |
| `push-pass.sh`         | Pass     | Golden-path push — should succeed and forward upstream |
| `push-pass-tag.sh`     | Pass     | Lightweight and annotated tags — should succeed        |
| `push-pass-secrets.sh` | Pass     | File patterns that look like secrets but pass gitleaks |
| `push-fail-author.sh`  | Fail     | Invalid author email domains (noreply, disallowed)     |
| `push-fail-message.sh` | Fail     | Commit message validation (WIP, fixup, DO NOT MERGE)   |
| `push-fail-diff.sh`    | Fail     | Diff content scanning (internal URLs, patterns)        |
| `push-fail-secrets.sh` | Fail     | Gitleaks detecting secrets in diff (AWS, GitHub, PEM)  |

**Transparent proxy:**

| Script                  | Category | What it tests                                              |
| ----------------------- | -------- | ---------------------------------------------------------- |
| `proxy-pass.sh`         | Pass     | Golden-path push — blocks for approval, then auto-approves |
| `proxy-pass-tag.sh`     | Pass     | Lightweight and annotated tags through proxy               |
| `proxy-fail-author.sh`  | Fail     | Invalid author email domains (noreply, disallowed)         |
| `proxy-fail-message.sh` | Fail     | Commit message validation (WIP, fixup, DO NOT MERGE)       |
| `proxy-fail-diff.sh`    | Fail     | Diff content scanning (internal URLs, patterns)            |
| `proxy-fail-secrets.sh` | Fail     | Gitleaks detecting secrets in diff (AWS, GitHub, PEM)      |

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

3. **Allow rules and permissions** in `fogwall-local.yml` — add your repo slug to the `rules.allow` slugs list and add
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

Two scripts spin up a complete Docker Compose environment (fogwall + Gitea + database), run all test groups, then tear
down:

```shell
bash test/run-postgres.sh             # PostgreSQL backend
bash test/run-mongo.sh                # MongoDB backend

# Leave the environment running after the suite (useful for debugging):
bash test/run-postgres.sh --no-teardown
```

These build the Docker image from source, so no pre-existing server is needed.

## Docker Compose (local Gitea)

The Compose setup runs fogwall against a local Gitea instance. Overlay files are independent mixins — one for the auth
provider, one for the database backend. They can be combined freely.

### Overlay files

**Auth overlays** — each mounts a different `fogwall-local.yml` config into the container:

| File                             | Auth provider                      | Default database |
| -------------------------------- | ---------------------------------- | ---------------- |
| _(none)_                         | Static (password hashes in config) | H2 in-memory     |
| `docker/docker-compose.ldap.yml` | OpenLDAP                           | H2 in-memory     |
| `docker/docker-compose.oidc.yml` | OIDC (mock-oauth2-server)          | H2 in-memory     |

**Database overlays** — each sets `FOGWALL_DATABASE_*` environment variables; no config file swap needed:

| File                                 | Backend      | Profile flag         | UI                     |
| ------------------------------------ | ------------ | -------------------- | ---------------------- |
| _(none)_                             | H2 in-memory | —                    | —                      |
| `docker/docker-compose.postgres.yml` | PostgreSQL   | `--profile postgres` | Adminer at :8082       |
| `docker/docker-compose.mysql.yml`    | MySQL        | `--profile mysql`    | Adminer at :8082       |
| `docker/docker-compose.mariadb.yml`  | MariaDB      | `--profile mariadb`  | Adminer at :8082       |
| `docker/docker-compose.mongo.yml`    | MongoDB      | `--profile mongo`    | Mongo Express at :8081 |

Any auth overlay can be combined with any database overlay (or none, to keep H2). Use the `compose.sh` wrapper rather
than bare `docker compose` — it assembles the right `-f`/`--profile` flags and auto-detects docker vs podman:

```bash
bash compose.sh [--auth ldap|oidc] [--db postgres|mysql|mariadb|mongo] -- up -d
```

### First-time setup

After starting any stack, run this once to create the Gitea admin user and test repository:

```shell
bash docker/gitea-setup.sh
```

### Common stacks

**Static auth + H2** (simplest — no external dependencies):

```shell
bash compose.sh -- up -d
```

**LDAP + H2**:

```shell
bash compose.sh --auth ldap -- up -d
```

**LDAP + PostgreSQL** (recommended for verifying IdP email locking and auto-provisioning):

```shell
bash compose.sh --auth ldap --db postgres -- up -d
```

**OIDC + PostgreSQL**:

```shell
bash compose.sh --auth oidc --db postgres -- up -d
```

**LDAP + MongoDB**:

```shell
bash compose.sh --auth ldap --db mongo -- up -d
```

### Auth provider details

#### Static auth

Log in at `http://localhost:8080` with `admin` / `admin` (defined in `docker/fogwall-local.yml`).

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
bash compose.sh --auth ldap -- rm -sf openldap
bash compose.sh --auth ldap -- up -d openldap
```

#### OIDC auth

Uses [navikt/mock-oauth2-server](https://github.com/navikt/mock-oauth2-server), which accepts any username with no
password required.

**One-time `/etc/hosts` entry** — required so the OIDC issuer URL is the same from your browser and from fogwall inside
Docker:

```text
127.0.0.1  mock-oauth2
```

Open `http://localhost:8080` and log in with any username.

### Proxy URLs

After `docker/gitea-setup.sh`, the test repository is reachable at:

```text
http://localhost:8080/server/gitea:3000/test-owner/test-repo.git
http://localhost:8080/proxy/gitea:3000/test-owner/test-repo.git
```

Clone example:

```shell
git clone http://fogwalladmin:Admin1234!@localhost:8080/server/gitea:3000/test-owner/test-repo.git
```

### Teardown

```bash
bash compose.sh [same --auth/--db flags as start] -- down -v
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
./gradlew :fogwall-dashboard:npmFormat   # auto-format src/ with Prettier
./gradlew :fogwall-dashboard:npmLint     # ESLint check (fails on errors)
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

## Releases

Releases follow a two-phase process to ensure every published image is identical to what was already scanned and running
as `:edge`.

**Phase 1 — version bump.** Create a `release/<version>` branch, update `version` in `build.gradle`, open a PR, and
enable auto-merge. The PR must pass all CI, CodeQL, CVE, and container scan checks before it can merge. Use the
`/release` Claude command to automate this.

**Phase 2 — tag.** Once the version bump lands on `main`, push an annotated tag (`v<version>`). The tag ruleset enforces
the same checks must have passed on that commit. The publish workflow then promotes the already-built `:edge` image
directly to the release tags (`:v1.0.0`, `:latest`, etc.) — no rebuild occurs. Use the `/release-tag` Claude command for
this step.

This means every release image is byte-for-byte identical to the `:edge` image that was scanned when the version bump
merged.

### Documenting new config surface

When a PR introduces a new config section (not just a key on an existing one) in
[docs/CONFIGURATION.md](docs/CONFIGURATION.md), tag it with the release it's shipping in, e.g.
`_Available since v1.3.0._`, right under the heading. This isn't backfilled onto existing sections — only applied going
forward from a section's introduction.
