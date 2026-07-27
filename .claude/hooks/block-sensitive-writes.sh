#!/usr/bin/env bash
# PreToolUse hook (Edit|Write) — blocks writes to files this project treats as sensitive:
# runtime uploads, DB backups, and local env files that may hold real credentials.
#
# Reads the Claude Code hook JSON payload from stdin, e.g.:
#   {"tool_name":"Edit","tool_input":{"file_path":"...","...":"..."}}
#
# Exit 0 + no output = allow. Exit 2 = block (Claude Code convention for PreToolUse hooks).

set -euo pipefail

payload="$(cat)"

# Prefer jq if available (handles JSON correctly); fall back to a simple grep extraction
# for machines without jq installed. The fallback only works for a flat "file_path" string
# value and is intentionally conservative — if extraction fails, we allow rather than block,
# since a false block is more disruptive than a missed check for an edge-case payload shape.
extract_file_path() {
  if command -v jq >/dev/null 2>&1; then
    echo "$payload" | jq -r '.tool_input.file_path // empty' 2>/dev/null
  else
    echo "$payload" | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/'
  fi
}

file_path="$(extract_file_path || true)"

if [ -z "${file_path:-}" ]; then
  exit 0
fi

# Normalize backslashes (Windows paths) to forward slashes for matching.
normalized="${file_path//\\//}"

is_sensitive=0
case "$normalized" in
  */backend/uploads/*|*/db_backups/*|*.env|*.env.local|*/frontend/.env.local)
    is_sensitive=1
    ;;
esac

if [ "$is_sensitive" -eq 1 ]; then
  echo "Blocked: '$file_path' is a sensitive/runtime path (uploads, DB backups, or a local .env file)." >&2
  echo "These hold real customer evidence, DB dumps, or secrets and shouldn't be edited by an agent." >&2
  echo "If this is genuinely intentional, ask the user to make this specific edit themselves." >&2
  exit 2
fi

# Soft warning (non-blocking) for the actual application.properties path, since .gitignore's
# "backend/application.properties" entry doesn't match this file's real location
# (backend/src/main/resources/application.properties) — see .claude/rules/security-rules.md.
case "$normalized" in
  */backend/src/main/resources/application.properties)
    echo "Note: editing application.properties — the .gitignore entry for this file doesn't match its real path (see .claude/rules/security-rules.md). Don't hardcode real credentials here; use an env-var default like the existing \${AZURE_CLIENT_ID:} pattern." >&2
    ;;
esac

exit 0
