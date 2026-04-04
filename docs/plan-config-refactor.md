# Config Loading Refactor — TODO

## Current state

### Files involved

| File                                                                                                                                                                       | Role                                                                                                     |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| [jgit-proxy-server/.../JettyConfigurationLoader.java](../jgit-proxy-server/src/main/java/org/finos/gitproxy/jetty/config/JettyConfigurationLoader.java)                    | Loads YAML files, deep-merges them, applies `GITPROXY_*` env var overrides                               |
| [jgit-proxy-server/.../JettyConfigurationBuilder.java](../jgit-proxy-server/src/main/java/org/finos/gitproxy/jetty/config/JettyConfigurationBuilder.java)                  | Casts raw `Map<String, Object>` tree into domain objects (providers, filters, push store, commit config) |
| [jgit-proxy-server/.../resources/git-proxy.yml](../jgit-proxy-server/src/main/resources/git-proxy.yml)                                                                     | Base defaults shipped with the JAR                                                                       |
| [jgit-proxy-server/.../resources/git-proxy-local.yml](../jgit-proxy-server/src/main/resources/git-proxy-local.yml)                                                         | Local dev overrides (not for production)                                                                 |
| [docker/git-proxy-local.yml](../docker/git-proxy-local.yml)                                                                                                                | Docker-mounted override for local Gitea                                                                  |
| [docker/git-proxy-postgres.yml](../docker/git-proxy-postgres.yml)                                                                                                          | Docker-mounted override for PostgreSQL                                                                   |
| [docker/git-proxy-mongo.yml](../docker/git-proxy-mongo.yml)                                                                                                                | Docker-mounted override for MongoDB                                                                      |
| [jgit-proxy-core/.../config/CommitConfig.java](../jgit-proxy-core/src/main/java/org/finos/gitproxy/config/CommitConfig.java)                                               | Domain object for commit validation rules — candidate for typed binding                                  |
| [jgit-proxy-core/.../config/GpgConfig.java](../jgit-proxy-core/src/main/java/org/finos/gitproxy/config/GpgConfig.java)                                                     | Domain object for GPG settings — candidate for typed binding                                             |
| [jgit-proxy-server/.../GitProxyJettyApplication.java](../jgit-proxy-server/src/main/java/org/finos/gitproxy/jetty/GitProxyJettyApplication.java)                           | Server entry point — constructs loader + builder                                                         |
| [jgit-proxy-dashboard/.../GitProxyWithDashboardApplication.java](../jgit-proxy-dashboard/src/main/java/org/finos/gitproxy/dashboard/GitProxyWithDashboardApplication.java) | Dashboard entry point — also constructs loader + builder                                                 |

`JettyConfigurationLoader` is a hand-rolled 3-tier YAML loader:

1. `git-proxy.yml` (classpath base)
2. `git-proxy-local.yml` (optional local overrides)
3. `GITPROXY_*` env vars (highest priority)

`JettyConfigurationBuilder` then casts the resulting `Map<String, Object>` tree into domain objects.

### Pain points

- **Partial env var coverage** — only simple scalar keys (port, base-path, provider enabled flag). Complex structures (whitelist filters, regex patterns) cannot be overridden via env vars.
- **No profiles** — no `git-proxy-prod.yml` / `git-proxy-dev.yml` activation mechanism.
- **No type safety** — raw `Map<String, Object>` throughout; type mismatches surface at runtime.
- **No validation** — bad config silently produces defaults or runtime exceptions; no fail-fast at startup.
- **No cloud config integration** — no Vault, AWS SSM, or Kubernetes Secrets support.

---

## Recommended replacement: Gestalt

Replace `JettyConfigurationLoader` with [Gestalt](https://github.com/gestalt-config/gestalt).

Gestalt is a lightweight, container-free config library that covers everything the hand-rolled loader lacks:

| Feature                        | Today          | Gestalt                  |
| ------------------------------ | -------------- | ------------------------ |
| YAML loading                   | ✅ (SnakeYAML) | ✅ (SnakeYAML module)    |
| File merging / layering        | ✅ manual      | ✅ native, ordinal-based |
| Env var overrides              | ⚠️ partial     | ✅ full, any key         |
| Profile activation             | ❌             | ✅ native                |
| Type-safe POJO binding         | ❌             | ✅                       |
| Startup validation (JSR-380)   | ❌             | ✅                       |
| Hot-reload                     | ❌             | ✅                       |
| Cloud config (Vault, SSM, K8s) | ❌             | ✅ (extension modules)   |

---

## Migration sketch

1. **Add Gestalt dependency** to [jgit-proxy-server/build.gradle](../jgit-proxy-server/build.gradle):

   ```groovy
   implementation 'com.github.gestalt-config:gestalt-core:0.x.x'
   implementation 'com.github.gestalt-config:gestalt-yaml:0.x.x'
   ```

2. **Define typed config records/POJOs** to replace the `Map<String, Object>` casts in [JettyConfigurationBuilder.java](../jgit-proxy-server/src/main/java/org/finos/gitproxy/jetty/config/JettyConfigurationBuilder.java) (e.g., `ServerConfig`, `ProviderConfig`, `CommitConfig`, `DatabaseConfig`). Annotate with Bean Validation constraints where appropriate. Existing domain objects [CommitConfig.java](../jgit-proxy-core/src/main/java/org/finos/gitproxy/config/CommitConfig.java) and [GpgConfig.java](../jgit-proxy-core/src/main/java/org/finos/gitproxy/config/GpgConfig.java) in `jgit-proxy-core` can be augmented rather than replaced.

3. **Replace [JettyConfigurationLoader.java](../jgit-proxy-server/src/main/java/org/finos/gitproxy/jetty/config/JettyConfigurationLoader.java)** with a `GestaltBuilder` composition:

   ```
   classpath:git-proxy.yml                  (base defaults, stays as-is)
   → classpath:git-proxy-{profile}.yml      (profile override, if active)
   → filesystem:git-proxy-local.yml         (external local override, stays as-is)
   → environment variables                  (highest priority, full key coverage)
   ```

4. **Simplify [JettyConfigurationBuilder.java](../jgit-proxy-server/src/main/java/org/finos/gitproxy/jetty/config/JettyConfigurationBuilder.java)** — replace all `(Map) rawConfig.get(...)` casts with `gestalt.getConfig("git-proxy", GitProxyConfig.class)`.

5. **Update entry points** [GitProxyJettyApplication.java](../jgit-proxy-server/src/main/java/org/finos/gitproxy/jetty/GitProxyJettyApplication.java) and [GitProxyWithDashboardApplication.java](../jgit-proxy-dashboard/src/main/java/org/finos/gitproxy/dashboard/GitProxyWithDashboardApplication.java) — swap `new JettyConfigurationLoader()` for the Gestalt-backed equivalent.

6. **Profile activation** — read `GITPROXY_PROFILE` env var (or system property) to select the profile YAML file.

---

## Libraries considered and rejected

- **Apache Commons Configuration 2** — no native profile concept; POJO binding via Beanutils is not first-class.
- **Owner** — last release 2019, effectively unmaintained, no YAML support.
