# API Conventions

## Base URL and routing

- All backend routes are under `/api/**` (except `/uploads/**`, served statically by `WebConfig`).
- Frontend's single source of truth for the API surface is `frontend/src/api/client.js` —
  `API_BASE = "http://localhost:8080/api"` is hardcoded there (no environment-based API base URL
  yet; if you add one, update both `client.js` and this file).
- CORS (`WebConfig.addCorsMappings`) only allows `http://localhost:3000` today. A deployed frontend
  origin must be added there before it will work against a deployed backend.

## Request/response shape

- **Controllers never return entities directly** — always a DTO (`dto/*.java`). If you add an
  endpoint, add a matching DTO even if it looks like a 1:1 mirror of the entity today; entities
  carry JPA proxies and lazy associations that don't serialize safely.
- **Error responses are a single uniform shape**, produced by `GlobalExceptionHandler`:
  ```json
  { "timestamp": "...", "status": 400, "error": "Bad Request", "message": "..." }
  ```
  Throw `ApiException(HttpStatus, String)` for any expected business-rule violation (see
  `PreCheckSubmissionService.submit` for the pattern: check preconditions, throw `ApiException`
  or `EvidenceRequiredException` with a user-facing message). Don't let a raw exception escape a
  service method if there's a clean 4xx story to tell instead — `DataIntegrityViolationException`
  is the backstop for things you didn't check for, not the primary mechanism.
- **Bulk operations (CSV import) return a `*ResultDto`** with `totalRows`, `createdCount`,
  `updatedCount`, and `errors: List<String>` — never fail the whole batch for one bad row; collect
  per-row errors and keep processing (see `AppUserService.importCsv`,
  `WorkspacePairService.importCsvGlobal`).

## Authentication & authorization on new endpoints

- Every new endpoint needs an explicit decision in `SecurityConfig.securityFilterChain`: does it
  need `authenticated()`, `access(allowlistRequired())`, or `access(roleRequired(...))`? There is no
  implicit default that's safe to assume — an endpoint with no matcher falls through to
  `.anyRequest().access(allowlistRequired())`, which is usually right but should be a conscious
  choice, not an oversight.
- Extract the caller's email via `JwtEmailUtil.extractEmail(jwt)` — never read a claim directly.
  A `null` email (unauthenticated, or a token missing the expected claim) must be handled
  explicitly, not assumed to be a valid caller.
- See `.claude/rules/security-rules.md` for the full authorization model.

## Pagination / filtering

- Nothing in this API paginates today — list endpoints (`GET /api/projects`, `GET /api/admin/users`,
  etc.) return the full collection and the frontend's `DataTable` component does client-side
  search/sort/filter. Don't add server-side pagination to one endpoint without a clear reason;
  it would make that endpoint inconsistent with every other list endpoint in the app.

## File uploads

- Multipart uploads use `@RequestParam MultipartFile file` (not `@RequestBody`), matching
  `WorkspacePairController.importGlobal` and the new `AdminController.importCsv`. Max size is
  20MB (`spring.servlet.multipart.max-*` in `application.properties`) — raise it there if a new
  upload type needs more, don't work around it per-endpoint.
- Evidence/attachment files go through `FileStorageService` and are served back at
  `/uploads/<filename>` — don't introduce a second storage location or serving path.
