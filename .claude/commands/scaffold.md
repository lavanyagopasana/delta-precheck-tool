---
description: Scaffold a new backend aggregate (entity+repo+service+controller+DTOs) or frontend page, following this repo's existing conventions
---

# Scaffold

Generate the boilerplate for a new backend aggregate or frontend page, matching this repo's
existing patterns exactly — don't invent a new structure. Use `$ARGUMENTS` to determine what's
being scaffolded (e.g. "backend entity called Notification belonging to Server", or "frontend page
for viewing escalation history").

First read `.claude/rules/architecture-boundaries.md` and `.claude/rules/code-style.md`, and look
at one real example of the kind of thing being scaffolded before generating anything — e.g. if
scaffolding a new entity hanging off `Server`, read `PreCheckItem.java` + `PreCheckItemRepository`
+ `PreCheckItemService` (or controller) + the relevant DTOs first, and copy their shape.

## Backend aggregate scaffold

For a new entity `X` belonging to an existing aggregate (typically `Server` or `Project`):

1. **Entity** (`entity/X.java`) — `@Entity`, `@Getter @Setter @NoArgsConstructor` (not `@Data`),
   `@ManyToOne(fetch = FetchType.LAZY)` back to its parent, a constructor taking the parent +
   required fields (see `PreCheckItem(Server server, String itemName)` as the pattern).
2. **Enum(s)** if X has a status — one enum per concept, in its own file (`entity/XStatus.java`).
3. **Repository** (`repository/XRepository.java`) — `extends JpaRepository<X, Long>` with derived
   query methods only (`findByServerId`, etc.) unless the aggregation genuinely can't be expressed
   that way — then compute it in the service instead of reaching for `@Query` JPQL first.
4. **Service** (`service/XService.java`) — constructor injection, business rules here, throws
   `ApiException`/`ResourceNotFoundException` for expected failure cases.
5. **DTOs** (`dto/XDto.java`, and `dto/XCreateRequest.java`/`XUpdateRequest.java` if it's writable)
   — never return the entity from a controller.
6. **Controller** (`controller/XController.java`) — thin: extract caller email via
   `JwtEmailUtil.extractEmail`, delegate to the service, return the DTO.
7. **Security** — add an explicit rule to `SecurityConfig.securityFilterChain` for any new route
   pattern; don't let it silently fall through without thinking about who should be allowed to
   call it (see `.claude/rules/security-rules.md`).
8. **Frontend wiring** — add the corresponding function(s) to `frontend/src/api/client.js` (never
   call `axios` directly from a page/component).

## Frontend page scaffold

1. New file under `frontend/src/pages/`, function component, hooks-based state (`useState`/
   `useEffect`), matching the load/error/loading pattern every existing page uses (see
   `ApprovalsPage.js` or `EscalationsPage.js` for the shortest clean examples).
2. Add the route in `App.js`'s `<Routes>`.
3. Add a `NavBar.js` entry if it needs to be reachable from the sidebar — follow the existing
   `{ to, icon, label }` link shape, and add an SVG icon to the `ICONS` map if needed.
4. Reuse `components/DataTable.js` for any tabular list instead of hand-rolling a table.
5. Use `useToast()` for feedback, not `alert()`.

## After scaffolding

Run `mvn -o -q compile` (backend) or let the CRA dev server's hot-reload surface errors (frontend)
before considering the scaffold done. Then hand off to `/team-review` and gstack's `/review`.
