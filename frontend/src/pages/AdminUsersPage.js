import React, { useEffect, useMemo, useState } from "react";
import {
  getAllowedUsers, upsertAllowedUser, removeAllowedUser, importUsersCsv,
  getTeams, createTeam, removeTeam, assignUserTeam,
} from "../api/client";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { useToast } from "../components/Toast";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
import { TrashIcon, EditIcon } from "../components/Icons";
import { useConfirm } from "../components/ConfirmDialog";
import { apiErrorMessage } from "../utils/apiError";
import { emailLocalPart } from "../utils/format";

const ROLE_OPTIONS = [
  { value: "MIGRATION_ENGINEER", label: "Migration Engineer" },
  { value: "MIGRATION_MANAGER", label: "Migration Manager" },
  { value: "DEV_LEAD", label: "Dev Lead" },
  { value: "QA_LEAD", label: "QA Lead" },
  { value: "ADMIN", label: "Admin" },
];

const roleLabel = (role) => ROLE_OPTIONS.find((r) => r.value === role)?.label || role;

// The only two roles a team is meaningful for. Mirrors the backend, where Team membership exists to
// scope a Migration Manager's engineer picker and nothing else.
const TEAM_ROLES = ["MIGRATION_MANAGER", "MIGRATION_ENGINEER"];

// Consistent accent color per role, reused by the summary strip and the row indicators.
const ROLE_COLOR = {
  ADMIN: "var(--color-red)",
  MIGRATION_MANAGER: "var(--color-primary)",
  DEV_LEAD: "var(--color-yellow)",
  QA_LEAD: "var(--color-green)",
  MIGRATION_ENGINEER: "var(--color-text-muted)",
};
const roleColor = (role) => ROLE_COLOR[role] || "var(--color-text-muted)";

// A team IS its managers, as far as anyone reading the screen is concerned: "manager1/manager2 team".
// Derived from the current managers rather than stored as the row name on purpose -- a name baked
// from a person goes stale the moment they move teams, leaving a team called after somebody who no
// longer runs it. Deriving means the displayed name follows reality with no rename step.
//
// The stored name (Team 1..Team 6) is still shown as secondary text, because that is the string the
// CSV team column and the SQL seed match on.
const teamDisplayName = (team) => {
  const managers = team.managerEmails || [];
  // No manager -> fall back to the team's own name. Returning a generic "Unmanaged team" here
  // replaced the one piece of identity the card had, so several such teams were indistinguishable
  // and you could not tell which one to fix. The missing manager is stated in the meta row instead.
  if (managers.length === 0) return team.name;
  return `${managers.map(emailLocalPart).join("/")} team`;
};

export default function AdminUsersPage() {
  const currentUser = useCurrentUser();
  const showToast = useToast();
  const confirm = useConfirm();
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("MIGRATION_ENGINEER");
  const [saving, setSaving] = useState(false);
  const [removingEmail, setRemovingEmail] = useState(null);
  const [editUser, setEditUser] = useState(null);
  const [editRole, setEditRole] = useState("MIGRATION_ENGINEER");
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState(null);
  const [csvModalOpen, setCsvModalOpen] = useState(false);
  const [csvFile, setCsvFile] = useState(null);
  const [csvRole, setCsvRole] = useState("MIGRATION_ENGINEER");
  const [csvSaving, setCsvSaving] = useState(false);
  const [csvError, setCsvError] = useState(null);
  // "ALL" rather than "" so the value is never falsy-ambiguous with a real role.
  const [roleFilter, setRoleFilter] = useState("ALL");
  // "ALL" = every team, "NONE" = only people with no team. "NONE" is a real case worth filtering
  // for: someone with no team falls back to the unfiltered engineer list, which is the state an
  // admin most often needs to hunt down.
  const [teamFilter, setTeamFilter] = useState("ALL");
  const [teams, setTeams] = useState([]);
  const [newTeamName, setNewTeamName] = useState("");
  const [teamSaving, setTeamSaving] = useState(false);

  const load = () => {
    setLoading(true);
    getAllowedUsers()
      .then((data) => {
        setUsers(data);
        setError(null);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load users."))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  // Tracked separately from `teams` so a FAILED load never renders as "No teams yet". It did, and it
  // was actively misleading: against a backend without /api/teams the request 404s, the catch
  // swallowed it, and the panel confidently reported an empty roster that actually had six teams in
  // it. "Couldn't load" and "there are none" have to look different.
  const [teamsError, setTeamsError] = useState(null);
  const loadTeams = () =>
    getTeams()
      .then((data) => {
        setTeams(data);
        setTeamsError(null);
      })
      .catch((err) => setTeamsError(apiErrorMessage(err, "Couldn't load teams.")));
  useEffect(() => {
    loadTeams();
  }, []);

  const handleCreateTeam = async (e) => {
    e.preventDefault();
    const trimmed = newTeamName.trim();
    if (!trimmed) return;
    setTeamSaving(true);
    try {
      await createTeam({ name: trimmed });
      showToast(`Team "${trimmed}" created.`, "success");
      setNewTeamName("");
      await loadTeams();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to create team.", "error");
    } finally {
      setTeamSaving(false);
    }
  };

  const handleRemoveTeam = async (team) => {
    const ok = await confirm({
      title: `Delete ${team.name}?`,
      // Worth stating plainly: deleting a team is not a destructive act against people. It only
      // widens their engineer dropdowns back to unfiltered until they are put on another team.
      message: `${team.memberEmails?.length || 0} member(s) will be left without a team. Nobody loses access.`,
      confirmLabel: "Delete team",
      danger: true,
    });
    if (!ok) return;
    try {
      await removeTeam(team.id);
      showToast(`Team "${team.name}" deleted.`, "success");
      await Promise.all([loadTeams(), getAllowedUsers().then(setUsers).catch(() => {})]);
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to delete team.", "error");
    }
  };

  const handleAssignTeam = async (user, rawTeamId) => {
    const teamId = rawTeamId ? Number(rawTeamId) : null;
    try {
      await assignUserTeam(user.email, teamId);
      showToast(teamId ? `${user.email} moved.` : `${user.email} removed from their team.`, "success");
      await Promise.all([getAllowedUsers().then(setUsers).catch(() => {}), loadTeams()]);
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to change team.", "error");
    }
  };

  const counts = useMemo(() => {
    const c = { total: users.length, ADMIN: 0, MIGRATION_MANAGER: 0, DEV_LEAD: 0, QA_LEAD: 0, MIGRATION_ENGINEER: 0 };
    users.forEach((u) => {
      if (c[u.role] !== undefined) c[u.role] += 1;
    });
    return c;
  }, [users]);

  // Role filtering happens here rather than inside DataTable because DataTable's own filtering is
  // free-text across columns; a role dropdown is an exact-match narrow, and the two need to compose.
  const visibleUsers = useMemo(() => {
    // Role and team compose (both must match), the same way each already composes with DataTable's
    // free-text search, rather than one silently replacing the other.
    let rows = roleFilter === "ALL" ? users : users.filter((u) => u.role === roleFilter);
    if (teamFilter === "NONE") {
      rows = rows.filter((u) => !u.teamId);
    } else if (teamFilter !== "ALL") {
      rows = rows.filter((u) => String(u.teamId) === teamFilter);
    }
    return rows;
  }, [users, roleFilter, teamFilter]);

  // Counted off the full user list, not the filtered one, so the numbers in this dropdown do not
  // shift as the role filter narrows -- a count that changes when you touch a different control
  // reads as a bug.
  const teamCounts = useMemo(() => {
    const c = { NONE: 0 };
    users.forEach((u) => {
      if (!u.teamId) c.NONE += 1;
      else c[u.teamId] = (c[u.teamId] || 0) + 1;
    });
    return c;
  }, [users]);

  const handleAdd = async (e) => {
    e.preventDefault();
    const trimmed = email.trim();
    if (!trimmed) return;
    setSaving(true);
    setError(null);
    try {
      await upsertAllowedUser({ email: trimmed, role });
      showToast(`${trimmed} added as ${roleLabel(role)}.`);
      setEmail("");
      setRole("MIGRATION_ENGINEER");
      load();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to add user.");
    } finally {
      setSaving(false);
    }
  };

  const closeCsvModal = () => {
    setCsvModalOpen(false);
    setCsvFile(null);
    setCsvRole("MIGRATION_ENGINEER");
    setCsvError(null);
  };

  const handleCsvImport = async (e) => {
    e.preventDefault();
    if (!csvFile) return;
    setCsvSaving(true);
    setCsvError(null);
    try {
      const result = await importUsersCsv(csvFile, csvRole);
      const parts = [`${result.createdCount} added`, `${result.updatedCount} updated`];
      // Teams the file named that didn't exist yet. Surfaced because a mistyped team cell shows up
      // here as a team nobody meant to create, which is the moment it is cheapest to notice.
      if (result.createdTeams?.length) parts.push(`${result.createdTeams.length} team(s) created`);
      if (result.errors?.length) parts.push(`${result.errors.length} skipped`);
      // Deliberately no longer claims a single role ("... as Migration Engineer"): rows can now each
      // carry their own, so naming the default would be wrong for any mixed-role file.
      showToast(`${parts.join(", ")}.`);

      // Row errors used to vanish -- the modal closed on success and the toast only counted them, so
      // an admin importing 30 people had no way to see WHICH rows were skipped or why. Keep the modal
      // open and show them; only auto-close when every row landed.
      // loadTeams() as well as load(): the import can now create teams, so the Teams panel and the
      // per-user Team dropdowns are both stale until it reruns.
      if (result.errors?.length) {
        setCsvError(result.errors.join("\n"));
        load();
        loadTeams();
      } else {
        closeCsvModal();
        load();
        loadTeams();
      }
    } catch (err) {
      setCsvError(err.response?.data?.message || "Failed to import CSV.");
    } finally {
      setCsvSaving(false);
    }
  };

  const openEdit = (user) => {
    setEditUser(user);
    setEditRole(user.role);
    setEditError(null);
  };

  const closeEdit = () => {
    setEditUser(null);
    setEditError(null);
  };

  const handleEditSave = async (e) => {
    e.preventDefault();
    if (!editUser) return;
    if (editRole === editUser.role) {
      closeEdit();
      return;
    }
    setEditSaving(true);
    setEditError(null);
    try {
      await upsertAllowedUser({ email: editUser.email, role: editRole });
      showToast(`${editUser.email} is now ${roleLabel(editRole)}.`);
      closeEdit();
      load();
    } catch (err) {
      setEditError(err.response?.data?.message || "Failed to update role.");
    } finally {
      setEditSaving(false);
    }
  };

  const handleRemove = async (user) => {
    const ok = await confirm({
      title: `Remove ${user.email}?`,
      message: "They will lose access to the app immediately.",
      confirmLabel: "Remove",
      danger: true,
    });
    if (!ok) return;
    setRemovingEmail(user.email);
    setError(null);
    try {
      await removeAllowedUser(user.email);
      showToast(`${user.email} removed.`);
      load();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to remove user.");
    } finally {
      setRemovingEmail(null);
    }
  };

  if (loading) return <p>Loading users...</p>;

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <h2 style={{ margin: 0 }}>Manage Access</h2>
        <button className="btn" onClick={() => setCsvModalOpen(true)}>
          Add Users via CSV
        </button>
      </div>
      <p style={{ color: "var(--color-text-muted)", marginTop: -10, marginBottom: 20 }}>
        Only people added here can sign in, even if their email is @cloudfuze.com.
      </p>

      <div className="card-row card-row--nowrap" style={{ marginBottom: 20 }}>
        <div className="stat-card">
          <div className="value">{counts.total}</div>
          <div className="label">Total Users</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("ADMIN") }}>{counts.ADMIN}</div>
          <div className="label">Admins</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("MIGRATION_MANAGER") }}>{counts.MIGRATION_MANAGER}</div>
          <div className="label">Migration Managers</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("DEV_LEAD") }}>{counts.DEV_LEAD}</div>
          <div className="label">Dev Leads</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("QA_LEAD") }}>{counts.QA_LEAD}</div>
          <div className="label">QA Leads</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("MIGRATION_ENGINEER") }}>{counts.MIGRATION_ENGINEER}</div>
          <div className="label">Engineers</div>
        </div>
      </div>

      <div className="card">
        <strong style={{ fontSize: 14 }}>Add a user</strong>
        <form onSubmit={handleAdd} style={{ marginTop: 12, display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 12.5, fontWeight: 600, color: "var(--color-text-muted)" }}>Email</label>
            <input
              type="text"
              placeholder="name@cloudfuze.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              style={{ width: 260 }}
            />
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 12.5, fontWeight: 600, color: "var(--color-text-muted)" }}>Role</label>
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              {ROLE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
          <button className="btn" type="submit" disabled={saving || !email.trim()}>
            {saving ? "Adding..." : "Add"}
          </button>
        </form>
      </div>

      {error && <div className="inline-hint" style={{ marginBottom: 12 }}>{error}</div>}

      {/* Teams decide which engineers each Migration Manager can assign on a project. Kept on this
          page rather than its own route because it is the same job as Manage Access -- deciding who
          may do what -- and an admin setting up a team needs the user list in front of them. */}
      <div className="card" style={{ marginBottom: 16 }}>
        <h3 style={{ margin: "0 0 4px", fontSize: 15 }}>Teams</h3>
        <p style={{ margin: "0 0 12px", fontSize: 12.5, color: "var(--color-text-muted)" }}>
          A project's engineer picker only offers engineers on that project's Migration Manager's
          team. A team can have more than one manager; both see the same engineers.
        </p>
        {/* type="text" is load-bearing, not decoration: index.css styles input[type="text"], and an
            input with no type attribute does not match that selector -- which is why this box
            rendered completely unstyled next to the identical-looking one in "Add a user". */}
        <form
          onSubmit={handleCreateTeam}
          style={{ display: "flex", gap: 12, marginBottom: 16, flexWrap: "wrap", alignItems: "flex-end" }}
        >
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 12.5, fontWeight: 600, color: "var(--color-text-muted)" }}>
              New team
            </label>
            <input
              type="text"
              value={newTeamName}
              onChange={(e) => setNewTeamName(e.target.value)}
              placeholder="e.g. Team 7"
              style={{ width: 260 }}
            />
          </div>
          <button className="btn" type="submit" disabled={teamSaving || !newTeamName.trim()}>
            {teamSaving ? "Creating..." : "Add team"}
          </button>
        </form>
        {teamsError ? (
          <div className="inline-hint">
            {teamsError} If the backend was just updated, it may still be running an older build
            without the teams endpoint — restart it and reload.
          </div>
        ) : teams.length === 0 ? (
          <div className="inline-hint">
            No teams yet. Until a manager is on a team, their projects list every engineer.
          </div>
        ) : (
          <div className="team-grid">
            {teams.map((t) => {
              const managers = t.managerEmails || [];
              const engineers = t.engineerEmails || [];
              const unmanaged = managers.length === 0;
              return (
                <div key={t.id} className={`team-card${unmanaged ? " team-card--unmanaged" : ""}`}>
                  <div className="team-card-head">
                    {/* Managers lead, because that is what a reader scans for. The stored name sits
                        underneath because it is the string the CSV team column matches on. */}
                    <span className="team-card-name" title={managers.join(", ")}>
                      {teamDisplayName(t)}
                    </span>
                    <button
                      type="button"
                      className="team-card-delete"
                      onClick={() => handleRemoveTeam(t)}
                      aria-label={`Delete ${t.name}`}
                      title={`Delete ${t.name}`}
                    >
                      <TrashIcon />
                    </button>
                  </div>
                  <div className="team-card-meta">
                    {/* The stored name is only worth repeating when it is NOT already the heading,
                        i.e. when managers named the card. It is what the CSV team column matches. */}
                    {!unmanaged && <span className="team-card-tag">{t.name}</span>}
                    {unmanaged && <span className="team-card-tag team-card-tag--warn">no manager yet</span>}
                    <span>
                      {engineers.length} engineer{engineers.length === 1 ? "" : "s"}
                    </span>
                    {managers.length > 1 && <span>{managers.length} managers</span>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Role filter lives in DataTable's toolbarRight, next to the search box, matching how
          Approvals and Project details present their filters. It composes with the free-text search
          (both must match) rather than replacing it: the text box is still the way to find one
          person by email, while this narrows to a whole role -- useful now that a single CSV import
          can add 20 people across five roles at once. */}
      <div className="users-table">
      <DataTable
        rows={visibleUsers}
        rowKey={(u) => u.email}
        searchPlaceholder="Search users by email or role..."
        emptyMessage={
          roleFilter === "ALL" && teamFilter === "ALL"
            ? "No users yet."
            : "No users match these filters."
        }
        toolbarRight={
          <>
            <label htmlFor="role-filter" className="sr-only">
              Filter by role
            </label>
            <select
              id="role-filter"
              value={roleFilter}
              onChange={(e) => setRoleFilter(e.target.value)}
              aria-label="Filter by role"
            >
              <option value="ALL">All roles ({counts.total})</option>
              {ROLE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label} ({counts[opt.value] || 0})
                </option>
              ))}
            </select>
            <label htmlFor="team-filter" className="sr-only">
              Filter by team
            </label>
            <select
              id="team-filter"
              value={teamFilter}
              onChange={(e) => setTeamFilter(e.target.value)}
              aria-label="Filter by team"
            >
              <option value="ALL">All teams ({counts.total})</option>
              <option value="NONE">No team ({teamCounts.NONE || 0})</option>
              {teams.map((t) => (
                <option key={t.id} value={String(t.id)}>
                  {teamDisplayName(t)} ({teamCounts[t.id] || 0})
                </option>
              ))}
            </select>
          </>
        }
        columns={[
          {
            key: "email",
            label: "Email",
            render: (u) => (
              <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
                <span>
                  {u.email}
                  {currentUser?.email?.toLowerCase() === u.email.toLowerCase() && (
                    <span style={{ color: "var(--color-text-faint)", fontSize: 12 }}> (you)</span>
                  )}
                </span>
                {/* Folded in from what used to be its own "Added" column. A full timestamp needed a
                    quarter of the table width and wrapped onto three lines to show something nobody
                    scans for; as a date under the email it costs no width and stays available, with
                    the exact time on hover. */}
                <span
                  style={{ fontSize: 11, color: "var(--color-text-faint)" }}
                  title={`Added ${new Date(u.addedAt).toLocaleString()}`}
                >
                  added {new Date(u.addedAt).toLocaleDateString()}
                </span>
              </div>
            ),
          },
          {
            key: "role",
            label: "Role",
            filterValue: (u) => roleLabel(u.role),
            render: (u) => (
              <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
                <span
                  aria-hidden="true"
                  style={{
                    width: 9,
                    height: 9,
                    borderRadius: "50%",
                    flexShrink: 0,
                    background: roleColor(u.role),
                  }}
                />
                {roleLabel(u.role)}
              </span>
            ),
          },
          {
            key: "team",
            label: "Team",
            filterValue: (u) => u.teamName || "",
            // Editable inline rather than behind the Edit modal: team is the one field an admin
            // changes in bulk while reading down the list, and the modal only handles role.
            render: (u) => {
              // Teams only decide which engineers a Migration Manager may assign, so they mean
              // nothing for ADMIN / DEV_LEAD / QA_LEAD -- the same Dev/QA Lead covers every team.
              // Rendering a live dropdown on those rows invited a change that has no effect, and
              // read as clutter on every admin row.
              if (!TEAM_ROLES.includes(u.role)) {
                return (
                  <span
                    style={{ color: "var(--color-text-faint)", fontSize: 12.5 }}
                    title={`${roleLabel(u.role)}s are not on a team — teams only scope a Migration Manager's engineer list.`}
                  >
                    &mdash;
                  </span>
                );
              }
              return (
                <select
                  className="team-cell-select"
                  value={u.teamId || ""}
                  onChange={(e) => handleAssignTeam(u, e.target.value)}
                  aria-label={`Team for ${u.email}`}
                >
                  <option value="">No team</option>
                  {teams.map((t) => (
                    <option key={t.id} value={t.id}>{teamDisplayName(t)} ({t.name})</option>
                  ))}
                </select>
              );
            },
          },
          {
            key: "actions",
            label: "",
            sortable: false,
            filterable: false,
            render: (u) => {
              const isSelf = currentUser?.email?.toLowerCase() === u.email.toLowerCase();
              return (
                <div style={{ display: "flex", gap: 8 }}>
                  <button
                    className="btn secondary"
                    style={{ padding: "6px 10px" }}
                    onClick={() => openEdit(u)}
                    disabled={isSelf}
                    title={isSelf ? "You can't change your own role -- another admin must." : "Edit role"}
                    aria-label="Edit role"
                  >
                    <EditIcon size={18} style={{ marginRight: 0 }} />
                  </button>
                  <button
                    className="btn secondary"
                    style={{ padding: "6px 10px" }}
                    onClick={() => handleRemove(u)}
                    disabled={removingEmail === u.email || isSelf}
                    title={isSelf ? "You can't remove your own access -- another admin must." : "Remove user"}
                    aria-label="Remove user"
                  >
                    {removingEmail === u.email ? (
                      <span className="spinner" />
                    ) : (
                      <TrashIcon size={18} style={{ marginRight: 0 }} />
                    )}
                  </button>
                </div>
              );
            },
          },
        ]}
      />
      </div>

      {editUser && (
        <Modal title="Edit role" onClose={closeEdit} width={420} closeIcon>
          <form onSubmit={handleEditSave}>
            <p style={{ marginTop: 0, marginBottom: 14, color: "var(--color-text-muted)", fontSize: 13 }}>
              {editUser.email}
            </p>
            <div style={{ marginBottom: 8 }}>
              <label style={{ display: "block", fontSize: 13, fontWeight: 600, marginBottom: 6 }}>Role</label>
              <select value={editRole} onChange={(e) => setEditRole(e.target.value)} style={{ width: "100%" }}>
                {ROLE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>

            {editError && <div className="inline-hint" style={{ marginBottom: 12 }}>{editError}</div>}

            <div className="form-actions" style={{ justifyContent: "flex-end", gap: 8 }}>
              <button type="button" className="btn secondary" onClick={closeEdit} disabled={editSaving}>
                Cancel
              </button>
              <button type="submit" className="btn" disabled={editSaving || editRole === editUser.role}>
                {editSaving ? "Saving..." : "Save"}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {csvModalOpen && (
        <Modal title="Add Users via CSV" onClose={closeCsvModal} width={420} closeIcon>
          <form onSubmit={handleCsvImport}>
            {/* Relabelled from "Role for everyone in this file": the file may now carry a per-person
                role column, in which case this is only the fallback for rows that leave it blank. */}
            <div style={{ marginBottom: 14 }}>
              <label htmlFor="csv-default-role" style={{ display: "block", fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
                Default role
              </label>
              <select
                id="csv-default-role"
                value={csvRole}
                onChange={(e) => setCsvRole(e.target.value)}
                style={{ width: "100%" }}
              >
                {ROLE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
              <p style={{ fontSize: 12, color: "var(--color-text-faint)", margin: "6px 0 0" }}>
                Used only for rows that don't specify their own role.
              </p>
            </div>

            <div style={{ marginBottom: 8 }}>
              <label style={{ display: "block", fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
                CSV file
              </label>
              <input
                type="file"
                accept=".csv"
                onChange={(e) => setCsvFile(e.target.files[0] || null)}
                style={{ width: "100%" }}
              />
            </div>
            <p style={{ fontSize: 12, color: "var(--color-text-faint)", marginTop: 0, marginBottom: 8 }}>
              One person per row. Add a <strong>role</strong> column to give each person their own
              role in a single file; leave a cell blank to use the default above. Columns named
              "email" and "role" are auto-detected in any order. With no header row, the first column
              is treated as the email.
            </p>
            <pre
              style={{
                fontSize: 11.5,
                background: "var(--color-gray-soft)",
                color: "var(--color-text-muted)",
                padding: "8px 10px",
                borderRadius: 6,
                margin: "0 0 10px",
                overflowX: "auto",
              }}
            >
{`email,role
asha@cloudfuze.com,Migration Manager
ravi@cloudfuze.com,Dev Lead
priya@cloudfuze.com,QA Lead
sam@cloudfuze.com,`}
            </pre>

            {/* pre-line so per-row errors (joined with \n) each get their own line instead of running
                together, and a max height so a 50-bad-row file scrolls rather than pushing the
                buttons off screen. */}
            {csvError && (
              <div
                className="inline-hint"
                style={{ marginBottom: 12, whiteSpace: "pre-line", maxHeight: 180, overflowY: "auto" }}
              >
                {csvError}
              </div>
            )}

            <div className="form-actions" style={{ justifyContent: "flex-end", gap: 8 }}>
              <button type="button" className="btn secondary" onClick={closeCsvModal} disabled={csvSaving}>
                Cancel
              </button>
              <button type="submit" className="btn" disabled={csvSaving || !csvFile}>
                {csvSaving ? "Creating..." : "Create"}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
