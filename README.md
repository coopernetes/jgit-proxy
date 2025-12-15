# git-proxy in Java
This is a simple implementation of a git proxy in Java. This is a possible successor to [finos/git-proxy](https://github.com/finos/git-proxy) which is written in Node.

## Project Structure

This project has been restructured into a multi-module Gradle project with the following modules:

### jgit-proxy-core
Core module containing reusable proxy and filter code:
- Servlet filters for request processing
- Provider interfaces and implementations (GitHub, GitLab, Bitbucket)
- Git protocol utilities
- Pluggable configuration abstraction layer

### jgit-proxy-jetty  
Standalone Jetty server application that uses the core module to provide a lightweight proxy server without Spring dependencies.

**To run:**
```shell
./gradlew :jgit-proxy-jetty:run
```

### jgit-proxy-spring
Spring Boot application with:
- REST API endpoints
- Spring-based configuration
- H2 database support for provider/filter configuration (planned)
- JPA entities for persistence (planned)

**To run (once compilation issues are resolved):**
```shell
./gradlew :jgit-proxy-spring:bootRun
```

## Usage

### Using the Jetty Standalone Server
The Jetty standalone server is the simplest way to run the proxy:

```shell
./gradlew :jgit-proxy-jetty:run
```

### Using the Spring Boot Server
```shell
./gradlew :jgit-proxy-spring:bootRun
```

## Endpoints
The proxy has support for GitHub, GitLab, and Bitbucket. The following endpoints are available which can be used to interact with an upstream git server:
- `/github.com/{owner}/{repo}`
- `/gitlab.com/{owner}/{repo}`
- `/bitbucket.org/{owner}/{repo}`

An example of how to use the proxy is as follows:
```shell
git clone http://localhost:8080/github.com/finos/git-proxy.git
```

## Configuration

### Core Module
The core module provides pluggable configuration through:
- `ProviderConfigurationSource` - Interface for loading provider configurations
- `FilterConfigurationSource` - Interface for loading filter configurations
- Default in-memory implementations

### Jetty Module
Configuration is done programmatically in the `GitProxyJettyApplication` class. You can:
- Add/remove providers
- Configure filters
- Set up whitelists

### Spring Module
Configuration is done through:
- `application.yml` for application properties
- JPA/H2 database for dynamic provider/filter configuration (planned)

## Development

### Building the Project
```shell
./gradlew build
```

### Building Individual Modules
```shell
./gradlew :jgit-proxy-core:build
./gradlew :jgit-proxy-jetty:build
./gradlew :jgit-proxy-spring:build
```

### Running Tests
```shell
./gradlew test
```

## Demo

![demo 1](./static/jgit-proxy-demo.gif)

![demo 2](./static/jgit-proxy-demo2.gif)

Running the server
![demo 3](./static/jgit-proxy-demo3.gif)

