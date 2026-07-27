---
name: api-design
description: How THIS project shapes its API responses, errors, DTOs, and auth-gating — not a general REST API design skill. Covers the uniform error envelope, DTO naming suffixes, and the CSV-import result shape already established in this codebase.
---

# API Design (project-specific)

Load this skill when adding or changing a backend endpoint. This is the detailed version of
`.claude/rules/api-conventions.md` — read that first for the compact rule list; this skill gives
the reasoning and examples.

## The established response shapes

**Success**: always a DTO (`dto/*.java`), never an entity. Three DTO naming patterns already exist
and should be matched, not reinvented:
- `*Dto` — a read/response shape (`ProjectSummaryDto`, `ServerReadinessDto`, `AppUserDto`).
- `*Request` — a write/input shape, validated with `jakarta.validation` annotations where it makes
  sense (`AppUserUpsertRequest`, `ProjectCreateRequest`, `EscalationCreateRequest`).
- `*ResultDto` — a bulk-operation summary specifically (`AppUserImportResultDto`,
  `WorkspacePairImportResultDto`), always shaped as `{totalRows, createdCount, updatedCount,
  errors: List<String>}`. Use this exact shape for any new bulk/CSV operation — don't invent a
  different summary format.

**Error**: a single uniform envelope for every failure, produced centrally by
`GlobalExceptionHandler` — `{timestamp, status, error, message}`. Every service-level failure should
be an `ApiException(HttpStatus, String)` (or the more specific `EvidenceRequiredException`,
`ResourceNotFoundException`) with a message written for the end user to read directly in a toast —
see `PreCheckSubmissionService.submit`'s three precondition checks for the tone to match
("Attach evidence for every checklist item before submitting for Migration Manager review.", not
"Validation failed: evidence_required").

## Designing a new endpoint

1. What DTO does it return, and does every field actually get populated by the service (check
   against the pattern in `ProjectService.copySummary` for "did I wire up every field")?
2. What can go wrong, and does each failure map to a specific `ApiException` with a
   human-readable message — not a generic 500 that `GlobalExceptionHandler`'s catch-all handler
   would otherwise surface with a raw exception message?
3. Who's allowed to call it? This is an explicit `SecurityConfig` decision every time — see
   `.claude/skills/architecture/SKILL.md` and `.claude/rules/security-rules.md`. There's no
   "endpoints are open by default" assumption to lean on; the generic `allowlistRequired()`
   fallback still needs to be a conscious choice.
4. Does the frontend need a new function in `frontend/src/api/client.js`? Every backend endpoint
   this app actually uses has exactly one corresponding export there — that file is the practical
   map of the whole API surface (`.claude/rules/architecture-boundaries.md`).

## Bulk/CSV import — the established recipe

Follow `AppUserService.importCsv` or `WorkspacePairService.importCsvGlobal` exactly for any new CSV
import feature:
1. Read the file via `BufferedReader`/`InputStreamReader` (UTF-8), collect all lines.
2. Parse each line with `CsvUtils.parseLine` (handles quoted fields with embedded commas — never
   `String.split(",")`).
3. Auto-detect an optional header row by normalizing each header cell (strip BOM, lowercase, strip
   non-letters) and checking for an expected column name; if found, skip row 0 as header, otherwise
   treat every row (including row 0) as data.
4. Process row-by-row: on a bad row, append a human-readable message to an `errors` list and
   `continue` — never let one bad row fail the whole import.
5. Return a `*ResultDto` with the counts and errors, exactly as described above.

## File uploads

`@RequestParam MultipartFile file`, not `@RequestBody` — matches every existing upload endpoint.
Files are stored via `FileStorageService` and served back at `/uploads/<name>`
(`WebConfig.addResourceHandlers`) — don't introduce a second storage/serving convention.

## What this skill does NOT cover

General REST design philosophy (verb/noun conventions, HATEOAS, versioning strategy) — none of
that is established or needed here yet. This skill is purely "how does this specific app already
do it, and how do I extend that consistently."
