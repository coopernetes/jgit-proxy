# User Management — AuthN / AuthZ / Identity Plan

## Motivation

jgit-proxy currently has no concept of authenticated users. The REST API is fully open, `reviewerUsername`
in `Attestation` is an unverified string, `DummyUserAuthorizationService` always returns `true`, and
the HTTP Basic challenge issued to git clients is never validated. This plan closes those gaps and builds
toward a model where every actor — dashboard reviewer _and_ git committer — is a verified, correlated
identity.

The end goal: when a push arrives, jgit-proxy knows _who_ the committer is (from the IdP, not just from
what git tells us), whether they are authorised to push to that upstream, and whether the push was
properly attributed. Approval decisions can then be made by identifiable, role-authorised reviewers
rather than anonymous callers.

---

## Design Principles

- **AuthN is pluggable.** The login mechanism must be swappable without changing downstream authz or
  push-validation logic. In-memory/static users, SQL, LDAP/AD, and OIDC must all be supported behind
  the same interface.
- **AuthZ is derived, not duplicated.** Roles and permissions are computed from the verified
  authenticated identity, not re-entered per request.
- **Git identity is linked, not trusted.** Committer email and upstream username from a git push are
  treated as candidates; they must match a verified identity link before granting push rights.
- **Upstream SCM OAuth is optional but valuable.** GitHub/GitLab OAuth replaces manual username entry
  with cryptographically verified identity and unlocks non-git API operations in later phases.
- **Use existing frameworks.** `jgit-proxy-dashboard` already pulls in `spring-webmvc`. Spring Security
  and `spring-security-oauth2-client` fit naturally and avoid re-implementing session management,
  CSRF, PKCE, etc.

---

## Component Overview

```
                        ┌─────────────────────────────────────┐
                        │         jgit-proxy-dashboard        │
                        │                                      │
    Browser / CLI ──────►   Spring Security Filter Chain      │
                        │   (session, CSRF, role checks)       │
                        │                                      │
                        │   AuthenticationManager              │
                        │     ├─ InMemoryAuthProvider          │
                        │     ├─ JdbcAuthProvider              │
                        │     ├─ LdapAuthProvider (AD/LDAP)    │
                        │     └─ OidcAuthProvider (Entra/etc)  │
                        │                                      │
                        │   UserStore (ProxyUser CRUD)         │
                        │   IdentityLinkStore (email/username) │
                        │   ScmOAuthLinkStore (OAuth tokens)   │
                        └─────────────┬───────────────────────┘
                                      │  delegates to
                        ┌─────────────▼───────────────────────┐
                        │         jgit-proxy-core              │
                        │                                      │
    git push ───────────►  BasicAuth validated against         │
                        │  UserStore (was always 401-challenge) │
                        │                                      │
                        │  UserAuthorizationService            │
                        │  (backed by UserStore+IdentityLink)  │
                        │                                      │
                        │  CheckUserPushPermissionHook/Filter  │
                        │  (calls UserAuthorizationService)    │
                        └─────────────────────────────────────┘
```

---

## Data Models

### `ProxyUser`

Central user record. Created on first login (SSO/OIDC flow) or manually by an admin (in-memory/JDBC).

| Field            | Type             | Notes                                        |
| ---------------- | ---------------- | -------------------------------------------- |
| `id`             | UUID             | Internal primary key                         |
| `username`       | String           | Login name; unique                           |
| `email`          | String           | Primary contact email                        |
| `displayName`    | String           | Human-readable                               |
| `roles`          | Set\<ProxyRole\> | `ADMIN`, `APPROVER`, `DEVELOPER`             |
| `enabled`        | boolean          | Soft-disable without deleting                |
| `externalIdpSub` | String           | Nullable; the IdP `sub` claim for OIDC users |
| `source`         | AuthSource       | `STATIC`, `JDBC`, `LDAP`, `OIDC`             |

### `IdentityLink`

Associates a `ProxyUser` with git/SCM identities. One user can have many links.

| Field      | Type     | Notes                                                               |
| ---------- | -------- | ------------------------------------------------------------------- |
| `id`       | UUID     |                                                                     |
| `userId`   | UUID     | FK → ProxyUser                                                      |
| `type`     | LinkType | `COMMITTER_EMAIL`, `SCM_USERNAME`                                   |
| `provider` | String   | Nullable; e.g. `github`, `gitlab` — null means any                  |
| `value`    | String   | The email address or SCM username                                   |
| `verified` | boolean  | `true` if confirmed via OAuth callback, `false` if manually entered |

### `ScmOAuthLink`

Stores a verified OAuth connection to an upstream SCM provider for a user. Created only after a
successful OAuth callback.

| Field         | Type               | Notes                                        |
| ------------- | ------------------ | -------------------------------------------- |
| `id`          | UUID               |                                              |
| `userId`      | UUID               | FK → ProxyUser                               |
| `provider`    | String             | `github`, `gitlab`, etc.                     |
| `scmUsername` | String             | Verified at OAuth callback time              |
| `accessToken` | String (encrypted) | Stored encrypted at rest; used for API calls |
| `scopes`      | String             | Comma-separated OAuth scopes granted         |
| `linkedAt`    | Instant            |                                              |

---

## Roles

| Role             | Grants                                                               |
| ---------------- | -------------------------------------------------------------------- |
| `ROLE_ADMIN`     | Full access: user/identity management, all push operations, approval |
| `ROLE_APPROVER`  | Read all pushes; approve/reject/cancel                               |
| `ROLE_DEVELOPER` | Read own pushes; no approval                                         |

Roles are stored on `ProxyUser` and surfaced as Spring Security `GrantedAuthority` objects.

---

## Implementation Phases

### Phase 0 — Spring Security foundation (dashboard)

**Goal:** protect the REST API and dashboard with a real authentication layer.

1. Add to `jgit-proxy-dashboard/build.gradle`:

   ```
   implementation 'org.springframework.security:spring-security-web'
   implementation 'org.springframework.security:spring-security-config'
   implementation 'org.springframework.security:spring-security-oauth2-client'
   implementation 'org.springframework.security:spring-security-oauth2-jose'  // JWT/OIDC
   ```

2. Create `SecurityConfig` (`@EnableWebSecurity`) in `jgit-proxy-dashboard`:
   - Form login at `/login` (org-branded, not SCM-specific) — the generic proxy login flow.
   - Session management: server-side sessions (or stateless JWT for API consumers — configurable).
   - CSRF enabled for browser forms; optionally disabled for `/api/**` if using Bearer tokens.
   - Permit: `GET /login`, `GET /health`, git push/fetch endpoints (`/push/**`, `/proxy/**`).
   - Require `ROLE_APPROVER` or `ROLE_ADMIN` for `POST /api/push/{id}/authorise|reject|cancel`.
   - Require any authenticated user for `GET /api/push/**`.
   - Require `ROLE_ADMIN` for `/api/admin/**` (user management endpoints, added in Phase 2).

3. Remove the unvalidated `reviewerUsername` from the request body in `PushController`. Derive it
   from the authenticated `SecurityContext` principal.

4. No `ProxyUser` entity yet — authenticate against a minimal in-memory user list from YAML config
   (`git-proxy.yml` static users block) to unblock development. This is the "static/in-memory" tier.

---

### Phase 1 — AuthN abstraction (pluggable identity providers)

**Goal:** support JDBC, LDAP/AD, and OIDC behind a common interface without changing authz or
push-validation logic.

#### 1.1 `UserStore` interface (jgit-proxy-core)

```java
public interface UserStore {
    Optional<ProxyUser> findById(UUID id);
    Optional<ProxyUser> findByUsername(String username);
    Optional<ProxyUser> findByExternalSub(String sub);
    ProxyUser save(ProxyUser user);
    void delete(UUID id);
}
```

Implementations:

| Implementation    | Module    | Backed by                                                       |
| ----------------- | --------- | --------------------------------------------------------------- |
| `StaticUserStore` | core      | YAML `git-proxy.yml` users block; read-only                     |
| `JdbcUserStore`   | core      | H2/Postgres via existing JDBC layer; mutable                    |
| `LdapUserStore`   | dashboard | `spring-security-ldap`; read-only (LDAP is the source of truth) |

LDAP/AD notes:

- `LdapUserStore` queries an LDAP directory to look up users; it does not write back.
- AD is standard LDAP with `userPrincipalName` as the username attribute; no special code path needed
  beyond configurable attribute mapping in YAML.
- Roles can optionally be mapped from LDAP group membership.

#### 1.2 Spring Security `AuthenticationProvider` adapters (dashboard)

Each `UserStore` gets a corresponding Spring Security `AuthenticationProvider`:

| Provider class           | Delegates to                                                                    |
| ------------------------ | ------------------------------------------------------------------------------- |
| `StaticUserAuthProvider` | `StaticUserStore` (username/bcrypt-hashed password from YAML)                   |
| `JdbcUserAuthProvider`   | `JdbcUserStore`                                                                 |
| `LdapUserAuthProvider`   | `spring-security-ldap` `LdapAuthenticator`                                      |
| `OidcUserService`        | Spring's built-in `OidcUserService`, extended to upsert `ProxyUser` from claims |

`AuthenticationManager` is configured in `SecurityConfig` with the active providers from YAML:

```yaml
git-proxy:
  auth:
    provider: oidc # static | jdbc | ldap | oidc
    static-users:
      - username: alice
        password: "{bcrypt}$2a$..."
        roles: [APPROVER]
    ldap:
      url: ldap://corp.example.com:389
      base-dn: dc=example,dc=com
      user-search-filter: (sAMAccountName={0})
      group-search-base: ou=groups
      group-role-attribute: cn
      role-map:
        git-approvers: APPROVER
        git-admins: ADMIN
    oidc:
      issuer-uri: https://login.microsoftonline.com/<tenant>/v2.0
      client-id: ...
      client-secret: ...
      redirect-uri: "{baseUrl}/login/oauth2/code/entra"
      scope: openid,profile,email
      username-claim: preferred_username
      role-claim: roles # optional Azure app role claim
```

#### 1.3 Git push authn (jgit-proxy-core / server)

`BasicAuthChallengeFilter` currently issues the 401 challenge but never validates credentials.
Add `BasicAuthValidationFilter` (order just after the challenge filter) that:

- Decodes the `Authorization: Basic` header.
- Calls `UserStore.findByUsername()` and validates the presented password (bcrypt for JDBC/static;
  LDAP bind for LDAP; not applicable for OIDC — OIDC users must use a generated API token instead).
- Injects the resolved `ProxyUser` into request attributes for downstream filters.
- Returns 403 if validation fails.

For OIDC users, generate a personal access token (stored in `ProxyUser`, presented as the HTTP Basic
password) rather than requiring a bearer-token-aware git client.

---

### Phase 2 — Identity linking (git ↔ system user)

**Goal:** resolve "the person who made this push" to a known `ProxyUser`.

#### 2.1 `IdentityLinkStore` interface (jgit-proxy-core)

```java
public interface IdentityLinkStore {
    List<IdentityLink> findByUserId(UUID userId);
    Optional<ProxyUser> resolveByEmail(String email);
    Optional<ProxyUser> resolveByScmUsername(String username, String provider);
    IdentityLink save(IdentityLink link);
    void delete(UUID linkId);
}
```

#### 2.2 `UserAuthorizationService` — real implementation

Replace `DummyUserAuthorizationService` with `LinkedIdentityAuthorizationService`:

- `userExists(email)` → `IdentityLinkStore.resolveByEmail(email).isPresent()`
- `isUserAuthorizedToPush(email, repoUrl)` → user must exist **and** have `ROLE_DEVELOPER` or above
  **and** the repo must be in the whitelist (existing `RepositoryWhitelistHook` check).
- `getUsernameByEmail(email)` → resolves via `IdentityLinkStore` then returns `ProxyUser.username`.

`CheckUserPushPermissionHook` and `CheckUserPushPermissionFilter` call this — no changes needed there.

#### 2.3 Admin API — identity management (dashboard)

New `AdminController` under `/api/admin/`:

| Method   | Path                                   | Purpose                                     |
| -------- | -------------------------------------- | ------------------------------------------- |
| `GET`    | `/api/admin/users`                     | List all users                              |
| `POST`   | `/api/admin/users`                     | Create user (JDBC/static only)              |
| `PUT`    | `/api/admin/users/{id}/roles`          | Update roles                                |
| `DELETE` | `/api/admin/users/{id}`                | Disable/delete user                         |
| `GET`    | `/api/admin/users/{id}/links`          | List identity links for a user              |
| `POST`   | `/api/admin/users/{id}/links`          | Add email or SCM username link (unverified) |
| `DELETE` | `/api/admin/users/{id}/links/{linkId}` | Remove a link                               |

All endpoints require `ROLE_ADMIN`. When a link is created manually, `verified = false`.

---

### Phase 3 — Upstream SCM OAuth (GitHub / GitLab OAuth App)

**Goal:** replace manually entered SCM usernames with cryptographically verified OAuth identity.
This is _additive_ — the system works without it (Phase 2 manual links), but SCM OAuth makes
identity stronger and enables future non-git operations.

#### 3.1 "Link SCM account" flow

The user is already authenticated to jgit-proxy (Phase 1). From their profile page:

1. User clicks "Connect GitHub account" (or GitLab).
2. Dashboard redirects to the SCM OAuth authorisation URL with `state = <csrf token + userId>`.
3. On callback (`/oauth2/callback/github`), jgit-proxy:
   a. Validates the `state` parameter.
   b. Exchanges the code for an access token.
   c. Calls `/user` (GitHub) or `/api/v4/user` (GitLab) to get the verified SCM username.
   d. Creates a `ScmOAuthLink` with `verified = true`.
   e. Creates or updates an `IdentityLink` with `type = SCM_USERNAME`, `verified = true`.
4. The push-permission check can now verify SCM username against the upstream.

Spring Security's `OAuth2AuthorizedClientManager` handles token storage and refresh; the callback
endpoint is a thin controller that extracts the username and writes `ScmOAuthLink`.

#### 3.2 YAML config for SCM OAuth apps

```yaml
git-proxy:
  scm-oauth:
    github:
      client-id: ...
      client-secret: ...
      scopes: read:user,repo
    gitlab:
      client-id: ...
      client-secret: ...
      base-url: https://gitlab.example.com # for self-hosted
      scopes: read_user
```

Both entries are optional; the "Link account" button only appears in the UI if the corresponding
entry is configured.

#### 3.3 Access token use (future)

`ScmOAuthLink.accessToken` is stored encrypted at rest (AES-256-GCM; key from config or KMS).
It is not used in this phase but is available for later (issue/PR creation, repo metadata lookup,
contributor graph checks).

---

### Phase 4 — Push attribution enforcement

**Goal:** every push to store-and-forward mode must have a fully verified identity chain before it
can be approved.

1. Add `IdentityVerificationHook` (pre-receive, order before `ApprovalPreReceiveHook`):
   - Extracts committer email from each incoming commit.
   - Calls `IdentityLinkStore.resolveByEmail(email)`.
   - If unresolved, sends `rp.sendMessage("BLOCKED: committer email <x> is not linked to any proxy user")`
     and rejects the push outright.
   - If resolved, checks that the HTTP Basic `username` matches the resolved `ProxyUser.username`
     (prevents one user pushing commits attributed to another).

2. Populate `PushRecord.userEmail` from the authenticated principal (was always null).

3. Optionally: if `ScmOAuthLink` exists for the resolved user + provider, cross-reference the
   upstream SCM username in the commit's author against the verified OAuth username.

---

## Configuration Summary

```yaml
git-proxy:
  # AuthN provider — picks the active UserStore + AuthenticationProvider
  auth:
    provider: jdbc # static | jdbc | ldap | oidc
    # ... provider-specific blocks as shown in Phase 1

  # Optional: SCM OAuth App credentials per provider
  scm-oauth:
    github:
      client-id: ...
      client-secret: ...

  # Token encryption key (for ScmOAuthLink.accessToken)
  token-encryption:
    key-source: env # env | kms | config
    env-var: GITPROXY_TOKEN_KEY
```

---

## Module responsibilities

| Module                 | Owns                                                                                                                                                                                                                                      |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `jgit-proxy-core`      | `UserStore` interface, `IdentityLinkStore` interface, `StaticUserStore`, `JdbcUserStore`, `LinkedIdentityAuthorizationService`, `IdentityVerificationHook`, `BasicAuthValidationFilter`, `ProxyUser`/`IdentityLink`/`ScmOAuthLink` models |
| `jgit-proxy-dashboard` | `SecurityConfig`, `LdapUserStore`, `OidcUserService`, `AdminController`, `ScmOAuthCallbackController`, Spring Security wiring, UI login/profile pages                                                                                     |
| `jgit-proxy-server`    | Picks up `UserStore` from `jgit-proxy-core`; no Spring Security (no dashboard, no session)                                                                                                                                                |

---

## Out of scope (initial proposal)

- Non-git operations (issue creation, PR creation via SCM API) — unlocked by Phase 3 tokens but
  not designed here.
- Commit content scanning using the same filter chain — separate plan.
- Audit log UI for admin actions.
- Multi-tenancy / per-provider role scoping (e.g. approver for GitHub repos but not GitLab).
