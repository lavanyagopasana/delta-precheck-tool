# Repository Map

Full file-by-file map as of this scaffold. Regenerate the relevant section here if files are
added/removed/renamed — this is meant to save a future session from re-running `find`/`ls` just to
orient itself.

## Root

```
CLAUDE.md, CLAUDE.local.md (gitignored), AGENTS.md, .mcp.json
.claude/                          this scaffold
.gitignore
README.md                         partially stale — see .claude/memory/domain-knowledge.md
Delta-Migration-Workflow-Flowchart.png, Delta-Migration-Workflow-and-Access.pdf
backend/, frontend/, db_backups/  (db_backups/ gitignored — runtime data)
```

## `backend/src/main/java/com/cloudfuze/deltatracker/`

```
config/
  SecurityConfig.java        JWT validation, permitAll fallback, per-route role gating
  WebConfig.java              CORS (localhost:3000 only) + /uploads static serving

controller/                   13 files — thin, delegate to services, return DTOs
  AdminController.java        /api/admin/users — list/upsert/remove/import-csv, ADMIN-only
  DashboardController.java    /api/dashboard/summary
  EscalationController.java   /api/escalations — list/create/resolve/open-count
  MeController.java           /api/me — current user identity + role
  PreCheckItemController.java /api/servers/{id}/precheck-items
  PreCheckSubmissionController.java  /api/servers/{id}/precheck-submission(+/submit)
  ProjectController.java      /api/projects — list/detail/create/assignments
  RosterController.java       /api/roster — managers + engineers for dropdowns
  ServerController.java       /api/servers — list/readiness/assign-project
  SignOffApprovalController.java  read-side of sign-off approvals (list/status)
  SignOffController.java      /api/servers/{id}/signoffs/{role}/approve|decline
  UploadController.java       /api/upload — generic evidence file upload
  WorkspacePairController.java /api/pairs — list/get/import (global CSV)

service/                       one per aggregate — all business logic lives here
  AppUserService.java          allowlist, roles, auto-provisioning, CSV import
  DashboardService.java        cross-project summary counts
  EmailService.java             SMTP notifications (pre-check submitted, approval needed, etc.)
  EscalationService.java        manual CRUD only — no auto-creation logic
  FileStorageService.java       saves/serves uploaded evidence files
  PreCheckItemService.java      per-item status/notes/evidence updates
  PreCheckSubmissionService.java submit-for-review preconditions + chain kickoff
  ProjectService.java           project CRUD, per-project summary/approval counts, visibility rules
  ServerService.java            server lookup, pre-check item seeding, readiness computation
  SignOffService.java           the approval chain — sequence, turn-taking, decline, finalize
  WorkspacePairService.java     CSV import (creates servers + pairs), pair CRUD

entity/                        ground truth for the data model
  AppUser.java / AppUserRole.java
  Escalation.java / EscalationPriority.java / EscalationStatus.java
  ItemStatus.java, PairStatus.java
  PreCheckItem.java, PreCheckSubmission.java
  ProductType.java
  Project.java, Server.java
  SignOff.java / SignOffRole.java / SignOffStatus.java
  SubmissionStatus.java
  WorkspacePair.java

repository/                    8 Spring Data JPA interfaces, derived queries only
  AppUserRepository, EscalationRepository, PreCheckItemRepository,
  PreCheckSubmissionRepository, ProjectRepository, ServerRepository,
  SignOffRepository, WorkspacePairRepository

dto/                           22 files — request/response/result shapes (see .claude/rules/api-conventions.md)

exception/
  ApiException.java             generic "expected" business-rule failure (status + message)
  EvidenceRequiredException.java specific 400 for missing evidence/notes/status on pre-check submit
  GlobalExceptionHandler.java    uniform {timestamp,status,error,message} envelope for every failure
  ResourceNotFoundException.java 404 helper

seed/
  AdminBootstrap.java            seeds exactly one ADMIN row on an empty app_users table at startup

util/
  CsvUtils.java                  hand-rolled quoted-CSV line parser
  JwtEmailUtil.java              extracts preferred_username/email claim from a Jwt
```

## `frontend/src/`

```
App.js                 routes, AccessGate (calls /api/me, gates Restricted/Pending screens), MSAL wiring
index.js               CRA entry point

api/client.js          every backend call the frontend makes — the practical API surface map

auth/
  authConfig.js         MSAL config, AUTH_CONFIGURED flag (true iff REACT_APP_AZURE_CLIENT_ID set)
  msalInstance.js       MSAL PublicClientApplication instance
  getAccessToken.js     returns the ID token as bearer credential (deliberate — see comment in file)
  CurrentUserContext.js React context carrying the /api/me result

pages/                 one per route
  Dashboard.js          summary cards + projects table
  ProjectsPage.js       full projects list
  ProjectDetailsPage.js assignments, CSV import, server list + drill-in
  ApprovalsPage.js      sign-off queue — pending/approved/declined filter, approve/reject actions
  EscalationsPage.js    escalation list + create/resolve
  ServerPreCheckPage.js pre-check checklist form for one server
  AdminUsersPage.js     Manage Access — single add/update + CSV bulk import
  LoginPage.js           shown when AUTH_CONFIGURED and not signed in

components/
  DataTable.js          generic sortable/filterable/searchable table used by every list page
  Modal.js               generic modal shell (title, close, width)
  Toast.js               useToast() hook + provider
  NavBar.js               sidebar links (role-gated Admin link), escalation-count poller
  StatusBadge.js          PairStatusBadge, ReadinessDot, EscalationStatusBadge, PriorityBadge
  EngineerChecklist.js    chip/search combobox for picking project team members
  CsvImportPanel.js       reusable "upload a CSV" UI (format viewer + upload button)
  WorkspacePairsPanel.js  pair list for one server
  PreCheckPanel.js        the actual checklist UI (used embedded in ServerPreCheckPage and modals)
  AttachmentPreview.js    evidence file preview/link
```

## Config files

```
backend/pom.xml                          Spring Boot 3.3.4, Java 21, PostgreSQL driver, Lombok
backend/src/main/resources/application.properties   all env-var-overridable defaults; see CLAUDE.md's table
frontend/package.json                    React 18, MSAL, axios, react-router-dom, CRA (react-scripts 5)
frontend/.env.local (gitignored)         REACT_APP_AZURE_CLIENT_ID + REACT_APP_AZURE_REDIRECT_URI
```
