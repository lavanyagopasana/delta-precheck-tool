# Delta Pre-Check Tool — How to use it

Before a Delta migration runs, someone must prove the data is correct: permissions, links, file
counts, database status. This tool records that proof and collects three approvals. The migration
cannot start until that is done.

---

## Who does what

| Role | Job |
|---|---|
| **Migration Engineer** | Fills in the checklist, submits it, runs the migration |
| **Migration Manager** | Approves 1st. Creates projects. **Cannot fill in the checklist** |
| **Dev Lead** | Approves 2nd. Decides whether QA is needed |
| **QA Lead** | Approves 3rd, only if the Dev Lead asked for it |
| **Admin** | Anything. Also releases a checklist locked to someone who has left |

A Manager cannot fill in the checklist because the Manager approves it first. The same person doing
both would make the approval meaningless.

---

## The words

**Project** = one customer. **Server** = a system in that project. **Combination** = one
source-to-destination pair, like *Teams to Slack*. **Workspace pair** = one user's account, loaded
from CSV.

The checklist and the approvals belong to the **combination**, not the server. One server can have
several combinations, each moving forward on its own.

---

## The workflow

| # | Step | Who |
|---|---|---|
| 1 | Create the project, assign a Migration Manager | Manager / Admin |
| 2 | Add the server URL | Engineer / Manager / Admin |
| 3 | Add the combination (source → destination), and set the product type | Engineer / Manager / Admin |
| 4 | Import the workspace pairs CSV | Engineer / Manager / Admin |
| 5 | Fill in the checklist | **Engineer only** |
| 6 | Submit | Same Engineer |
| 7 | Approve | Migration Manager |
| 8 | Approve, and say if QA is needed | Dev Lead |
| 9 | Approve | QA Lead (only if asked) |
| 10 | Start the migration | Engineer |
| 11 | Finish the migration | Engineer |

Steps 1–4 happen once. Steps 5–11 repeat for every Delta cycle.

If no Migration Manager is assigned in step 1, nobody can submit in step 6.

CSV import (step 4): a bad row is reported with its row number, and every good row still imports.
Fix the bad rows and upload again.

---

## Step 5 — Filling in the checklist

**The checklist depends on the server's product type.** Content, Email and Message each get a
different set of items. Set the product type on the server first — if it is not set, you get the
Content checklist.

For every item: set the status, attach a file, write a one-line note.

### Content — 8 items

| # | Item | What you confirm |
|---|---|---|
| 1 | Delta Type | Pre delta, or the Final delta |
| 2 | OneTime Migration | The first full migration finished |
| 3 | Previous Delta Migration | The earlier delta ran, if there was one |
| 4 | Data Verified | File and folder counts match |
| 5 | Permissions Verified | Sharing and access carried over |
| 6 | Hyperlinks Verified | Links inside documents still work |
| 7 | Workspace Status Updated in DB | The database record is correct |
| 8 | Drive changes | Changes since the last run are accounted for |

### Email — 4 items

| # | Item | What you confirm |
|---|---|---|
| 1 | Delta Type | Pre delta, or the Final delta |
| 2 | OneTime Migration | The first full migration finished |
| 3 | Data Verified | Mail counts match |
| 4 | Workspace Status Updated in DB | The database record is correct |

### Message — 5 items

| # | Item | What you confirm |
|---|---|---|
| 1 | Delta Type | Pre delta, or the Final delta |
| 2 | OneTime Migration | The first full migration finished |
| 3 | Delta Message Sync | Messages since the last run have synced |
| 4 | Data Verified | Message counts match |
| 5 | Workspace Status Updated in DB | The database record is correct |

### Which items need a file and a note

**Delta Type never does** — you only pick Pre or Final. Every other item does.

**Previous Delta Migration** (Content only) appears only when you set Delta Type to **Pre delta**.
On a Final delta it is hidden and not required at all.

So the number needing a file and a note is:

| Product type | Pre delta | Final delta |
|---|---|---|
| Content | 7 | 6 |
| Email | 3 | 3 |
| Message | 4 | 4 |

### Attach ONE combined file per item

Do not attach five separate screenshots to one item. Combine them into one file first, then attach
that single file.

Checked permissions on eight accounts? Take one screenshot showing all eight, or paste them into one
Word document or PDF, or export one spreadsheet. Attach that.

The approver opens each item once. Six loose screenshots means six things to open in no clear order.
One combined file gets approved faster.

Ways to combine: one wide screenshot · paste into one Word doc or PDF · one exported spreadsheet ·
one short screen recording instead of many screenshots.

### Allowed file types

| Type | Formats |
|---|---|
| Images | PNG, JPG, JPEG, GIF, WEBP, BMP, TIF, TIFF, HEIC, HEIF, AVIF |
| Documents | PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX, ODT, ODS, ODP, RTF |
| Text / data | CSV, TSV, TXT, LOG, JSON, XML, MD, HAR |
| Email exports | MSG, EML |
| Archives | ZIP, 7Z, RAR, TAR, GZ, TGZ |
| Recordings | MP4, MOV, WEBM, MKV, AVI, M4V, MP3, WAV, M4A |

**Limit: 25 MB per file.** Too big? Zip it, or save as JPG instead of PNG.

**SVG and web pages (HTML) are never accepted.** They can carry hidden code that would run in the
reviewer's browser. Screenshot it instead.

### The checklist locks to one person

Starting a checklist locks it to you. Nobody else can type in it. If that person has left or is on
leave, only an **Admin** can release it.

---

## Step 6 — Submitting

Submit is refused if: any item has no status · any item is missing its file · any item is missing its
note · you did not pick Pre or Final delta · no Migration Manager is assigned to the project.

The message names exactly what is missing. Fix it and submit again. Once submitted, the checklist
locks and the three approval rows are created automatically.

---

## Steps 7–9 — The approvals

Order is fixed: **Migration Manager → Dev Lead → QA Lead.** You cannot skip ahead. Each person sees
their pending work on the **Approvals** page and gets an email.

When the Dev Lead approves, they answer one question — is QA needed? **No** means QA is skipped and
the combination is ready immediately. **Yes** sends it to the QA Lead.

### A decline restarts the whole cycle

It does not go back one step.

When anyone declines: everything you filled in is saved to history, the live checklist is wiped
clean, all three approvals are removed, the cycle number goes up by one, and **the entire checklist
must be filled in again.**

The person declining must give a reason. Read it, fix the real problem, then refill.

A combination showing *Cycle 2* with an empty checklist is a normal declined attempt, not a bug.
Your earlier work is safe in the history.

---

## Steps 10–11 — Running it

All approvals done means the combination is **Delta Ready** and the Engineers get an email.
Click **Start** when you begin, **Finish** when the data has moved. That closes one cycle.

Most migrations run several **Pre deltas** to catch changes, then one **Final delta**. After the
Final delta finishes, the combination is closed and the server can be decommissioned. Every cycle
keeps its own attachments and approvals in the history.

---

## Reporting a problem with the data

Use **Ticket Tracker** for problems with the migration or the data itself — a customer issue, a
product bug. Paste the ticket number or the link and the tool pulls in the details.

---

## Quick answers

**I cannot type in the checklist.** You are not a Migration Engineer, or someone else started it
first. The checklist shows who has it.

**Submit will not work.** Something is missing — usually a note, a file, or no Manager on the
project. The message names it.

**Empty checklist, says Cycle 2.** Someone declined. Read the reason and refill. Old work is in the
history.

**My file will not upload.** Wrong format, or over 25 MB. SVG and HTML are never accepted.

**My checklist has different items than my colleague's.** That is correct. Content, Email and
Message each have their own checklist.

**I cannot approve.** Not your turn, or not your role. Order is Manager, Dev Lead, QA Lead.

**The person who started this has left.** Only an Admin can release it.

**Can I undo an approval?** No. Decline it and run a fresh cycle.
