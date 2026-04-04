# Configuration Reference

jgit-proxy uses YAML configuration with optional local overrides and environment variable support.

## Configuration files

| File | Purpose | Loaded from |
|------|---------|-------------|
| `git-proxy.yml` | Base defaults shipped with the jar | classpath |
| `git-proxy-local.yml` | Local/deployment overrides | classpath (optional) |

Files are merged in order — `git-proxy-local.yml` values take priority over `git-proxy.yml`. Missing keys fall back to defaults. A select set of values can also be overridden via environment variables (highest priority of all).

## Environment variable overrides

Strip the `GITPROXY_` prefix, lowercase, and replace `_` with `.` to get the config path.

| Environment Variable | Config path | Example |
|---|---|---|
| `GITPROXY_SERVER_PORT` | `server.port` | `9090` |
| `GITPROXY_SERVER_APPROVAL_MODE` | `server.approvalMode` | `ui` |
| `GITPROXY_DATABASE_TYPE` | `database.type` | `postgres` |
| `GITPROXY_DATABASE_HOST` | `database.host` | `db.internal` |
| `GITPROXY_PROVIDERS_GITHUB_ENABLED` | `providers.github.enabled` | `false` |
| `GITPROXY_PROVIDERS_<NAME>_URI` | `providers.<name>.uri` | `https://gitlab.corp.com` |

> Complex nested structures (whitelists, full commit validation blocks) are not overridable via env vars. Use YAML files instead.

## Server settings

```yaml
server:
  port: 8080

  # Approval mode for store-and-forward pushes:
  #   auto       — approves every clean push immediately (default; no dashboard required)
  #   ui         — waits for a human reviewer via the REST API
  #   servicenow — delegates to a ServiceNow approval workflow
  # Note: GitProxyWithDashboardApplication always uses 'ui' regardless of this setting.
  approval-mode: auto

  # Sideband keepalive interval in seconds for store-and-forward operations.
  # Sends periodic progress packets to prevent idle-timeout disconnects during
  # long steps (secret scanning, approval polling). Set to 0 to disable.
  heartbeat-interval-seconds: 10

  # Base URL used in dashboard links sent to clients via sideband messages.
  # Defaults to http://localhost:<port> if not set.
  # service-url: https://gitproxy.internal.example.com
```

## Database

```yaml
database:
  type: h2-mem   # memory | h2-mem | h2-file | sqlite | postgres | mongo
```

### Database backends

| Type | Description | Extra keys |
|------|-------------|------------|
| `memory` | In-process, lost on restart | — |
| `h2-mem` | H2 in-memory (default) | `name` (default: `gitproxy`) |
| `h2-file` | H2 persisted to disk | `path` (default: `./.data/gitproxy`) |
| `sqlite` | SQLite file | `path` (default: `./.data/gitproxy.db`) |
| `postgres` | PostgreSQL | `host`, `port`, `name`, `username`, `password` |
| `mongo` | MongoDB | `url`, `name` |

```yaml
# Postgres example
database:
  type: postgres
  host: db.internal
  port: 5432
  name: gitproxy
  username: gitproxy
  password: secret

# Mongo example
database:
  type: mongo
  url: mongodb://gitproxy:secret@localhost:27017
  name: gitproxy
```

## Providers

Providers define the upstream Git hosting services the proxy routes to.

```yaml
providers:
  # Built-in providers — URI is inferred automatically
  github:
    enabled: true
  gitlab:
    enabled: true
  bitbucket:
    enabled: true

  # Custom provider — requires explicit URI
  internal-gitlab:
    enabled: true
    servlet-path: /enterprise
    uri: https://gitlab.internal.example.com

  debian-gitlab:
    enabled: true
    servlet-path: /debian
    uri: https://salsa.debian.org/
```

### Provider properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Whether the provider is active |
| `servlet-path` | string | `""` | Additional URL prefix for this provider |
| `uri` | string | _(built-in default)_ | Upstream base URI (required for custom providers) |

## Commit validation

All validation rules apply to both store-and-forward and transparent proxy modes.

```yaml
commit:
    author:
      email:
        domain:
          # Regex the email domain must match. Omit to allow all domains.
          allow: "(corp\\.example\\.com|contractors\\.example\\.com)$"
        local:
          # Regex blocking specific local-parts (before @). Omit to allow all.
          block: "^(noreply|no-reply|bot|nobody)$"

    message:
      block:
        literals:
          - "WIP"
          - "DO NOT MERGE"
        patterns:
          - '(?i)(password|secret|token)\s*[=:]\s*\S+'

    diff:
      block:
        # Scanned against added lines (+) in the push diff.
        literals:
          - "internal.corp.example.com"
        patterns:
          - '(?i)https?://[a-z0-9.-]*\.corp\.example\.com\b'

    # Secret scanning via gitleaks (https://github.com/gitleaks/gitleaks).
    # The JAR ships with a bundled gitleaks binary so scanning works out of the box.
    # Binary resolution order:
    #   1. scanner-path — explicit path, bypasses everything else
    #   2. version + auto-install: true — downloads and caches on startup
    #   3. bundled JAR binary
    #   4. system PATH
    secret-scanning:
      enabled: false
      # version: 8.22.0
      # auto-install: true
      # install-dir: ~/.cache/jgit-proxy/gitleaks
      # scanner-path: /usr/local/bin/gitleaks
      # config-file: /app/conf/.gitleaks.toml
      # timeout-seconds: 30
```

## Whitelist filters

Whitelist filters control which repositories are accessible through the proxy. Multiple entries can be defined, each scoped to specific providers and operations. At least one whitelist must match for a request to be allowed.

Slugs, owners, and names support glob patterns (e.g. `finos/*`, `*-public`).

```yaml
filters:
    whitelists:
      # Specific repos by slug (owner/name) — both FETCH and PUSH
      - enabled: true
        order: 1100
        operations:
          - FETCH
          - PUSH
        providers:
          - github
        slugs:
          - finos/git-proxy
          - coopernetes/test-repo

      # All repos under an owner — FETCH only, any provider
      - enabled: true
        order: 1200
        operations:
          - FETCH
        owners:
          - finos

      # Repos by name pattern across all providers
      - enabled: true
        order: 1300
        operations:
          - FETCH
        names:
          - hello-world
          - my-org-*
```

### Whitelist properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Whether this entry is active |
| `order` | int | `1100` | Evaluation order (lower = earlier; 1000–1999 range) |
| `operations` | list | _none_ | `FETCH`, `PUSH` — which operations this entry matches |
| `providers` | list | _all_ | Provider names to scope this entry to |
| `slugs` | list | _none_ | `owner/repo` slugs; supports glob patterns |
| `owners` | list | _none_ | Owner/org names; supports glob patterns |
| `names` | list | _none_ | Repository names; supports glob patterns |

## Running

```bash
# Proxy only (no dashboard):
./gradlew :jgit-proxy-server:run

# Proxy + dashboard + REST API:
./gradlew :jgit-proxy-dashboard:run

# Override port via environment variable:
GITPROXY_SERVER_PORT=9090 ./gradlew :jgit-proxy-server:run
```

Logs: `jgit-proxy-server/logs/application.log`
