# Security Rules

Project-specific security invariants. gstack's `/cso` does a general security sweep; this file
covers what's specific to this app's auth model and has already bitten this project once.

## The `AZURE_CLIENT_ID` silent-open-mode trap

`SecurityConfig.authConfigured()` returns `false` whenever `azure.client-id` (i.e. the
`AZURE_CLIENT_ID` env var) is blank — and in that case, **every** `/api/**` route becomes
`permitAll()`, with no authentication at all. This is intentional (it's how local dev works before
an Azure app registration exists), but it means:

- **Restarting the backend without re-exporting `AZURE_CLIENT_ID` doesn't fail loudly** — the app
  starts fine, `/api/me` returns `200 {"email":null,"role":null,"allowed":true}` for anyone, and
  every page still renders. This already caused a real incident this session (an admin's "Admin"
  nav link silently vanished because a restart dropped the env var, not because of any role/DB
  issue).
- **Never "fix" a missing-auth symptom by loosening a permission check** — check whether
  `AZURE_CLIENT_ID` is actually set in the running process first. If `/api/me` returns a null
  `email` for a user who should be authenticated, that's the first thing to check, not a bug in
  `AppUserService.roleOf`.
- Before deploying anywhere real, confirm `AZURE_CLIENT_ID` is set in that environment and that
  `/api/me` actually returns a populated `email`/`role` for a real signed-in user — don't just
  confirm the app "loads".

## Authorization model — two independent layers

1. **Authentication**: "is this a valid Microsoft account" — handled by `SecurityConfig`'s JWT
   validation (issuer, audience, signature via Microsoft's JWKS). Any work/school Microsoft
   account can authenticate; this layer says nothing about whether they should have access.
2. **Authorization**: "should this person be allowed to use the app, and as what role" — entirely
   owned by `AppUserService` and the `app_users` table, independent of Azure AD. A person can
   authenticate successfully and still get "Access pending approval" if they're not in
   `app_users` (when `AZURE_REQUIRE_ALLOWLIST=true`) or have no meaningful role.

Never conflate these two. A change that makes authentication more permissive (e.g. accepting a
wider token audience) does **not** need to touch authorization, and vice versa.

## Role-gated endpoints

`SecurityConfig` gates specific route groups beyond the base allowlist check:

- `/api/servers/*/signoffs/**` (non-GET) — `ADMIN`, `MIGRATION_MANAGER`, `DEV_LEAD`, `QA_LEAD` only.
- `/api/pairs/import`, `/api/servers/*/pairs/import` (CSV import) — `ADMIN`, `MIGRATION_ENGINEER`,
  `MIGRATION_MANAGER` only.
- `/api/servers/*/precheck-items/**`, `/api/servers/*/precheck-submission/**` (non-GET) —
  `MIGRATION_ENGINEER`, `MIGRATION_MANAGER` only. Notably **`ADMIN` is deliberately excluded** from
  this one, unlike the two rules above — don't "fix" this by adding `ADMIN` back in without
  checking with a human first; it may be intentional (admins manage access, not fill out
  pre-checks).
- Managing the allowlist itself (`/api/admin/users/**`) requires `ADMIN`, enforced in
  `AppUserService.requireAdmin` (not in `SecurityConfig` — it's a defense-in-depth check called
  from `AdminController` directly).

Any new endpoint that mutates sign-off status, pre-check data, or the user allowlist needs an
explicit role decision — don't let it fall through to the generic allowlist-only default without
thinking about it first.

## Data handling

- **Never log or display a JWT, access token, or ID token.** `getAccessToken.js`'s comment
  explains that the ID token doubles as the bearer credential here — treat it with the same care
  as a real access token.
- **Email is the only identity key** across `AppUser`, sign-offs, escalations, and pre-check
  attribution — always compare case-insensitively (`.claude/rules/code-style.md`). A
  case-sensitivity bug here is a security bug (it could let someone bypass an "only the person who
  started this pre-check can submit it" check).
- **`.gitignore` currently lists `backend/application.properties`, but the real file lives at
  `backend/src/main/resources/application.properties`** — the ignore rule doesn't actually match
  it. Today the file only contains non-sensitive local defaults (`root`/`root` DB credentials,
  a placeholder SMTP username), but if real credentials are ever hardcoded there instead of left
  as env-var defaults, they would **not** be excluded from git once this becomes a real repository.
  Fix the `.gitignore` path (or better, never put real secrets in that file at all) before that
  becomes a live risk.
- Uploaded evidence files (`backend/uploads/`) and DB backups (`db_backups/`) are already
  gitignored — keep it that way; these can contain customer data.

## Before any change to `SecurityConfig`

Treat this as security-sensitive by default: run gstack's `/cso` in addition to normal review, and
loop in a human before merging (per `AGENTS.md`'s escalation rules) if the change removes or
weakens an existing `.access(...)` restriction.
