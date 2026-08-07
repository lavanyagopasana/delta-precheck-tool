# Fix production login, make decommission erase, add Email product type

## Why

Sign-in worked on every laptop and failed in production. Chasing that turned up a
second class of problem — several behaviours that only differ once the app is
deployed or once a product type isn't Content — so this branch covers those too.

---

## 1. Production login (the reason this branch exists)

`react-scripts` inlines every `REACT_APP_*` value into `main.<hash>.js` at build
time. The deployed bundle therefore carried:

```js
redirectUri: "http://localhost:3000"
apiBase:     "http://localhost:8080"
```

Entra ID was being asked to send production users back to their own machine, and
every API call pointed at localhost. Setting env vars on the server could not fix
it — the values were already frozen inside the JavaScript.

`frontend/.env.local` made it worse: CRA loads it for **production** builds too,
at higher precedence than `.env.production`. Verified by experiment — a value set
in `.env.production` was ignored and the `.env.local` one shipped.

**Fix:** configuration resolves at runtime (`frontend/src/config/runtimeConfig.js`).
Precedence: `public/runtime-config.js` (editable on the server, no rebuild) →
build-time env → the page's own origin. A build-time *localhost* URL is ignored
when the page is served from a real host, so a bundle built with dev settings
self-corrects when deployed.

**Still required outside this repo:** the production URL must be registered on the
Azure app registration under Authentication → **Single-page application**, and the
backend needs `APP_ALLOWED_ORIGINS` / `APP_FRONTEND_URL`.

---

## 2. Decommission now erases a server ⚠️ breaking, irreversible

Previously it set a flag. It now deletes the server and everything under it:
combinations, migration pairs, pre-check items **and their uploaded evidence
files**, the sign-off chain, Delta cycles, and tickets.

There is no undo — `reinstate()` and its `DELETE` endpoint are gone, because
nothing would remain to restore. **This destroys the sign-off/evidence audit
trail for that server.** Accepted deliberately; nothing exports it first, so
anything needed for audit has to be exported before running it.

Guards kept: ADMIN only (enforced in `SecurityConfig` *and* `ServerService`), and
every combination must have completed its Final Delta. A server with no
combinations is rejected, so this can't be used as a plain delete.

The cascade lives in the new `ServerPurgeService`, shared with
`ProjectService.delete` — which **fixes a latent bug there**: the old inline
cascade predated Delta cycles and never deleted them, so deleting a project with
any Delta cycle failed on a foreign-key constraint.

---

## 3. Email product type

- Own 4-item checklist: `Delta Type → OneTime Migration → Data Verified →
  Workspace Status Updated in DB`. No Previous Delta Migration, Permissions,
  Hyperlinks or Drive changes — none apply to a mailbox migration.
- 2-column CSV (`source_email, destination_email`). The backend already accepted
  this (`REQUIRED_COLUMNS` is only the two emails, both paths nullable); only the
  frontend advertised the wrong columns. Exports no longer emit two permanently
  empty path columns.
- An **untouched** checklist realigns to its product type's item set on read, so
  combinations seeded before Email had its own list correct themselves. Skipped
  entirely once anything has been filled in — a real record is never rewritten.

**Message is still a placeholder** reusing the Content checklist and CSV shape,
now explicitly commented as such.

---

## 4. Permissions

- Pre-check submission restricted to `MIGRATION_ENGINEER` + `ADMIN`.
  `MIGRATION_MANAGER` removed: the manager is the first approver in the chain, so
  filling in the form they then approve collapsed two steps into one person.
  ADMIN kept as the unblock path for a pre-check locked to an unavailable engineer.
- **Bug fix:** Dev Lead and QA Lead could not see server details at all.
  `ServerUrlsPanel` opened with `if (!canManage) return null`, gating a whole
  *read* view on a *write* permission — so the people asked to approve the work
  couldn't see it. The panel now always renders; `canManage` gates only the write
  actions inside.

---

## 5. Renames and ordering

- `Pre Delta Migration` → `Previous Delta Migration`. The item name is the
  matching key and is persisted per row, so both names are accepted — otherwise
  the conditional-requirement rule silently stops applying to existing
  combinations and the item becomes permanently mandatory.
- `Delta Type` moved to the top of every checklist; it decides whether Previous
  Delta Migration applies at all.
- `Pre-Delta #1` → `Pre-Delta 1`.
- **Bug fix:** checklist ordering used `indexOf`, which returns `-1` for an
  unknown name and sorted it *first*, above Delta Type. Every checklist seeded
  before the rename holds such a name.

---

## 6. UI

Dashboard KPI strip, Delta history, combination header, and the CSV format /
re-upload flows. Details in the commits. Includes one fix found by `/qa`:
`.btn:disabled` was overridden by `.btn.secondary` (equal specificity, later rule
wins), so **every disabled secondary button in the app rendered as enabled** —
clickable-looking, inert, no explanation.

---

## Testing

- **Backend:** 121 unit tests pass, including new `ServerPurgeServiceTest` (cascade
  order, evidence-file deletion, Delta cycles), `ProductTypeChecklistTest`, and
  three checklist-ordering tests that were **verified to fail on the pre-fix code**.
- **Frontend:** 26 tests pass, including 14 for `runtimeConfig` covering the exact
  production failure (localhost-baked bundle served from a real host).
- **Browser QA** via `/qa` against a real database: dashboard, project page,
  combination page, Delta history, checklist modal, CSV format modal. Zero console
  errors.

### Known, pre-existing, not from this branch

12 backend Spring-context tests fail with `org.h2.Driver` being handed a
`jdbc:mysql://` URL. Confirmed identical on `main`. The suite is red before and
after; worth a separate fix.

### Not verified in a browser

Role gating. The QA browser can't complete Microsoft sign-in (MSAL caches tokens
in `sessionStorage`, so cookies can't be imported), so QA ran against an auth-free
instance where every user reads as ADMIN. Role behaviour is covered by unit tests
only.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
