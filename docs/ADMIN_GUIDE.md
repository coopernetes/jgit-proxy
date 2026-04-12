# Administrator and Operator Guide

This guide covers deploying, configuring, and operating git-proxy-java. It is written for the person responsible
for running the proxy — setting up user accounts, configuring providers and rules, diagnosing problems, and keeping
the service healthy.

For the YAML configuration reference, see [CONFIGURATION.md](CONFIGURATION.md).
For developers pushing through the proxy, see [USER_GUIDE.md](USER_GUIDE.md).

---

## Conceptual model: three independent layers

Before diving into configuration details, it helps to understand that access control in git-proxy-java is three
orthogonal layers that all must pass before a push is forwarded:

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

A push fails at the first layer that rejects it. A common misconfiguration is to add a repo to `rules.allow` but
forget to add a `permissions` entry for the user — or vice versa. Both are required.

**Access rules** are site-wide policy: they determine what the proxy will route at all, independently of who is
pushing. Think of them as a firewall rule list.

**User permissions** are per-user grants scoped to a provider and path. They determine whether a particular
authenticated user is permitted to push to (or review) a particular repository.

**Commit validation** runs against the push content: author email domains, commit messages, diff scanning, secret
scanning. These apply to everyone regardless of permissions.

---

## User accounts

git-proxy-java supports four authentication backends. **LDAP, AD, and OIDC are the expected production choices.**
Local auth manages users in the database (add/remove users, reset passwords via the dashboard) with passwords
defined in YAML config. It is self-contained and requires no external directory, but every user must be
provisioned manually. It is suitable for small teams or single-operator deployments; LDAP, AD, or OIDC are
preferable when the org already has a directory.

### Authentication backends

| Backend | `auth.provider` | When to use |
| ------- | --------------- | ----------- |
| Local (static) | `local` | Dev / demo only. Passwords in YAML config. |
| LDAP | `ldap` | Generic LDAP directory (OpenLDAP, 389 DS, etc.) |
| Active Directory | `ad` | On-premises AD domain. UPN bind, no `user-dn-patterns` needed. |
| OIDC | `oidc` | Keycloak, Okta, Entra ID, Dex, etc. |

See [CONFIGURATION.md — Authentication](CONFIGURATION.md#authentication) for the full config reference and
worked examples.

### How users are provisioned per backend

**Local:** users are defined entirely in the `users:` YAML block. Each entry needs a username, BCrypt password
hash, and at least one email. Roles and SCM identities are set here too. Changes require a config reload.

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

**LDAP / AD:** users are provisioned automatically on first login. The proxy creates a user record from the
directory attributes returned at bind time. The `mail` attribute (if present) is stored as a locked email —
locked means it cannot be edited from the profile UI, since the directory is the source of truth. Roles are
assigned via `auth.role-mappings` (LDAP group CNs → role names). When `role-mappings` is configured, a user
who does not match any mapped group is **denied access entirely** — they authenticate successfully against the
directory but are refused by the proxy. This is intentional: the proxy is not open to all directory users by
default. To grant baseline access, map a broad group (e.g. all-staff) to `USER`.

SCM identities and permissions still need to be set up after first login — either by the user themselves from
their profile page, by an admin via the dashboard, or via a supplemental `users:` YAML entry (which can carry
`scm-identities` without a `password-hash` for IdP-authed users).

**OIDC:** same auto-provisioning and deny-by-default behaviour as LDAP. Groups from the configured
`groups-claim` (default: `groups`) are mapped to roles via `auth.role-mappings`. Email comes from the `email`
claim in the ID token. Users whose token carries no matching group claim are denied access.

### Dashboard roles

Roles control what a user can do in the dashboard and REST API:

| Role | What it grants |
| ---- | -------------- |
| `USER` (default) | View push records; approve or reject pushes they have `REVIEW` permission on; manage their own profile (emails, SCM identities) |
| `ADMIN` | Everything USER can do, plus: create/delete users, reset passwords, manage any user's profile, view all push records |
| `SELF_CERTIFY` | Grants the **capability** to self-approve pushes. This is the prerequisite gate — it must be present before any per-repo `SELF_CERTIFY` permission takes effect. |

`ROLE_USER` is granted to every authenticated user automatically when no `role-mappings` are configured
(open mode). When `role-mappings` are configured, access is deny-by-default — a user must belong to at
least one mapped group or they are refused login entirely. Map a broad group to `USER` to grant baseline
access to all directory members.

`ROLE_SELF_CERTIFY` is the prerequisite gate for self-approval. It represents the capability, attested by
your org's IdP or IAM process. Self-approval requires **both** this role and a per-repo `SELF_CERTIFY`
permission entry — neither alone is sufficient. This separation lets organisations externalise the capability
grant (who is trusted to self-certify at all) to their existing directory/IAM procedures, while the per-repo
entitlement remains managed inside git-proxy-java.

How to grant `ROLE_SELF_CERTIFY`:

- **LDAP / AD / OIDC:** add `SELF_CERTIFY` to `auth.role-mappings` and map it to the appropriate IdP group.
- **Local auth:** add `SELF_CERTIFY` to `roles:` in the user's `users:` YAML entry.

> **Note:** Organisations that require mandatory peer review (four-eyes) for all activity should simply not
> grant `SELF_CERTIFY` role or permissions. If no user holds `SELF_CERTIFY`, all pushes require a separate
> reviewer.

**Roles are dashboard-level access only.** They do not control which repos a user can push to —
that is what permissions (below) are for.

### Emails and SCM identities

Every user record carries two independent data sets that the proxy uses to verify identity on each push:

**Emails** — the set of email addresses the user commits with (i.e. the value in `git config user.email`).
On every push, every author and committer email in the incoming commits is checked against this list. If an
email is not registered to the authenticated user, the push fails in `strict` mode or warns in `warn` mode.
This is what ties commit attribution to a verified real person.

**SCM identities** — the upstream provider username(s) for this user (e.g. their GitHub login). On every
push, the proxy calls the upstream API using the PAT supplied in the git credentials and checks the returned
username against this list. This confirms that the token being used actually belongs to the person who
authenticated with the proxy, not a shared or borrowed token.

These are two independent checks and both must pass in `strict` mode. They catch different things:
a commit email mismatch means the developer's git client is misconfigured or the commit is attributed to
someone else; an SCM identity mismatch means the token does not belong to the authenticated user.

**How emails are populated:**

- Local auth: set in the `users:` YAML block; editable from the profile UI.
- LDAP/AD: the directory `mail` attribute is imported on first login as a locked email (not editable
  from the UI — the directory is the source of truth). Additional emails can be added via the admin dashboard.
- OIDC: the `email` claim from the ID token is imported on first login as a locked email.

**How SCM identities are populated:**

There is no automatic source for SCM identities — they must be added manually regardless of auth backend.
After first login, either the user themselves or an admin can add SCM identities from the profile page in
the dashboard. For example: provider `github/github.com`, username `alice-gh`. Users manage their own
profile; admins can manage any user's profile.

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

Until SCM identities are populated, pushes from that user will fail identity verification in `strict` mode.
Use `identity-verification: warn` during rollout to let pushes through while identities are being registered.
See [USER_GUIDE.md — Identity verification](USER_GUIDE.md#identity-verification) for the developer-facing
view of what these checks look like at the terminal.

### Disabling local admin when using an IdP

When LDAP, AD, or OIDC is configured, the static `users:` block still works and is evaluated alongside the
IdP. In most production setups you want to remove static local accounts (or at minimum remove any with
`roles: [ADMIN]`) once IdP-based login is confirmed working. See #103 for planned enforcement of this.

---

## Repo permissions

Permissions control which users can push to which repos, and who can review pushes.

```yaml
permissions:
  - username: alice
    provider: github/github.com
    path: /myorg/myrepo
    operations: PUSH
```

### Operations

| Value | What it grants |
| ----- | -------------- |
| `PUSH` | User can submit pushes to this repo for validation and review |
| `REVIEW` | User can approve or reject pushes to this repo submitted by others |
| `PUSH_AND_REVIEW` | Shorthand for both PUSH and REVIEW |
| `SELF_CERTIFY` | Per-repo entitlement: this user may self-approve pushes to this repo. Requires `ROLE_SELF_CERTIFY` (the capability role) to also be present — see [Dashboard roles](#dashboard-roles). Does **not** imply PUSH or REVIEW; grant those separately if needed. |

### SELF_CERTIFY — for solo contributors

`SELF_CERTIFY` is the right choice for a developer who works independently and does not have a team reviewer.
Without it, pushes in `ui` approval mode wait indefinitely for someone else to approve them.

Self-approval requires **two** things — both must be in place:

1. The `SELF_CERTIFY` role (capability gate) — granted via `auth.role-mappings` or `roles: [SELF_CERTIFY]`
   in local config. This is the org-level attestation that the user is trusted to self-certify at all.
2. A `SELF_CERTIFY` permission entry for the specific repo — the per-repo entitlement.

To set up a trusted solo contributor who approves their own work:

```yaml
# Step 1: grant the SELF_CERTIFY capability role (local auth example)
users:
  - username: bob
    password-hash: "{bcrypt}$2a$12$..."
    roles: [SELF_CERTIFY]   # or via auth.role-mappings for LDAP/AD/OIDC

# Step 2: grant the per-repo entitlement
permissions:
  - username: bob
    provider: github/github.com
    path: /myorg/myrepo
    operations: PUSH
  - username: bob
    provider: github/github.com
    path: /myorg/myrepo
    operations: SELF_CERTIFY
```

Bob's pushes are validated as normal (commit rules, secret scanning, identity checks). Once validation passes,
the proxy records a self-certification in the audit log and forwards without waiting for a reviewer.

If Bob also needs to review others' pushes to that repo, add a third entry with `operations: REVIEW`.

### Path matching

Paths default to exact (`LITERAL`) matching. Use `path-type` for wildcards:

```yaml
# GLOB — all repos under an owner
- username: alice
  provider: gitlab/gitlab.com
  path: /myorg/*
  path-type: GLOB
  operations: PUSH

# REGEX — Java regex matched against /owner/repo
- username: alice
  provider: github/github.com
  path: \/myorg\/service\-.*
  path-type: REGEX
  operations: PUSH
```

### Permissions vs access rules

A user with `PUSH` permission on `/myorg/myrepo` can still be blocked if `/myorg/myrepo` is not in `rules.allow`.
Both must be satisfied. The distinction:

- **Access rules** → "does the proxy route this repo at all?" — operator policy
- **Permissions** → "can this user push to it?" — per-user grant

A wildcard allow rule (`slugs: ["*/*"]`) effectively means "route everything" and shifts all control to the
permissions layer. A tightly scoped allow rule means you do not need to worry about accidentally granting a user
permission to a repo the proxy does not handle.

---

## Access rules

```yaml
rules:
  allow:
    - enabled: true
      order: 110
      operations: [FETCH, PUSH]
      providers: [github/github.com]
      slugs:
        - /myorg/repo-one
        - /myorg/repo-two

  deny:
    - enabled: true
      order: 100           # deny rules with lower order numbers take precedence
      operations: [PUSH]
      slugs:
        - /myorg/archived-repo
```

Rules are evaluated in `order` number order (lower = earlier). Deny rules override allow rules at the same order
number. The proxy is **default-deny**: if no allow rule matches, the request is rejected.

`operations` scopes a rule to `PUSH`, `FETCH`, or both. A repo can be open for fetch but restricted for push.

---

## Approval mode

```yaml
server:
  approval-mode: auto  # auto | ui
```

| Mode | Behaviour |
| ---- | --------- |
| `auto` | Clean pushes are immediately approved and forwarded after validation. No reviewer needed. Good for teams that use validation as a guardrail without a manual review step, and for solo contributors. |
| `ui` | Every push enters `PENDING` state and waits for a reviewer to approve or reject in the dashboard. The `git push` command stays open until a decision is made. |

`SELF_CERTIFY` permission interacts with `ui` mode: users with the capability and per-repo entitlement can
self-review their own push in the dashboard. The review step still happens — they attest to and record their
own approval. This signals to operators and the audit log that the pusher has reviewed and accepted
responsibility for the changes. Other users' pushes still require a peer reviewer.

The dashboard module (`git-proxy-java-dashboard`) always uses `ui` mode. The standalone server module defaults to `auto`.

---

## Logging

### Default log locations

| Environment | Log output |
| ----------- | ---------- |
| `./gradlew run` | `git-proxy-java-server/logs/application.log` + console |
| Docker / production | console only (stdout); redirect or use a log driver |

The default Log4j2 config logs `org.finos.gitproxy` at `DEBUG` and everything else at `INFO`.

### Enabling debug logging for specific subsystems

Override the bundled `log4j2.xml` at runtime — no rebuild required:

```bash
# Local run
JAVA_TOOL_OPTIONS=-Dlog4j2.configurationFile=/path/to/my-log4j2.xml \
  ./gradlew :git-proxy-java-dashboard:run

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

This enables `DEBUG` on `org.springframework.security` and `org.springframework.web.client`. Remove it when done — it
is very chatty.

#### JGit HTTP transport (upstream push/fetch failures)

Add to your `log4j2.xml`:

```xml
<Logger name="org.eclipse.jgit" level="DEBUG"/>
<Logger name="org.eclipse.jgit.http.server" level="DEBUG"/>
<Logger name="org.eclipse.jgit.transport" level="DEBUG"/>
```

Produces detailed output for each step of the JGit credential negotiation and pack transfer. Useful when a push
reaches the proxy but fails forwarding to upstream.

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

Logs each HTTP request and response made by Jetty's `ProxyServlet` to the upstream. Useful when the transparent
proxy path (`/proxy/`) fails to reach the upstream.

### Reading logs for a failed push

Each push gets a `requestId` in the MDC (visible in the `[%X{requestId}]` field in the log pattern). To follow a
single push through the log:

```bash
grep "your-request-id" logs/application.log
```

The `requestId` is also printed in the sideband output to the git client, so you can match terminal output to
log lines.

> **Roadmap:** OpenTelemetry tracing support (propagating trace/span IDs into the log MDC and exporting spans
> to a collector) is tracked in [#106](https://github.com/coopernetes/git-proxy-java/issues/106). Once
> implemented, the `requestId` will be correlatable across distributed systems without manual log grepping.

### Git client output formatting

Two environment variables control the `remote:` sideband messages sent to git clients during a push:

| Variable | Effect |
| -------- | ------ |
| `NO_COLOR` | Disables ANSI colour. Follows the [no-color.org](https://no-color.org) convention — set to any non-empty value. |
| `GITPROXY_NO_EMOJI` | Replaces emoji (✅ ❌ ⛔ 🔑) with plain ASCII. Useful for CI systems or terminals that do not render Unicode. |

Set on the server process, not on the client. See [CONFIGURATION.md — Git client output](CONFIGURATION.md#git-client-output)
for Docker Compose examples.

---

## JGit filesystem requirements

JGit requires write access to two locations at runtime. Failures here produce cryptic errors that look like git
transport problems but are actually filesystem permission issues.

### Home directory

JGit reads `~/.gitconfig` and writes lock files in `$HOME`. In a container, `HOME` must point to a writable
directory.

The Docker image sets `ENV HOME=/app/home` and creates `/app/home` with correct permissions. If you override the
image's entrypoint or run under a different UID, verify that `$HOME` is writable:

```bash
# Inside the container:
ls -la $HOME
touch $HOME/.test && rm $HOME/.test   # must succeed
```

**OpenShift / arbitrary UID:** OpenShift runs containers as a random UID by default. The image is built with GID 0
group-write on `/app/home`, `/app/.data`, and `/app/logs` (`chmod g+rwX`) so that any UID in group 0 can write to
them. If you see `Permission denied` errors on startup, check whether your security context is overriding the GID.

### `/tmp` for scratch repos and gitleaks

JGit creates temporary bare repositories in `java.io.tmpdir` (defaults to `/tmp`) for store-and-forward pushes
and for transparent proxy diff inspection. Gitleaks also writes temporary files there.

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

When `secret-scanning.enabled: true`, the proxy needs to execute the gitleaks binary. The bundled binary (inside
the JAR) is extracted to `java.io.tmpdir` at startup — that directory must allow executable files (`noexec`
prevents this).

If the temp dir is `noexec`, point gitleaks at a writable, exec-allowed path:

```yaml
commit:
  secret-scanning:
    enabled: true
    scanner-path: /app/.data/gitleaks   # explicit path bypasses auto-extraction
```

Or pre-install gitleaks and put it on `PATH` — the proxy will find it via system path lookup before falling back
to the bundled binary.

---

## Production checklist

### Database

Default `h2-mem` loses all push records on restart. For production:

```yaml
# PostgreSQL — recommended
database:
  type: postgres
  url: jdbc:postgresql://db.internal:5432/gitproxy?sslmode=verify-full&sslrootcert=/certs/ca.crt
  username: gitproxy
  password: secret

# H2 file — zero external dependencies, persistent
database:
  type: h2-file
  path: /app/.data/gitproxy
```

Schema is applied automatically via Flyway on startup.

### TLS

Put git-proxy-java behind a reverse proxy (nginx, Caddy, Envoy) for TLS termination in production. The application
can also terminate TLS directly if preferred — see [CONFIGURATION.md — TLS](CONFIGURATION.md#tls).

For upstream connections to internal GitLab/Bitbucket/Forgejo instances with a corporate CA:

```yaml
server:
  tls:
    trust-ca-bundle: /etc/gitproxy/tls/internal-ca.pem
```

This merges the corporate CA with the JVM's built-in trust anchors so public providers (GitHub, GitLab SaaS)
continue to work without changes.

### Health check

The dashboard module exposes an unauthenticated health endpoint:

```text
GET /api/health   → 200 OK with status payload when the server is up
```

The standalone server module (`git-proxy-java-server`) does not expose a health endpoint — use a TCP
check against the proxy port instead.

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
  session-timeout-seconds: 28800   # 8 hours
```

### API key

The REST API accepts a single shared API key for machine-to-machine calls (e.g. approval scripts). Change
the default before going to production:

```yaml
# In config or via env var:
GITPROXY_API_KEY: "your-secret-key"
```

The shared key is a stopgap for automation until proper machine auth is available. It carries no user
identity — all calls made with it are unattributed. Prefer session-based access (log in as a named service
account) for any automation that needs an audit trail.

> **Roadmap:** Per-user and per-service API keys, and an OAuth2 resource server mode for machine-to-machine
> auth, are tracked in [#57](https://github.com/coopernetes/git-proxy-java/issues/57). Until then, treat the
> shared key as a temporary measure and rotate it regularly.

---

## Common operational problems

### Push is rejected with "repository not permitted"

Check both layers:

1. Is the repo in `rules.allow`? Verify the slug matches exactly (including leading `/`).
2. Does the user have a `permissions` entry for this provider + path with `operations: PUSH`?

### Push hangs waiting for approval indefinitely

The server is in `ui` mode and no reviewer has approved the push. Either:

- Any authenticated user (other than the pusher) can open the push record and approve it in the dashboard.
- Or grant the pusher `SELF_CERTIFY` permission so they can approve their own clean pushes.

If `require-review-permission: true` is set, only users with an explicit `REVIEW` permission entry for the
repository can approve.

### Push blocked: identity not linked

The proxy cannot match the token to a registered proxy user. This check is always enforced — `identity-verification`
mode does not affect it. Check:

1. Does the user's profile have an `scm-identities` entry for the correct provider?
2. Does the token have the required API scope to call `GET /user`?

### Push blocked or warned: commit email mismatch

One or more commit author/committer emails are not registered to the authenticated user. This is controlled
by `identity-verification`:

- In `strict` mode the push is blocked. Check that the user's email list includes the address they commit with
  (`git config user.email`), and that the commits were not authored by someone else.
- In `warn` mode the push goes through but the mismatch is logged and visible in the push record. Switch to
  `strict` once you are confident emails are populated for all users.

### OIDC login fails / redirect loop

1. Enable the Spring Security debug profile (`docker/log4j2-debug.xml`) — see [Debug profiles](#debug-profiles-by-problem-area).
2. Check the redirect URI registered in the IdP matches `https://<your-host>/login/oauth2/code/gitproxy` exactly.
3. For Entra ID: verify `jwk-set-uri` is set — without it, token issuer validation fails silently.

### Gitleaks produces no output / scan appears to be skipped

Check `logs/application.log` for lines containing `gitleaks`. The log will show which binary path was resolved
and whether the scan ran. If the binary cannot be executed (permission denied, noexec mount), the proxy falls
back to skipping the scan rather than failing the push — add `gitleaks` to PATH or set `scanner-path`
explicitly.

### Push fails after approval with an upstream error (404, 403, etc.)

Once a push passes validation and is approved, the proxy forwards it to the upstream SCM transparently — no
further processing occurs. Any error from the upstream is passed straight back to the git client exactly as
if the developer were pushing directly.

Common upstream errors and their causes:

| Error | Likely cause |
| ----- | ------------ |
| `Repository not found` / 404 | The token does not have access to the repository. GitHub returns 404 (not 403) for both missing repos and insufficient permissions on private repos — this is intentional on GitHub's part to avoid leaking repo existence. |
| `403 Forbidden` | The token has repo access but lacks the required write scope (e.g. a fine-grained PAT missing `Contents: write`). |
| `pre-receive hook declined` | The upstream has its own server-side hooks that rejected the push. Nothing the proxy can do — the developer needs to resolve it upstream. |
| `remote: error: GH006: Protected branch` | The target branch has branch protection rules on the upstream. Again, upstream-side — not a proxy issue. |

These errors appear in the developer's terminal and in the push record in the dashboard. They are not logged
as proxy errors — from the proxy's perspective the forwarding succeeded.

**Diagnosing token scope issues:** if a push consistently fails with 404 or 403 immediately after approval,
ask the developer to test the same push directly (bypassing the proxy) with the same token. If it also fails
direct, the problem is the token — not the proxy.

### `Permission denied` on startup in a container

JGit failed to write to `$HOME` or `/tmp`. Verify:

```bash
docker exec <container> sh -c 'ls -la $HOME && touch $HOME/.probe && rm $HOME/.probe'
docker exec <container> sh -c 'touch /tmp/.probe && rm /tmp/.probe'
```

If either fails, see [JGit filesystem requirements](#jgit-filesystem-requirements) above.
