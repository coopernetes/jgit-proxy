# Administrator and Operator Guide

This guide covers deploying, configuring, and operating fogwall. It is written for the person responsible for running
the proxy — setting up user accounts, configuring providers and rules, diagnosing problems, and keeping the service
healthy.

For the YAML configuration reference, see [CONFIGURATION.md](CONFIGURATION.md). For developers pushing through the
proxy, see [USER_GUIDE.md](USER_GUIDE.md).

---

## Conceptual model: three independent layers

Before diving into configuration details, it helps to understand that access control in fogwall is three orthogonal
layers that all must pass before a push is forwarded:

```text
1. Access rules       rules.allow / rules.deny
   "Is this repo even on the proxy's allowed list?"
         ↓
2. User permissions   permissions:
   "Is this user allowed to push to this specific repo?"
         ↓
3. Commit validation  commit:
   "Does the content of this push comply with policy?"
```

A push fails at the first layer that rejects it. A common misconfiguration is to add a repo to `rules.allow` but forget
to add a `permissions` entry for the user — or vice versa. Both are required.

**Access rules** are site-wide policy: they determine what the proxy will route at all, independently of who is pushing.
Think of them as a firewall rule list.

**User permissions** are per-user grants scoped to a provider and path. They determine whether a particular
authenticated user is permitted to push to (or review) a particular repository.

**Commit validation** runs against the push content: author/committer email policy, commit messages, commit-trailer
policy (DCO `Signed-off-by`, `Co-authored-by`), diff scanning, secret scanning. These apply to everyone regardless of
permissions.

---

## Developer onboarding — the Setup page

The dashboard serves a **Setup** page (reached from the help / quick-start icon in the top bar) that generates
deployment-specific git config for developers, with this deployment's real hostnames filled in. It is generated from the
running configuration (providers, service URL, SSH listener), so it cannot drift from what fogwall actually serves.

- **Push-only by default.** The generated config reroutes only developers' _pushes_ to fogwall (git `pushInsteadOf`);
  clones and fetches keep going straight to the upstream. This keeps read-only access unaffected and avoids a read-time
  dependency on fogwall — reads are typically already inspected elsewhere. Routing fetches through fogwall is offered as
  an explicit opt-in, and the page tells developers who only clone/fetch that they need nothing at all.
- **Global vs per-repo.** The page offers both a one-paste global `~/.gitconfig` form (applies to every repo under the
  upstream host, gated by your URL rules) and an explicit per-repository form (`git remote set-url --push`, visible in
  `git remote -v`), noting the global form's blast radius.
- **It is public** (served at `/api/setup`, no login) — a developer who cannot yet log in is exactly who needs setup
  instructions, and fogwall is often deployed where the GitHub-hosted docs are blocked. It exposes only routing
  information already implied by the provider list; no secrets.
- **Set [`server.service-url`](CONFIGURATION.md#server-settings)** so the generated URLs are correct. When it is unset,
  the page derives the base URL from the developer's own browser address, which is wrong behind a reverse proxy — the
  page shows a warning in that case. Setting `service-url` is the fix.

---

## User accounts

fogwall supports four authentication backends. **LDAP, AD, and OIDC are the expected production choices.** Local auth
manages users in the database (add/remove users, reset passwords via the dashboard) with passwords defined in YAML
config. It is self-contained and requires no external directory, but every user must be provisioned manually. It is
suitable for small teams or single-operator deployments; LDAP, AD, or OIDC are preferable when the org already has a
directory.

### Authentication backends

| Backend          | `auth.provider` | When to use                                                    |
| ---------------- | --------------- | -------------------------------------------------------------- |
| Local (static)   | `local`         | Dev / demo only. Passwords in YAML config.                     |
| LDAP             | `ldap`          | Generic LDAP directory (OpenLDAP, 389 DS, etc.)                |
| Active Directory | `ad`            | On-premises AD domain. UPN bind, no `user-dn-patterns` needed. |
| OIDC             | `oidc`          | Keycloak, Okta, Entra ID, Dex, etc.                            |

See [CONFIGURATION.md — Authentication](CONFIGURATION.md#authentication) for the full config reference and worked
examples.

### How users are provisioned per backend

**Local:** users are defined entirely in the `users:` YAML block. Each entry needs a username, BCrypt password hash, and
at least one email. Roles and SCM identities are set here too. Changes require a config reload.

```yaml
users:
  - username: alice
    password-hash: "{bcrypt}$2a$12$..."
    roles: [ADMIN]
    emails:
      - alice@corp.example.com
    scm-identities:
      - provider: github/github.com
        username: alice-github
```

**LDAP / AD:** users are provisioned automatically on first login. The proxy creates a user record from the directory
attributes returned at bind time. The `mail` attribute (if present) is stored as a locked email — locked means it cannot
be edited from the profile UI, since the directory is the source of truth. Roles are assigned via `auth.role-mappings`
(LDAP group CNs → role names). When `role-mappings` is configured, a user who does not match any mapped group is
**denied access entirely** — they authenticate successfully against the directory but are refused by the proxy. This is
intentional: the proxy is not open to all directory users by default. To grant baseline access, map a broad group (e.g.
all-staff) to `USER`, or set `auth.require-role-mapping: false` to treat the directory purely as an authentication
mechanism and grant `ROLE_USER` to anyone who authenticates. See
[CONFIGURATION.md — Role mappings](CONFIGURATION.md#role-mappings).

SCM identities and permissions still need to be set up after first login — either by the user themselves from their
profile page, by an admin via the dashboard, or via a supplemental `users:` YAML entry (which can carry `scm-identities`
without a `password-hash` for IdP-authed users).

**OIDC:** same auto-provisioning and deny-by-default behaviour as LDAP. Groups from the configured `groups-claim`
(default: `groups`) are mapped to roles via `auth.role-mappings`. Email comes from the `email` claim in the ID token.
Users whose token carries no matching group claim are denied access.

### Dashboard roles

Roles control what a user can do in the dashboard and REST API:

| Role             | What it grants                                                                                                                                                   |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `USER` (default) | View push records; approve or reject pushes they have `REVIEW` permission on; manage their own profile (emails, SCM identities)                                  |
| `ADMIN`          | Everything USER can do, plus: create/delete users, reset passwords, manage any user's profile, view all push records                                             |
| `SELF_CERTIFY`   | Grants the **capability** to self-approve pushes. This is the prerequisite gate — it must be present before any per-repo `SELF_CERTIFY` permission takes effect. |

`ROLE_USER` is granted to every authenticated user automatically when no `role-mappings` are configured (open mode).
When `role-mappings` are configured, access is deny-by-default — a user must belong to at least one mapped group or they
are refused login entirely. Map a broad group to `USER` to grant baseline access to all directory members.

`ROLE_SELF_CERTIFY` is the prerequisite gate for self-approval. It represents the capability, attested by your org's IdP
or IAM process. Self-approval requires **both** this role and a per-repo `SELF_CERTIFY` permission entry — neither alone
is sufficient. This separation lets organisations externalise the capability grant (who is trusted to self-certify at
all) to their existing directory/IAM procedures, while the per-repo entitlement remains managed inside fogwall.

How to grant `ROLE_SELF_CERTIFY`:

- **LDAP / AD / OIDC:** add `SELF_CERTIFY` to `auth.role-mappings` and map it to the appropriate IdP group.
- **Local auth:** add `SELF_CERTIFY` to `roles:` in the user's `users:` YAML entry.

<!-- prettier-ignore-start -->
> [!NOTE]
> Organisations that require mandatory peer review (four-eyes) for all activity should simply not grant `SELF_CERTIFY` role or permissions. If no user holds `SELF_CERTIFY`, all pushes require a separate reviewer.
<!-- prettier-ignore-end -->

**Roles are dashboard-level access only.** They do not control which repos a user can push to — that is what permissions
(below) are for.

### Emails and SCM identities

Every user record carries two independent data sets that the proxy uses to verify identity on each push:

**Emails** — the set of email addresses the user commits with (i.e. the value in `git config user.email`). On every
push, every author and committer email in the incoming commits is checked against this list. If an email is not
registered to the authenticated user, the push fails in `strict` mode or warns in `warn` mode. This is what ties commit
attribution to a verified real person.

**SCM identities** — the upstream provider username(s) for this user (e.g. their GitHub login). On every push, the proxy
calls the upstream API using the PAT supplied in the git credentials and checks the returned username against this list.
This confirms that the token being used actually belongs to the person who authenticated with the proxy, not a shared or
borrowed token.

These are two independent checks and both must pass in `strict` mode. They catch different things: a commit email
mismatch means the developer's git client is misconfigured or the commit is attributed to someone else; an SCM identity
mismatch means the token does not belong to the authenticated user.

**How emails are populated:**

- Local auth: set in the `users:` YAML block; editable from the profile UI.
- LDAP/AD: the directory `mail` attribute is imported on first login as a locked email (not editable from the UI — the
  directory is the source of truth). Additional emails can be added via the admin dashboard.
- OIDC: the `email` claim from the ID token is imported on first login as a locked email.

**How SCM identities are populated:**

There is no automatic source for SCM identities — they must be added manually regardless of auth backend. After first
login, either the user themselves or an admin can add SCM identities from the profile page in the dashboard. For
example: provider `github/github.com`, username `alice-gh`. Users manage their own profile; admins can manage any user's
profile.

For local auth, SCM identities can also be set in the `users:` YAML block:

```yaml
users:
  - username: alice
    # ...
    scm-identities:
      - provider: github/github.com
        username: alice-gh
      - provider: gitlab/gitlab.com
        username: alice
```

Until SCM identities are populated, pushes from that user will fail identity verification in `strict` mode. Use
`attribution-policy` with `committer: warn` during rollout to let pushes through while identities are being registered.
See [USER_GUIDE.md — Identity verification](USER_GUIDE.md#identity-verification) for the developer-facing view of what
these checks look like at the terminal.

### Disabling local admin when using an IdP

When LDAP, AD, or OIDC is configured, the static `users:` block still works and is evaluated alongside the IdP. In most
production setups you want to remove static local accounts (or at minimum remove any with `roles: [ADMIN]`) once
IdP-based login is confirmed working. See #103 for planned enforcement of this.

---

## Repo permissions

Permissions control which users can push to which repos, and who can review pushes.

```yaml
permissions:
  - username: alice
    provider: github/github.com
    path: /myorg/myrepo
    grant: PUSH
```

### Operations

| Value             | What it grants                                                                                                                                                                                                                                              |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PUSH`            | User can submit pushes to this repo for validation and review                                                                                                                                                                                               |
| `REVIEW`          | User can approve or reject pushes to this repo submitted by others                                                                                                                                                                                          |
| `PUSH_AND_REVIEW` | Shorthand for both PUSH and REVIEW                                                                                                                                                                                                                          |
| `SELF_CERTIFY`    | Per-repo entitlement: this user may self-approve pushes to this repo. Requires `ROLE_SELF_CERTIFY` (the capability role) to also be present — see [Dashboard roles](#dashboard-roles). Does **not** imply PUSH or REVIEW; grant those separately if needed. |

### SELF_CERTIFY — for solo contributors

`SELF_CERTIFY` is the right choice for a developer who works independently and does not have a team reviewer. Without
it, pushes in `ui` approval mode wait indefinitely for someone else to approve them.

Self-approval requires **two** things — both must be in place:

1. The `SELF_CERTIFY` role (capability gate) — granted via `auth.role-mappings` or `roles: [SELF_CERTIFY]` in local
   config. This is the org-level attestation that the user is trusted to self-certify at all.
2. A `SELF_CERTIFY` permission entry for the specific repo — the per-repo entitlement.

To set up a trusted solo contributor who approves their own work:

```yaml
# Step 1: grant the SELF_CERTIFY capability role (local auth example)
users:
  - username: bob
    password-hash: "{bcrypt}$2a$12$..."
    roles: [SELF_CERTIFY] # or via auth.role-mappings for LDAP/AD/OIDC

# Step 2: grant the per-repo entitlement
permissions:
  - username: bob
    provider: github/github.com
    path: /myorg/myrepo
    grant: PUSH
  - username: bob
    provider: github/github.com
    path: /myorg/myrepo
    grant: SELF_CERTIFY
```

Bob's pushes are validated as normal (commit rules, secret scanning, identity checks). Once validation passes, the proxy
records a self-certification in the audit log and forwards without waiting for a reviewer.

If Bob also needs to review others' pushes to that repo, add a third entry with `grant: REVIEW`.

### Permission groups

_Available since v1.3.0._

For teams larger than a handful of users, granting permissions one entry per user gets unwieldy. A `groups:` block
grants the same target/match model to every member at once:

```yaml
groups:
  - name: platform-team
    description: Platform engineering
    members: [alice, bob, carol]
    grants:
      - provider: github/github.com
        path: /myorg/*
        path-type: GLOB
        grant: PUSH
```

A member's effective access is the union of their direct `permissions:` entries and every group they belong to — groups
are additive, not a replacement for per-user grants. Groups defined in YAML are read-only in the dashboard (config is
the source of truth); groups created via the dashboard UI are DB-backed and fully editable there. Both kinds show up
together in the **Groups** admin page.

### Path matching

Paths default to exact (`LITERAL`) matching. Use `path-type` for wildcards:

```yaml
# GLOB — all repos under an owner
- username: alice
  provider: gitlab/gitlab.com
  path: /myorg/*
  path-type: GLOB
  grant: PUSH

# REGEX — Java regex matched against /owner/repo
- username: alice
  provider: github/github.com
  path: \/myorg\/service\-.*
  path-type: REGEX
  grant: PUSH
```

### Permissions vs access rules

A user with `PUSH` permission on `/myorg/myrepo` can still be blocked if `/myorg/myrepo` is not in `rules.allow`. Both
must be satisfied. The distinction:

- **Access rules** → "does the proxy route this repo at all?" — operator policy
- **Permissions** → "can this user push to it?" — per-user grant

A wildcard allow rule (`slugs: ["*/*"]`) effectively means "route everything" and shifts all control to the permissions
layer. A tightly scoped allow rule means you do not need to worry about accidentally granting a user permission to a
repo the proxy does not handle.

---

## Access rules

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operation: [FETCH, PUSH]
      providers: [github/github.com]
      slugs:
        - /myorg/repo-one
        - /myorg/repo-two

  deny:
    - enabled: true
      order: 100 # deny rules with lower order numbers take precedence
      operation: [PUSH]
      slugs:
        - /myorg/archived-repo
```

Rules are evaluated in `order` number order (lower = earlier). Deny rules override allow rules at the same order number.
The proxy is **default-deny**: if no allow rule matches, the request is rejected.

`operation` scopes a rule to `PUSH`, `FETCH`, or both. A repo can be open for fetch but restricted for push.

### Disabling fetch serving entirely

Access rules gate _which upstreams_ are reachable for `FETCH`. A separate, coarser switch controls whether server mode
serves clone/fetch from its local mirror **at all**:

```yaml
server:
  serve-fetch: false # global default; push-only gateway, no local mirror served

providers:
  github:
    serve-fetch: true # optional per-provider override of the global default
```

Serving fetches is the default and the right one for most deployments — a developer whose remote is the fogwall URL
expects `git pull` to work against it, and taking that away breaks the single-remote workflow. Turn it off when:

- fogwall is a push-validation gateway that is not meant to be a read path for anything;
- the mirror holds repositories you would rather not serve from fogwall's disk at all, regardless of who asks;
- you want the reachable surface as small as the use case requires.

When disabled, the `git-upload-pack` capability is simply not mounted (HTTP) and is refused on the SSH transport; a
fetch is rejected with a clear git-side message — `fatal: remote error: fetches are not served through this gateway` —
rather than a `404` that reads as a missing repository. Push (`receive-pack`) is unaffected, and the switch applies to
**both** server mode transports so neither can serve a fetch the other refuses.

This is deliberately not a per-user read-permission model: for a public upstream there is no credential to authorize,
and for a private one the fetch already carries the caller's own upstream credentials, which answers the question
authoritatively. Use access rules to gate _which_ repos are reachable, and `serve-fetch` to decide whether fogwall
serves fetches at all. Transparent proxy mode forwards to upstream rather than serving a local mirror, so it is
unaffected by this setting.

### Dry-run testing rules and permissions

_Available since v1.3.0._

Before rolling out a new access rule or permission grant, verify the outcome against the live configuration without
waiting for a real push:

```
POST /api/repos/rules/test
{ "provider": "github/github.com", "owner": "myorg", "name": "myrepo", "operation": "PUSH" }
→ { "decision": "ALLOW", "matchedRuleId": 110, "steps": [...] }

POST /api/users/{username}/permissions/test
{ "provider": "github/github.com", "path": "/myorg/myrepo", "grant": "PUSH" }
→ { "allowed": true, "source": "GROUP", "groupName": "platform-team" }
```

Both endpoints are read-only evaluations against whatever rules, permissions, and groups are currently loaded — no push
is created. `source` on the permission check distinguishes a direct per-user grant (`DIRECT`) from one inherited via a
[permission group](#permission-groups) (`GROUP`). These endpoints are dashboard-only (`fogwall-dashboard`); the
standalone server has no REST API.

---

## Approval mode

```yaml
server:
  approval-mode: auto # auto | ui
```

| Mode   | Behaviour                                                                                                                                                                                            |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `auto` | Clean pushes are immediately approved and forwarded after validation. No reviewer needed. Good for teams that use validation as a guardrail without a manual review step, and for solo contributors. |
| `ui`   | Every push enters `PENDING` state and waits for a reviewer to approve or reject in the dashboard. The `git push` command stays open until a decision is made.                                        |

`SELF_CERTIFY` permission interacts with `ui` mode: users with the capability and per-repo entitlement can self-review
their own push in the dashboard. The review step still happens — they attest to and record their own approval. This
signals to operators and the audit log that the pusher has reviewed and accepted responsibility for the changes. Other
users' pushes still require a peer reviewer.

The dashboard module (`fogwall-dashboard`) always uses `ui` mode. The standalone server module defaults to `auto`.

---

## Logging

### Default log locations

| Environment         | Log output                                          |
| ------------------- | --------------------------------------------------- |
| `./gradlew run`     | `fogwall-server/logs/application.log` + console     |
| Docker / production | console only (stdout); redirect or use a log driver |

The default Log4j2 config logs `com.rbc.fogwall` at `DEBUG` and everything else at `INFO`.

### Enabling debug logging for specific subsystems

Override the bundled `log4j2.xml` at runtime — no rebuild required:

```bash
# Local run
JAVA_TOOL_OPTIONS=-Dlog4j2.configurationFile=/path/to/my-log4j2.xml \
  ./gradlew :fogwall-dashboard:run

# Docker
volumes:
  - ./my-log4j2.xml:/app/conf/log4j2.xml:ro
environment:
  JAVA_TOOL_OPTIONS: -Dlog4j2.configurationFile=/app/conf/log4j2.xml
```

### Debug profiles by problem area

#### OIDC / Spring Security authentication failures

`docker/log4j2-debug.xml` is included for this. Activate it in Docker Compose:

```yaml
volumes:
  - ./docker/log4j2-debug.xml:/app/conf/log4j2-debug.xml:ro
environment:
  JAVA_TOOL_OPTIONS: -Dlog4j2.configurationFile=/app/conf/log4j2-debug.xml
```

This enables `DEBUG` on `org.springframework.security` and `org.springframework.web.client`. Remove it when done — it is
very chatty.

#### JGit HTTP transport (upstream push/fetch failures)

Add to your `log4j2.xml`:

```xml
<Logger name="org.eclipse.jgit" level="DEBUG"/>
<Logger name="org.eclipse.jgit.http.server" level="DEBUG"/>
<Logger name="org.eclipse.jgit.transport" level="DEBUG"/>
```

Produces detailed output for each step of the JGit credential negotiation and pack transfer. Useful when a push reaches
the proxy but fails forwarding to upstream.

#### Jetty request handling (incoming connections, servlet dispatch)

```xml
<Logger name="org.eclipse.jetty" level="DEBUG"/>
<Logger name="org.eclipse.jetty.server" level="DEBUG"/>
<Logger name="org.eclipse.jetty.http" level="DEBUG"/>
```

#### Upstream HTTP client (transparent proxy mode)

```xml
<Logger name="org.eclipse.jetty.client" level="DEBUG"/>
```

Logs each HTTP request and response made by Jetty's `ProxyServlet` to the upstream. Useful when the transparent proxy
path (`/proxy/`) fails to reach the upstream.

### Reading logs for a failed push

Each push gets a `requestId` in the MDC (visible in the `[%X{requestId}]` field in the log pattern). To follow a single
push through the log:

```bash
grep "your-request-id" logs/application.log
```

The `requestId` is also printed in the sideband output to the git client, so you can match terminal output to log lines.

<!-- prettier-ignore-start -->
> [!NOTE]
> **Roadmap:** OpenTelemetry tracing support (propagating trace/span IDs into the log MDC and exporting spans to a collector) is tracked in [#106](https://github.com/RBC/fogwall/issues/106). Once implemented, the `requestId` will be correlatable across distributed systems without manual log grepping.
<!-- prettier-ignore-end -->

### Git client output formatting

Two environment variables control the `remote:` sideband messages sent to git clients during a push:

| Variable           | Effect                                                                                                          |
| ------------------ | --------------------------------------------------------------------------------------------------------------- |
| `NO_COLOR`         | Disables ANSI colour. Follows the [no-color.org](https://no-color.org) convention — set to any non-empty value. |
| `FOGWALL_NO_EMOJI` | Replaces emoji (✅ ❌ ⛔ 🔑) with plain ASCII. Useful for CI systems or terminals that do not render Unicode.   |

Set on the server process, not on the client. See
[CONFIGURATION.md — Git client output](CONFIGURATION.md#git-client-output) for Docker Compose examples.

---

## JGit filesystem requirements

JGit requires write access to two locations at runtime. Failures here produce cryptic errors that look like git
transport problems but are actually filesystem permission issues.

### Home directory

JGit reads `~/.gitconfig` and writes lock files in `$HOME`. In a container, `HOME` must point to a writable directory.

The Docker image sets `ENV HOME=/app/home` and creates `/app/home` with correct permissions. If you override the image's
entrypoint or run under a different UID, verify that `$HOME` is writable:

```bash
# Inside the container:
ls -la $HOME
touch $HOME/.test && rm $HOME/.test   # must succeed
```

**OpenShift / arbitrary UID:** OpenShift runs containers as a random UID by default. The image is built with GID 0
group-write on `/app/home`, `/app/.data`, and `/app/logs` (`chmod g+rwX`) so that any UID in group 0 can write to them.
If you see `Permission denied` errors on startup, check whether your security context is overriding the GID.

### `/tmp` for scratch repos and gitleaks

JGit creates temporary bare repositories in `java.io.tmpdir` (defaults to `/tmp`) for server mode pushes and for
transparent proxy diff inspection. Gitleaks also writes temporary files there.

If `/tmp` is not writable (e.g. `noexec` mount, read-only root filesystem), override the JVM temp dir:

```yaml
environment:
  JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/app/.data/tmp
```

And create the directory in your deployment:

```bash
mkdir -p /app/.data/tmp
chmod 700 /app/.data/tmp
```

For Kubernetes with a `readOnlyRootFilesystem: true` security context, mount an `emptyDir` at `/tmp`:

```yaml
volumes:
  - name: tmp
    emptyDir: {}
volumeMounts:
  - name: tmp
    mountPath: /tmp
```

### Gitleaks binary permissions

When `secret-scan.enabled: true`, the proxy needs to execute the gitleaks binary. The bundled binary (inside the JAR) is
extracted to `java.io.tmpdir` at startup — that directory must allow executable files (`noexec` prevents this).

If the temp dir is `noexec`, point gitleaks at a writable, exec-allowed path:

```yaml
commit:
  secret-scan:
    enabled: true
    scanner-path: /app/.data/gitleaks # explicit path bypasses auto-extraction
```

Or pre-install gitleaks and put it on `PATH` — the proxy will find it via system path lookup before falling back to the
bundled binary.

---

## Externalized configuration

fogwall follows 12-factor config principles: the application ships with safe defaults baked into the JAR, and operators
layer environment-specific values on top without modifying the image.

Both `fogwall-server` and `fogwall-dashboard` load config through [Gestalt](https://gestalt-config.github.io/gestalt/),
a lightweight Java config library rather than Spring's `@ConfigurationProperties`/`Environment` stack — same reasoning
as [not using Spring Boot](ARCHITECTURE.md#proxy--dashboard-fogwall-dashboard) — fogwall needs config loading to work
identically in `fogwall-server`, which has no Spring on its classpath at all. Gestalt is a much smaller dependency that
covers the same core need (typed config binding, layered sources, environment variable overrides) without pulling in a
DI container. The profile mechanism below is directly modeled on Spring profiles — the concept of named, composable
config overlays activated by name is worth keeping even without the rest of Spring's config machinery.

### How config is loaded

Sources are merged in priority order (lowest → highest):

| Priority    | Source                                                  | Mechanism                                |
| ----------- | ------------------------------------------------------- | ---------------------------------------- |
| 1 (lowest)  | `fogwall.yml`                                           | Bundled in the JAR — base defaults       |
| 2           | Profile YAMLs named in `FOGWALL_CONFIG_PROFILES`        | Classpath lookup (see below)             |
| 3           | `FOGWALL_*` environment variables                       | Strip prefix, lowercase, `_` → `.`       |
| 4 (highest) | Hot-reload overlay (`reload.file.path` or `reload.git`) | Filesystem path; applied on every reload |

A higher-priority source only overrides the specific keys it defines — other base values are preserved.

### Profile-based config files — the `/app/conf/` pattern

The Docker image prepends `/app/conf/` to the JVM classpath. Any YAML file mounted there is treated as a classpath
resource and loaded automatically when its profile is activated.

**Step 1 — Mount the file:**

```yaml
# docker-compose.yml or Kubernetes pod spec
volumes:
  - ./my-config.yml:/app/conf/fogwall-my-config.yml:ro
# Or in Kubernetes, mount a ConfigMap:
# - name: fogwall-config
#   mountPath: /app/conf
```

**Step 2 — Activate the profile:**

```yaml
environment:
  FOGWALL_CONFIG_PROFILES: my-config
```

The loader looks for `fogwall-{profile}.yml` on the classpath. With `/app/conf/` prepended, your mounted file is found
first.

<!-- prettier-ignore-start -->
> [!IMPORTANT]
> A file mounted at `/app/conf/` is silently ignored unless the matching profile name is set in `FOGWALL_CONFIG_PROFILES`. There is no auto-discovery — the profile name is the activation key.
<!-- prettier-ignore-end -->

**Multiple profiles** are comma-separated; later profiles take priority over earlier ones:

```
FOGWALL_CONFIG_PROFILES=docker-default,ldap
```

This loads `fogwall-docker-default.yml` then `fogwall-ldap.yml`; `ldap` wins on any key both files define.

<!-- prettier-ignore-start -->
> [!WARNING]
> **List merge caveat:** Gestalt replaces lists at the key level — it does not append. If two profile files both define `permissions:`, the later file's list replaces the earlier one entirely. Keep all entries for a given list key in a single profile file. A common split that avoids this: one profile for organizational config (users, permissions, rules) and a second for environment-specific connectivity (auth provider URL, database, TLS) which never defines list keys.
<!-- prettier-ignore-end -->

### Environment variable overrides

Any `FOGWALL_` prefixed env var overrides the equivalent config key at the highest priority (above profiles, below
hot-reload overlays). The mapping is: strip `FOGWALL_`, lowercase, replace `_` with `.`:

```
FOGWALL_SERVER_PORT=9090              → server.port
FOGWALL_DATABASE_TYPE=postgres        → database.type
FOGWALL_SECRET__SCAN_ENABLED=false   → secret-scan.enabled
```

Use env vars for values that differ per-environment (secrets, hostnames, ports) and profile YAML files for structural
config (users, permissions, rules) that is too complex to express as a flat key-value pair.

### Hot-reload overlay

The `reload:` block configures a separate high-priority overlay that is re-read at runtime without restarting the
server. See [CONFIGURATION.md — Hot reload](CONFIGURATION.md#hot-reload) for the full reference.

The overlay file path can be a ConfigMap mount too:

```yaml
reload:
  file:
    enabled: true
    path: /app/conf/fogwall-runtime.yml
```

This lets operations teams push rule or permission changes by updating a ConfigMap and triggering
`POST /api/config/reload` — no pod restart needed.

---

## Network requirements

fogwall opens outbound connections to upstream SCM providers (GitHub, GitLab, Bitbucket, Gitea) from the **server**, not
from the developer's workstation. Your network team needs to allow egress from the proxy host, not from individual
developer machines.

### Outbound connections the proxy makes

| Path                                       | Library                  | Destination               |
| ------------------------------------------ | ------------------------ | ------------------------- |
| Server mode upstream push (HTTPS)          | JGit Transport (HTTPS)   | SCM provider git endpoint |
| Server mode upstream push (SSH)            | JGit Transport (SSH)     | SCM provider SSH endpoint |
| Transparent proxy forwarding               | Jetty HttpClient (HTTPS) | SCM provider git endpoint |
| SCM identity resolution (PAT verification) | Apache HttpClient 5      | SCM provider REST API     |
| SSH fingerprint lookup                     | Apache HttpClient 5      | SCM provider REST API     |

All paths must be able to reach the upstream SCM provider. A common operational mistake is opening the firewall for one
path but not the others — pushes appear to succeed locally but fail when the proxy tries to verify the committer's
identity via the API.

### Corporate HTTP proxy

If outbound internet access requires routing through a corporate HTTP proxy, set the standard environment variables
before starting fogwall:

```bash
export HTTPS_PROXY=http://proxy.corp.example.com:8080
export HTTP_PROXY=http://proxy.corp.example.com:8080
export NO_PROXY=localhost,127.0.0.1,*.internal.example.com
```

fogwall reads these at startup and configures all three outbound paths accordingly. No YAML config is needed.

When the configured proxy requires authentication, set `server.outbound-proxy.auth` in YAML — see
[Outbound proxy](CONFIGURATION.md#outbound-proxy) in the configuration reference for Basic and Kerberos options. NTLM is
not supported as a scheme fogwall speaks directly: it's a deprecated protocol, and Jetty's HTTP client (used for
transparent-proxy forwarding) has no NTLM support at all. Kerberos/Negotiate is the modern successor in Active-Directory
environments and is supported natively across all three outbound paths.

### Connectivity diagnostics (dashboard)

The dashboard admin panel includes a **Provider Connectivity** section (`Admin → Provider Connectivity`) that runs
layered outbound checks against each configured provider. Use this to generate a sharable diagnostic report for your
network team without requiring them to access server logs.

**Baseline check** (all providers): for each provider runs in sequence and stops at the first failure:

1. **TCP** — opens a socket to `host:port` (5 s timeout). Classifies the outcome as REFUSED, TIMEOUT, or RESET so a
   firewall DROP vs REJECT is immediately distinguishable.
2. **TLS** — completes the TLS handshake and reports the negotiated protocol, cipher suite, and peer certificate CN.
   Detects MITM/SSL-inspection appliances that swap the upstream certificate.
3. **HTTP** — sends `GET /` and records the HTTP status code and response time.

**Targeted check** (single provider + optional repo path): runs the same three steps, then adds:

4. **Git probe** — sends `GET /info/refs?service=git-upload-pack` and `GET /info/refs?service=git-receive-pack` with a
   `User-Agent: git/2.x.x` header. Any HTTP response (200, 401, 403 …) means the request reached the upstream — git URL
   patterns and the git user-agent are not being filtered. A TIMEOUT or RESET after TCP/TLS passed indicates a DLP
   appliance blocking git-specific traffic specifically.

The targeted check returns a structured `steps` log in the API response (`GET /api/admin/connectivity?provider=<name>`)
that can be copied directly into a ticket for the network team.

### DLP appliances and non-GET blocking

Some enterprises deploy DLP (Data Loss Prevention) appliances that inspect or selectively block outbound HTTPS traffic.
A common policy blocks anything other than GET requests to `github.com` or similar SCM hosts — this will prevent fogwall
from forwarding pushes upstream even if the proxy can reach the host.

Symptoms: clones through the proxy succeed, but pushes fail at the upstream forwarding step with a 403 or a TCP reset.
The git probe in the targeted connectivity check will show this as a TIMEOUT or RESET on the `git-receive-pack` step
after TCP and TLS both pass.

**Resolution:** work with your network team to allowlist the proxy server's egress IP for POST/PUT traffic to the SCM
provider's git endpoint. A transparent HTTPS inspection proxy (MITM) will also break JGit's certificate pinning — the
proxy host's egress IP should bypass SSL inspection, not just be allowlisted at the IP layer.

### Large pushes failing behind a reverse proxy (chunked transfer-encoding)

When fogwall is deployed behind a reverse proxy (HAProxy, nginx, a cloud load balancer), pushes with large packs (> 1
MiB) can fail with:

```
send-pack: unexpected disconnect while reading sideband packet
fatal: the remote end hung up unexpectedly
```

Server-side logs show `ParseGitRequestFilter` errors such as `EOFException: Short read of block` or
`Invalid packet line header`.

**Root cause:** git uses `Transfer-Encoding: chunked` for pushes exceeding `http.postBuffer` (default 1 MiB). Many
reverse proxies don't fully support chunked request forwarding — they may terminate the chunked stream early, dechunk
and rebuffer it, or split the body across multiple backend requests, so fogwall receives a truncated or malformed
request. Small pushes (< 1 MiB) use `Content-Length` instead and are unaffected, which is why this often shows up only
once a repo or commit grows past that size.

**Client-side workaround** — force git to send the pack as a single `Content-Length` request instead of chunked:

```bash
git config --global http.postBuffer 524288000
```

**Server-side workaround (nginx)** — ensure the proxy buffers the full request body before forwarding and allows a large
enough body size:

```
proxy_request_buffering on;
client_max_body_size 500m;
```

### Sizing memory for pushes

fogwall buffers each request body in memory for the life of the request, in both proxy modes — validation needs the
whole pack before it can decide anything. Two settings bound that, and they multiply:

| Setting                          | Default | Bounds                        |
| -------------------------------- | ------- | ----------------------------- |
| `server.max-push-bytes`          | 64 MiB  | how large one push may be     |
| `server.max-concurrent-requests` | 512     | how many run at the same time |

The worst case is `max-push-bytes × concurrent large pushes`, so **raising `max-push-bytes` means raising the
container's memory limit to match.** Do not set JVM heap flags to compensate: fogwall's image deliberately ships without
`-Xmx` so the JVM sizes its heap from the container's cgroup limit (about 25% of it by default). Setting `-Xmx` yourself
overrides that and pins the heap regardless of how the container is sized. Give the container more memory instead.

A push over the limit is rejected before the body is read, so it costs no memory and the developer gets a clear message
naming the limit rather than a timeout or a connection reset.

**Interaction with `http.postBuffer`.** The client workaround above raises the threshold at which git switches to
chunked encoding; it does not change how large a push may be. A push under `http.postBuffer` declares a
`Content-Length`, which lets fogwall reject an over-size push without reading anything. Above it, the push is chunked
and fogwall counts bytes as they arrive instead. Both paths enforce the same limit.

**If 64 MiB is too small for your estate**, prefer these over raising the limit:

- Seed one-off imports and repository migrations directly upstream, then let the proxy handle incremental pushes. A
  migration is a coordinated, one-time event and does not need to be self-service.
- Push large histories in stages — older commits first, then newer.
- Keep large binaries out of git history in the first place. Note that **Git LFS is not currently supported through
  fogwall** (see the User Guide); LFS uploads are refused because fogwall cannot inspect content that travels outside
  the git protocol.

### Sizing disk for pushes

Received pack data is inflated into a per-push quarantine directory on disk before validation runs, and `max-push-bytes`
caps only the _compressed_ wire size. `server.max-object-size-bytes` (default 128 MiB) caps what any single object may
inflate to, which stops the cheap decompression-bomb case, but there is no total-decompressed limit: a pack split across
many highly-compressible objects can still inflate to roughly `max-push-bytes × 1000` on disk in the worst case before
it is rejected. Quarantine directories are deleted when the request ends, so this is transient pressure, not growth —
but the volume holding the quarantine (the working directory by default) should be sized, or quota'd, with that worst
case and `max-concurrent-requests` in mind rather than assuming pushes stay near their wire size.

### Local mirror clone depth

fogwall keeps a local bare mirror of each upstream repo to inspect push content. Its clone depth is configurable per
proxy mode under `cache:` (see [CONFIGURATION.md](CONFIGURATION.md#local-mirror-cache)). Server mode defaults to full
history; the transparent proxy defaults to a shallow clone, because a first full clone of a very large repository
through the proxy can exceed HTTP connection timeouts. If proxy-mode first-clones are timing out for a large repo, keep
it shallow (the default) or tune `cache.proxy.shallow-since`; if you want the proxy to mirror full history and can
absorb the first-clone cost, set `cache.proxy.clone-depth: 0`. A shallow default is safe: reachability and hidden-commit
checks deepen the mirror to full history on demand before deciding.

### Inspecting and invalidating the local mirror cache

The **Admin → Local mirror cache** page (requires `ROLE_ADMIN`) shows the mirrors each mode currently holds — server
mode and transparent proxy are listed separately — with each mirror's upstream URL, on-disk size, ref count (expandable
to the branches and tags present), when it was first cloned, and when it last fetched upstream. Two actions are
available: **Invalidate** removes one mirror, and **Invalidate all** clears a mode's cache. Either way the local clone
is deleted and re-created from upstream on the repo's next push/fetch, so this is the fix for a mirror that has gone
stale or been poisoned (e.g. a failed upstream forward left objects upstream never received) — recovery that previously
required a pod restart. Invalidation is safe on a running server: it deletes the per-repo clone but keeps the cache
directory, and every invalidation is logged with the acting admin's login.

This state is **per-pod** — each pod serves its own in-memory cache, so the page reflects the cache of whichever pod
handled the request. To inspect or invalidate a specific pod's cache in a multi-pod deployment, reach that pod directly
(e.g. via `kubectl port-forward` to the pod). The same operations are exposed over REST under `/api/admin/cache` for
scripting.

---

## Production checklist

### Database

Default `h2-mem` loses all push records on restart. For production:

```yaml
# PostgreSQL — recommended
database:
  type: postgres
  url: jdbc:postgresql://db.internal:5432/fogwall?sslmode=verify-full&sslrootcert=/certs/ca.crt
  username: fogwall
  password: secret

# H2 file — zero external dependencies, persistent
database:
  type: h2-file
  path: /app/.data/fogwall

# MySQL / MariaDB — same config shape, different type
database:
  type: mysql # or mariadb
  url: jdbc:mysql://db.internal:3306/fogwall
  username: fogwall
  password: secret
```

`database.type` accepts `h2-mem` (default), `h2-file`, `postgres`, `mysql`, `mariadb`, or `mongo`. Schema is applied
automatically via Flyway on startup for the JDBC backends.

### TLS

Put fogwall behind a reverse proxy (nginx, Caddy, Envoy) for TLS termination in production. The application can also
terminate TLS directly if preferred — see [CONFIGURATION.md — TLS](CONFIGURATION.md#tls).

For upstream connections to internal GitLab/Bitbucket/Forgejo instances with a corporate CA:

```yaml
server:
  tls:
    trust-ca-bundle: /etc/fogwall/tls/internal-ca.pem
```

This merges the corporate CA with the JVM's built-in trust anchors so public providers (GitHub, GitLab SaaS) continue to
work without changes.

### Standalone server image (no dashboard)

The default `docker build .` produces the dashboard image (`FogwallDashboardApplication`) — proxy, REST API, approval
UI. For enforcement-only deployments that don't need the dashboard or approval UI (CI pipelines, automated
environments), build the lighter standalone server target instead:

```bash
docker build --target server -t fogwall-server .
```

This runs `FogwallJettyApplication` — the git validation and forwarding pipeline with YAML-driven configuration, no
Spring, no React/Node build step, no REST API. It uses the same config override mechanism as the dashboard image (mount
a `fogwall-{profile}.yml` at `/app/conf/`, set `FOGWALL_CONFIG_PROFILES`) and exposes the same port 8080.

```bash
docker run -e FOGWALL_CONFIG_PROFILES=docker-default \
  -v ./docker/fogwall-docker-default.yml:/app/conf/fogwall-docker-default.yml:ro \
  -p 8080:8080 fogwall-server
```

### Health check

The dashboard module exposes an unauthenticated health endpoint:

```text
GET /api/health   → 200 OK with status payload when the server is up
```

The standalone server module (`fogwall-server`) does not expose a health endpoint — use a TCP check against the proxy
port instead.

For Kubernetes (dashboard module):

```yaml
livenessProbe:
  httpGet:
    path: /api/health
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /api/health
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

### Session timeout

Default session lifetime is 24 hours. Tighten for compliance environments:

```yaml
auth:
  session-timeout-seconds: 28800 # 8 hours
```

### API key

The REST API accepts a single shared API key for machine-to-machine calls (e.g. approval scripts). Change the default
before going to production:

```yaml
# In config or via env var:
FOGWALL_API_KEY: "your-secret-key"
```

The shared key is a stopgap for automation until proper machine auth is available. It carries no user identity — all
calls made with it are unattributed. Prefer session-based access (log in as a named service account) for any automation
that needs an audit trail.

<!-- prettier-ignore-start -->
> [!NOTE]
> **Roadmap:** Per-user and per-service API keys, and an OAuth2 resource server mode for machine-to-machine auth, are tracked in [#57](https://github.com/RBC/fogwall/issues/57). Until then, treat the shared key as a temporary measure and rotate it regularly.
<!-- prettier-ignore-end -->

---

## SSH transport

_Available since v1.3.0._

fogwall can accept pushes over SSH on port 2222 (default). This is an alternative to the HTTP push path — not a
replacement. SSH transport and HTTP transport run side-by-side; a provider can be reached via either or both.

### Exposing SSH on the standard port

The container never binds port 22 directly — that would need root or `CAP_NET_BIND_SERVICE`, the same constraint that
already keeps the HTTP listener on plaintext 8080 behind your load balancer's TLS termination for 443. Apply the same
pattern for SSH: a plain TCP/L4 passthrough rule (external `:22` → the pod's `:2222`) needs no app or container change,
since SSH is a single TCP stream with no Host-header-style routing for an L7 proxy to key off. The Helm chart's
`sshService.*` values do this out of the box.

This matters for clients: Git's SCP-like shorthand (`git@host:owner/repo.git`, what GitHub's own
`git@github.com:owner/repo.git` uses) has no field for a non-default port — only the explicit `ssh://host:port/path`
form does. Without the port-22 passthrough above, your users are stuck with the explicit form (see
[Adding an SSH remote](USER_GUIDE.md#setting-up-ssh) in the user guide). With it, the shorthand form works unchanged,
since fogwall's own command parsing doesn't care which URL syntax the client's git produced it from.

### How SSH identity verification works

The SSH push path enforces the **same compliance guarantee** as the HTTP path — every push is tied to a verified SCM
user — but the mechanism is different because there is no token available:

1. **Inbound MINA auth (connection gate):** the client's public key must be registered in the pusher's fogwall profile
   (`ssh-keys`). This is equivalent to HTTP Basic auth — it authenticates the proxy user.
2. **SCM identity verification (compliance gate):** fogwall calls the provider REST API to fetch the SSH public keys
   registered by each SCM identity linked to the proxy user, then checks whether the connecting key's SHA-256
   fingerprint is among them. If it is, the push record's `scmUsername` is set to the matching SCM login. If it is not,
   **the push is blocked** — the same outcome as a failed token verification on the HTTP path.

Both steps are required. Step 1 alone is not sufficient — a key registered only in fogwall (but not on the SCM) will
clear MINA auth but fail step 2.

**Provider support:** fingerprint lookup is implemented for GitHub, GitLab, Forgejo, and Gitea. Providers that do not
implement this lookup (Bitbucket, generic proxy) will block all SSH pushes fail-closed. SSH is intentionally not
supported for those providers until a compliant identity verification path exists.

### Configuring a provider for SSH

_The single-entry model below is available since v1.4.0 (earlier releases required a separate `ssh://` provider entry)._

SSH transport is a property of a provider entry — the **same** entry serves both HTTP and SSH. Turn it on with an `ssh:`
sub-block. For a self-hosted Gitea instance:

```yaml
providers:
  gitea:
    type: gitea
    uri: https://gitea.corp.example.com # HTTP/API endpoint (also used for SSH-key identity lookup)
    api-token: <service-account-PAT> # see below
    ssh:
      enabled: true # also serve SSH; endpoint derived as ssh://git@gitea.corp.example.com
      # uri: ssh://git@gitea.corp.example.com:3022  # set explicitly for a non-standard SSH port or username
```

With `ssh.enabled: true` and no `ssh.uri`, the SSH endpoint is derived as `ssh://git@<host>` from the provider's HTTP
`uri`. Set `ssh.uri` explicitly when the upstream uses a non-`git` SSH username (GitHub Enterprise Cloud with data
residency uses the enterprise slug: `ssh://{slug}@{tenant}.ghe.com`) or a non-standard SSH port. The path clients use is
`ssh://fogwall-host:2222/<provider-host>/<org>/<repo>.git`, keyed on the provider's HTTP host.

Permissions, access rules, and SCM identities are all keyed by the single provider name and apply to both transports:

```yaml
permissions:
  - username: alice
    provider: gitea # one entry covers HTTP and SSH pushes
    match:
      target: SLUG
      value: /myorg/.*
      type: REGEX
    grant: PUSH
```

### SCM identity link for SSH

Because HTTP and SSH share one provider entry, a user needs only **one** `scm-identities` entry — it applies to both
transports. The provider ID is the provider's name in the `providers:` block:

```yaml
users:
  - username: alice
    scm-identities:
      - provider: gitea # applies to HTTP and SSH pushes alike
        username: alice-gitea
```

An identity linked via OAuth (see [SCM OAuth](#scm-oauth-account-linking)) likewise applies to both transports — a user
who links their account over HTTP can then push over SSH with no extra configuration.

### Upstream host key verification

When fogwall forwards an SSH push it authenticates to the upstream SCM using the developer's **forwarded SSH agent**.
The upstream host key is what binds that agent to the genuine provider, so fogwall verifies it and **fails closed by
default**: an unknown or changed upstream host key aborts the forward. (Without this, an attacker able to redirect the
upstream connection would receive the developer's forwarded agent — an account-takeover primitive.)

Trust is resolved in this order:

1. **Bundled defaults.** fogwall ships pinned host keys for its built-in hosts — github.com, gitlab.com, codeberg.org,
   bitbucket.org, gitea.com — so they work out of the box. Regenerate with `scripts/pin-ssh-host-keys.sh` when a
   provider rotates its key.
2. **Pinned in config (recommended for custom providers).** Pin a private/internal SCM's host key with a standard
   `known_hosts` line — globally under `server.ssh.extra-known-hosts`, or per-provider under that provider's
   `ssh.known-hosts` (scoped to its upstream, since known_hosts lines are host-keyed):

   ```yaml
   server:
     ssh:
       extra-known-hosts:
         - "git.internal.example.com ssh-ed25519 AAAA..."

   providers:
     gitea:
       uri: https://gitea.corp.example.com
       ssh:
         enabled: true
         known-hosts:
           - "gitea.corp.example.com ssh-ed25519 AAAA..."
         # known-hosts-path: /etc/fogwall/gitea_known_hosts  # or point at a file
   ```

3. **Operator-supplied file.** Point `server.ssh.known-hosts-path` at a `known_hosts` file. The container image bakes
   the bundled keys at `/etc/fogwall/known_hosts`; mount your own file there (or anywhere, and set the path) to add or
   rotate host keys **without upgrading fogwall**.
4. **Trust on first use (opt-in).** `server.ssh.trust-on-first-use: true` pins an otherwise-unknown host's key on the
   first connection — logged loudly with its fingerprint — and rejects a later change. Convenient for internal providers
   on a trusted network whose key can't be pinned ahead of time; it is **not** a substitute for pinning across an
   untrusted network. Default is `false` (unknown key rejected).

Effective trust is the union of the bundled/configured file, the inline `extra-known-hosts`, and any TOFU-pinned keys.

### The `api-token` requirement

The provider REST API is called to fetch SSH public keys for registered SCM identities. GitHub's endpoint
(`GET /users/{login}/keys`) is public — no token is needed. Forgejo and GitLab require authentication when the instance
is configured with `REQUIRE_SIGNIN_VIEW=true` (common in corporate deployments where the git server is not publicly
accessible).

Create a service account on the upstream SCM and generate a PAT with `read:user` scope (Forgejo) or `read_user` scope
(GitLab). This account does not need repository access — it only needs to list user SSH public keys. Set the token in
the provider config:

```yaml
providers:
  gitea:
    type: gitea
    uri: https://gitea.corp.example.com
    api-token: <service-account-PAT>
    ssh:
      enabled: true
```

There is no environment variable override for `api-token` (the env var mechanism does not support hyphenated config
keys). Use a profile config file to supply the token outside of the checked-in base config:

```yaml
# /app/conf/fogwall-local.yml  (mounted into the container, not committed)
providers:
  gitea:
    api-token: gta_xxxxx
```

### `api-uri` — when it is needed

The provider's `uri` is the HTTP/HTTPS endpoint, so the REST API base is derived from it directly and no `api-uri` is
needed in the normal case.

`api-uri` is only required when the HTTP API runs on a non-standard port on the same host — for example a local
development Gitea where HTTP is on 3000 and SSH on 3022:

```yaml
gitea:
  type: gitea
  uri: http://localhost:3000
  api-uri: http://localhost:3000
  ssh:
    enabled: true
    uri: ssh://git@localhost:3022
```

### Requiring agent forwarding

Fogwall uses the client's forwarded SSH agent to authenticate outbound SSH connections to the upstream SCM. The client
**must** connect with `ssh -A` (or `ForwardAgent yes` in `~/.ssh/config`). If agent forwarding is absent, the push is
blocked with a clear error:

```text
error: SSH agent forwarding required — connect with 'ssh -A' or set 'ForwardAgent yes' in ~/.ssh/config
```

There is no configuration to disable this requirement — fogwall never reads local identity files for upstream auth.

---

## SCM OAuth account linking

_Available since v1.4.0._

Lets developers link their proxy account to an upstream SCM identity via OAuth from their profile page, instead of
typing a free-text SCM username. See [CONFIGURATION.md — SCM OAuth](CONFIGURATION.md#scm-oauth) for the full config
reference; this section covers operator setup steps and operational behaviour.

### Registering a GitHub App

fogwall's OAuth linking flow works with a **GitHub App** (GitHub's currently recommended integration type) — it does not
need a classic OAuth App.

1. Create the app under your org's GitHub settings (or a personal account, for testing).
2. **Account permissions** — grant exactly:
   - **Email addresses**: Read-only
   - **Git SSH keys**: Read-only
3. **Callback URL**: `https://<your-fogwall-host>/api/scm-oauth/<provider-name>/callback`, where `<provider-name>` is
   the top-level `providers:` key this app's `oauth:` block is nested under — e.g. `github`, not the literal string
   "github.com". This is always your fogwall host, regardless of whether the provider instance points at github.com, a
   GHEC-with-data-residency `*.ghe.com` tenant, or a self-managed GHES host — only the _outbound_
   authorize/token/user-API calls fogwall makes differ by host, not where GitHub calls back to. `<your-fogwall-host>` is
   exactly `server.service-url` (the bare origin, no path suffix — see the breaking-change note below if you're
   upgrading from a pre-1.4.0 release).
4. Generate a client secret and note the client ID. **No private key is needed** — a GitHub App's private key
   authenticates the app/installation itself (server-to-server), which this user-to-server linking flow never uses; only
   the client-id/client-secret pair is used, for the token exchange.
5. Install the app on your GitHub org (or your personal account, for testing) so it can be authorized by member
   accounts.

Repeat with a second, separately registered app for each additional GitHub-type provider instance you run (e.g. one app
for github.com/GHEC, a second for a `*.ghe.com` tenant) — each needs its own client-id/secret, set under that distinct
`providers:` entry's own nested `oauth:` block.

### Registering a GitLab OAuth application

Under **User Settings → Applications** (or your GitLab instance's admin area for an instance-wide app): set the same
callback URL shape as above, and check the **`read_user`** scope. fogwall's authorize request always asks for exactly
this scope for a GitLab-type provider — it isn't configurable, since linking never needs anything broader.

### Registering a Forgejo/Gitea OAuth application

Under the instance's own **Settings → Applications** page: register an OAuth2 application with the same callback URL
shape as above, and note the generated client ID/secret. Request the `read:user` scope — fogwall's authorize request
always asks for exactly this for a `forgejo`-type provider, same as GitHub/GitLab, and it isn't configurable.

This works against a self-hosted Forgejo/Gitea instance you administer, and also against Codeberg — its OAuth2
applications live under **Settings → Applications → Manage OAuth2 Applications** (or under an organization's own
settings, for an org-owned app), same flow as any other Forgejo instance.

### What `strict` identity mode changes operationally

With `scm-oauth.identity-mode: strict`, `CheckUserPushPermissionHook` only honors OAuth-verified SCM identities for push
authorization — on both HTTP and SSH transports. A user whose only SCM identity is manually/free-text entered (or who
hasn't linked one at all) gets a clear push-time rejection pointing them at the profile page to link via OAuth. There is
no fallback to permissive behavior if OAuth linking becomes unavailable (see token encryption key handling below) — the
two are deliberately decoupled: a token-encryption problem disables the _link/callback_ endpoints, never push
authorization, so `strict` mode's guarantee can't be silently weakened by an infrastructure fault.

`POST /api/me/identities` (manually adding an SCM identity) is also disabled in `strict` mode, both in the dashboard UI
and server-side on the endpoint itself — a manually-entered identity would never actually be usable for push
authorization in this mode, so allowing it to be added would only create a confusing dead state. This is narrower than
it might sound: it does **not** affect `POST /api/me/emails` — commit-author-email verification is governed by the
independent `commit.attribution-policy` setting, not `scm-oauth.identity-mode`.

### What happens on unlink

`DELETE /api/scm-oauth/<provider>/unlink` (the "Unlink" button in the profile page's SCM Identities tab) removes:

- the verified SCM identity itself
- the stored OAuth token (with a best-effort revocation call to the provider)
- any SSH keys that were imported from that provider
- any emails that were imported from that provider's verified-emails list

If the same SSH key or email was also verified by a second linked provider (e.g. the same key registered on both GitHub
and GitLab), unlinking one only removes that provider's claim on it — the key/email stays registered, now attributed
solely to the remaining provider(s), and is only fully removed once no linked provider claims it anymore. Re-linking is
always available to restore the identity and re-import SSH keys/emails if needed.

### Production checklist addition: token encryption key

See [Production checklist](#production-checklist) below for database/TLS. For SCM OAuth specifically: generate a 32-byte
key and mount it as a secret rather than relying on the local-devex auto-generated fallback:

```bash
openssl rand -base64 32 > fogwall-scm-oauth-key
```

```yaml
scm-oauth:
  token-encryption-key-path: /run/secrets/fogwall-scm-oauth-key
```

If this is left unset, fogwall auto-generates and persists a key under `./.data/` and logs a loud `WARN` on every
startup — fine for local development, but that file may not survive a container restart/redeploy in production. If lost,
every linked user simply needs to re-link (push authorization is never affected).

---

## Proposals

_Available since v1.4.0, opt-in per provider._

Extends fogwall past `git push` into the rest of the contribution lifecycle — proxying the `gh` CLI's issue/PR
create-edit-comment-review traffic through the same identity resolution, permission engine, and audit trail as the
git-push path. See [CONFIGURATION.md — SCM API proxy](CONFIGURATION.md#proposals) for the full config reference and
[docs/internals/SCM_API_PROXY.md](internals/SCM_API_PROXY.md) for the design rationale; this section covers what an
operator needs to understand before turning it on.

### Token model and the egress assumption

Developers bring their own personal access token. fogwall forwards it upstream unchanged after inspecting the request,
and never mints or supplies a credential for this path.

[SCM OAuth](#scm-oauth-account-linking) is a separate mechanism, for fogwall-managed operations — today the
account-linking UI, later potentially fogwall acting on a user's behalf. It does not provide a token for a CLI.

Enforcement is **content interception**, not the credential. So what you get here depends on something fogwall does not
provide: **direct access to the SCM API has to be blocked elsewhere**, or a developer can bypass the proxy with the same
token. fogwall governs the sanctioned API host and inspects what goes through it; organization-wide egress control is
better served by traditional web proxies and network security appliances.

The git-push path already works this way — a developer with a valid PAT can `git push` straight to github.com if nothing
stops them.

### Enabling it

One switch, off by default, per provider — plus the port that listener will bind:

```yaml
providers:
  github:
    proposals:
      enabled: true
      port: 9443 # required — see "Each provider needs its own port" below
```

**Each enabled provider needs its own port.** The SCM API proxy does not share the main fogwall server port that serves
git traffic, and does not sit under a URL path: the dialect is mounted at the root of a dedicated listener
(`/api/graphql` for GitHub, `/api/v4/*` for GitLab, `/api/v1/*` for Gitea/Forgejo). That is forced by the clients — `gh`
and `fj` address the API from the host root and silently discard any path prefix — and a single shared listener would
collide between two instances of the same platform, since every GitLab claims `/api/v4`. fogwall refuses to start if a
provider has `proposals.enabled: true` with no port, rather than opening a listener no CLI could reach. Developers are
then given a host and port; see [docs/USER_GUIDE.md](USER_GUIDE.md#proposals-prmrs-through-fogwall).

Optionally add `require-known-cli: true` to refuse callers whose `User-Agent` isn't one of the four recognised SCM CLIs
— browsers, bare `curl`, unrecognised automation. The raw header is recorded on every audit record either way, which is
how you spot a CLI upgrade changing its wire format.

> [!WARNING] `User-Agent` is set by the client and can be forged. This is hardening, not a security control — it can
> only deny requests that would otherwise be allowed, and never grants anything.

### TLS on the proposals listeners

**Every one of these ports has to be reachable over HTTPS.** `gh`, `glab`, `tea` and `fj` all address a custom host over
HTTPS and give you no way to ask for plain HTTP, so a plaintext listener is unreachable by the tools it exists to serve.
TLS must terminate somewhere in front of it — you have two shapes:

- **Terminate at the edge.** An ingress, route, or load balancer per provider port, with fogwall's listeners left on
  plain HTTP behind it. This is the usual Kubernetes/OpenShift shape: one Service exposing the proposals ports, one
  Ingress per provider, each with its own hostname and certificate.
- **Terminate at fogwall.** Configure [`server.tls`](CONFIGURATION.md#tls) and every proposals listener inherits it
  automatically — same certificate, its own port. There is no per-provider TLS block to configure: the certificate is
  issued per hostname and these listeners differ only by port. If you give each provider its own hostname _and_
  terminate at fogwall, the certificate's SANs must cover all of them.

fogwall can't tell whether something upstream is terminating TLS for it, so it doesn't guess: with `server.tls` unset it
logs a warning at startup naming each plaintext listener. That warning is expected and harmless in the edge-termination
shape. Treat a CLI reporting a connection or handshake error against a proposals port as this, until ruled out.

If you terminate at fogwall with a certificate from an internal CA, the CLIs are Go binaries and will need that CA in
their trust store (or `SSL_CERT_FILE` pointing at it) — worth saying in whatever you hand developers.

**If you terminate at the edge, check that your ingress does not decode or normalise the request path.** GitLab
addresses a project as a single `owner%2Frepo` segment, and Gitea encodes a repository-relative file path into one
segment of its blob endpoints. Both encoded slashes have to reach fogwall intact: decoded, the segment splits and the
request names a different repository, which fogwall refuses. nginx-ingress changes path handling once a `rewrite-target`
with a capture group is involved; HAProxy-backed OpenShift Routes are generally pass-through.

The failure mode is narrow enough to be confusing — GitLab denied or 404ing while GitHub works fine — so confirm it
rather than assume it. `curl` a project path with an encoded slash through the ingress and check what fogwall logs as
the request URI.

### What the allowlist permits

Enabling a provider does not expose its API. fogwall forwards a fixed set of operations, held in code rather than
configuration, and denies everything else:

| permitted                                    | denied                                                        |
| -------------------------------------------- | ------------------------------------------------------------- |
| issue create, edit, close, comment           | submitting a review, approving                                |
| PR/MR create, edit, close, comment           | merge                                                         |
| label, assignee and reviewer-request changes | release, tracked-time, dependency and project-board endpoints |

Labels, assignees and reviewers are permitted whichever way the CLI sends them — as fields on the create, or as the
separate follow-up call each CLI makes when the same attribute is changed by an edit. **Requesting** a review is
permitted; **submitting** one is not, and they are different endpoints. See [Authorization](#authorization) for the full
scope of a `PROPOSE` grant.

Anything the allowlist does not recognise is denied, so a CLI reaching a new endpoint after an upgrade is refused rather
than forwarded. Per-CLI command names are in [USER_GUIDE.md](USER_GUIDE.md#proposals-prmrs-through-fogwall); the
endpoint and mutation tables behind them are in [docs/internals/SCM_API_PROXY.md](internals/SCM_API_PROXY.md).

### Authorization

Per-repo authorization for mutations goes through the ordinary `permissions:` mechanism — grant a user `PROPOSE` on the
repos they should be able to file issues/PRs against (see
[CONFIGURATION.md — Permissions](CONFIGURATION.md#permissions)). `PROPOSE` is its own grant: a user can hold it without
push access, or hold `PUSH` without it.

It covers the full request surface of the allowlisted endpoints on a matching repo, not only title, body and comment —
and not only _fields_. Where a CLI changes an attribute through its own call rather than a field, that call is
allowlisted too and authorized against the same repo: GitHub sends `replaceActorsForAssignable` for any `--assignee` and
`requestReviewsByLogin` for any `--reviewer`, and Gitea reaches `POST /issues/{n}/labels` for `--add-labels`. Both forms
are behind one of the CLIs' own flags (`glab mr update --target-branch`, `gh pr edit --base`, `--add-assignee`,
`--add-label`, `--milestone`, `--lock-discussion`), and `tea` PATCHes the whole object on every edit. Three
consequences:

- A pull/merge request's **base branch** can be retargeted, always within the same repository — no allowlisted edit
  endpoint takes a repository-valued field, so a proposal cannot be moved elsewhere.
- A few associations reach **beyond the repo**: GitHub projects are org-level, and on GitLab a milestone or epic can be
  group-level. (GitHub and Gitea milestones are repo-scoped.) This is the only effect not confined to the matched repo.
- One command is often **several audited operations**. `gh pr create --label --assignee --reviewer` is a create plus
  three follow-up calls, each authorized against the same repo and recorded separately, so the audit trail holds more
  rows than the developer ran commands.

Merge, and submitting or approving a review, stay out of reach: separate endpoints, none allowlisted. Requesting a
reviewer is permitted — a different operation from giving the verdict.

### Content inspection

The prose a proposal carries — a pull/merge request title and description, a comment body — is inspected before it is
forwarded, against three sets of rules: the blocked literals and patterns in `proposals.block`; gitleaks, when
`secret-scan.enabled` is on; and the built-in PII/identifier bundles, when `content-patterns.enabled` is on with at
least one bundle selected. `proposals.block` is separate from `diff-scan.block`: one governs pushed diffs, the other
proposal content. Secret scanning and the pattern bundles are shared with the push path — neither is diff-specific.

This is not optional hardening. Without it, a contributor blocked from _pushing_ a secret can paste the same secret into
a pull request description and fogwall relays it verbatim.

Inspection reads the **whole request body**, not a list of known fields: every key and scalar in the JSON, at any depth,
plus the raw bytes. The raw reading covers anything an extractor does not name — a dialect gaining a new prose field
stays covered — while the decoded reading defeats escaping, since a token written as an escape sequence matches nothing
as raw text but is plain once decoded. GitHub adds a third reading, the GraphQL query's own literals: a GraphQL request
wraps its query in JSON, so decoding the transport leaves GraphQL's own string escaping intact, and arguments inlined in
the query text never appear as JSON values at all.

A content violation is recorded as `REJECTED`. `DENIED` is for operations that are not allowlisted, or that the caller
holds no `PROPOSE` grant for.

```yaml
proposals:
  block:
    literals:
      - "internal.corp.example.com"
    patterns:
      - '(?i)https?://[a-z0-9.-]*\.corp\.example\.com\b'
```

With no `proposals.block` entries configured, only secret scanning applies.

Secret scanning **fails closed here**, unlike the push path: if scanning is enabled but the scanner cannot run, the
proposal is refused. A push that slips through is still recorded and reviewable afterwards, whereas a forwarded proposal
has already published its text upstream where fogwall cannot reach it.

#### PII bundles block here, rather than warning

Content-pattern bundles (`content-patterns.bundles` — SIN, SSN, NINO and the rest) are
[WARN-only on the push path](CONFIGURATION.md#content-pattern-scanning): a match is surfaced to the human reviewer every
push already requires, and never blocks. A proposal has no such reviewer — it is forwarded or refused — so a warning
recorded against a description that is already upstream is not a control. A match therefore refuses the proposal,
recorded as `REJECTED` alongside the data type and jurisdiction. The matched value itself is never written to the audit
record; it is the thing the rule exists to withhold.

Set `content-patterns.scan-proposals: false` to keep bundle scanning on pushes while leaving proposals to
`proposals.block` and secret scanning alone.

When content inspection is what refused a request, the request variables are deliberately **not** stored on the audit
record. The offending text is the payload, so keeping it would put the secret fogwall just blocked into fogwall's own
database; the recorded reason still names the rule that matched, with the matched value redacted by the scanner.

### Why reads and mutations are gated differently

Mutations get real per-repo enforcement, checked against that user's `PROPOSE` grants — how the target repo is
determined differs by dialect: GitHub's GraphQL mutation carries only an opaque node ID, resolved to `owner/repo` via a
cache (TTL is itself a security parameter — see CONFIGURATION.md); GitLab's REST calls carry `owner/repo` directly in
the URL, so no resolution step is needed. Reads (`gh issue list`, `glab mr list`, etc.) are **not** individually
resolved or permission-checked in any dialect — they are forwarded for any authenticated caller, which keeps read
traffic cheap. Per-repo read gating is not currently implemented.

### Audit trail

Every proxied **mutation** produces one audit record — who, the resolved repo, the operation performed, and the
allow/deny outcome — following the same auditability bar as the push path. These are viewable in the dashboard under
**SCM API** (a plain list, no approval workflow — these are already-decided audit records), or queryable directly from
the `scm_api_action_records` table/collection.

A **refused** request is recorded too, once the caller has been authenticated — including one fogwall turned away
because the endpoint matched no allowlist rule, where there is no operation to name and `mutation_field` is null (the
reason carries the method and path instead). That case is how you notice a CLI upgrade has started calling an endpoint
the allowlist doesn't know: the failures show up here, attributed to a user and a client version, rather than reaching
you as a bug report.

Two things are deliberately **not** recorded. Successful **reads** produce no record, which is what keeps the read path
close to pass-through. Neither does anything refused _before_ authentication — an unrecognised client, or a token that
resolves to no user — matching the push path, where a request rejected before it parses as a push writes no push record.
It also means writing to the audit trail costs valid credentials, so it can't be filled by an anonymous caller.

Reads are also not restricted by path: a `GET` against the provider's API, or a GraphQL `query`, is forwarded for any
authenticated caller. A read changes nothing upstream, and it returns only what the caller's own token would return
asking the provider directly — fogwall relays that token and grants nothing on top of it. What it does mean is that **if
fogwall is the sanctioned route to a self-hosted provider inside your perimeter, read traffic through it is not in
fogwall's audit trail.** The provider's own access logs are the record of what was read. Worth knowing you are relying
on them, rather than discovering it during an investigation.

---

## Common operational problems

### Push is rejected with "repository not permitted"

Check both layers:

1. Is the repo in `rules.allow`? Verify the slug matches exactly (including leading `/`).
2. Does the user have a `permissions` entry for this provider + path with `grant: PUSH`?

### Push hangs waiting for approval indefinitely

The server is in `ui` mode and no reviewer has approved the push. Either:

- Any authenticated user (other than the pusher) can open the push record and approve it in the dashboard.
- Or grant the pusher `SELF_CERTIFY` permission so they can approve their own clean pushes.

If `require-review-permission: true` is set, only users with an explicit `REVIEW` permission entry for the repository
can approve.

### Push blocked: identity not linked

The proxy cannot match the token to a registered proxy user. This check is always enforced — `attribution-policy` mode
does not affect it. Check:

1. Does the user's profile have an `scm-identities` entry for the correct provider?
2. Does the token have the required API scope to call `GET /user`?

### SSH push rejected: SSH key not linked to any SCM identity

The fingerprint of the connecting SSH key was not found among the keys registered on the upstream SCM for the linked SCM
identity. Possible causes:

1. The key is in fogwall (`ssh-keys`) but not on the upstream SCM account. Have the user add it in their SCM account
   settings.
2. The linked `scm-identities` entry refers to the wrong provider or username. The provider must be the SSH provider
   name (e.g. `gitea-ssh`), not the HTTP provider name (`gitea`).
3. The `api-token` is missing or has expired, causing the key lookup to return an empty list. Check the server log for
   `Failed to fetch SSH keys for ... user '...'` and renew the token.

### SSH push rejected: SSH identity verification not supported by provider

The provider used for this push does not implement SSH fingerprint lookup (e.g. `type: generic`). The SSH path is
fail-closed — pushes are blocked unless the provider can verify the connecting key against the SCM user's registered
keys. Switch to a supported provider type (`forgejo`, `gitlab`, or `github`). Opt-in fail-open behaviour for unsupported
providers is planned as a follow-up feature.

### Push blocked or warned: commit email mismatch

One or more commit author/committer emails are not registered to the authenticated user. This is controlled by
`attribution-policy`:

- In `strict` mode the push is blocked. Check that the user's email list includes the address they commit with
  (`git config user.email`), and that the commits were not authored by someone else.
- In `warn` mode the push goes through but the mismatch is logged and visible in the push record. Switch to `strict`
  once you are confident emails are populated for all users.

### SSH push still blocked after adding a key to the SCM account

The SSH fingerprint enricher caches results per `(provider, scm-login)` with a 7-day TTL. If a user registers a new SSH
key on their SCM account after the cache was last populated, the new fingerprint will not be visible until the entry
expires or the server restarts. To force an immediate re-fetch without a restart, the operator can reload config (if
live reload is configured) or restart the server. Future pushes from that user will populate a fresh cache entry.

The same applies when a key is removed from the SCM account — the old fingerprint remains cached until TTL expiry.

### OIDC login fails / redirect loop

1. Enable the Spring Security debug profile (`docker/log4j2-debug.xml`) — see
   [Debug profiles](#debug-profiles-by-problem-area).
2. Check the redirect URI registered in the IdP matches `https://<your-host>/login/oauth2/code/fogwall` exactly.
3. For Entra ID: make sure `issuer-uri` ends in `/v2.0` and `skip-user-info: true` is set — see the
   [Entra ID section in CONFIGURATION.md](CONFIGURATION.md#entra-id-azure-ad). `jwk-set-uri` is **not** needed for
   Entra: it is a plain endpoint override and no longer changes how tokens are validated.

### OIDC login fails with "Claim '…' not present in the ID token"

The configured `auth.oidc.user-name-attribute` names a claim your IdP did not include. The OIDC spec guarantees only
`sub` in an ID token — `email` and the rest are voluntary, and IdPs differ in what they send (Entra ID, for example,
omits `email` unless the optional claim is added to the app registration). Either configure the IdP to include the
claim, or point `user-name-attribute` at one it actually sends. The warning in the application log lists the claims that
were present.

### Upgrading from a pre-1.2.0 deployment: OIDC redirect URI mismatch (AADSTS50011)

The project was renamed in 1.2.0, which changed the Spring Security OAuth2 registration ID from `gitproxy` to `fogwall`.
This shifts the callback URL that fogwall sends to the IdP in the authorization request:

| Version | Redirect URI sent to IdP                    |
| ------- | ------------------------------------------- |
| < 1.2.0 | `https://<host>/login/oauth2/code/gitproxy` |
| ≥ 1.2.0 | `https://<host>/login/oauth2/code/fogwall`  |

**Fix:** add the new URI to your IdP app registration alongside the existing one. In Entra ID: App registrations → your
app → Authentication → add `https://<host>/login/oauth2/code/fogwall` as a redirect URI. Both URIs can coexist — remove
the old one once all deployments are on 1.2.0+.

### Upgrading from a pre-1.4.0 deployment: `server.service-url` no longer includes `/dashboard`

Before 1.4.0, `server.service-url` was expected to already carry whatever path prefix your reverse proxy or load
balancer put the dashboard behind (typically `https://<host>/dashboard`), and fogwall concatenated routes directly onto
it. As of 1.4.0 (introduced alongside SCM OAuth account linking, #40, which needs to build a callback URL for a REST
endpoint that isn't under the dashboard's own path) `service-url` must be the bare origin instead — fogwall appends
`/dashboard`, `/api`, etc. itself.

**Fix:** if your existing `service-url` ends in `/dashboard` (or any other path), drop that suffix. This is not optional
to skip — leaving the old value in place means push-record links and the "identity not linked" hint in sideband messages
point at the wrong path (`.../dashboard/dashboard/push/<id>`, a 404), and OAuth linking's callback URL registered with
your GitHub App/GitLab OAuth app won't match what fogwall actually sends.

### Gitleaks produces no output / scan appears to be skipped

Check `logs/application.log` for lines containing `gitleaks`. The log will show which binary path was resolved and
whether the scan ran. If the binary cannot be executed (permission denied, noexec mount), the proxy falls back to
skipping the scan rather than failing the push — add `gitleaks` to PATH or set `scanner-path` explicitly.

### Push fails after approval with an upstream error (404, 403, etc.)

Once a push passes validation and is approved, the proxy forwards it to the upstream SCM transparently — no further
processing occurs. Any error from the upstream is passed straight back to the git client exactly as if the developer
were pushing directly.

Common upstream errors and their causes:

| Error                                    | Likely cause                                                                                                                                                                                                                |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Repository not found` / 404             | The token does not have access to the repository. GitHub returns 404 (not 403) for both missing repos and insufficient permissions on private repos — this is intentional on GitHub's part to avoid leaking repo existence. |
| `403 Forbidden`                          | The token has repo access but lacks the required write scope (e.g. a fine-grained PAT missing `Contents: write`).                                                                                                           |
| `pre-receive hook declined`              | The upstream has its own server-side hooks that rejected the push. Nothing the proxy can do — the developer needs to resolve it upstream.                                                                                   |
| `remote: error: GH006: Protected branch` | The target branch has branch protection rules on the upstream. Again, upstream-side — not a proxy issue.                                                                                                                    |

These errors appear in the developer's terminal and in the push record in the dashboard. They are not logged as proxy
errors — from the proxy's perspective the forwarding succeeded.

**Diagnosing token scope issues:** if a push consistently fails with 404 or 403 immediately after approval, ask the
developer to test the same push directly (bypassing the proxy) with the same token. If it also fails direct, the problem
is the token — not the proxy.

### `Permission denied` on startup in a container

JGit failed to write to `$HOME` or `/tmp`. Verify:

```bash
docker exec <container> sh -c 'ls -la $HOME && touch $HOME/.probe && rm $HOME/.probe'
docker exec <container> sh -c 'touch /tmp/.probe && rm /tmp/.probe'
```

If either fails, see [JGit filesystem requirements](#jgit-filesystem-requirements) above.
