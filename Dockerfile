# syntax=docker/dockerfile:1

# ── Build stage ──────────────────────────────────────────────────────────────
FROM docker.io/eclipse-temurin:21-jdk AS builder

# Install Node.js directly from the official distribution with SHA256 verification.
# To update: download the new tarball, verify against nodejs.org/dist/vX.Y.Z/SHASUMS256.txt,
# and update both NODE_VERSION and NODE_SHA256 below.
ARG NODE_VERSION=24.11.1
ARG NODE_SHA256_AMD64=58a5ff5cc8f2200e458bea22e329d5c1994aa1b111d499ca46ec2411d58239ca
ARG NODE_SHA256_ARM64=0dc93ec5c798b0d347f068db6d205d03dea9a71765e6a53922b682b91265d71f
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
COPY . .

# Build the distribution (all deps bundled in lib/).
# Node.js is installed above; the node-gradle plugin uses it from PATH (download=false).
RUN ./gradlew clean :git-proxy-java-dashboard:installDist --no-daemon -q

# Prepend a conf/ directory to the classpath so that a mounted git-proxy-local.yml
# is picked up by JettyConfigurationLoader at runtime.
RUN sed -i \
    's|^CLASSPATH=\$APP_HOME/lib|CLASSPATH=$APP_HOME/conf:$APP_HOME/lib|' \
    git-proxy-java-dashboard/build/install/git-proxy-java-dashboard/bin/git-proxy-java-dashboard

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM docker.io/eclipse-temurin:21-jre

WORKDIR /app

# Copy the built distribution
COPY --from=builder \
    /workspace/git-proxy-java-dashboard/build/install/git-proxy-java-dashboard/ /app/

# Create the conf directory; mount a git-proxy-local.yml here to override config.
# Example: -v ./docker/git-proxy-local.yml:/app/conf/git-proxy-local.yml:ro
RUN mkdir -p /app/conf

# Data directory for file-based databases (h2-file, sqlite)
RUN mkdir -p /app/.data

EXPOSE 8080

ENTRYPOINT ["/app/bin/git-proxy-java-dashboard"]
