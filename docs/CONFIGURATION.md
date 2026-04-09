# Configuration Reference

git-proxy-java uses layered YAML configuration merged at startup. A base file ships with the jar; additional profile files
and environment variable overrides are applied on top in a defined order.

## Configuration files and profiles

### Load order (lowest → highest priority)

| Layer | Source                               | When loaded                                           |
| ----- | ------------------------------------ | ----------------------------------------------------- |
| 1     | `git-proxy.yml`                      | Always — base defaults bundled in the jar             |
| 2     | `git-proxy-{profile}.yml`            | For each profile listed in `GITPROXY_CONFIG_PROFILES` |
| 3     | Environment variables (`GITPROXY_*`) | Always — highest priority                             |

### `GITPROXY_CONFIG_PROFILES`

Set this environment variable to a comma-separated list of profile names. For each name, git-proxy-java looks for
`git-proxy-{name}.yml` on the classpath (including any files mounted into `/app/conf/` in Docker). Unknown or missing
profile files are silently skipped.

```bash
# Local development — loads git-proxy-local.yml
GITPROXY_CONFIG_PROFILES=local

# Docker with LDAP auth — loads git-proxy-docker-default.yml then git-proxy-ldap.yml
GITPROXY_CONFIG_PROFILES=docker-default,ldap

# Docker with OIDC auth and PostgreSQL
GITPROXY_CONFIG_PROFILES=docker-default,oidc
# (postgres settings come from GITPROXY_DATABASE_* env vars, no profile file needed)
```

Later profiles take priority over earlier ones. All profiles take priority over `git-proxy.yml`. Environment variables
override everything.

### Bundled profiles

| Profile name     | File                           | Purpose                                                   |
| ---------------- | ------------------------------ | --------------------------------------------------------- |
| `local`          | `git-proxy-local.yml`          | Local development: dev users, Vite CORS, test allow rules |
| `docker-default` | `git-proxy-docker-default.yml` | Docker base: admin user, Gitea provider, validation rules |
| `ldap`           | `git-proxy-ldap.yml`           | LDAP authentication config (used with `docker-default`)   |
| `oidc`           | `git-proxy-oidc.yml`           | OIDC authentication config (used with `docker-default`)   |

> When running via `./gradlew run`, `GITPROXY_CONFIG_PROFILES=local` is set automatically. In Docker, set it explicitly
> via the Compose file or your deployment config.

### Docker Compose

The Docker Compose setup uses overlay files to compose the stack. See
[docker-compose.ldap.yml](../docker-compose.ldap.yml) and [docker-compose.oidc.yml](../docker-compose.oidc.yml) for
examples of how profiles are combined.

```bash
# Default (local auth, h2 database)
docker compose up -d

# LDAP auth
docker compose -f docker-compose.yml -f docker-compose.ldap.yml up -d

# OIDC auth + PostgreSQL
docker compose --profile postgres \
  -f docker-compose.yml -f docker-compose.oidc.yml -f docker-compose.postgres.yml up -d
```

## Environment variable overrides

Strip the `GITPROXY_` prefix, lowercase, and replace `_` with `.` to get the config path.

| Environment Variable                | Config path                 | Example                   |
| ----------------------------------- | --------------------------- | ------------------------- |
| `GITPROXY_CONFIG_PROFILES`          | _(meta — not a config key)_ | `docker-default,ldap`     |
| `GITPROXY_SERVER_PORT`              | `server.port`               | `9090`                    |
| `GITPROXY_SERVER_APPROVAL_MODE`     | `server.approvalMode`       | `ui`                      |
| `GITPROXY_DATABASE_TYPE`            | `database.type`             | `postgres`                |
| `GITPROXY_DATABASE_URL`             | `database.url`              | `jdbc:postgresql://...`   |
| `GITPROXY_DATABASE_HOST`            | `database.host`             | `db.internal`             |
| `GITPROXY_PROVIDERS_GITHUB_ENABLED` | `providers.github.enabled`  | `false`                   |
| `GITPROXY_PROVIDERS_<NAME>_URI`     | `providers.<name>.uri`      | `https://gitlab.corp.com` |

> Complex nested structures (URL rules, full commit validation blocks) are not overridable via env vars. Use YAML
> profile files instead.

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

  # Base URL of the dashboard, used in links sent to clients via sideband messages.
  # Should include the /dashboard path prefix.
  # Defaults to http://localhost:<port>/dashboard if not set.
  # service-url: https://gitproxy.internal.example.com/dashboard
```

## TLS

### Server HTTPS listener

By default git-proxy-java listens on plain HTTP. To enable HTTPS, add a `server.tls` block.

**PEM-based (preferred — no keytool required):**

```yaml
server:
  tls:
    port: 8443
    certificate: /etc/gitproxy/tls/server.pem      # X.509 certificate or chain, PEM
    key: /etc/gitproxy/tls/server-key.pem           # PKCS8 private key, unencrypted PEM
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
      path: /etc/gitproxy/tls/keystore.p12
      password: changeit
      type: PKCS12   # or JKS
```

Plain HTTP on `server.port` remains active when HTTPS is configured — both listeners run concurrently.

### Custom upstream CA trust

Enterprise PKIs typically issue certificates that Java's built-in truststore doesn't include, causing
`SSLHandshakeException` on upstream connections to internal GitLab/Bitbucket/Forgejo instances. git-proxy-java
supports trusting a custom CA bundle without touching the JVM truststore or running `keytool`.

```yaml
server:
  tls:
    trust-ca-bundle: /etc/gitproxy/tls/internal-ca.pem
```

The PEM file may contain one or more `-----BEGIN CERTIFICATE-----` blocks (a full CA chain is fine). Custom
CAs are merged with the JVM's built-in trust anchors — public hosts (GitHub, GitLab SaaS, Bitbucket Cloud)
continue to work without any changes.

This applies to both proxy modes:
- **Transparent proxy** — Jetty's `HttpClient` used for upstream forwarding
- **Store-and-forward** — JGit's HTTP transport used for forwarding after local receipt

## Database

```yaml
database:
  type: h2-mem # h2-mem | h2-file | postgres | mongo
```

### Database backends

| Type       | Description                 | Extra keys                                                              |
| ---------- | --------------------------- | ----------------------------------------------------------------------- |
| `h2-mem`   | H2 in-memory (default)      | `name` (default: `gitproxy`)                                            |
| `h2-file`  | H2 persisted to disk        | `path` (default: `./.data/gitproxy`)                                    |
| `sqlite`   | SQLite file                 | `path` (default: `./.data/gitproxy.db`)                                 |
| `postgres` | PostgreSQL                  | `url` **or** `host`, `port`, `name`, `username`, `password`             |
| `mongo`    | MongoDB                     | `url` (required); `name` optional if the database is in the URI path    |

For both `postgres` and `mongo`, setting `url` to a full connection string is the recommended approach when you need
driver-specific options (TLS, SSL certificates, connection parameters) that are not exposed as individual config fields.

```yaml
# Postgres — individual fields
database:
  type: postgres
  host: db.internal
  port: 5432
  name: gitproxy
  username: gitproxy
  password: secret

# Postgres — connection string (use this for sslmode, certificates, etc.)
database:
  type: postgres
  url: jdbc:postgresql://db.internal:5432/gitproxy?sslmode=verify-full&sslrootcert=/certs/ca.crt
  username: gitproxy
  password: secret

# Mongo — connection string (name extracted from URI path)
database:
  type: mongo
  url: mongodb://gitproxy:secret@mongo.internal:27017/gitproxy?tls=true&tlsCAFile=/certs/ca.crt

# Mongo — connection string with separate name field
database:
  type: mongo
  url: mongodb://gitproxy:secret@mongo.internal:27017
  name: gitproxy
```

## Authentication

The dashboard supports four authentication providers, selected via `auth.provider`.

```yaml
auth:
  provider: local   # local | ldap | ad | oidc (default: local)
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

  # Map git-proxy-java role names to lists of LDAP group CNs.
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

> **Tip:** The AD provider understands Active Directory error sub-codes on bind failure 49 (expired passwords, locked
> accounts, etc.) and maps them to specific Spring Security exceptions.

### OIDC

Authenticates users via OpenID Connect authorization code flow (Keycloak, Okta, Entra ID, Dex, etc.).

```yaml
auth:
  provider: oidc
  oidc:
    # OIDC issuer URI — Spring Security fetches {issuerUri}/.well-known/openid-configuration at startup.
    issuer-uri: https://accounts.example.com

    client-id: gitproxy-client
    client-secret: gitproxy-secret

    # Optional: path to a PKCS#8 PEM RSA private key for private_key_jwt client auth.
    # When set, client-secret is not required.
    # private-key-path: /run/secrets/gitproxy-oidc-private-key.pem

  # OIDC claim containing the user's group memberships. Defaults to "groups",
  # which is standard for Keycloak, Okta, and most Entra ID configurations.
  groups-claim: groups

  # Map git-proxy-java role names to lists of OIDC group values from the claim above.
  role-mappings:
    ADMIN:
      - git-admins
```

#### Entra ID (Azure AD)

Entra ID requires two extra settings. The `jwk-set-uri` field is the key signal — when it is set, git-proxy-java skips OIDC
discovery and issuer validation. This is necessary because Entra issues tokens with
`iss=https://sts.windows.net/{tenant}/` rather than the discovery base URL, which would cause Spring Security to reject
them otherwise.

```yaml
auth:
  provider: oidc
  oidc:
    issuer-uri: https://login.microsoftonline.com/{tenant-id}/v2.0
    client-id: <app-registration-client-id>
    client-secret: <client-secret>

    # Required for Entra ID — triggers issuer-validation bypass.
    jwk-set-uri: https://login.microsoftonline.com/{tenant-id}/discovery/v2.0/keys

  # Requires "Group claims" to be enabled in the app registration (Token configuration → Groups claim).
  # Group values will be object IDs (GUIDs) unless "Group names" is selected in the manifest.
  groups-claim: groups

  role-mappings:
    ADMIN:
      - <object-id-of-admin-group>
```

> **App registration checklist:**
>
> 1. Platform: Web — redirect URI `https://<your-host>/login/oauth2/code/gitproxy`
> 2. API permissions: `openid`, `profile`, `email` (delegated)
> 3. Token configuration → add Groups claim → select "Security groups"

### Role mappings

`auth.role-mappings` applies to LDAP, AD, and OIDC. Keys are role names (without the `ROLE_` prefix); values are lists
of group names or claim values from the IdP. `ROLE_USER` is always granted to every authenticated user.

| Role    | Dashboard access |
| ------- | ---------------- |
| `USER`  | View and act on pushes awaiting approval |
| `ADMIN` | All USER permissions + create/delete users, reset passwords, manage identities |

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

| Property       | Type    | Default              | Description                                                                                                                                                 |
| -------------- | ------- | -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `enabled`      | boolean | `true`               | Whether the provider is active                                                                                                                              |
| `servlet-path` | string  | `""`                 | Additional URL prefix for this provider                                                                                                                     |
| `uri`          | string  | _(built-in default)_ | Upstream base URI. Required for custom-named providers; omit for built-ins.                                                                                 |
| `type`         | string  | _(from name)_        | Provider implementation: `github`, `gitlab`, `bitbucket`, `codeberg`, `forgejo`, `gitea`. Required for any name that is not one of the five reserved names. |

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

> **M&A / private server use case:** This same mechanism works for self-hosted Bitbucket Data Center instances. Set
> `uri` to your internal Bitbucket URL and the proxy will route and rewrite credentials accordingly, making it
> straightforward to gate pushes to acquired-company repositories during an integration period.

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
    # install-dir: ~/.cache/git-proxy-java/gitleaks
    # scanner-path: /usr/local/bin/gitleaks
    # config-file: /app/conf/.gitleaks.toml
    # timeout-seconds: 30
```

## Identity verification

```yaml
commit:
  identity-verification: warn # warn | strict | off
```

For every push, the proxy runs two independent checks:

1. **SCM login check** — calls the upstream provider's user API with the token supplied in the git credentials (the HTTP
   Basic-auth password). The returned login (e.g. GitHub `login`, GitLab `username`) is matched against the
   authenticated git-proxy-java user's `scm-identities`. This is the only identity signal the SCM can reliably provide —
   email is not used here (GitHub omits it when private email visibility is enabled; other providers vary).

2. **Commit email check** — every author and committer email in the pushed commits is checked against the authenticated
   git-proxy-java user's `emails` list. These emails are populated independently of the SCM: they come from the IdP on
   LDAP/OIDC login, or from additional associations added via the dashboard. This is what ties commit attribution back
   to a verified real person.

Both checks must pass in `strict` mode. This catches a developer whose git client is misconfigured (`user.email` doesn't
match their registered address) and — more critically — commits attributed to someone other than the person who actually
pushed.

> **The HTTP Basic-auth username in the remote URL is not used for identity resolution.** It is ignored by all providers
> (except Bitbucket). Configure your remote URL with any username — `git`, `me`, your actual name — it makes no
> difference.

### Modes

| Mode     | Behaviour                                                                                                      | Use when                                                                       |
| -------- | -------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `strict` | Blocks the push if the SCM username or any commit email cannot be matched to the authenticated git-proxy-java user | Production — this is the only mode that actually enforces identity             |
| `warn`   | Allows the push through but emits a sideband warning to the git client and records the mismatch                | Rolling out to an existing team — lets you observe mismatches before enforcing |
| `off`    | Check is disabled entirely                                                                                     | Migrations or environments where SCM identity data is not yet populated        |

> **`warn` is not a security control.** Pushes succeed regardless of the outcome. Only `strict` blocks unverified
> pushes. The default is `warn` to avoid breaking existing deployments on first install — but `strict` should be the
> target for any production deployment once users have registered their SCM identities.

### Token scope requirements

Identity resolution calls `GET /user` (or equivalent) on the upstream SCM using the pusher's token. The token must carry
at least the following scope:

| Provider | API endpoint                           | Additional scope                                                       |
| -------- | -------------------------------------- | ---------------------------------------------------------------------- |
| GitHub   | `GET https://api.github.com/user`      | No additional scopes required for either classic or fine-grained PATs. |
| GitLab   | `GET {uri}/api/v4/user`                | `read_user` or `api` (not recommended)                                 |
| Codeberg | `GET https://codeberg.org/api/v1/user` | `read:user`                                                            |
| Gitea    | `GET https://gitea.com/api/v1/user`    | `read:user`                                                            |

If the token is missing the required scope, `fetchScmIdentity` returns empty and the push is treated as unresolved —
which is a warning in `warn` mode and a block in `strict` mode.

### Prerequisites

Identity verification requires user records with populated `scm-identities` and `emails`. A push from a user with no
registered SCM identity always fails in `strict` mode. Use `warn` during rollout to give users time to register before
enforcement begins.

```yaml
users:
  - username: alice
    password-hash: "{bcrypt}$2a$12$..."
    emails:
      - alice@example.com
    scm-identities:
      - provider: github
        username: alice-gh
      - provider: gitlab
        username: alice
```

## URL rules

URL rules control which repositories are accessible through the proxy. git-proxy-java is **default-deny**: if no allow
rules are configured for a provider, all pushes and fetches to that provider are rejected. At least one allow rule
must match for a request to proceed.

Slugs, owners, and names support glob patterns (e.g. `finos/*`, `*-public`).

```yaml
rules:
  allow:
    # Specific repos by slug (owner/name) — both FETCH and PUSH
    - enabled: true
      order: 110
      operations:
        - FETCH
        - PUSH
      providers:
        - github
      slugs:
        - /finos/git-proxy
        - /coopernetes/test-repo

    # All repos under an owner — FETCH only, any provider
    - enabled: true
      order: 120
      operations:
        - FETCH
      owners:
        - finos

  deny:
    # Block a specific repo across all operations
    - enabled: true
      order: 100
      slugs:
        - /myorg/forbidden-repo
```

To allow all repositories on a provider (open mode), use a wildcard slug:

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operations:
        - FETCH
        - PUSH
      slugs:
        - "*/*"
```

### URL rule properties

| Property     | Type    | Default | Description                                           |
| ------------ | ------- | ------- | ----------------------------------------------------- |
| `enabled`    | boolean | `true`  | Whether this entry is active                          |
| `order`      | int     | —       | Evaluation order (lower = earlier; 50–199 range)      |
| `operations` | list    | _none_  | `FETCH`, `PUSH` — which operations this entry matches |
| `providers`  | list    | _all_   | Provider names to scope this entry to                 |
| `slugs`      | list    | _none_  | `/owner/repo` slugs; supports glob patterns           |
| `owners`     | list    | _none_  | Owner/org names; supports glob patterns               |
| `names`      | list    | _none_  | Repository names; supports glob patterns              |

## Running

```bash
# Proxy only (no dashboard):
./gradlew :git-proxy-java-server:run

# Proxy + dashboard + REST API:
./gradlew :git-proxy-java-dashboard:run

# Override port via environment variable:
GITPROXY_SERVER_PORT=9090 ./gradlew :git-proxy-java-server:run
```

Logs: `git-proxy-java-server/logs/application.log`

## Logging

git-proxy-java ships a default `logback.xml` (console + rolling file, INFO level). To override it without
rebuilding the image, point the JVM at an external Logback config file:

```bash
# Local run
JAVA_TOOL_OPTIONS=-Dlogback.configurationFile=/path/to/logback.xml ./gradlew :git-proxy-java-dashboard:run

# Docker — mount your config and set the env var
volumes:
  - ./my-logback.xml:/app/conf/logback.xml:ro
environment:
  JAVA_TOOL_OPTIONS: -Dlogback.configurationFile=/app/conf/logback.xml
```

`JAVA_TOOL_OPTIONS` is read directly by the JVM, so it works regardless of how the application is launched.

A ready-made debug config (`docker/logback-debug.xml`) is included for diagnosing OIDC and Spring Security
issues — it enables `DEBUG` on `org.springframework.security` and `org.springframework.web.client`. See the
comments in that file for how to activate it.
