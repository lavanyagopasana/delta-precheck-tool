#!/usr/bin/env bash
# PostToolUse hook (Edit|Write) — auto-formats the edited file, IF a formatter is actually
# configured for its toolchain. As of this scaffold, this repo has NO formatter configured yet:
#   - Backend: no spotless/google-java-format plugin in backend/pom.xml
#   - Frontend: no prettier config/dependency in frontend/package.json (only CRA's default
#     "eslintConfig": { "extends": ["react-app"] }, which lints but doesn't auto-format)
#
# This script is intentionally a no-op today rather than pretending a formatter exists. It's
# written to activate automatically the moment either toolchain gains one, so it doesn't need to
# be rewritten later — just add the plugin/dependency and this starts working.

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

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

case "$file_path" in
  *.java)
    if grep -q "spotless" "$REPO_ROOT/backend/pom.xml" 2>/dev/null; then
      (cd "$REPO_ROOT/backend" && mvn -o -q spotless:apply -Dspotless.check.skip=false 2>/dev/null) || true
    fi
    # else: no Java formatter configured yet — silent no-op, by design (see header comment).
    ;;
  *.js|*.jsx)
    if grep -q '"prettier"' "$REPO_ROOT/frontend/package.json" 2>/dev/null; then
      (cd "$REPO_ROOT/frontend" && npx --no-install prettier --write "$file_path" 2>/dev/null) || true
    fi
    # else: no Prettier configured yet — silent no-op, by design (see header comment).
    ;;
esac

exit 0
