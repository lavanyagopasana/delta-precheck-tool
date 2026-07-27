---
name: documentation
description: Where documentation actually lives in this repo, what's known to be stale (README.md business rules), and what must be updated when behavior changes (memory files, not just code comments). Not a general documentation-writing skill — use gstack's /document-generate or /document-release for that.
---

# Documentation (project-specific)

Load this skill when a change needs to be documented, or when deciding whether something a user
said matches what's actually documented anywhere. For actually *generating* new docs or a
release changelog from scratch, use gstack's `/document-generate` / `/document-release` — this
skill is about **this repo's specific documentation landscape**: where things live, what's
trustworthy, and what isn't.

## Where documentation lives, and how much to trust each

| Location | Trust level | Notes |
|---|---|---|
| `backend/src/main/java/.../entity/*.java` | **Highest** — ground truth | The data model as it actually is today |
| `backend/src/main/java/.../service/*.java` (code comments) | High | Comments explain *why*, written by whoever last touched the business rule |
| `.claude/memory/*.md` | High, but only as current as the last update | This is the durable knowledge base — update it, don't let it drift |
| `README.md` | **Known partially stale** — verify before trusting | Describes an older per-workspace-pair, per-category pre-check model with automatic escalation that no longer matches the code (see `.claude/memory/domain-knowledge.md` for the full diff) |
| Frontend UI copy / labels | Medium | Usually accurate but can lag a backend semantic change (e.g. a "Pending" label that no longer means what the count actually measures) |

**Rule of thumb: entities and services are truth, README is a hypothesis to verify.** When a user's
question or request assumes something the README says but the entities/services contradict, say so
explicitly rather than silently going with whichever one the user seems to expect.

## What must be updated when behavior changes

Code comments alone are not enough — this repo's `.claude/memory/` files are what future sessions
read *first* (per `CLAUDE.md`'s token-optimization goal), so a change that isn't reflected there is
effectively invisible to the next session unless it re-reads the same source files from scratch.

- **Any bug fix that changes what a status/count *means*** (the Done/Pending fix this session is
  the canonical example) → add an entry to `.claude/memory/decisions.md` explaining the old
  (wrong) meaning, the new meaning, and why — not just "fixed a bug."
- **Any new environment variable** → add it to `CLAUDE.md`'s Environment Variables table, not just
  `application.properties`'s comments.
- **Any newly-discovered README staleness** → add it to `.claude/memory/domain-knowledge.md`'s
  discrepancy list rather than silently working around it every time it comes up. Fixing the
  README itself is also welcome, but recording the discrepancy is the minimum.
- **Any new business rule** (a new precondition, a new role permission, a new auto-provisioning
  rule) → `.claude/memory/domain-knowledge.md`.
- **Progress/state** (what's mid-flight, what's a known gap like "no tests exist") →
  `.claude/memory/progress.md`.

## Writing style for this repo's docs

Match the tone already established in `CLAUDE.md` and the rules files written this session: direct,
specific, with file:line-level references where possible, and explicit about *why* a constraint
exists (often "this already caused a real problem once") rather than presenting rules as arbitrary
policy. Avoid restating what the code obviously does — explain the non-obvious parts: hidden
constraints, footguns (`AZURE_CLIENT_ID`), and things that look like a bug but aren't (or vice
versa).

## Keep `.mcp.json` and doc-adjacent config honest too

If an MCP server is added/removed, or a new tool changes how documentation/tickets get looked up
(e.g. the Jira MCP suggested in `.mcp.json`, evidenced by the "Jira Tickets Tracking" nav label and
`Escalation.ticketNumber`), note it in `.claude/memory/decisions.md` so the next session knows
*why* that integration exists and doesn't remove it as unused.
