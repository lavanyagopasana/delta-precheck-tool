# Workflow: Bug Fix

## Steps

1. **Check the known traps first** — `.claude/commands/bugfix.md` lists them: is
   `AZURE_CLIENT_ID` actually set on the running backend, is the bug really a "row exists" vs.
   "actually approved/submitted" confusion, is the bug report based on `README.md`'s stale
   description rather than the real code, are two duplicated constants out of sync, was the
   backend actually restarted after the fix. A large fraction of this app's real bugs so far have
   been one of these — check before assuming something novel.
2. **`/investigate`** (gstack) — if none of the known traps apply, use gstack's general systematic
   debugging.
3. **Fix at the root cause.** If the bug is a semantic confusion (like the Done/Pending bug — see
   `.claude/memory/decisions.md`), fix what the code *means*, not just the symptom's output.
4. **Consider a regression test.** There are no tests today (`.claude/rules/testing-standard.md`),
   but a bug that already happened once is the strongest argument for adding one — especially for
   the four priority classes listed there (`SignOffService`, `PreCheckSubmissionService.submit`,
   `AppUserService`, `ProjectService.buildSummary`). Use `.claude/agents/test-writer.md` for this.
5. **Record it** in `.claude/memory/decisions.md` if it reveals something non-obvious (a new trap,
   a new instance of a known one, or a genuinely new class of bug) — don't let the same confusion
   cost someone else the same investigation time.
6. **`/team-review`** → **`/review`** (gstack) → **`/qa <url>`** (gstack) — verify the actual fix
   in a running instance, not just that the code reads correctly. For approval/sign-off bugs
   specifically, verify against more than one chain state (not-submitted, mid-chain, fully
   resolved) — a fix that only looks right for one state isn't verified.
7. **`/ship`** (gstack).

## Fallback if gstack isn't installed

Use `.claude/commands/bugfix.md` directly for the trap-checking + root-cause guidance, then verify
manually and open the PR yourself.
