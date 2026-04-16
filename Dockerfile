# syntax=docker/dockerfile:1

# ── Build stage ──────────────────────────────────────────────────────────────
FROM docker.io/eclipse-temurin:21-jdk@sha256:06a4f4be86d459307036eb97c55a24686bd1312fe88c152723c915b7b2e6a8b4 AS builder

# Install Node.js directly from the official distribution with SHA256 verification.
# To update: download the new tarball, verify against nodejs.org/dist/vX.Y.Z/SHASUMS256.txt,
# and update both NODE_VERSION and NODE_SHA256 below.
ARG NODE_VERSION=24.14.1
ARG NODE_SHA256_AMD64=ace9fa104992ed0829642629c46ca7bd7fd6e76278cb96c958c4b387d29658ea
ARG NODE_SHA256_ARM64=734ff04fa7f8ed2e8a78d40cacf5ac3fc4515dac2858757cbab313eb483ba8a2
ARG TARGETARCH
RUN case "${TARGETARCH}" in \
      arm64) NODE_ARCH=linux-arm64; NODE_SHA256="${NODE_SHA256_ARM64}" ;; \
      *)     NODE_ARCH=linux-x64;   NODE_SHA256="${NODE_SHA256_AMD64}" ;; \
    esac \
    && curl -fsSL https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-${NODE_ARCH}.tar.gz \
       -o /tmp/node.tar.gz \
    && echo "${NODE_SHA256}  /tmp/node.tar.gz" | sha256sum --check \
    && tar -xzf /tmp/node.tar.gz -C /usr/local --strip-components=1 \
    && rm /tmp/node.tar.gz \
    && node --version \
    && npm --version

WORKDIR /workspace

# Download the Gradle wrapper JARs in a dedicated layer so they are cached
# independently of source changes.
COPY gradle/ gradle/
COPY gradlew gradlew.bat ./
RUN ./gradlew --version --no-daemon -q

COPY . .

# Build the distribution (all deps bundled in lib/).
# Node.js is installed above; the node-gradle plugin uses it from PATH (download=false).
# BuildKit cache mounts persist Gradle/npm downloads across builds.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/root/.npm \
    ./gradlew clean :git-proxy-java-dashboard:installDist --no-daemon -q

# Prepend a conf/ directory to the classpath so that a mounted git-proxy-local.yml
# is picked up by JettyConfigurationLoader at runtime.
RUN sed -i \
    's|^CLASSPATH=\$APP_HOME/lib|CLASSPATH=$APP_HOME/conf:$APP_HOME/lib|' \
    git-proxy-java-dashboard/build/install/git-proxy-java-dashboard/bin/git-proxy-java-dashboard

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM docker.io/eclipse-temurin:21-jre@sha256:137163a1850fd2088d94ecd8420358d83086bd287d3c4f6f14b7d09786490c4d

WORKDIR /app

# Copy the built distribution
COPY --from=builder \
    /workspace/git-proxy-java-dashboard/build/install/git-proxy-java-dashboard/ /app/

# Create the conf directory; mount a git-proxy-{profile}.yml here to override config.
# Example: -v ./docker/git-proxy-local.yml:/app/conf/git-proxy-local.yml:ro
# docker run -e GITPROXY_CONFIG_PROFILE=local -v ./docker/git-proxy-local.yml:/app/conf/git-proxy-local.yml:ro ...
RUN mkdir -p /app/conf

# Data directory for file-based databases (h2-file, sqlite), log output, and
# JGit home (used for lock files and system config). Owned by GID 0 with
# group-write so the image works under OpenShift's arbitrary-UID security model.
RUN bash -c 'mkdir -p /app/{.data,logs,home} \
    && chown -R 1000:0 /app/{.data,logs,home} \
    && chmod g+rwX /app/{.data,logs,home}'

ENV HOME=/app/home

EXPOSE 8080

USER 1000

ENTRYPOINT ["/app/bin/git-proxy-java-dashboard"]
