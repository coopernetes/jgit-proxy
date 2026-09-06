# Configuration Reference

fogwall uses layered YAML configuration merged at startup. A base file ships with the jar; additional profile files and
environment variable overrides are applied on top in a defined order.

> A section introducing a new config surface is tagged with the release it first shipped in, e.g.
> `_Available since v1.3.0._`, right under the heading. Untagged sections predate this convention — it isn't backfilled
> retroactively, only applied going forward from the section's introduction.

## Configuration files and profiles

### Load order (lowest → highest priority)

| Layer | Source                              | When loaded                                          |
| ----- | ----------------------------------- | ---------------------------------------------------- |
| 1     | `fogwall.yml`                       | Always — base defaults bundled in the jar            |
| 2     | `fogwall-{profile}.yml`             | For each profile listed in `FOGWALL_CONFIG_PROFILES` |
| 3     | Environment variables (`FOGWALL_*`) | Always — highest priority                            |

### `FOGWALL_CONFIG_PROFILES`

Set this environment variable to a comma-separated list of profile names. For each name, fogwall looks for
`fogwall-{name}.yml` on the classpath (including any files mounted into `/app/conf/` in Docker). Unknown or missing
profile files are silently skipped.

```bash
# Local development — loads fogwall-local.yml
FOGWALL_CONFIG_PROFILES=local

# Docker with LDAP auth — loads fogwall-docker-default.yml then fogwall-ldap.yml
FOGWALL_CONFIG_PROFILES=docker-default,ldap

# Docker with OIDC auth and PostgreSQL
FOGWALL_CONFIG_PROFILES=docker-default,oidc
# (postgres settings come from FOGWALL_DATABASE_* env vars, no profile file needed)
```

Later profiles take priority over earlier ones. All profiles take priority over `fogwall.yml`. Environment variables
override everything.

### Bundled profiles

| Profile name     | File                         | Purpose                                                   |
| ---------------- | ---------------------------- | --------------------------------------------------------- |
| `local`          | `fogwall-local.yml`          | Local development: dev users, Vite CORS, test allow rules |
| `docker-default` | `fogwall-docker-default.yml` | Docker base: admin user, Gitea provider, validation rules |
| `ldap`           | `fogwall-ldap.yml`           | LDAP authentication config (used with `docker-default`)   |
| `oidc`           | `fogwall-oidc.yml`           | OIDC authentication config (used with `docker-default`)   |

> When running via `./gradlew run`, `FOGWALL_CONFIG_PROFILES=local` is set automatically. In Docker, set it explicitly
> via the Compose file or your deployment config.

### Docker Compose

The Docker Compose setup uses overlay files to compose the stack. See
[docker/docker-compose.ldap.yml](../docker/docker-compose.ldap.yml) and
[docker/docker-compose.oidc.yml](../docker/docker-compose.oidc.yml) for examples of how profiles are combined.

```bash
# Default (local auth, h2 database)
docker compose up -d

# LDAP auth
docker compose -f docker/docker-compose.yml -f docker/docker-compose.ldap.yml up -d

# OIDC auth + PostgreSQL
docker compose --profile postgres \
  -f docker/docker-compose.yml -f docker/docker-compose.oidc.yml -f docker/docker-compose.postgres.yml up -d
```

## Environment variable overrides

Strip the `FOGWALL_` prefix, lowercase, and replace `_` with `.` to get the config path.

| Environment Variable                      | Config path                        | Example                                 |
| ----------------------------------------- | ---------------------------------- | --------------------------------------- |
| `FOGWALL_CONFIG_PROFILES`                 | _(meta — not a config key)_        | `docker-default,ldap`                   |
| `FOGWALL_SERVER_PORT`                     | `server.port`                      | `9090`                                  |
| `FOGWALL_SERVER_APPROVAL_MODE`            | `server.approvalMode`              | `ui`                                    |
| `FOGWALL_SERVER_SERVICE_URL`              | `server.serviceUrl`                | `https://fogwall.example.com/dashboard` |
| `FOGWALL_DATABASE_TYPE`                   | `database.type`                    | `postgres`                              |
| `FOGWALL_DATABASE_URL`                    | `database.url`                     | `jdbc:postgresql://...`                 |
| `FOGWALL_DATABASE_HOST`                   | `database.host`                    | `db.internal`                           |
| `FOGWALL_DATABASE_POOL_MAXIMUMPOOLSIZE`   | `database.pool.maximum-pool-size`  | `3`                                     |
| `FOGWALL_DATABASE_POOL_MINIMUMIDLE`       | `database.pool.minimum-idle`       | `1`                                     |
| `FOGWALL_DATABASE_POOL_CONNECTIONTIMEOUT` | `database.pool.connection-timeout` | `30000`                                 |
| `FOGWALL_SERVER_SESSIONSTORE`             | `server.session-store`             | `jdbc`                                  |
| `FOGWALL_SERVER_REDIS_HOST`               | `server.redis.host`                | `redis.cluster.local`                   |
| `FOGWALL_SERVER_REDIS_PORT`               | `server.redis.port`                | `6379`                                  |
| `FOGWALL_SERVER_ALLOWEDORIGINS`           | `server.allowed-origins`           | `https://dashboard.example.com`         |
| `FOGWALL_PROVIDERS_GITHUB_ENABLED`        | `providers.github.enabled`         | `false`                                 |
| `FOGWALL_PROVIDERS_<NAME>_URI`            | `providers.<name>.uri`             | `https://gitlab.corp.com`               |

> Complex nested structures (URL rules, full commit validation blocks) are not overridable via env vars. Use YAML
> profile files instead.

### Hyphenated keys and provider names

_Available since v1.3.0._

The single-underscore rule above can't produce a hyphen — there's no way to write `providers.gitea-ssh.api-token` as an
env var name if every `_` is a path separator. For a config key or a provider name that itself contains a hyphen, use
the double-underscore convention instead (same idea as systemd, Kubernetes, and Docker Compose): `__` is the path
separator, and a lone `_` becomes a hyphen.

| Environment Variable                      | Config path                     |
| ----------------------------------------- | ------------------------------- |
| `FOGWALL_PROVIDERS__GITEA_SSH__API_TOKEN` | `providers.gitea-ssh.api-token` |
| `FOGWALL_SERVER__SESSION_STORE`           | `server.session-store`          |

This only activates when the env var name contains `__` — every existing single-underscore example above still works
unchanged, since none of them contain `__`.

## Server settings

```yaml
server:
  port: 8080

  # Approval mode for server mode pushes:
  #   auto       — approves every clean push immediately (default; no dashboard required)
  #   ui         — waits for a human reviewer via the REST API
  #   servicenow — delegates to a ServiceNow approval workflow
  # Note: FogwallDashboardApplication always uses 'ui' regardless of this setting.
  approval-mode: auto

  # How long a server mode push waits for a review decision while the client
  # connection is held open (approval-mode: ui or servicenow). On expiry the push is
  # marked CANCELED and rejected — the developer must re-push and re-review. This is a
  # live-session bound, not a durable queue; hold it short. Default 1800 (30 minutes).
  approval-timeout-seconds: 1800

  # Sideband keepalive interval in seconds for server mode operations.
  # Sends periodic progress packets to prevent idle-timeout disconnects during
  # long steps (secret scanning, approval polling). Set to 0 to disable.
  heartbeat-interval-seconds: 10

  # Whether server mode serves clone/fetch from its local mirror. Default true —
  # a developer whose remote is the fogwall URL expects `git pull` to work against it.
  # Applies to both server mode transports (HTTP and SSH). Set to false to make fogwall
  # a push-only gateway that never serves a local mirror; fetches are then refused with
  # a clear git-side error ("fetches are not served through this gateway"), not a 404
  # that reads as a missing repository. Push (receive-pack) is unaffected either way.
  # Transparent proxy mode is unaffected — it forwards to upstream, serving no local
  # mirror. Override per provider with providers.<name>.serve-fetch.
  serve-fetch: true

  # Maximum number of requests handled concurrently on virtual threads. Requests over
  # the limit wait for a slot. Each in-flight push holds its buffered pack data in
  # memory until the request completes, so size this to heap capacity and typical pack
  # size — prefer adding instances over raising this limit when scaling out.
  # Set to 0 to disable virtual-thread dispatch (requests then run directly on the
  # platform thread pool, capped by its own size).
  max-concurrent-requests: 512

  # Jetty platform thread-pool sizing. With virtual-thread dispatch on (the default,
  # above) this pool runs only Jetty's acceptors and selectors — not blocking
  # application work — so the defaults suit most deployments; these knobs are for
  # tuning a large or constrained instance. Defaults match Jetty's own QueuedThreadPool.
  # A config with min > max is rejected at startup.
  threads:
    min: 8 # minimum (core) platform threads kept alive
    max: 200 # maximum platform threads
    idle-timeout-ms: 60000 # ms an idle thread above `min` is kept before being reaped

  # Largest request body fogwall will accept, in bytes. Applies to both proxy modes.
  # An over-size push is rejected with a git error before the body is read, so it
  # costs no memory. Set to 0 to disable the check — note that "unlimited" is still
  # bounded by heap in practice, and absolutely by ~2GiB, since the body is buffered
  # as a single array.
  # Raising this needs a matching increase in container memory: the real bound is heap
  # divided by concurrent pushes. See "Sizing memory for pushes" in the Admin Guide.
  max-push-bytes: 67108864 # 64MiB

  # Largest decompressed size of any single object in a pushed pack, in bytes.
  # Applies to both proxy modes, and to both server mode transports (HTTP and
  # SSH). max-push-bytes above caps the compressed wire size, but a crafted pack can
  # inflate to roughly 1000x its compressed size — this caps the inflated side, per
  # object. A push containing a violating object is rejected during pack parsing.
  #
  # The default sits deliberately far above the binary-blob filter's 50MiB policy
  # ceiling: binary-blob is the tunable policy layer for "how big may a file be",
  # while this limit exists only to stop decompression bombs, and should be kept
  # high enough that no legitimate push ever hits it. Set to 0 to disable.
  max-object-size-bytes: 134217728 # 128MiB

  # Base URL fogwall is externally reachable at — the bare host, WITHOUT a /dashboard suffix (fogwall appends
  # /dashboard, /api, etc. itself where needed). Used in links sent to clients via sideband messages, and required
  # for SCM OAuth account linking (#40) to build a correct redirect_uri — OAuth linking is disabled with a clear
  # error if this is unset. No default — must be set explicitly.
  #
  # BREAKING CHANGE in v1.4.0: prior releases expected this to already include any path prefix your reverse proxy
  # adds (e.g. https://fogwall.internal.example.com/dashboard), with fogwall concatenating routes directly onto it.
  # As of v1.4.0 it must be the bare origin instead — drop a trailing /dashboard (or other prefix) from your existing
  # value when upgrading, or push-record/profile links in sideband messages will point at the wrong path.
  # service-url: https://fogwall.internal.example.com

  # When false (default), any authenticated user may review any push they did not push
  # themselves. Set to true to require an explicit REVIEW permission entry for the repo.
  # Use true for deployments that need restricted approvers with formal sign-off.
  require-review-permission: false

  # Origins allowed to make cross-origin requests to the dashboard REST API.
  # Required when the frontend is served from a different hostname than the backend
  # (e.g. Vite dev server on port 5173, or a load-balanced dashboard behind a different host).
  # Default (empty): same-origin only.
  # allowed-origins:
  #   - http://localhost:5173
  #   - https://dashboard.example.com

  # HTTP session persistence backend. Controls where authenticated sessions are stored.
  # Options:
  #   none   — in-memory (default); sessions lost on restart, not shared across pods
  #   jdbc   — persisted to the configured JDBC database; zero new infrastructure required
  #   mongo   — persisted to the configured MongoDB database; zero new infrastructure required
  #   redis  — persisted to a Redis or Valkey instance; configure via server.redis.*
  # Use jdbc or redis for multi-instance deployments so sessions survive pod restarts
  # and remain valid across all replicas.
  # session-store: none

  # Redis connection — only required when session-store: redis
  # redis:
  #   host: redis.cluster.local
  #   port: 6379
  #   password: ""
  #   ssl: false
```

### Session persistence for multi-instance deployments

By default, authenticated sessions are stored in memory. This works for single-instance deployments but means:

- Sessions are lost when a pod restarts
- A user hitting a different pod after a load balancer switch will be logged out

Set `server.session-store` to persist sessions across restarts and share them between replicas.

**JDBC (recommended — zero new infrastructure):**

```yaml
server:
  session-store: jdbc

database:
  type: postgres
  url: jdbc:postgresql://db.internal:5432/fogwall
  pool:
    maximum-pool-size: 3
    minimum-idle: 1
```

The session tables (`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`) are created automatically by the database migrator on
first startup. No manual DDL required.

**Redis / Valkey:**

```yaml
server:
  session-store: redis
  redis:
    host: redis.cluster.local # or valkey.cluster.local
    port: 6379
    password: "" # omit if no auth configured
    ssl: false # set true for TLS-secured Redis
```

A minimal single-replica Redis or Valkey pod is sufficient — sessions are small and low-throughput. No persistence or
clustering required for this use case.

**MongoDB:**

```yaml
server:
  session-store: mongo

database:
  type: mongo
  url: mongodb://fogwall:secret@mongo.internal:27017/fogwall
```

Sessions are stored in the `proxy_sessions` collection alongside the other `proxy_*` collections. A TTL index on
`expireAt` lets MongoDB expire idle sessions server-side — no background cleanup task runs in the proxy. The session
store reuses the same connection pool as the rest of the MongoDB-backed stores, so no extra configuration is needed.
Requires `database.type: mongo`.

## Local mirror cache

To inspect push content, fogwall keeps a local bare mirror of each upstream repository. How much history that mirror
holds is a per-deployment tradeoff: a full mirror is the most correct basis for reachability checks, but a first clone
of a large repository (e.g. one with a large binary history) can be slow enough to exceed HTTP connection timeouts; a
shallow mirror clones cheaply but truncates history. The two modes are configured separately and only their **defaults**
differ — **server mode defaults to full history, transparent proxy to a shallow clone.** Either mode accepts either
knob: server mode is used against large repositories too, so shallow cloning is fully supported there — set
`cache.server.shallow-since` or `cache.server.clone-depth` to enable it.

```yaml
cache:
  proxy:
    shallow-since: 90d # keep history back ~90 days (preferred)
    # clone-depth: 100 # or a fixed commit depth; used only when shallow-since is unset
  server:
    clone-depth: 0 # 0 = full history (the default)
```

| Key                          | Default (proxy / server) | Meaning                                                                                                    |
| ---------------------------- | ------------------------ | ---------------------------------------------------------------------------------------------------------- |
| `cache.<mode>.shallow-since` | unset                    | Time-based boundary — `90d`, `12h`, `30m`, or an ISO-8601 duration (`PT48H`). Takes precedence over depth. |
| `cache.<mode>.clone-depth`   | `100` / `0`              | Commit depth for the shallow clone; `0` means full history. Used only when `shallow-since` is unset.       |

Prefer `shallow-since`: a commit _depth_ is a graph-distance bound, not a function of age, so which commits fall outside
`depth=100` is unpredictable for a given repository — whereas "keep 90 days" is something an operator can reason about
and write against a retention requirement.

A shallow default is a clone-cost optimisation, not a correctness ceiling: checks that would get a wrong answer from a
truncated mirror (reachability / hidden-commit detection) deepen it to full history on demand before deciding, so a
release tag on a commit that predates the shallow boundary is still validated correctly.

## TLS

### Server HTTPS listener

By default fogwall listens on plain HTTP. To enable HTTPS, add a `server.tls` block.

**PEM-based (preferred — no keytool required):**

```yaml
server:
  tls:
    port: 8443
    certificate: /etc/fogwall/tls/server.pem # X.509 certificate or chain, PEM
    key: /etc/fogwall/tls/server-key.pem # PKCS8 private key, unencrypted PEM
```

The private key must be in PKCS8 format. Convert a PKCS1 key with:

```bash
openssl pkcs8 -topk8 -nocrypt -in server.pem -out server-key.pem
```

**Keystore-based (for shops with existing managed keystores):**

```yaml
server:
  tls:
    port: 8443
    keystore:
      path: /etc/fogwall/tls/keystore.p12
      password: changeit
      type: PKCS12 # or JKS
```

Plain HTTP on `server.port` remains active when HTTPS is configured — both listeners run concurrently.

### Custom upstream CA trust

Enterprise PKIs typically issue certificates that Java's built-in truststore doesn't include, causing
`SSLHandshakeException` on upstream connections to internal GitLab/Bitbucket/Forgejo instances. fogwall supports
trusting a custom CA bundle without touching the JVM truststore or running `keytool`.

```yaml
server:
  tls:
    trust-ca-bundle: /etc/fogwall/tls/internal-ca.pem
```

The PEM file may contain one or more `-----BEGIN CERTIFICATE-----` blocks (a full CA chain is fine). Custom CAs are
merged with the JVM's built-in trust anchors — public hosts (GitHub, GitLab SaaS, Bitbucket Cloud) continue to work
without any changes.

This applies to both proxy modes:

- **Transparent proxy** — Jetty's `HttpClient` used for upstream forwarding
- **Server mode** — JGit's HTTP transport used for forwarding after local receipt

## Outbound proxy

_Available since v1.3.0._

For environments without direct internet access, fogwall can route its own outbound connections through a corporate HTTP
proxy. This covers all three places fogwall makes outbound connections: server mode upstream pushes (JGit Transport),
transparent-proxy forwarding (Jetty `HttpClient`), and provider REST API calls (identity resolution, SSH key listing).

```yaml
server:
  outbound-proxy:
    https-proxy: http://proxy.example.com:8080
    no-proxy: localhost,*.internal.example.com
    auth:
      type: none # none (default) | basic | kerberos
```

`http-proxy`, `https-proxy`, and `no-proxy` fall back to the `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` environment variables
when left unset in YAML — an explicit YAML value always takes precedence. Leave `auth.type` at `none` (the default) when
the configured proxy doesn't require fogwall to authenticate itself.

### Proxy authentication

When the configured proxy requires authentication, two schemes are supported:

```yaml
server:
  outbound-proxy:
    https-proxy: http://proxy.example.com:8080
    auth:
      type: basic
      username: ${PROXY_USER}
      password: ${PROXY_PASS}
```

```yaml
server:
  outbound-proxy:
    https-proxy: http://proxy.example.com:8080
    auth:
      type: kerberos
      # keytab-path and principal are both optional. Omitted (the default): fogwall authenticates using
      # whatever Kerberos ticket is already in the OS's ticket cache — the common case on a machine that
      # already has a live ticket from domain/SSO login. Set both only when fogwall should authenticate
      # as its own service identity instead (e.g. a long-running headless deployment with no live session).
      # keytab-path: /etc/fogwall/proxy.keytab
      # principal: fogwall/host@CORP.EXAMPLE.COM
```

NTLM is intentionally not supported as a scheme fogwall speaks directly — it's a deprecated protocol, and Jetty's HTTP
client (the transparent-proxy path) has no NTLM support at all and can't cleanly add it, since the reference NTLM
implementation (jcifs) is LGPL-licensed and Jetty won't bundle it. Kerberos/Negotiate is the modern successor in Active
Directory environments and is natively supported across all three outbound paths.

## Database

```yaml
database:
  type: h2-mem # h2-mem | h2-file | postgres | mysql | mariadb | mongo
```

### Database backends

| Type       | Description            | Extra keys                                                                 |
| ---------- | ---------------------- | -------------------------------------------------------------------------- |
| `h2-mem`   | H2 in-memory (default) | `name` (default: `fogwall`)                                                |
| `h2-file`  | H2 persisted to disk   | `path` (default: `./.data/fogwall`)                                        |
| `postgres` | PostgreSQL             | `url` **or** `host`, `port`, `name`, `username`, `password`                |
| `mysql`    | MySQL 8.0+             | `url` **or** `host`, `port` (default 3306), `name`, `username`, `password` |
| `mariadb`  | MariaDB 10.5+          | `url` **or** `host`, `port` (default 3306), `name`, `username`, `password` |
| `mongo`    | MongoDB                | `url` (required); `name` optional if the database is in the URI path       |

SQLite is intentionally not supported — its single-writer file locking is unsuitable for a proxy that may run more than
one instance against the same database.

For `postgres`, `mysql`, `mariadb`, and `mongo`, setting `url` to a full connection string is the recommended approach
when you need driver-specific options (TLS, SSL certificates, connection parameters) that are not exposed as individual
config fields.

MySQL and MariaDB use **separate JDBC drivers** (`mysql-connector-j` and `mariadb-java-client`, not one shared driver) —
pick the `type` matching the actual engine you're running, even though the two are largely wire-compatible.
`database.port` defaults to PostgreSQL's port (5432); for `mysql`/`mariadb` set it explicitly (typically `3306`) unless
the server actually listens on 5432 — fogwall logs a startup warning if it detects the untouched default being used with
either type.

### Connection pool tuning

Applies to all JDBC backends (`h2-mem`, `h2-file`, `postgres`, `mysql`, `mariadb`). The default pool size is
deliberately small — git push workloads are sequential per user, so a large pool buys nothing and drives up aggregate
connection counts when multiple instances share a database. The
[HikariCP pool sizing guide](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) covers this in depth.

```yaml
database:
  type: postgres
  url: jdbc:postgresql://db.internal:5432/fogwall
  pool:
    maximum-pool-size: 3 # per-instance connections; multiply by instance count for total DB load
    minimum-idle: 1 # release idle connections; omit to keep the full pool warm
    connection-timeout: 30000 # ms; fail fast if the pool is exhausted
    idle-timeout: 600000 # ms; retire connections after 10 min idle
    max-lifetime: 1800000 # ms; rotate connections every 30 min
```

For deployments sharing a database across multiple instances (multiple environments, blue/green, canary), set
`maximum-pool-size` to the lowest value that keeps p99 push latency acceptable. For most proxy workloads, 2–5
connections per instance is sufficient.

```yaml
# Postgres — individual fields
database:
  type: postgres
  host: db.internal
  port: 5432
  name: fogwall
  username: fogwall
  password: secret

# Postgres — connection string (use this for sslmode, certificates, etc.)
database:
  type: postgres
  url: jdbc:postgresql://db.internal:5432/fogwall?sslmode=verify-full&sslrootcert=/certs/ca.crt
  username: fogwall
  password: secret

# MySQL — individual fields
database:
  type: mysql
  host: db.internal
  port: 3306
  name: fogwall
  username: fogwall
  password: secret

# MySQL — connection string
database:
  type: mysql
  url: jdbc:mysql://db.internal:3306/fogwall?useSSL=true&requireSSL=true
  username: fogwall
  password: secret

# MariaDB — individual fields
database:
  type: mariadb
  host: db.internal
  port: 3306
  name: fogwall
  username: fogwall
  password: secret

# MariaDB — connection string
database:
  type: mariadb
  url: jdbc:mariadb://db.internal:3306/fogwall?useSsl=true
  username: fogwall
  password: secret

# Mongo — connection string (name extracted from URI path)
database:
  type: mongo
  url: mongodb://fogwall:secret@mongo.internal:27017/fogwall?tls=true&tlsCAFile=/certs/ca.crt

# Mongo — connection string with separate name field
database:
  type: mongo
  url: mongodb://fogwall:secret@mongo.internal:27017
  name: fogwall
```

### SCM token identity cache

When users are configured, fogwall caches successful token-to-username resolutions so that repeated pushes from the same
PAT do not incur a provider API call every time. The cache is backed by the configured database (JDBC or MongoDB) and
keyed on a SHA-512 digest of the token — the raw token is never stored.

| Variable                         | Default | Effect                                                                                                                               |
| -------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `FOGWALL_SCM_CACHE_MAX_AGE_DAYS` | `7`     | Maximum age of a cache entry in days. Entries older than this are ignored on read and overwritten on the next successful resolution. |

Token rotation is handled automatically: a new PAT produces a new cache key, so the old entry simply ages out. There is
no need to flush the cache manually when tokens are rotated.

### MongoDB: coexisting with the upstream Node.js git-proxy

If you are migrating from [finos/git-proxy](https://github.com/finos/git-proxy) (the Node.js implementation) and
pointing this proxy at a database that previously held its data, the two applications use incompatible document schemas.
The safest path is to **provision a new MongoDB database** (e.g. `fogwall`) and point `database.url` at it. This avoids
all collision risk and keeps indexes, backups, and ops tooling cleanly separated.

If provisioning a separate database is not feasible, this proxy now uses collection names that do not collide with the
upstream Node.js implementation:

| Collection         | Written by                 | Notes                                                              |
| ------------------ | -------------------------- | ------------------------------------------------------------------ |
| `proxy_users`      | `MongoUserStore`           | Renamed from `users` to avoid collision with upstream's `users`.   |
| `proxy_pushes`     | `MongoPushStore`           | Renamed from `pushes` to avoid collision with upstream's `pushes`. |
| `repo_permissions` | `MongoRepoPermissionStore` | No upstream equivalent.                                            |
| `access_rules`     | `MongoUrlRuleRegistry`     | No upstream equivalent.                                            |
| `fetch_records`    | `MongoFetchStore`          | No upstream equivalent.                                            |

This means you _can_ point both apps at the same MongoDB database without corrupting each other's data. We still
recommend separate databases for operational clarity — shared databases make backups, restores, and index tuning harder
to reason about — but it is no longer a correctness hazard. Starting with 1.0.0, these collection names are part of the
project's stability contract and will not be renamed without an in-place migration path.

## Authentication

The dashboard supports four authentication providers, selected via `auth.provider`.

```yaml
auth:
  provider: local # local | ldap | ad | oidc (default: local)

  # Maximum idle time before a session expires and the user must re-authenticate.
  # Default: 86400 (24 hours). Tighten to 28800 (8 hours) or less for compliance environments.
  session-timeout-seconds: 86400
```

### Local (default)

Usernames and BCrypt password hashes are defined directly in the `users:` block. See the [Users](#users) section.

### LDAP

Authenticates users against a generic LDAP directory using a bind operation.

```yaml
auth:
  provider: ldap
  ldap:
    # LDAP server URL including base DN.
    url: ldap://ldap.example.com:389/dc=example,dc=com

    # User DN pattern — {0} is substituted with the login username.
    user-dn-patterns: cn={0},ou=users

    # Optional bind credentials for group search / attribute lookup.
    bind-dn: cn=admin,dc=example,dc=com
    bind-password: secret

    # Base DN (relative to url base) to search for group membership.
    # When set, group names are mapped to roles via auth.role-mappings below.
    group-search-base: ou=groups

    # LDAP filter for group membership. {0} = user full DN, {1} = username.
    group-search-filter: "(member={0})"

  # Map fogwall role names to lists of LDAP group CNs.
  # When a user is a member of any listed group, the role is granted.
  role-mappings:
    ADMIN:
      - git-admins
      - security-team
```

### Active Directory

Authenticates users against an on-premises Active Directory domain using UPN bind (`user@domain.com`). Unlike the
generic LDAP provider, no `user-dn-patterns` is required — Spring Security constructs the UPN automatically from the
`domain` and the submitted username.

```yaml
auth:
  provider: ad
  ad:
    # AD domain name — used to form user@domain UPN for bind.
    domain: corp.example.com

    # Domain controller URL. When omitted, Spring Security resolves the DC via DNS SRV records.
    url: ldap://dc.corp.example.com:389

    # Optional: base DN for group search. When set, group membership is used for role mapping.
    group-search-base: DC=corp,DC=example,DC=com

    # LDAP filter for group membership. {0} = user full DN.
    group-search-filter: "(member={0})"

  role-mappings:
    ADMIN:
      - CN=git-admins,OU=Groups,DC=corp,DC=example,DC=com
```

<!-- prettier-ignore-start -->
> [!TIP]
> The AD provider understands Active Directory error sub-codes on bind failure 49 (expired passwords, locked accounts, etc.) and maps them to specific Spring Security exceptions.
<!-- prettier-ignore-end -->

### OIDC

Authenticates users via OpenID Connect authorization code flow (Keycloak, Okta, Entra ID, Dex, etc.).

```yaml
auth:
  provider: oidc
  oidc:
    # OIDC issuer URI — Spring Security fetches {issuerUri}/.well-known/openid-configuration at startup.
    issuer-uri: https://accounts.example.com

    client-id: fogwall-client
    client-secret: fogwall-secret

    # Optional: path to a PKCS#8 PEM RSA private key for private_key_jwt client auth.
    # When set, client-secret is not required.
    # private-key-path: /run/secrets/fogwall-oidc-private-key.pem

    # Optional: path to the X.509 certificate (PEM) matching private-key-path.
    # Required for Entra ID — Entra matches registered certificates by x5t thumbprint, not kid.
    # Without this, every token exchange fails with AADSTS700027.
    # Generate: openssl req -new -x509 -key private.pem -out cert.pem -days 365
    # cert-path: /run/secrets/fogwall-oidc-cert.pem

    # Optional: explicit kid to embed in the private_key_jwt assertion header.
    # Use this for providers that match the assertion against a registered JWKS by kid
    # (Keycloak, Okta, Auth0, Dex). Without it, a random UUID kid is generated on each
    # restart, which breaks authentication with those providers.
    # Find the kid by inspecting your provider's JWKS endpoint and matching it to the
    # public key you registered.
    # Not needed when cert-path is set (Entra ID uses x5t instead of kid).
    # key-id: my-registered-kid

    # Claim used as the principal name (the fogwall username). Defaults to "sub" — the only
    # claim the OIDC spec guarantees in every ID token. Claims like preferred_username and
    # email are voluntary: point this at one only if your IdP actually sends it, otherwise
    # login fails with a clear "claim not present" error.
    # user-name-attribute: email

    # Read all claims from the ID token and never call the UserInfo endpoint. Needed when
    # user-name-attribute names a claim your IdP's UserInfo response omits (see the Entra ID
    # section below); harmless otherwise.
    # skip-user-info: true

    # Endpoint overrides. OIDC discovery ALWAYS runs at startup against issuer-uri; these
    # replace individual discovered endpoints for split-egress setups (e.g. an internal JWKS
    # mirror). They have no effect on token validation.
    # authorization-uri: ...
    # token-uri: ...
    # user-info-uri: ...
    # jwk-set-uri: ...

  # OIDC claim containing the user's group memberships. Defaults to "groups",
  # which is standard for Keycloak, Okta, and most Entra ID configurations.
  groups-claim: groups

  # Map fogwall role names to lists of OIDC group values from the claim above.
  role-mappings:
    ADMIN:
      - git-admins
```

#### Entra ID (Azure AD)

Entra ID works with standard OIDC discovery and full stock token validation — no bypass and no endpoint overrides. What
it does need is two settings that account for its unusual (but spec-conformant — all profile/email claims are voluntary,
only `sub` is guaranteed) **claims** behaviour:

- **`user-name-attribute: email`** — with the `email` optional claim added to the app registration (checklist below).
  Don't skip this one: the default (`sub`) logs in without any error, but Entra's `sub` is an opaque generated string,
  so every downstream record — permissions, push history, the admin UI — ends up keyed to an unreadable identifier.
  Nothing breaks loudly; it is just miserable to operate.
- **`skip-user-info: true`** (recommended). Entra's UserInfo endpoint is Microsoft Graph, which returns HTTP 200 with a
  minimal claim set and has historically broken Spring's principal construction when `user-name-attribute` names a claim
  its response lacks. Skipping UserInfo reads all claims from the ID token and drops the runtime dependency on Graph
  (and its `User.Read` permission) entirely.

```yaml
auth:
  provider: oidc
  oidc:
    # The /v2.0 suffix matters: it selects the v2 endpoints, and ID-token format follows the
    # endpoint — v2 tokens carry iss=https://login.microsoftonline.com/{tenant-id}/v2.0, which
    # matches discovery and validates cleanly.
    issuer-uri: https://login.microsoftonline.com/{tenant-id}/v2.0
    client-id: <app-registration-client-id>
    client-secret: <client-secret>
    skip-user-info: true
    user-name-attribute: email

  # Requires "Group claims" to be enabled in the app registration (Token configuration → Groups claim).
  # Group values will be object IDs (GUIDs) unless "Group names" is selected in the manifest.
  groups-claim: groups

  role-mappings:
    ADMIN:
      - <object-id-of-admin-group>
```

<!-- prettier-ignore-start -->
> [!IMPORTANT]
> **App registration checklist:**
> 1. Platform: Web — redirect URI `https://<your-host>/login/oauth2/code/fogwall`
> 2. API permissions: `openid`, `profile`, `email` (delegated)
> 3. Token configuration → add Groups claim → select "Security groups"
> 4. Token configuration → add optional claim → ID token → `email` (feeds fogwall's locked-email provisioning)
<!-- prettier-ignore-end -->

**Legacy v1-token tenants.** An app registration reached through the v1 endpoints issues v1-format tokens with
`iss=https://sts.windows.net/{tenant-id}/`, which fails validation against the v2 discovery issuer. The fix is to use
the v2 issuer URI above. If your tenant genuinely cannot, point `issuer-uri` directly at
`https://sts.windows.net/{tenant-id}/` — it serves a self-consistent discovery document — and set `user-name-attribute`
to a claim v1-format tokens actually carry (check a decoded token; `upn` is the usual candidate).

To tell which case you are in, decode an ID token from a real login and check its `ver` claim (`"1.0"` or `"2.0"`) —
that is authoritative. Don't infer it from the app manifest: Microsoft documents `requestedAccessTokenVersion` as
applying to access tokens issued to your app when it acts as an API, and in deployments verified so far a tenant with
that setting unset still issued v2 ID tokens through the `/v2.0` endpoints — but tenant configurations vary, so check
the token.

### Role mappings

`auth.role-mappings` applies to LDAP, AD, and OIDC. Keys are role names (without the `ROLE_` prefix); values are lists
of group names or claim values from the IdP.

| Role    | Dashboard access                                                               |
| ------- | ------------------------------------------------------------------------------ |
| `USER`  | View and act on pushes awaiting approval                                       |
| `ADMIN` | All USER permissions + create/delete users, reset passwords, manage identities |

When `role-mappings` is empty, the operator has not configured group-based access control: `ROLE_USER` is granted to
every authenticated user (open mode). When `role-mappings` is non-empty, access is **deny-by-default** — a user whose
IdP groups don't match any mapping authenticates successfully against the directory/IdP but is refused access by
fogwall.

```yaml
auth:
  role-mappings:
    ADMIN:
      - git-admins
  # Deny-by-default is the correct posture for regulated environments and is the default.
  # Set to false to treat the IdP purely as an authentication mechanism (SSO convenience): any
  # user who authenticates successfully is granted ROLE_USER even if no group mapping matches.
  # role-mappings (if present) then only grant additional roles on top. No-op when role-mappings
  # is empty, since open mode is already the behaviour in that case.
  require-role-mapping: true
```

## Providers

Providers define the upstream Git hosting services the proxy routes to.

```yaml
providers:
  # Reserved names — provider type and default URI are built in
  github:
    enabled: true # → github.com
  gitlab:
    enabled: true # → gitlab.com
  bitbucket:
    enabled: true # → bitbucket.org
  codeberg:
    enabled: true # → codeberg.org
  gitea:
    enabled: true # → gitea.com

  # Custom-named providers — 'type' and 'uri' are both required
  my-internal-server:
    enabled: true
    type: github # uses GitHubProvider (identity resolution, GHES API path logic, etc.)
    uri: https://github.corp.example.com

  my-forgejo:
    enabled: true
    type: forgejo # ForgejoProvider; uri is required (forgejo has no canonical public host)
    uri: https://forge.internal.example.com

  acme-bitbucket:
    enabled: true
    type: bitbucket
    uri: https://bitbucket.acme.com
```

### Provider properties

| Property                   | Type    | Default                | Description                                                                                                                                                                                                                           |
| -------------------------- | ------- | ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `enabled`                  | boolean | `true`                 | Whether the provider is active                                                                                                                                                                                                        |
| `servlet-path`             | string  | `""`                   | Additional URL prefix for this provider                                                                                                                                                                                               |
| `uri`                      | string  | _(built-in default)_   | Upstream base URI. Required for custom-named providers; omit for built-ins.                                                                                                                                                           |
| `type`                     | string  | _(from name)_          | Provider implementation: `github`, `gitlab`, `bitbucket`, `codeberg`, `forgejo`, `gitea`. Required for any name that is not one of the five reserved names.                                                                           |
| `api-uri`                  | string  | _(derived from `uri`)_ | HTTP base URI for provider REST API calls (identity resolution, SSH key lookup). Only needed when the HTTP API port can't be derived from `uri` — e.g. a self-hosted instance where the HTTP API runs on a non-standard port.         |
| `api-token`                | string  | _(none)_               | PAT used when the provider's SSH key listing API requires authentication (Forgejo/GitLab with `REQUIRE_SIGNIN_VIEW=true`). GitHub's equivalent endpoint is public and needs no token.                                                 |
| `ssh`                      | block   | _(disabled)_           | SSH transport for this provider — the same entry serves both HTTP and SSH. See [Serving a provider over SSH](#serving-a-provider-over-ssh).                                                                                           |
| `blocked-info-refs-status` | int     | `403`                  | HTTP status returned when a blocked `/info/refs` discovery request is denied. `403` is unambiguous; `404` obscures whether the repo exists (security by obscurity).                                                                   |
| `serve-fetch`              | boolean | _(inherits global)_    | Per-provider override for whether server mode serves clone/fetch from the local mirror (both transports). Omit to inherit the global [`server.serve-fetch`](#server-settings); set `true`/`false` to override for this provider only. |

The five reserved names (`github`, `gitlab`, `bitbucket`, `codeberg`, `gitea`) carry a built-in default URI and provider
type. Any other name is opaque — the name is never parsed for type hints — so `type` and `uri` must both be set. The
typed provider supplies API URL logic, identity resolution, and (for Bitbucket) credential rewriting; `uri` overrides
only the upstream address.

### Bitbucket identity resolution

Bitbucket does not enforce the git push username — only the token is validated. To enable identity resolution (required
for push permission checks and commit identity verification), the proxy adopts the convention that the **HTTP Basic-auth
username in the remote URL must be the user's Bitbucket account email address**.

Configure the remote URL like this:

```bash
https://<email>:<api-token>@bitbucket.org/<workspace>/<repo>.git
```

The proxy calls `GET /2.0/user` using those credentials to look up the user's Bitbucket `username` (the auto-generated
URL-safe identifier, e.g. `a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6`). It then rewrites the outbound credentials to
`username:token` before forwarding the push to Bitbucket — this is necessary because Bitbucket's git endpoint only
accepts the internal username, not an email address.

**Required API token scopes:** `read:user:bitbucket` and `write:repository:bitbucket`.

<!-- prettier-ignore-start -->
> [!TIP]
> **M&A / private server use case:** This same mechanism works for self-hosted Bitbucket Data Center instances. Set `uri` to your internal Bitbucket URL and the proxy will route and rewrite credentials accordingly, making it straightforward to gate pushes to acquired-company repositories during an integration period.
<!-- prettier-ignore-end -->

## SCM OAuth

_Available since v1.4.0._

Lets a proxy user link their account to an upstream SCM identity (GitHub, GitLab, or a Forgejo/Gitea/Codeberg instance)
via OAuth from their profile page, instead of typing their SCM username into a free-text field. A successful link sets
`verified = true` on that identity, which `scm-oauth.identity-mode: strict` can then require for push authorization —
closing the gap where a manually entered SCM username is trusted with no proof the pusher actually controls it. Linking
also imports the SCM provider's own verified emails and registered SSH public keys, so a developer doesn't have to
re-enter data the provider already has confirmed.

```yaml
scm-oauth:
  # permissive (default): any linked SCM identity is usable for push authorization, verified or not — today's
  # behaviour, unchanged.
  # strict: only OAuth-verified identities count, on both HTTP and SSH push paths.
  identity-mode: permissive

  # Path to a file holding a base64-encoded 32-byte AES-256-GCM key, used to encrypt linked OAuth tokens at rest.
  # If unset, a key is auto-generated under ./.data/ for local development only — a loud warning is logged on every
  # startup when this happens. Production deployments MUST set this to a durable, backed-up location (see
  # ADMIN_GUIDE.md's production checklist); losing an auto-generated key just means every linked user has to
  # re-link — push authorization itself is never affected by a token-encryption problem.
  token-encryption-key-path: /run/secrets/fogwall-scm-oauth-key

# OAuth app registration is a property of the provider instance it belongs to, nested under that provider's own
# providers.<name>.oauth block below — not a separate map keyed by the same name. An operator running two separate
# GitHub OAuth apps at once (one for github.com/GHEC, a second for a GHEC-with-data-residency *.ghe.com tenant, or a
# self-managed GHES host) already needs two separate providers: entries for routing; each just carries its own
# oauth: block alongside its uri. The OAuth authorize/token/user-API host is always derived from that same provider
# instance's own `uri` — github.com/GHEC, *.ghe.com, and self-managed GHES are each detected automatically, so
# there's nothing to set for that under oauth: specifically. GHES's `api-uri` (the general provider setting, not
# scm-oauth-specific) is also auto-derived and only needs setting explicitly when the API isn't reachable on the
# standard host/port (e.g. local dev).
providers:
  github:
    enabled: true # github.com/GHEC
    oauth:
      enabled: true
      client-id: Iv1.abc123
      client-secret-path: /run/secrets/fogwall-github-oauth-secret

  github-ghe:
    enabled: true
    type: github
    uri: https://my-tenant.ghe.com
    oauth:
      enabled: true
      client-id: Iv1.def456
      client-secret-path: /run/secrets/fogwall-github-ghe-oauth-secret

  gitlab:
    enabled: true # gitlab.com
    oauth:
      enabled: true
      client-id: abc123
      client-secret-path: /run/secrets/fogwall-gitlab-oauth-secret

  forgejo:
    enabled: true
    type: forgejo
    uri: https://forgejo.example.internal # self-hosted
    oauth:
      enabled: true
      client-id: abc123
      client-secret-path: /run/secrets/fogwall-forgejo-oauth-secret
```

### SCM OAuth properties

| Property                                    | Type    | Default      | Description                                                                                                      |
| ------------------------------------------- | ------- | ------------ | ---------------------------------------------------------------------------------------------------------------- |
| `identity-mode`                             | string  | `permissive` | `permissive` or `strict` — see above.                                                                            |
| `token-encryption-key-path`                 | string  | _(none)_     | Path to the base64-encoded 32-byte AES-256-GCM key file. Auto-generated under `./.data/` for local dev if unset. |
| `providers.<name>.oauth.enabled`            | boolean | `false`      | Whether "Link via OAuth" is offered for this provider.                                                           |
| `providers.<name>.oauth.client-id`          | string  | `""`         | OAuth app/client ID.                                                                                             |
| `providers.<name>.oauth.client-secret-path` | string  | `""`         | Path to a file holding the OAuth app/client secret.                                                              |

**Registering a GitHub App:** account permissions needed are exactly **Email addresses (read-only)** and **Git SSH keys
(read-only)** — no others, and no private key (a GitHub App's private key is for app/installation-level auth, which this
user-to-server linking flow never uses). Callback URL:
`https://<server.service-url>/api/scm-oauth/<provider-name>/callback`, where `<provider-name>` is the top-level
`providers:` key (e.g. `github`), not the provider type.

**Registering a Forgejo/Gitea OAuth application:** self-service OAuth2 application registration under the instance's own
Settings → Applications page (works the same way on Codeberg, self-hosted Forgejo/Gitea, and org-owned applications),
requesting the `read:user` scope. Callback URL is the same shape as above.

## Proposals

_Available since v1.4.0, opt-in per provider — see [docs/internals/SCM_API_PROXY.md](internals/SCM_API_PROXY.md)._

Extends fogwall past `git push` into the rest of the contribution lifecycle: proxying `gh`'s issue/PR
create-edit-comment-review traffic and `glab`'s issue/MR equivalent, reusing fogwall's existing identity resolution,
permission engine, and audit trail. See [docs/ADMIN_GUIDE.md](ADMIN_GUIDE.md#proposals) for the operational model
(BYO-token, egress assumption) and [docs/USER_GUIDE.md](USER_GUIDE.md#proposals-prmrs-through-fogwall) for how a
developer points `gh`/`glab` at it.

The providers use different wire formats under the hood — GitHub's is GraphQL with an opaque node ID that must be
resolved to `owner/repo`; GitLab's and Forgejo's are REST with the repository addressed directly in the URL, so no
resolution step exists for them (`node-id-cache-ttl` below is a GitHub-only setting, inert for the others). See
[docs/internals/SCM_API_PROXY.md](internals/SCM_API_PROXY.md) for the per-provider wire-format detail. Every dialect
shares the same opt-in config shape — a global settings block, and a per-provider `enabled` flag that actually mounts
the path:

```yaml
proposals:
  # TTL for GitHub's GraphQL node-ID -> owner/repo resolution cache (ISO-8601 duration). A SECURITY parameter, not
  # just a perf knob: a GraphQL node ID can outlive a repo rename/transfer while what it resolves to changes
  # underneath it. Conservative default; shorten further for a deployment where repo renames/transfers are unusually
  # frequent. Has no effect on GitLab, which addresses its target directly in the URL.
  node-id-cache-ttl: PT5M

providers:
  github:
    enabled: true
    proposals:
      enabled: true # per-provider opt-in (default false)
      port: 9443 # required when enabled — a dedicated listener, see below
  gitlab:
    enabled: true
    proposals:
      enabled: true
      port: 9444
  gitea:
    enabled: true
    proposals:
      enabled: true
      port: 9445
      require-known-cli: true # optional hardening; default false
```

**Each enabled provider needs its own `port`**, and fogwall fails to start if one is enabled without it. The dialect is
mounted at the root of that listener (`/api/graphql`, `/api/v4/*`, `/api/v1/*`) because that is the only place the CLIs
can reach it: `gh` and `fj` address the API from the host root and silently discard any path prefix. A single shared
listener isn't an option either — every GitLab claims `/api/v4` and every Gitea/Forgejo `/api/v1`, so two instances of
the same platform would collide. Clients are then pointed at a plain host and port (`GH_HOST`, `GITLAB_HOST`,
`tea login add --url`, `fj -H`). See [docs/internals/SCM_API_PROXY.md](internals/SCM_API_PROXY.md) for the per-CLI
evidence.

**TLS is inherited from [`server.tls`](#tls); there is no per-provider TLS block.** When `server.tls` is set, every
proposals listener serves HTTPS with the same certificate, on its own port. The CLIs only ever address a custom host
over HTTPS, so TLS has to terminate either at fogwall this way or at an ingress in front of it — with `server.tls`
unset, fogwall logs a warning naming each plaintext listener. See
[ADMIN_GUIDE.md — TLS on the proposals listeners](ADMIN_GUIDE.md#tls-on-the-proposals-listeners).

`require-known-cli` refuses callers whose `User-Agent` isn't one of the four recognised SCM CLIs — browsers, bare
`curl`, unrecognised automation. It is **hardening, not a security boundary**: `User-Agent` is caller-controlled, so the
setting can only deny a request that would otherwise be allowed, never permit one the allowlist and permission engine
would refuse. It defaults off because a CLI release that changes its `User-Agent` format would otherwise start failing
for reasons unrelated to policy. The raw header is recorded on every audit record either way, since each CLI advertises
its version there.

Per-repo authorization for **mutations** (issue/PR create, edit, comment, review) goes through the existing
`RepoPermission` grants — see [Permissions](#permissions) below — with a dedicated `PROPOSE` grant kept independent from
`PUSH`/`REVIEW`, so an operator can permission git-push and SCM API mutations separately:

```yaml
permissions:
  - username: alice
    provider: github
    match:
      value: /acme/widgets
    grant: PROPOSE
```

**Reads** (GraphQL `query` traffic — an ordinary `gh issue list`, for example) are not gated by `PROPOSE`. They are
forwarded for any authenticated caller, which keeps the default read cost near pass-through — no allowlist, no node-ID
resolution, no extra round-trip.

### Proposal properties

| Property                                       | Type    | Default | Description                                                                               |
| ---------------------------------------------- | ------- | ------- | ----------------------------------------------------------------------------------------- |
| `proposals.node-id-cache-ttl`                  | string  | `PT5M`  | ISO-8601 duration. See the security note above.                                           |
| `providers.<name>.proposals.enabled`           | boolean | `false` | Whether the SCM API proxy is mounted for this provider.                                   |
| `providers.<name>.proposals.port`              | int     | —       | Dedicated listener port. **Required** when `enabled`; startup fails without it.           |
| `providers.<name>.proposals.require-known-cli` | boolean | `false` | Refuse callers whose `User-Agent` isn't a recognised SCM CLI. Subtractive hardening only. |

### Token model

The CLI carries a personal access token the user supplies. fogwall forwards it upstream unchanged after inspecting the
request, and never mints or supplies a credential for this path.

[SCM OAuth](#scm-oauth) is a separate mechanism, for fogwall-managed operations rather than external tooling. It does
not provide a token for a CLI. See [docs/ADMIN_GUIDE.md](ADMIN_GUIDE.md#proposals) for the egress assumption this relies
on.

## SSH transport

_Available since v1.3.0._

An alternative to the HTTP push path — see [docs/ADMIN_GUIDE.md](ADMIN_GUIDE.md#ssh-transport) for how identity
verification and agent forwarding work. This section covers the listener config itself.

```yaml
server:
  ssh:
    enabled: false # off by default
    port: 2222 # non-standard on purpose - see note below
    host-key-path: .ssh/fogwall_host_key # generated on first start if absent
```

| Property        | Type    | Default                 | Description                                                                   |
| --------------- | ------- | ----------------------- | ----------------------------------------------------------------------------- |
| `enabled`       | boolean | `false`                 | Whether the SSH listener starts                                               |
| `port`          | int     | `2222`                  | TCP port the SSH server binds                                                 |
| `host-key-path` | string  | `.ssh/fogwall_host_key` | Path to the SSH host key file (absolute or relative to the working directory) |

<!-- prettier-ignore-start -->
> [!NOTE]
> **Why port 2222, and why `git@host:path` shorthand doesn't work out of the box:** the default avoids clashing with a
> real `sshd` that may already be running on the same host, and avoids the container needing root or
> `CAP_NET_BIND_SERVICE` just to bind a port below 1024. The trade-off is that Git's SCP-like shorthand
> (`git@host:owner/repo.git`, the syntax GitHub's `git@github.com:...` uses) has no field for a non-default port — only
> the explicit `ssh://host:port/path` form does. If you want the shorthand to work, put fogwall's SSH port behind a
> plain TCP/L4 passthrough on your load balancer or `Service` (external `:22` → the pod's `:2222`) rather than changing
> what the container itself binds — the same pattern most deployments already use for terminating TLS on 443 in front
> of the container's plaintext 8080. The Helm chart's `sshService.*` values do exactly this.
<!-- prettier-ignore-end -->

### Serving a provider over SSH

_The single-entry model below is available since v1.4.0._

The `server.ssh` block above starts the SSH listener; SSH transport is then turned on **per provider** via an `ssh:`
sub-block on that provider's entry. A single provider entry serves both HTTP and SSH to the same upstream — there is no
need for a separate `github-ssh` entry, and an OAuth-linked identity applies to both transports automatically.

```yaml
providers:
  github:
    enabled: true # github.com over HTTPS
    ssh:
      enabled: true # also serve SSH — the endpoint is derived as ssh://git@github.com

  gitea-internal:
    enabled: true
    type: gitea
    uri: https://gitea.corp.example.com # HTTP/API endpoint
    ssh:
      enabled: true
      uri: ssh://git@gitea.corp.example.com:3022 # explicit endpoint for a non-standard SSH port
      known-hosts:
        - "[gitea.corp.example.com]:3022 ssh-ed25519 AAAA..." # pin this upstream's host key

  # GitHub Enterprise Cloud with data residency uses the enterprise slug as the SSH username, not "git":
  acme-ghe:
    enabled: true
    type: github
    uri: https://acme.ghe.com
    ssh:
      enabled: true
      uri: ssh://acme@acme.ghe.com
```

| Property (under `ssh:`) | Type    | Default     | Description                                                                                                                                                                                          |
| ----------------------- | ------- | ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `enabled`               | boolean | `false`     | Serve this provider over SSH. With no `ssh.uri`, the endpoint is derived as `ssh://git@<host>` from the provider's HTTP `uri` (port 22).                                                             |
| `uri`                   | string  | _(derived)_ | Explicit SSH endpoint. Required when the upstream uses a non-`git` SSH username (e.g. GHEC data residency) or a non-standard port. Must use the `ssh://` scheme.                                     |
| `known-hosts`           | list    | _(none)_    | Inline `known_hosts` lines pinning this upstream's SSH host key(s). Merged with the global `server.ssh.known-hosts-path` / bundled defaults. Entries are host-keyed, so they scope to this upstream. |
| `known-hosts-path`      | string  | _(none)_    | Path to a `known_hosts` file whose lines pin this upstream's host key(s). Read once at startup and merged like `known-hosts`.                                                                        |

<!-- prettier-ignore-start -->
> [!NOTE]
> The `uri` on a provider entry is always the **HTTP/HTTPS** endpoint (it is also the API base used for SSH-key
> identity resolution). A top-level `uri` with an `ssh://` scheme no longer enables SSH — configure SSH through the
> `ssh:` sub-block instead.
<!-- prettier-ignore-end -->

## Commit validation

Per-commit checks (identity, email policy, message content, trailer policy) apply to both server mode and transparent
proxy modes.

```yaml
commit:
  # Committer email policy — the committer is the employee who ran git commit or git rebase.
  # This is the primary corporate control: enforce that your staff use their work identity.
  # Rebased commits from external contributors still pass as long as the committer email is valid.
  # Also applied to the tagger of an annotated tag push: git fills the tagger line from the same
  # user.email as the committer line, so there is no separate tagger policy to configure.
  committer:
    email:
      # Unified allow/block rules — see "Email policy rules" below.
      rules:
        - { action: allow, field: domain, match: regex, value: "corp\\.example\\.com$" }
        - { action: block, field: local, match: regex, value: "^(noreply|no-reply|bot|nobody)$" }

  # Author email policy — the author is whoever originally wrote the commit.
  # Configure this only if you want to disallow rebasing external contributors' commits.
  # When set, any commit whose author email is not permitted is blocked — developers must open
  # PRs from the original fork rather than rebasing upstream changes. Omit to allow external
  # author emails (the most common setup).
  author:
    email:
      rules:
        - { action: allow, field: domain, match: regex, value: "corp\\.example\\.com$" }

  message:
    block:
      literals:
        - "WIP"
        - "DO NOT MERGE"
      patterns:
        - '(?i)(password|secret|token)\s*[=:]\s*\S+'

  # Commit-trailer policy — DCO Signed-off-by and Co-authored-by. Both controls are enforce-or-off
  # and apply to both transports. See "Trailer policy" below.
  trailers:
    signed-off-by:
      require: false # true = every commit must carry a Signed-off-by trailer (DCO)
      require-author-match: false # true = the Signed-off-by email must equal the commit author's
    co-authored-by:
      policy: off # off | ban | allowlist | require
      # Under `allowlist`, each co-author email is checked against these rules (same shape as above):
      email:
        rules:
          - { action: allow, field: domain, match: regex, value: "corp\\.example\\.com$" }
          - { action: allow, field: address, match: literal, value: "noreply@anthropic.com" }
```

### Email policy rules

`author.email`, `committer.email`, and `co-authored-by.email` all share one shape: an ordered list of `rules`. Each rule
is `{ action, field, match, value }`:

| Key      | Values                           | Meaning                                                                                          |
| -------- | -------------------------------- | ------------------------------------------------------------------------------------------------ |
| `action` | `allow` \| `block`               | Whether a match permits or rejects the email.                                                    |
| `field`  | `domain` \| `local` \| `address` | Which part of the address to test (`local` is before `@`, `address` is the full `local@domain`). |
| `match`  | `literal` \| `regex`             | `literal` is case-insensitive exact equality; `regex` uses find-semantics. Default `regex`.      |
| `value`  | string                           | The literal string or regex source.                                                              |

Evaluation: **a block match always rejects**; then, **if any allow rule is present, the email must match at least one**
to pass. With no allow rule, anything not blocked is permitted. This lets you express, for example, "allow `@corp.com`
and the single bot address `noreply@anthropic.com`, but block the `svc-` service accounts":

```yaml
rules:
  - { action: allow, field: domain, match: regex, value: "corp\\.example\\.com$" }
  - { action: allow, field: address, match: literal, value: "noreply@anthropic.com" }
  - { action: block, field: local, match: regex, value: "^svc-" }
```

> **Deprecated aliases.** The older `email.domain.allow` and `email.local.block` single-regex keys are still accepted
> for one minor release and are folded into equivalent `allow domain regex` / `block local regex` rules at startup (a
> deprecation warning is logged). Migrate to the `rules` list — it can express blocks on domains, allows on local-parts,
> full-address matching, and literals, none of which the old keys could.

### Trailer policy

Two independent, enforce-or-off controls over commit-message trailers (both apply to server mode and transparent proxy):

- **`signed-off-by.require`** — reject any commit lacking a `Signed-off-by:` trailer (Developer Certificate of Origin).
  With **`require-author-match: true`**, at least one `Signed-off-by` email must equal the commit's own author email
  (the DCO "sign off your own work" rule).
- **`co-authored-by.policy`** — one of:
  - `off` (default) — no restriction.
  - `ban` — reject any commit that carries a `Co-authored-by:` trailer (e.g. legal teams requiring a single attributable
    author per commit).
  - `require` — reject any commit that has no `Co-authored-by:` trailer.
  - `allowlist` — reject a `Co-authored-by` whose email is not permitted by `co-authored-by.email.rules` (the same
    email-policy shape above), e.g. to permit only approved internal bots.

Captured `Signed-off-by` and `Co-authored-by` trailers are also stored on the push record and shown per-commit in the
dashboard push detail, independent of any policy.

## Diff scan

Push-level check applied once per push against the aggregate diff (all commits combined). Only added lines (`+`) are
scanned — deletions and context lines are ignored.

```yaml
diff-scan:
  block:
    literals:
      - "internal.corp.example.com"
    patterns:
      - '(?i)https?://[a-z0-9.-]*\.corp\.example\.com\b'
```

## Secret scanning

Secret scanning via gitleaks (<https://github.com/gitleaks/gitleaks>). Applied once per push. The JAR ships with a
bundled gitleaks binary so scanning works out of the box.

Binary resolution order (first match wins):

1. `scanner-path` — explicit path, bypasses everything else
2. `version` + `auto-install: true` — downloads and caches that version on startup
3. Bundled JAR binary (default version, always present)
4. System `PATH`

```yaml
secret-scan:
  enabled: false
  # version: 8.22.0
  # auto-install: true
  # install-dir: ~/.cache/fogwall/gitleaks
  # scanner-path: /usr/local/bin/gitleaks

  # External TOML rules file. Ignored when inline-config is set.
  # config-file: /app/conf/.gitleaks.toml

  # Inline TOML config — takes precedence over config-file. Hot-reloadable via
  # POST /api/config/reload?section=secret-scan. Content must be valid gitleaks TOML:
  # inline-config: |
  #   title = "my-org"
  #   [extend]
  #   useDefault = true
  #   [[rules]]
  #   id = "my-org-api-key"
  #   regex = '''MY_ORG_[A-Z0-9]{32}'''

  # timeout-seconds: 30
```

## Binary blob detection

_Available since v1.3.0._

Push-level check applied once per push against the aggregate diff (all commits combined) — same scope as
[diff scan](#diff-scan). Flags added/modified blobs that exceed a size threshold or match a denied MIME type.
ENFORCE-only: a match always blocks the push (there is no advisory/WARN mode yet).

MIME type classification sniffs the first few bytes of each blob's content against a built-in table of magic-byte
signatures (the same technique tools like `file`/libmagic use, at a much smaller scale) — file extensions are never
consulted, since they're trivially renamed and unreliable across operating systems. Only a small, bounded header is read
per blob; blob content is never fully loaded. Note that ZIP-based Office formats (`.docx`/`.xlsx`/`.pptx`) share the
same container signature as plain `.zip`/`.jar` archives and cannot be distinguished from magic bytes alone — all are
classified as `application/zip`.

```yaml
binary-blob:
  enabled: true # on by default — the size threshold alone is a useful safety net out of the box
  max-size-bytes: 52428800 # 50MiB; 0 = no size limit
  deny-mime-types: # PDF and ZIP-family denied by default; further types are a policy choice
    - application/pdf
    - application/zip
    # - application/x-executable
    # - application/x-msdownload
```

### Detectable MIME types

`deny-mime-types` entries must match one of the values below exactly — these are the only content types the built-in
magic-byte signature table can identify. Anything not in this list is never denied by MIME type (though it may still be
denied by `max-size-bytes`).

| MIME type                         | Matches                                                                                                                        |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `application/pdf`                 | PDF documents                                                                                                                  |
| `application/zip`                 | ZIP archives, JARs, and OOXML Office docs (`.docx`/`.xlsx`/`.pptx` — indistinguishable from plain zip at the magic-byte level) |
| `application/gzip`                | gzip-compressed files (`.gz`, `.tar.gz`)                                                                                       |
| `application/x-7z-compressed`     | 7-Zip archives                                                                                                                 |
| `application/vnd.rar`             | RAR archives                                                                                                                   |
| `application/x-executable`        | ELF binaries (Linux executables/shared objects)                                                                                |
| `application/x-msdownload`        | Windows PE binaries (`.exe`, `.dll`)                                                                                           |
| `application/vnd.sqlite3`         | SQLite database files                                                                                                          |
| `application/java-vm`             | Compiled Java class files                                                                                                      |
| `application/wasm`                | WebAssembly binaries                                                                                                           |
| `application/zstd`                | Zstandard-compressed files                                                                                                     |
| `application/x-xz`                | XZ-compressed files                                                                                                            |
| `application/x-bzip2`             | Bzip2-compressed files                                                                                                         |
| `application/vnd.apache.parquet`  | Apache Parquet columnar data files                                                                                             |
| `application/x-java-keystore`     | Java Keystore (`.jks`)                                                                                                         |
| `application/x-java-jce-keystore` | Java Cryptography Extension Keystore (`.jceks`)                                                                                |
| `application/x-qemu-disk`         | QEMU/QCOW2 virtual disk images                                                                                                 |
| `application/x-hdf5`              | HDF5 data files (e.g. Keras model checkpoints)                                                                                 |

## Content-pattern scanning

_Available since v1.3.0._

Push-level check applied against both the aggregate diff and every pushed commit's message. Flags structured identifier
content (national ID numbers, IBANs, credit card numbers, crypto wallet addresses, etc.) using fogwall's built-in
pattern bundles — distinct from [secret scanning](#secret-scanning), which targets credential-shaped content (API keys,
tokens). Every bundle here requires a structural checksum or Presidio-derived regex match, not free-text PII detection
(names, addresses, emails) — that class needs NLP/NER to keep an acceptable false-positive rate, which is out of scope;
see [Design notes](#design-notes) below.

**WARN-only on the push path** — a match is recorded as a `WARN` step for the reviewer to see, it never blocks the push.
These patterns have a real false-positive rate (a bare regex match on a short numeric identifier can't be fully
disambiguated from unrelated numbers), and there's currently no override path for a wrongly-blocked push. Since every
push already requires a human reviewer to look at it, WARN gets the finding in front of them without the downside of
blocking on a false positive.

**Blocking on the proposal path** — that reasoning depends on the reviewer, and a proposal has none: it is forwarded to
the upstream or refused, with no held state to annotate. A warning recorded against a merge request description that is
already published is not a control, so a match there refuses the proposal (`REJECTED`) the same way a blocked term or a
detected secret does. See [ADMIN_GUIDE.md — Content inspection](ADMIN_GUIDE.md#content-inspection).

Bundle content (regexes, context keywords, structural validators like Luhn/IBAN/Base58Check checksums) is hand-ported
from [data-privacy-stack/presidio](https://github.com/data-privacy-stack/presidio) (MIT licensed) where noted below —
see `PROVENANCE.md` alongside the bundle resources in the fogwall source tree for exact provenance, including the small
number of validators (US bank routing, Bitcoin SegWit/Taproot, Ethereum) that aren't from Presidio. fogwall does not run
Presidio itself; only the matching logic is translated to Java.

```yaml
content-patterns:
  enabled: true
  bundles:
    - national-id-all-geos # every national-id-<cc> bundle below
    - generic-iban
    - generic-crypto-wallet
    # generic-credit-card and generic-us-bank-routing use a Luhn-strength (mod-10) checksum - noisier than the
    # above, so they're opt-in only; add them individually, or use the generic-all alias for all four generic
    # bundles at once.
  scan-diff: true # set false to skip scanning the push diff
  scan-commit-messages: true # set false to skip scanning commit messages
  scan-proposals: true # set false to skip scanning proposal titles, descriptions and comments
```

`scan-diff`/`scan-commit-messages`/`scan-proposals` independently gate the three content sources - all default `true`,
so selecting a bundle covers every surface it can appear on. An operator who considers commit messages low-risk (or
wants to reduce push-summary noise) can disable that half without affecting diff scanning, and vice versa. This is
distinct from disabling a bundle: the bundle selection still applies to whichever source(s) remain enabled.

### Available bundles

Two tiers: `national-id-<cc>` bundles (ISO 3166-1 alpha-2 country codes) each cover a single country's
national-identity-registry number — not that country's full PII catalog. `generic-<type>` bundles cover a data type that
isn't tied to a jurisdiction. Two group aliases are always available: `national-id-all-geos` (every `national-id-*`
bundle) and `generic-all` (every `generic-*` bundle).

#### National ID bundles

| Bundle           | Jurisdiction   | Detects                                                                                  |
| ---------------- | -------------- | ---------------------------------------------------------------------------------------- |
| `national-id-ca` | Canada         | Social Insurance Number (SIN)                                                            |
| `national-id-us` | United States  | Social Security Number (SSN)                                                             |
| `national-id-gb` | United Kingdom | National Insurance Number (NINO); NHS Number                                             |
| `national-id-au` | Australia      | Tax File Number (TFN); Australian Business Number (ABN); Australian Company Number (ACN) |
| `national-id-de` | Germany        | Rentenversicherungsnummer                                                                |
| `national-id-in` | India          | Aadhaar Number                                                                           |
| `national-id-sg` | Singapore      | NRIC/FIN Number                                                                          |
| `national-id-za` | South Africa   | South African ID Number                                                                  |
| `national-id-es` | Spain          | NIF Number                                                                               |
| `national-id-se` | Sweden         | Personnummer                                                                             |
| `national-id-tr` | Turkey         | National ID Number (TC Kimlik No)                                                        |
| `national-id-fi` | Finland        | Personal Identity Code (Henkilötunnus)                                                   |
| `national-id-it` | Italy          | Fiscal Code (Codice Fiscale)                                                             |
| `national-id-kr` | South Korea    | Resident Registration Number                                                             |
| `national-id-ng` | Nigeria        | National Identification Number                                                           |
| `national-id-ph` | Philippines    | UMID Number                                                                              |
| `national-id-th` | Thailand       | National ID Number                                                                       |

#### Generic bundles

| Bundle                    | Enabled by default | Detects                                                                                               | Checksum strength                    |
| ------------------------- | :----------------: | ----------------------------------------------------------------------------------------------------- | ------------------------------------ |
| `generic-iban`            |        Yes         | IBAN                                                                                                  | ISO 7064 mod-97-10 (strong)          |
| `generic-crypto-wallet`   |        Yes         | Bitcoin (legacy Base58Check, SegWit/Taproot Bech32); Ethereum (EIP-55, checksum-cased addresses only) | SHA-256d / BCH / Keccak-256 (strong) |
| `generic-credit-card`     |    No — opt in     | Credit card number                                                                                    | Luhn (mod-10, weak)                  |
| `generic-us-bank-routing` |    No — opt in     | US bank routing number (ABA)                                                                          | ABA weighted mod-10 (weak)           |

### Design notes

Presidio's recognizer catalog goes well beyond what fogwall ports: unstructured categories like names, physical
addresses, phone numbers, and email addresses rely on Presidio's NLP/NER pipeline to keep an acceptable false-positive
rate, not a checksum. fogwall has no NLP pipeline (that's the dependency this feature deliberately avoided — see
`PROVENANCE.md`), so only checksum-or-strict-structural-regex data types are in scope. If a data type doesn't have a
real checksum, it isn't a good fit for this WARN-only, regex-based mechanism.

## Hot reload

Selected config sections can be reloaded at runtime without restarting the server. Two reload sources are supported:

- **File watch** — monitors a local YAML file; triggers automatically on modification
- **Git source** — periodically pulls a git repository and reads a YAML overlay from it

```yaml
reload:
  file:
    enabled: false
    path: /app/conf/fogwall-local.yml # watched for modifications
  git:
    enabled: false
    url: https://github.com/myorg/config.git
    branch: main
    file-path: fogwall.yml
    interval-seconds: 300 # 0 = manual trigger only
```

#### Git source authentication

For private repositories, set these environment variables — no config file changes needed:

```
FOGWALL_RELOAD_GIT_AUTH_USERNAME=<username or token placeholder>
FOGWALL_RELOAD_GIT_AUTH_PASSWORD=<personal access token or password>
```

Both variables must be set together; if only one is present a warning is logged and the clone/pull proceeds without
credentials. For token-only auth (GitHub, GitLab, Gitea PATs) the username can be any non-empty string — `git` or
`x-token` are common placeholders.

### Reloadable sections

| Section        | YAML key        | What changes take effect                                                |
| -------------- | --------------- | ----------------------------------------------------------------------- |
| `commit`       | `commit:`       | Author email rules, message block lists, commit attribution policy mode |
| `diff-scan`    | `diff-scan:`    | Diff content block literals and patterns                                |
| `secret-scan`  | `secret-scan:`  | All gitleaks settings including `inline-config`                         |
| `binary-blob`  | `binary-blob:`  | Blob size limit and denied MIME types                                   |
| `rules`        | `rules:`        | URL access control allow/deny rules                                     |
| `permissions`  | `permissions:`  | Config-sourced user→repo permission grants                              |
| `attestations` | `attestations:` | Dashboard approval form questions                                       |

Provider, server, and database sections always require a restart.

### Manual trigger

```
POST /api/config/reload                         # reload all sections
POST /api/config/reload?section=commit          # commit rules only
POST /api/config/reload?section=diff-scan       # diff scan only
POST /api/config/reload?section=secret-scan     # gitleaks config only
POST /api/config/reload?section=binary-blob     # binary blob detection only
POST /api/config/reload?section=rules           # URL rules only
POST /api/config/reload?section=permissions     # permissions only
POST /api/config/reload?section=attestations    # attestation questions only
```

The dashboard admin panel also provides a section dropdown for manual triggers.

## Commit attribution policy

> Formerly `commit.identity-verification`. The old key still binds (with a deprecation warning) but will be removed in a
> future release — rename it to `commit.attribution-policy`.

```yaml
commit:
  attribution-policy:
    committer: warn # warn | strict | off (default: warn)
    author: off # warn | strict | off (default: off)
```

For every push, the proxy runs two checks:

1. **SCM login check** — calls the upstream provider's user API with the token supplied in the git credentials (the HTTP
   Basic-auth password). The returned login (e.g. GitHub `login`, GitLab `username`) is matched against the
   authenticated fogwall user's `scm-identities`. This check is **always enforced** regardless of the
   `attribution-policy` mode — a push from a token that cannot be matched to a registered proxy user is always blocked.

2. **Commit email check** — every author and committer email in the pushed commits is checked against the authenticated
   fogwall user's `emails` list. These emails are populated independently of the SCM: they come from the IdP on
   LDAP/OIDC login, or from additional associations added via the dashboard. This is what ties commit attribution back
   to a verified real person. The `attribution-policy` mode controls this check only.

<!-- prettier-ignore-start -->
> [!NOTE]
> The HTTP Basic-auth username in the remote URL is not used for identity resolution. It is ignored by all providers (except Bitbucket). Configure your remote URL with any username — `git`, `me`, your actual name — it makes no difference.
<!-- prettier-ignore-end -->

### Modes

`attribution-policy` controls the **commit email check** only, independently for `committer` and `author`. The SCM login
check is always enforced.

| Mode     | Behaviour                                                                                       | Use when                                                                       |
| -------- | ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `strict` | Blocks the push if any commit email cannot be matched to the authenticated fogwall user         | Production — enforces that every commit is attributed to the person who pushed |
| `warn`   | Allows the push through but emits a sideband warning to the git client and records the mismatch | Rolling out to an existing team — lets you observe mismatches before enforcing |
| `off`    | Commit email check is disabled entirely                                                         | Migrations or environments where email data is not yet populated               |

<!-- prettier-ignore-start -->
> [!CAUTION]
> `warn` is not a security control. Pushes succeed regardless of the email check outcome. Only `strict` blocks mismatched commits. The default is `warn` to avoid breaking existing deployments on first install.
>
> **Committer vs author:** the two are checked independently. `committer` defaults to `warn` — the committer is who last touched the commit object, i.e. the pusher on their own work. `author` defaults to `off` because rebased or cherry-picked commits legitimately preserve a different original author, so blocking on it would reject valid workflows. Enable `author: strict` only on closed boundaries (private-to-private, M&A integration) where every commit must be authored by the pusher; leave it `off` for open-source contribution flows.
<!-- prettier-ignore-end -->

### Token scope requirements

The SCM login check calls `GET /user` (or equivalent) on the upstream SCM using the pusher's token. The token must carry
at least the following scope:

| Provider | API endpoint                           | Additional scope                                                       |
| -------- | -------------------------------------- | ---------------------------------------------------------------------- |
| GitHub   | `GET https://api.github.com/user`      | No additional scopes required for either classic or fine-grained PATs. |
| GitLab   | `GET {uri}/api/v4/user`                | `read_user` or `api` (not recommended)                                 |
| Codeberg | `GET https://codeberg.org/api/v1/user` | `read:user`                                                            |
| Gitea    | `GET https://gitea.com/api/v1/user`    | `read:user`                                                            |

If the token is missing the required scope or cannot be resolved to a registered proxy user, the push is blocked
regardless of `attribution-policy` mode.

### Prerequisites

Both checks require the user record to be populated before a push. A push from a token that cannot be matched to any
registered proxy user is always blocked. Use `attribution-policy` with `committer: warn` during rollout to allow pushes
through while users register their commit emails; the SCM identity must be registered before any push can proceed.

```yaml
users:
  - username: alice
    password-hash: "{bcrypt}$2a$12$..."
    roles:
      - ADMIN # optional; defaults to [USER] if omitted
    emails:
      - alice@example.com
    # push-usernames: HTTP Basic-auth usernames accepted for this user when pushing.
    # The proxy username is always implicitly valid; these are additional aliases.
    # Useful when git clients send a fixed username (e.g. "git") that differs from
    # the proxy username. Stored internally as SCM identities under the "proxy" provider.
    push-usernames:
      - git
      - alice-bot
    scm-identities:
      - provider: github
        username: alice-gh
      - provider: gitlab
        username: alice
```

## URL rules

URL rules control which repositories are accessible through the proxy. fogwall is **default-deny**: if no allow rules
are configured for a provider, all pushes and fetches to that provider are rejected. At least one allow rule must match
for a request to proceed.

Rules use a unified `match` block that specifies what to match against (`target`), the pattern string (`value`), and how
to interpret it (`type`). Evaluation is first-match-wins by `order` — identical to iptables/firewall rule semantics.

`order` is optional for YAML-configured rules. When omitted, it's inferred from the entry's position within its
`allow[]`/`deny[]` array — the first entry gets `0`, the second `100`, and so on, leaving gaps for later insertion. An
explicit `order` always takes precedence over the inferred position. Rules created via the dashboard or REST API have no
array position to infer from, so they use a fixed default (`100`) unless an explicit `order` is set.

```yaml
rules:
  allow:
    # Specific repo by exact slug — both operations, scoped to one provider
    - enabled: true
      order: 110
      operation: BOTH
      provider: github
      match:
        target: SLUG
        value: /RBC/fogwall
        type: LITERAL

    # All repos under an owner — fetch only, any provider
    - enabled: true
      order: 120
      operation: FETCH
      match:
        target: OWNER
        value: finos
        type: GLOB

  deny:
    # Block a specific repo across all operations
    - enabled: true
      order: 100
      match:
        target: SLUG
        value: /myorg/forbidden-repo
        type: LITERAL
```

To allow all repositories on a provider (open mode):

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operation: BOTH
      provider: internal-github
      match:
        target: OWNER
        value: "*"
        type: GLOB
```

### URL rule properties

| Property       | Type    | Default      | Description                                                                |
| -------------- | ------- | ------------ | -------------------------------------------------------------------------- |
| `enabled`      | boolean | `true`       | Whether this entry is active                                               |
| `order`        | int     | _(position)_ | Evaluation order (lower = earlier; first match wins). Optional — see below |
| `operation`    | string  | `BOTH`       | `FETCH`, `PUSH`, or `BOTH` — which git operation this entry matches        |
| `provider`     | string  | _(all)_      | Provider name to scope this entry to; omit or leave blank for all          |
| `match`        | object  | —            | Repository match criteria — see below                                      |
| `match.target` | enum    | `SLUG`       | What to match: `SLUG` (`/owner/repo`), `OWNER`, or `NAME`                  |
| `match.value`  | string  | —            | The pattern to match against the chosen target                             |
| `match.type`   | enum    | `GLOB`       | How to interpret the pattern: `LITERAL`, `GLOB`, or `REGEX`                |

### Pattern matching

Each rule matches exactly one thing — the `match` block selects what URL part to test (`target`) and how to test it
(`type`). To express an AND condition (e.g. specific owner AND specific name prefix), use `target: SLUG` and write a
single glob or regex that covers both parts.

#### LITERAL

Exact string match after normalising a leading `/`. `/acme/repo` and `acme/repo` are equivalent.

#### GLOB

Wildcard matching using `*` (any characters) and `?` (single character).

| Target  | `*` behaviour                                               |
| ------- | ----------------------------------------------------------- |
| `SLUG`  | Does **not** cross `/` — use `acme/*` not `acme/**`         |
| `OWNER` | Owner names cannot contain `/` — `*` matches any valid name |
| `NAME`  | Repo names cannot contain `/` — `*` matches any valid name  |

| Pattern (GLOB, target=SLUG) | Matches                                     | Does NOT match  |
| --------------------------- | ------------------------------------------- | --------------- |
| `/acme/repo` _(LITERAL)_    | `/acme/repo`                                | `/acme/other`   |
| `/acme/*`                   | `/acme/repo`, `/acme/my-service`            | `/other/repo`   |
| `/acme/service-*`           | `/acme/service-api`, `/acme/service-worker` | `/acme/repo`    |
| `/acme/repo-?`              | `/acme/repo-1`, `/acme/repo-a`              | `/acme/repo-12` |

#### REGEX

Full Java regular expression. A few things to know before writing regex rules:

- **Full-string match**: the pattern must match the entire candidate string — there are no implicit anchors, but
  `matches()` semantics apply. A pattern of `acme` does **not** match `/acme/repo`; write `/acme/.*` or `.*acme.*`.
- **`/` does not need escaping**: Java regex uses strings, not a `/pattern/` literal syntax. Write `/acme/.*` not
  `\/acme\/.*`.
- **Case-insensitive**: use the `(?i)` inline flag — e.g. `(?i)/acme/.*`.
- **Anchoring**: explicit `^` and `$` are redundant with `matches()` but harmless if included.

| Pattern (REGEX, target=SLUG) | Matches                               | Does NOT match      |
| ---------------------------- | ------------------------------------- | ------------------- |
| `/acme/.*`                   | `/acme/repo`, `/acme/my-service`      | `/other/repo`       |
| `/(acme\|partner)/.*`        | `/acme/repo`, `/partner/repo`         | `/other/repo`       |
| `/acme/service-[0-9]+`       | `/acme/service-1`, `/acme/service-42` | `/acme/service-api` |
| `(?i)/acme/.*`               | `/acme/repo`, `/ACME/Repo`            | `/other/repo`       |

| Pattern (REGEX, target=NAME)       | Matches                      | Does NOT match |
| ---------------------------------- | ---------------------------- | -------------- |
| `(?i)(^&#124;-)secret(-&#124;$).*` | `secret-config`, `my-secret` | `secretariat`  |
| `migrate-.*`                       | `migrate-app`, `migrate-db`  | `old-migrate`  |

```yaml
rules:
  deny:
    # Block any repo whose name contains "secret" as a distinct word segment (case-insensitive)
    - enabled: true
      order: 50
      operation: PUSH
      match:
        target: NAME
        value: "(?i)(^|-)secret(-|$).*"
        type: REGEX

    # Block repos matching multiple owner orgs using alternation
    - enabled: true
      order: 51
      operation: BOTH
      match:
        target: OWNER
        value: "(blocked-org|suspended-org)"
        type: REGEX
```

### Real-world URL rule examples

**Gateway for a specific SCM — allow all push and fetch:**

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operation: BOTH
      provider: internal-github
      match:
        target: OWNER
        value: "*"
        type: GLOB
```

**Allow repos under a set of known owner orgs, identified by a name prefix:**

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operation: BOTH
      provider: internal-github
      match:
        target: OWNER
        value: "team-(alpha|beta|gamma)"
        type: REGEX
```

**Allow push only for repos whose name starts with a known prefix:**

When repos are identified by a project code followed by a hyphen, match on `NAME` so the rule applies regardless of
which org the repo lives under.

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operation: PUSH
      provider: internal-github
      match:
        target: NAME
        value: "proj0-*"
        type: GLOB

  # Multiple project prefixes — one rule per prefix, or combine with REGEX alternation:
  allow:
    - enabled: true
      order: 111
      operation: PUSH
      provider: internal-github
      match:
        target: NAME
        value: "(proj0|proj1|shared)-.*"
        type: REGEX
```

**Combine owner and name matching (AND condition):**

Use `target: SLUG` with a glob or regex — the slug is `/owner/repo` so a single pattern can constrain both parts.

```yaml
rules:
  allow:
    # Allow fetch from the source SCM for a specific org + name prefix (glob AND)
    - enabled: true
      order: 110
      operation: FETCH
      provider: source-github
      match:
        target: SLUG
        value: "acquired-org/migrate-*"
        type: GLOB

    # Allow push to the destination SCM — stricter control with regex
    - enabled: true
      order: 120
      operation: PUSH
      provider: dest-gitlab
      match:
        target: SLUG
        value: "/migrated-org/migrate-.*"
        type: REGEX
```

## Permissions

Permissions control which proxy users can push to or review pushes from specific repositories. They are checked
**after** URL rules: a push that is blocked by a deny rule never reaches the permission check.

Permissions are hot-reloadable (see [Reloadable sections](#reloadable-sections)).

```yaml
permissions:
  # LITERAL (default): exact /owner/repo match
  - username: alice
    provider: github
    match:
      target: SLUG
      value: /myorg/myrepo
      type: LITERAL
    grant: PUSH

  # GLOB: wildcard repo name under a specific owner
  - username: bob
    provider: gitlab
    match:
      target: SLUG
      value: /myorg/*
      type: GLOB
    grant: PUSH_AND_REVIEW

  # OWNER target: grant access to all repos under an org
  - username: carol
    provider: github
    match:
      target: OWNER
      value: myorg
      type: GLOB
    grant: REVIEW

  # REGEX on SLUG: match repos under multiple orgs
  - username: dave
    provider: github
    match:
      target: SLUG
      value: "/team-(alpha|beta)/.*"
      type: REGEX
    grant: PUSH_AND_REVIEW

  # SELF_CERTIFY: trusted contributor who can approve their own clean pushes.
  # Requires both this permission entry AND the SELF_CERTIFY role on the user.
  - username: trusted
    provider: github
    match:
      target: SLUG
      value: /myorg/myrepo
      type: LITERAL
    grant: SELF_CERTIFY
```

### Permission properties

| Property       | Type   | Default           | Description                                                                                                                                                            |
| -------------- | ------ | ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `username`     | string | —                 | Proxy username (must match a `users:` entry or a DB user)                                                                                                              |
| `provider`     | string | —                 | Provider name as defined in `providers:` config                                                                                                                        |
| `match`        | object | —                 | Repository match criteria — see below                                                                                                                                  |
| `match.target` | enum   | `SLUG`            | What to match: `SLUG` (`/owner/repo`), `OWNER`, or `NAME`                                                                                                              |
| `match.value`  | string | —                 | The pattern to match against the chosen target                                                                                                                         |
| `match.type`   | enum   | `GLOB`            | How to interpret the pattern: `LITERAL`, `GLOB`, or `REGEX`                                                                                                            |
| `grant`        | enum   | `PUSH_AND_REVIEW` | What the user may do: `PUSH`, `REVIEW`, `PUSH_AND_REVIEW`, `SELF_CERTIFY`, `PROPOSE` (v1.4.0+, [SCM API proxy](#proposals) mutations — independent of `PUSH`/`REVIEW`) |

### Pattern matching

Permissions support the same three match types as URL rules (LITERAL, GLOB, REGEX) applied to the same three targets
(SLUG, OWNER, NAME). See [Pattern matching](#pattern-matching) above for full semantics including regex behaviour.

GLOB on `target: SLUG` follows slug path conventions:

| Pattern (GLOB, target=SLUG) | Matches                                     | Does NOT match |
| --------------------------- | ------------------------------------------- | -------------- |
| `/acme/repo`                | `/acme/repo`                                | `/acme/other`  |
| `/acme/*`                   | `/acme/repo`, `/acme/my-service`            | `/other/repo`  |
| `/acme/service-*`           | `/acme/service-api`, `/acme/service-worker` | `/acme/repo`   |
| `/*/proj0-*`                | `/acme/proj0-api`, `/other/proj0-db`        | `/acme/other`  |

<!-- prettier-ignore-start -->
> [!NOTE]
> **Conflict detection:** At config load time and when saving via the dashboard API, fogwall rejects any new permission entry whose pattern overlaps with an existing entry for the same user and provider. Two entries overlap when they are equal, or when one is a GLOB/REGEX pattern that would match the other's value. This prevents silent misconfiguration where the effective permission depends on evaluation order.
<!-- prettier-ignore-end -->

### Real-world permission examples

**Allow a user to push any repo on a specific provider:**

```yaml
permissions:
  - username: alice
    provider: internal-github
    match:
      target: OWNER
      value: "*"
      type: GLOB
    grant: PUSH_AND_REVIEW
```

**Allow push to repos whose name starts with a project code:**

```yaml
permissions:
  - username: alice
    provider: internal-github
    match:
      target: NAME
      value: "proj0-*"
      type: GLOB
    grant: PUSH

  # Or match the same prefix across any owner using SLUG:
  - username: alice
    provider: internal-github
    match:
      target: SLUG
      value: "/*/proj0-*"
      type: GLOB
    grant: PUSH
```

**Regex — match repos under multiple owner orgs:**

```yaml
permissions:
  - username: alice
    provider: internal-github
    match:
      target: SLUG
      value: "/team-(alpha|beta)/.*"
      type: REGEX
    grant: PUSH_AND_REVIEW
```

**Self-certify for a trusted committer scoped to a prefix:**

A trusted committer needs both entries: `PUSH_AND_REVIEW` to be able to push, and `SELF_CERTIFY` to bypass the peer
review requirement. These cover separate code paths and are not treated as conflicting.

```yaml
permissions:
  # Push and review access
  - username: trusted-dev
    provider: internal-github
    match:
      target: NAME
      value: "proj0-*"
      type: GLOB
    grant: PUSH_AND_REVIEW

  # Self-certify on the same scope (requires SELF_CERTIFY role on the user too)
  - username: trusted-dev
    provider: internal-github
    match:
      target: NAME
      value: "proj0-*"
      type: GLOB
    grant: SELF_CERTIFY
```

### Grant

| Value             | Effect                                                                                                                    |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `PUSH`            | User may push to matching repositories                                                                                    |
| `REVIEW`          | User may approve or reject pushes submitted by others                                                                     |
| `PUSH_AND_REVIEW` | Shorthand for both PUSH and REVIEW; does **not** include SELF_CERTIFY                                                     |
| `SELF_CERTIFY`    | Trusted contributor: may approve their own clean pushes without a peer reviewer. Requires the `SELF_CERTIFY` role as well |

<!-- prettier-ignore-start -->
> [!IMPORTANT]
> `SELF_CERTIFY` is a two-key lock: the user must have both a `SELF_CERTIFY` permission entry for the repository _and_ the `SELF_CERTIFY` role (set via `users[].roles` or `auth.role-mappings`). Either alone is not sufficient.
<!-- prettier-ignore-end -->

## Groups

_Available since v1.3.0._

Named groups of users that share a common set of permission grants — an alternative to repeating the same `permissions:`
entry for every member individually. A user assigned to a group inherits all of the group's grants in addition to any
directly-assigned permissions.

```yaml
groups:
  - name: team-alpha
    description: "Alpha team push access"
    members:
      - alice
      - bob
    grants:
      - provider: github
        match:
          target: SLUG
          value: /myorg/**
          type: GLOB
        grant: PUSH
```

### Group properties

| Property            | Type   | Default | Description                                                                                        |
| ------------------- | ------ | ------- | -------------------------------------------------------------------------------------------------- |
| `name`              | string | —       | Group name, shown in the dashboard                                                                 |
| `description`       | string | `""`    | Free-text description                                                                              |
| `members`           | list   | `[]`    | Usernames belonging to this group (must match a `users:` entry or a DB user)                       |
| `grants`            | list   | `[]`    | Permission grants applied to every member — same shape as `permissions:` entries, minus `username` |
| `grants[].provider` | string | —       | Provider name as defined in `providers:` config                                                    |
| `grants[].match`    | object | —       | Repository match criteria — same semantics as [Permissions](#permissions)                          |
| `grants[].grant`    | enum   | `PUSH`  | `PUSH`, `REVIEW`, `PUSH_AND_REVIEW`, or `SELF_CERTIFY` — see [Grant](#grant)                       |

<!-- prettier-ignore-start -->
> [!NOTE]
> Groups defined here are CONFIG-sourced and read-only from the dashboard — editing or deleting a config-sourced group
> via the UI/REST API is rejected. Groups created through the dashboard instead are DB-sourced and fully editable there.
> This mirrors how CONFIG-sourced `permissions:`/`rules:` entries behave.
<!-- prettier-ignore-end -->

## Attestations

Attestation questions are presented to reviewers in the dashboard approval form. All configured questions must be
answered before the reviewer can submit an approval. Attestations are hot-reloadable.

```yaml
attestations:
  - id: reviewed-content
    type: checkbox
    label: "I have reviewed the diff and it contains no sensitive or proprietary information"
    required: true

  - id: policy-compliance
    type: checkbox
    label: "This push complies with our open source contribution policy"
    required: true

  - id: ticket-ref
    type: text
    label: "Internal ticket or justification reference"
    required: false

  - id: risk-level
    type: dropdown
    label: "Estimated risk level for this change"
    options:
      - Low
      - Medium
      - High
    required: true
    tooltip: "Select the risk level based on the scope and nature of the change"

  - id: policy-review
    type: checkbox
    label: "I have reviewed the applicable policy"
    required: true
    links:
      - text: "Open source contribution policy"
        url: "https://policy.example.com/open-source"
      - text: "Data classification guide"
        url: "https://policy.example.com/data-classification"
```

### Attestation properties

| Property   | Type    | Default    | Description                                                              |
| ---------- | ------- | ---------- | ------------------------------------------------------------------------ |
| `id`       | string  | —          | Unique key used to store the reviewer's answer in the push record        |
| `type`     | string  | `checkbox` | Input type: `checkbox`, `text`, or `dropdown`                            |
| `label`    | string  | —          | Question text shown in the review form                                   |
| `required` | boolean | `false`    | Whether the question must be answered before the reviewer can submit     |
| `links`    | list    | `[]`       | Policy/reference links (`text` + `url` each) rendered below the question |
| `options`  | list    | _(empty)_  | Choices for `dropdown` type; ignored for other types                     |
| `tooltip`  | string  | _(none)_   | Optional help text shown alongside the question                          |

Set `attestations: []` (or omit the key) to disable attestations entirely.

## Running

```bash
# Proxy only (no dashboard):
./gradlew :fogwall-server:run

# Proxy + dashboard + REST API:
./gradlew :fogwall-dashboard:run

# Override port via environment variable:
FOGWALL_SERVER_PORT=9090 ./gradlew :fogwall-server:run
```

Logs: `fogwall-server/logs/application.log`

## Logging

fogwall uses Log4j2 for logging. To override the bundled config without rebuilding the image, mount a custom
`log4j2.xml` and point the JVM at it:

```bash
# Local run
JAVA_TOOL_OPTIONS=-Dlog4j2.configurationFile=/path/to/log4j2.xml ./gradlew :fogwall-dashboard:run

# Docker — mount your config and set the env var
volumes:
  - ./my-log4j2.xml:/app/conf/log4j2.xml:ro
environment:
  JAVA_TOOL_OPTIONS: -Dlog4j2.configurationFile=/app/conf/log4j2.xml
```

`JAVA_TOOL_OPTIONS` is read directly by the JVM, so it works regardless of how the application is launched.

A ready-made debug config (`docker/log4j2-debug.xml`) is included for diagnosing OIDC and Spring Security issues — it
enables `DEBUG` on `org.springframework.security` and `org.springframework.web.client`. See the comments in that file
for how to activate it.

## Git client output

fogwall sends validation results and status messages to the git client via sideband (the `remote:` lines visible during
a push). Two environment variables control the formatting of these messages:

| Variable           | Effect                                                                                                                                                            |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `NO_COLOR`         | Disables ANSI colour in sideband output. Follows the [no-color.org](https://no-color.org) convention — set to any value to disable.                               |
| `FOGWALL_NO_EMOJI` | Replaces emoji symbols (✅ ❌ ⛔ 🔑 etc.) with plain ASCII equivalents. Useful when pushing through terminals or CI systems that do not render Unicode correctly. |

Both are read at runtime from the server's environment — no restart is required if set before the process starts, but
they cannot be changed while the server is running.

```bash
# Docker Compose — add to the fogwall service environment block
environment:
  NO_COLOR: "1"
  FOGWALL_NO_EMOJI: "1"
```
