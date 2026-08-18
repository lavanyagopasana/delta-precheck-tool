# Security Rules

Project-specific security invariants. gstack's `/cso` does a general security sweep; this file
covers what's specific to this app's auth model and has already bitten this project once.

## The `AZURE_CLIENT_ID` silent-open-mode trap

`SecurityConfig.authConfigured()` returns `false` whenever `azure.client-id` (i.e. the
`AZURE_CLIENT_ID` env var) is blank — and in that case, **every** `/api/**` route becomes
`permitAll()`, with no authentication at all. This is intentional (it's how local dev works before
an Azure app registration exists), but it means:

- **As of 2026-07-27, `AZURE_CLIENT_ID`/`AZURE_TENANT_ID` are baked into `application.properties`
  as defaults** (see `.claude/memory/decisions.md`), so this no longer happens from a forgotten
  env var on a routine restart. It can still happen if something **explicitly** overrides
  `AZURE_CLIENT_ID` to blank (a shell export, a deploy config, a test script) — that's the scenario
  to check for now, not "did I forget to export it."
- **This doesn't fail loudly** when it does happen — the app starts fine, `/api/me` returns
  `200 {"email":null,"role":null,"allowed":true}` for anyone, and every page still renders. This
  already caused a real incident once (an admin's "Admin" nav link silently vanished because a
  restart happened in an environment where the var had been forced blank, not because of any
  role/DB issue).
- **Never "fix" a missing-auth symptom by loosening a permission check** — check whether
  `AZURE_CLIENT_ID` is actually populated in the running process first (or explicitly blanked). If
  `/api/me` returns a null `email` for a user who should be authenticated, that's the first thing
  to check, not a bug in `AppUserService.roleOf`.
- Before deploying anywhere real, confirm `AZURE_CLIENT_ID`/`AZURE_TENANT_ID` resolve to the real
  values in that environment and that `/api/me` actually returns a populated `email`/`role` for a
  real signed-in user — don't just confirm the app "loads".

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
- `/api/combinations/*/precheck-items/**`, `/api/combinations/*/precheck-submission/**` (non-GET) —
  **`MIGRATION_ENGINEER` and `ADMIN` only**, as of 2026-08-06. Two deliberate exclusions here, both
  confirmed with a human — don't "fix" either without asking again:
  - `MIGRATION_MANAGER` was **removed** on 2026-08-06. The manager is the first approver in the
    sign-off chain, so letting them also fill in the form they then approve collapses two steps of
    the chain into one person. (They keep GET access and review via the Approvals page.)
  - `ADMIN` is **kept**, as the unblock path for a pre-check locked to an engineer who has become
    unavailable — without it that situation needs a direct database edit. `handleWithdraw` /
    `canWithdraw` in `PreCheckPanel` is admin-only for the same reason.

  The frontend mirror is `PRECHECK_EDIT_ROLES` in `PreCheckPanel.js` plus `canFillPreCheck` in
  `WorkspacePairsPanel.js`. Those only hide controls; `SecurityConfig` is what enforces the rule, so
  changing one without the others leaves buttons that 403 (or worse, a rule that isn't applied).
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
- **`backend/src/main/resources/application.properties` is tracked, on purpose, and always will be.**
  It is the app's real configuration and nothing builds without it. `.gitignore` used to list
  `backend/application.properties`, a path that matches no file that has ever existed here, which
  advertised a protection that was not in force. That dead line is gone as of 2026-08-18 and the
  `.gitignore` now says plainly why the file is tracked.
  The invariant that replaces it: **every secret-shaped value in that file stays a
  `${ENV_VAR:default}` placeholder.** As of 2026-08-18 all three (`spring.datasource.password`,
  `spring.mail.password`, `jira.api-token`) do. Paste a literal password or API token in and it is
  committed and pushed with the next change — there is no ignore rule standing between you and that.
  Real credentials belong in the environment.
- Uploaded evidence files (`backend/uploads/`) and DB backups (`db_backups/`) are already
  gitignored — keep it that way; these can contain customer data.

## Before any change to `SecurityConfig`

Treat this as security-sensitive by default: run gstack's `/cso` in addition to normal review, and
loop in a human before merging (per `AGENTS.md`'s escalation rules) if the change removes or
weakens an existing `.access(...)` restriction.
