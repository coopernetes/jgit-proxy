# Jetty Module Configuration

The Jetty server implementation uses YAML-based configuration to dynamically configure providers, filters, and server settings. This is separate from the Spring Boot configuration (`application.yml`).

## Configuration Files

| File | Purpose |
|------|---------|
| `git-proxy.yml` | Base configuration shipped with the jar |
| `git-proxy-local.yml` | Local overrides for development (optional) |

Files are loaded from the classpath in order, with `git-proxy-local.yml` values taking priority.

## Environment Variable Overrides

Configuration values can be overridden using environment variables with the `GITPROXY_` prefix:

| Environment Variable | Configuration Key | Example |
|---|---|---|
| `GITPROXY_SERVER_PORT` | `server.port` | `9090` |
| `GITPROXY_GITPROXY_BASEPATH` | `git-proxy.base-path` | `/git` |
| `GITPROXY_PROVIDERS_<NAME>_ENABLED` | `git-proxy.providers.<name>.enabled` | `true` |

> **Note:** Whitelist filter configuration is not supported via environment variables due to its complex nested structure. Use YAML files instead.

## Server Settings

```yaml
server:
  port: 8080
```

## Provider Configuration

Providers define the upstream Git hosting services to proxy. Built-in providers (github, gitlab, bitbucket) use well-known URIs. Custom providers require an explicit URI.

```yaml
git-proxy:
  providers:
    # Built-in providers (use well-known URIs)
    github:
      enabled: true
    gitlab:
      enabled: true
    bitbucket:
      enabled: true

    # Custom provider with explicit URI
    internal-gitlab:
      enabled: true
      servlet-path: /enterprise
      uri: https://gitlab.internal.example.com

    # Another custom provider
    debian-gitlab:
      enabled: true
      servlet-path: /debian
      uri: https://salsa.debian.org/
```

### Provider Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Whether the provider is active |
| `servlet-path` | string | `""` | Additional path prefix for the provider |
| `uri` | string | _(built-in default)_ | Base URI for the upstream Git server |

## Filter Configuration

### Whitelist Filters

Whitelist filters control which repositories are allowed to be accessed through the proxy. Multiple whitelists can be defined, each scoped to specific providers and operations.

```yaml
git-proxy:
  filters:
    whitelists:
      # Allow specific repos by slug (owner/name)
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

      # Allow all repos from an owner
      - enabled: true
        order: 1200
        operations:
          - FETCH
        providers:
          - github
        owners:
          - finos

      # Allow repos by name across all providers
      - enabled: true
        order: 1300
        operations:
          - FETCH
        names:
          - hello-world
```

### Whitelist Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Whether this whitelist is active |
| `order` | int | `1100` | Filter execution order (1000-1999 range) |
| `operations` | list | _none_ | Git operations to match: `FETCH`, `PUSH` |
| `providers` | list | _all_ | Provider names this whitelist applies to |
| `slugs` | list | _none_ | Repository slugs (e.g., `owner/repo`) |
| `owners` | list | _none_ | Repository owners/orgs |
| `names` | list | _none_ | Repository names |

## Running

```shell
# Default configuration
./gradlew :jgit-proxy-jetty:run

# Override port via environment variable
GITPROXY_SERVER_PORT=9090 ./gradlew :jgit-proxy-jetty:run
```

