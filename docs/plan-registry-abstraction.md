# Registry Abstraction — Provider & Whitelist

## Problem

Providers and whitelist rules are currently loaded once at startup from YAML config (`git-proxy.yml` / env vars) into in-memory structures. In corporate environments it must be possible to drive these from an external authoritative source (a central config API, a secret store, a shared database) without restarting the process.

---

## Proposed Interfaces

### `ProviderRegistry`

Replaces / supersedes the existing `ProviderConfigurationSource`.

```java
public interface ProviderRegistry {
    List<GitProxyProvider> getProviders();
    Optional<GitProxyProvider> getProvider(String name);

    /**
     * Called periodically or on demand. Implementations may be no-ops.
     */
    void refresh();
}
```

### `WhitelistRegistry`

```java
public interface WhitelistRegistry {
    /** Return all active whitelist entries. */
    List<WhitelistEntry> getEntries();

    void refresh();
}
```

`WhitelistEntry` is a plain value type that captures what a single YAML stanza currently holds: optional provider scope, `slugs`, `owners`, `names`, `enabled`, `order`.  
`WhitelistByUrlFilter` is then built from `WhitelistEntry` objects rather than directly from YAML — decoupling rule storage from filter construction.

---

## Candidate Implementations

| Implementation              | Class (proposed)                                 | Backed by                                                                |
| --------------------------- | ------------------------------------------------ | ------------------------------------------------------------------------ |
| In-memory (current default) | `InMemoryProviderRegistry`                       | YAML config at startup; falls back to built-in defaults if config absent |
| Database                    | `DbProviderRegistry` / `DbWhitelistRegistry`     | JPA/JDBC; existing `jgit-proxy-core` DB layer                            |
| HTTP API                    | `HttpProviderRegistry` / `HttpWhitelistRegistry` | JSON REST endpoint; periodic poll or webhook invalidation                |
| S3 / object store           | `S3ProviderRegistry` / `S3WhitelistRegistry`     | JSON document in a bucket; periodic poll                                 |

Implementations are selected at startup via a config key, e.g.:

```yaml
git-proxy:
  registry:
    provider-source: in-memory # in-memory | db | http | s3
    whitelist-source: in-memory
    refresh-interval-seconds: 60
```

---

## Wiring

`GitProxyJettyApplication` currently calls `JettyConfigurationBuilder` which creates everything directly from the config loader. The change is:

1. Introduce a `RegistryFactory` that reads `registry.provider-source` / `registry.whitelist-source` and returns the correct `ProviderRegistry` / `WhitelistRegistry` impl.
2. `JettyConfigurationBuilder` receives these registries instead of touching the config loader directly for provider/whitelist data.
3. `GitProxyServletRegistrar` calls `registry.getProviders()` each time it (re-)registers servlets, enabling live reload.
4. `WhitelistAggregateFilter` calls `whitelistRegistry.getEntries()` on each request (cheap from in-memory; cached for remote sources with a TTL).

---

## Refresh Strategy

Remote registries (HTTP, S3, DB) should:

- Cache the last successful fetch with a configurable TTL (`refresh-interval-seconds`).
- Return the cached value on fetch failure (fail-open with a warning log).
- Expose a `refresh()` method so a future management API can trigger an immediate reload.

In-memory registry ignores `refresh()` (a no-op).

---

## Out of Scope (this proposal)

- Writes / mutations through the registry (read-only for now).
- Authentication/authorisation for the HTTP and S3 backends (left to the concrete impl).
- Dashboard UI for editing providers or whitelist rules.
