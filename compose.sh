#!/usr/bin/env bash
# Wrapper around docker/podman compose that assembles the right overlay flags.
#
# Usage:
#   bash compose.sh [--auth ldap|oidc] [--db postgres|mongo] -- <compose subcommand> [args...]
#
# Examples:
#   bash compose.sh -- up -d
#   bash compose.sh --auth ldap -- up -d
#   bash compose.sh --db postgres -- up -d
#   bash compose.sh --auth ldap --db postgres -- up -d
#   bash compose.sh --auth ldap --db postgres -- down -v

set -euo pipefail

AUTH=""
DB=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --auth)
      AUTH="$2"
      shift 2
      ;;
    --db)
      DB="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: bash compose.sh [--auth ldap|oidc] [--db postgres|mongo] -- <compose subcommand> [args...]" >&2
      exit 1
      ;;
  esac
done

if [[ -n "$AUTH" && "$AUTH" != "ldap" && "$AUTH" != "oidc" ]]; then
  echo "Invalid --auth value: $AUTH (must be ldap or oidc)" >&2
  exit 1
fi

if [[ -n "$DB" && "$DB" != "postgres" && "$DB" != "mongo" ]]; then
  echo "Invalid --db value: $DB (must be postgres or mongo)" >&2
  exit 1
fi

if command -v podman &>/dev/null; then
  COMPOSE="podman compose"
else
  COMPOSE="docker compose"
fi

ARGS=(-f docker-compose.yml)

if [[ -n "$AUTH" ]]; then
  ARGS+=(-f "docker-compose.${AUTH}.yml")
fi

if [[ -n "$DB" ]]; then
  ARGS+=(--profile "$DB" -f "docker-compose.${DB}.yml")
fi

$COMPOSE "${ARGS[@]}" "$@"
