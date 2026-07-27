---
description: Fix a bug in this codebase, checking this project's known traps before assuming a novel root cause
---

# Bugfix

Fix the bug described in `$ARGUMENTS`. For general systematic debugging, use gstack's
`/investigate` first — this command adds the project-specific traps worth checking **before**
assuming you've found a novel root cause, since several bugs in this app have turned out to be one
of these repeating patterns rather than something new.

## Check these known traps first

1. **Is auth actually configured?** If the symptom is "a user's role/permissions look wrong" or
   "`/api/me` returns unexpected data," check whether the backend process actually has
   `AZURE_CLIENT_ID` set before assuming a bug in `AppUserService` or `SecurityConfig`. A backend
   restarted without it silently returns `{email: null, role: null, allowed: true}` for everyone —
   see `.claude/rules/security-rules.md`.
2. **"Done" vs "row exists."** If the symptom involves sign-off/approval counts, pre-check status,
   or anything with a `SignOffStatus`/`SubmissionStatus`, check whether the code is testing for the
   status actually being `APPROVED`/`SUBMITTED`, or just testing whether a row/record exists at
   all (`Optional.isPresent()` without checking `.getStatus()`). This exact confusion caused a real
   bug in `DashboardService`/`ProjectService` this session — see `.claude/memory/decisions.md`.
3. **Is `README.md` the source of the wrong assumption?** If the bug report or fix attempt is based
   on how the app is "supposed to" work per the README, verify against the actual entities/services
   first — the README describes an older per-workspace-pair, per-category pre-check model that no
   longer matches the code. See `.claude/memory/domain-knowledge.md`.
4. **Duplicated constants out of sync.** `APPROVAL_SEQUENCE` exists in both `SignOffService` and
   `ProjectService`; `normalizeHeader` exists in both `AppUserService` and `WorkspacePairService`.
   If behavior differs between two seemingly-parallel code paths, check whether one copy was
   updated and the other wasn't.
5. **Backend not actually restarted.** There's no `spring-boot-devtools` in this project — a
   backend code change never takes effect until the process is killed and restarted. If a fix
   "isn't working," confirm the running process is actually running the new code, not an assumption
   that a save reloads it.

## If none of the known traps apply

Proceed with normal root-cause investigation (gstack's `/investigate` covers this well). Once
found:

1. Fix at the root cause, not by adding a defensive check for the symptom — e.g. the fix for the
   Done/Pending bug changed what "done" *means*, it didn't add a fallback for when the count looks
   wrong.
2. Add a note to `.claude/memory/decisions.md` if the bug reveals something non-obvious about how
   this codebase works that the next person (or next Claude session) would benefit from knowing —
   especially if it's a new instance of one of the traps above, or a new trap entirely.
3. Consider whether `.claude/rules/testing-standard.md`'s priority list should gain a regression
   test for this area (there are no tests today, so this is opt-in, but a bug that already happened
   once is a strong argument for one — see `.claude/agents/test-writer.md`).

## Handoff

`/team-review` → gstack's `/review` → gstack's `/qa <url>` (verify the actual fix in the running
app, not just that the code looks right) → gstack's `/ship`.
