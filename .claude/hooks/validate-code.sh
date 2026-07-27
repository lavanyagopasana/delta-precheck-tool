#!/usr/bin/env bash
# PostToolUse hook (Edit|Write) — lightweight, non-blocking pattern checks for a handful of
# project-specific anti-patterns documented in .claude/rules/code-style.md and
# .claude/rules/architecture-boundaries.md. This is intentionally cheap (grep, not a real
# compiler/linter run on every keystroke) so it doesn't slow down every edit. It only ever
# prints warnings to stderr; it never blocks (exit code is always 0).

set -uo pipefail

payload="$(cat)"

extract_file_path() {
  if command -v jq >/dev/null 2>&1; then
    echo "$payload" | jq -r '.tool_input.file_path // empty' 2>/dev/null
  else
    echo "$payload" | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/'
  fi
}

file_path="$(extract_file_path || true)"

if [ -z "${file_path:-}" ] || [ ! -f "$file_path" ]; then
  exit 0
fi

warn() {
  echo "[validate-code] $1" >&2
}

case "$file_path" in
  */entity/*.java)
    if grep -qE '^\s*@Data\b' "$file_path" 2>/dev/null; then
      warn "$file_path uses @Data — this repo's entities use @Getter/@Setter/@NoArgsConstructor instead (avoids @Data's generated equals/hashCode/toString on JPA lazy associations). See .claude/rules/code-style.md."
    fi
    ;;
  */controller/*.java)
    if grep -qE '\bRepository\s+\w+\s*;' "$file_path" 2>/dev/null || grep -qE '\w+Repository\.\w+\(' "$file_path" 2>/dev/null; then
      warn "$file_path appears to call a *Repository directly from a controller. This repo's layering routes repository access through a service. See .claude/rules/architecture-boundaries.md."
    fi
    if grep -qE 'public\s+\w*Entity\b|return\s+\w+Repository\.findById' "$file_path" 2>/dev/null; then
      warn "$file_path may be returning an entity directly from a controller method instead of a DTO. See .claude/rules/api-conventions.md."
    fi
    ;;
  */service/*.java)
    if grep -qE '\.split\("\s*,\s*"\)' "$file_path" 2>/dev/null; then
      warn "$file_path uses a naive comma-split for what may be CSV data — use CsvUtils.parseLine instead (handles quoted fields with embedded commas). See .claude/rules/code-style.md."
    fi
    ;;
  */pages/*.js|*/components/*.js)
    if grep -qE '\baxios\.(get|post|put|patch|delete)\(' "$file_path" 2>/dev/null; then
      warn "$file_path calls axios directly. Every API call in this app goes through frontend/src/api/client.js — add a function there instead. See .claude/rules/architecture-boundaries.md."
    fi
    if grep -qE '\bfetch\(\s*["'\'']http' "$file_path" 2>/dev/null; then
      warn "$file_path calls fetch() with a hardcoded URL directly. Route this through frontend/src/api/client.js instead."
    fi
    ;;
esac

exit 0
