# syntax=docker/dockerfile:1

# ── Build stage ──────────────────────────────────────────────────────────────
FROM docker.io/eclipse-temurin:21-jdk AS builder

WORKDIR /workspace
COPY . .

# Build the distribution (all deps bundled in lib/).
# The dashboard build also compiles the React frontend via the node-gradle plugin
# (Node is downloaded automatically; no separate Node stage needed).
RUN ./gradlew :jgit-proxy-dashboard:installDist --no-daemon -q

# Prepend a conf/ directory to the classpath so that a mounted git-proxy-local.yml
# is picked up by JettyConfigurationLoader at runtime.
RUN sed -i \
    's|^CLASSPATH=\$APP_HOME/lib|CLASSPATH=$APP_HOME/conf:$APP_HOME/lib|' \
    jgit-proxy-dashboard/build/install/jgit-proxy-dashboard/bin/jgit-proxy-dashboard

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM docker.io/eclipse-temurin:21-jre

WORKDIR /app

# Copy the built distribution
COPY --from=builder \
    /workspace/jgit-proxy-dashboard/build/install/jgit-proxy-dashboard/ /app/

# Create the conf directory; mount a git-proxy-local.yml here to override config.
# Example: -v ./docker/git-proxy-local.yml:/app/conf/git-proxy-local.yml:ro
RUN mkdir -p /app/conf

# Data directory for file-based databases (h2-file, sqlite)
RUN mkdir -p /app/.data

EXPOSE 8080

ENTRYPOINT ["/app/bin/jgit-proxy-dashboard"]
