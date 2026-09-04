import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  getProjects,
  getDeletedProjects,
  createProject,
  getRoster,
  removeProject,
  updateProjectDetails,
} from "../api/client";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
import HistoryButton from "../components/HistoryButton";
import { TrashIcon, PlusIcon, EditIcon } from "../components/Icons";
import { useConfirm } from "../components/ConfirmDialog";
import { emailLocalPart, humanizePhase } from "../utils/format";

const EMPTY_ROSTER = { migrationManagers: [], engineers: [] };

export default function ProjectsPage() {
  const navigate = useNavigate();
  const currentUser = useCurrentUser();
  const showToast = useToast();
  const confirm = useConfirm();
  const [projects, setProjects] = useState([]);
  const [roster, setRoster] = useState(EMPTY_ROSTER);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showModal, setShowModal] = useState(false);
  const [name, setName] = useState("");
  const [migrationManagerName, setMigrationManagerName] = useState("");
  const [saving, setSaving] = useState(false);

  const createdByName = AUTH_CONFIGURED ? currentUser?.email || currentUser?.name || "" : "";
  // A Migration Manager creating a project is automatically its manager -- only non-managers
  // (engineers, admins) need to pick one from the roster.
  const creatorIsManager = AUTH_CONFIGURED && currentUser?.role === "MIGRATION_MANAGER";

  // Mirrors ProjectService.canDelete: admins can always delete. Everyone else (creator / managing
  // Migration Manager) can delete ONLY an empty project -- no servers imported yet (serverCount 0).
  // Once a CSV is uploaded, deletion is admin-only.
  const canDeleteProject = (p) => {
    if (!AUTH_CONFIGURED) return true;
    const email = currentUser?.email?.toLowerCase();
    const role = currentUser?.role;
    if (!email || !role) return false;
    if (role === "ADMIN") return true;
    if (p.serverCount > 0) return false;
    if (p.createdBy?.toLowerCase() === email) return true;
    return role === "MIGRATION_MANAGER" && p.migrationManagerName?.toLowerCase() === email;
  };

  // Editing follows the exact same rule as deleting (see canDeleteProject): admins always;
  // otherwise only an empty project by its creator or Migration Manager.
  const canEditProject = canDeleteProject;

  const [editing, setEditing] = useState(null);
  const [editName, setEditName] = useState("");
  const [editMM, setEditMM] = useState("");
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState(null);

  const openEdit = (p) => {
    setEditing(p);
    setEditName(p.name || "");
    setEditMM(p.migrationManagerName || "");
    setEditError(null);
  };

  const handleUpdate = async (e) => {
    e.preventDefault();
    if (!editName.trim()) return;
    setEditSaving(true);
    setEditError(null);
    try {
      await updateProjectDetails(editing.id, {
        name: editName.trim(),
        migrationManagerName: editMM || null,
      });
      showToast(`Project "${editName.trim()}" updated.`, "success");
      setEditing(null);
      load();
    } catch (err) {
      setEditError(err.response?.data?.message || "Failed to update project.");
    } finally {
      setEditSaving(false);
    }
  };

  const handleDelete = async (p) => {
    const ok = await confirm({
      title: `Delete project "${p.name}"?`,
      message: `This permanently removes its ${p.serverCount} server(s) and all their pre-checks, sign-offs, migration pairs, and tickets. This cannot be undone.`,
      confirmLabel: "Delete Project",
      danger: true,
    });
    if (!ok) return;
    try {
      await removeProject(p.id);
      // Drop the row from state rather than refetching the whole list. The delete has already
      // succeeded by this point, and its effect on this page is exactly "this row is gone" -- so a
      // round trip to be told that buys nothing and costs a visible reload. Anything the server
      // recomputed (counts on other rows) is untouched by removing one project, and the next
      // natural load picks up anything else.
      setProjects((current) => current.filter((row) => row.id !== p.id));
      showToast(`Project "${p.name}" deleted.`);
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to delete project.");
    }
  };

  const load = () => {
    setLoading(true);
    getProjects()
      .then((data) => {
        setProjects(data);
        setError(null);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load projects."))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);
  useEffect(() => {
    getRoster()
      .then(setRoster)
      .catch(() => {});
  }, []);

  const resetForm = () => {
    setShowModal(false);
    setName("");
    setMigrationManagerName("");
    setError(null);
  };

  const handleCreate = async (e) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || (!creatorIsManager && !migrationManagerName)) return;
    setSaving(true);
    setError(null);
    try {
      const project = await createProject({
        name: trimmed,
        createdBy: createdByName,
        migrationManagerName: creatorIsManager ? null : migrationManagerName,
      });
      showToast(`Project "${trimmed}" created.`);
      resetForm();
      load();
      navigate(`/projects/${project.id}`);
    } catch (err) {
      setError(err.response?.data?.message || "Failed to create project.");
    } finally {
      setSaving(false);
    }
  };

  // Only blanks the screen before there is anything to show. A plain `if (loading)` replaced the
  // entire rendered page on EVERY refresh, so any action that reloaded -- deleting a project most
  // visibly -- flashed the heading, filters and table out to a bare "Loading..." line and back.
  // Once data is on screen it stays there while the next fetch runs. Matches Dashboard.js, which
  // already guarded with `loading && !summary`.
  if (loading && projects.length === 0) return <p>Loading projects...</p>;

  return (
    <div>
      {showModal && (
        <Modal title="Create a project" onClose={resetForm}>
          <form onSubmit={handleCreate}>
            <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
              <div style={{ flex: "1 1 220px" }}>
                <label htmlFor="project-name" style={{ display: "block", fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>
                  Project Name <span style={{ color: "var(--color-red)" }}>*</span>
                </label>
                <input
                  id="project-name"
                  type="text"
                  placeholder="Project name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  style={{ width: "100%" }}
                />
              </div>

              {!creatorIsManager && (
                <div style={{ flex: "1 1 220px" }}>
                  <label style={{ display: "block", fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>
                    Migration Manager <span style={{ color: "var(--color-red)" }}>*</span>
                  </label>
                  <select
                    value={migrationManagerName}
                    onChange={(e) => setMigrationManagerName(e.target.value)}
                    style={{ width: "100%" }}
                  >
                    <option value="">Select...</option>
                    {roster.migrationManagers.map((email) => (
                      <option key={email} value={email}>
                        {email}
                      </option>
                    ))}
                  </select>
                </div>
              )}
            </div>

            {error && <div className="inline-hint" style={{ marginTop: 14 }}>{error}</div>}

            <div className="form-actions">
              <button
                className="btn"
                type="submit"
                disabled={saving || !name.trim() || (!creatorIsManager && !migrationManagerName)}
              >
                {saving ? "Creating..." : "Create Project"}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {editing && (
        <Modal title={`Edit "${editing.name}"`} onClose={() => setEditing(null)} closeIcon>
          <form onSubmit={handleUpdate}>
            <div>
              <label style={{ display: "block", fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>
                Project Name <span style={{ color: "var(--color-red)" }}>*</span>
              </label>
              <input type="text" value={editName} onChange={(e) => setEditName(e.target.value)} style={{ width: "100%" }} />
            </div>

            <div style={{ marginTop: 14 }}>
              <label style={{ display: "block", fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>
                Migration Manager
              </label>
              <select value={editMM} onChange={(e) => setEditMM(e.target.value)} style={{ width: "100%", maxWidth: 300 }}>
                <option value="">— Not assigned —</option>
                {editMM && !roster.migrationManagers.includes(editMM) && (
                  <option value={editMM}>{editMM}</option>
                )}
                {roster.migrationManagers.map((email) => (
                  <option key={email} value={email}>
                    {email}
                  </option>
                ))}
              </select>
              <div style={{ fontSize: 11.5, color: "var(--color-text-faint)", marginTop: 4 }}>
                Changing the manager rolls approvals back to the manager step — the pre-check stays exactly as it is,
                and the chain continues from the new manager (any Delta is un-initiated).
              </div>
            </div>

            {editError && <div className="inline-hint" style={{ marginTop: 14 }}>{editError}</div>}

            <div className="form-actions">
              <button className="btn" type="submit" disabled={editSaving || !editName.trim()}>
                {editSaving ? "Saving..." : "Save Changes"}
              </button>
            </div>
          </form>
        </Modal>
      )}

      <DataTable
        title="Projects"
        rows={projects}
        rowKey={(p) => p.id}
        onRowClick={(p) => navigate(`/projects/${p.id}`)}
        searchPlaceholder="Filter projects..."
        emptyMessage="No projects yet. Click Add Project above."
        toolbarRight={
          <div style={{ display: "flex", gap: 8 }}>
            {/* Visible to everyone, not gated: a deleted project has no page of its own left to view
                its history on, so this list is the only place a deletion stays visible afterwards. */}
            <HistoryButton
              label="Recently deleted projects"
              title="Recently deleted projects"
              sections={[
                {
                  title: "Deleted projects",
                  fetch: getDeletedProjects,
                  emptyText: "No project has been deleted yet.",
                  kind: "change",
                },
              ]}
            />
            <button className="btn" onClick={() => setShowModal(true)}>
              <PlusIcon />
              Add Project
            </button>
          </div>
        }
        columns={[
          {
            key: "name",
            label: "Project",
            // The badge sits on its own line UNDER the name, not inline after it. Project names here
            // are long enough to wrap over three or four lines, and an inline badge lands wherever the
            // last line happens to end -- which reads as part of the name rather than as a label about
            // it. A block below the name is always in the same place.
            render: (p) => (
              <div>
                <div>{p.name}</div>
                {p.externalId && (
                  <span
                    className="badge"
                    style={{ marginTop: 4, fontSize: 10.5 }}
                    title={`Synced from PMO${p.externalPhase ? ` -- phase ${p.externalPhase}` : ""}`}
                  >
                    PMO
                  </span>
                )}
              </div>
            ),
          },
          {
            key: "externalCustomerName",
            label: "Customer",
            render: (p) => p.externalCustomerName || "-",
          },
          // Centred: a single-character count sitting at the left edge of a three-word header does not
          // read as belonging to it.
          { key: "serverCount", label: "No. Server URLs", filterable: false, align: "center" },
          {
            key: "migrationManagers",
            label: "Migration Manager",
            sortable: false,
            filterable: false,
            render: (p) =>
              p.migrationManagers?.length ? (
                <span title={p.migrationManagers.join(", ")}>
                  {p.migrationManagers.map(emailLocalPart).join(", ")}
                </span>
              ) : p.externalManagerName ? (
                // PMO gives us a display name, not an email, so it can't be assigned automatically
                // (migrationManagerName is matched as an email everywhere). Show it as a hint about
                // who to pick, clearly marked so it doesn't read as an assignment that already happened.
                <span style={{ opacity: 0.75 }} title={`PMO project manager: ${p.externalManagerName}. Not assigned here yet.`}>
                  Not assigned ({p.externalManagerName} in PMO)
                </span>
              ) : (
                "Not assigned yet"
              ),
          },
          {
            // PMO's own phase for the project (KICKOFF -> ... -> DELTA -> COMPLETED). Read-only: this
            // tool does not drive it, PMO does, and it is refreshed on every poll. Useful here because
            // DELTA is precisely the phase this tracker gates, so it says which projects are close to
            // needing a pre-check. Blank for projects created by hand, which have no PMO phase at all.
            //
            // Last of the data columns, by request. The actions column after it carries no label and
            // holds the edit/delete buttons, so this is the rightmost thing on the row that reads as
            // information about the project.
            key: "externalPhase",
            label: "PMO Phase",
            render: (p) => (p.externalPhase ? humanizePhase(p.externalPhase) : "-"),
          },
          // No "Created By" column. Every PMO-synced project carried the literal string "PMO sync"
          // there (PmoSyncService.SYNC_CREATED_BY), so on a list that is almost entirely PMO projects
          // it was a full column repeating what the PMO badge under the project name already says.
          // The field itself is untouched -- it still drives the can-edit/can-delete checks below.
          {
            key: "actions",
            label: "",
            sortable: false,
            filterable: false,
            align: "center",
            // Uses the .row-actions/.row-action pattern already established on the Tickets page rather
            // than solid .btn/.btn.danger. Two reasons: a filled red block per row made Delete shout
            // louder than the row's own content, and Projects was the only table styling its row
            // actions differently from every other table in the app.
            render: (p) => (
              <div className="row-actions" onClick={(e) => e.stopPropagation()}>
                {canEditProject(p) && (
                  <button
                    type="button"
                    className="row-action"
                    title="Edit project"
                    aria-label="Edit project"
                    onClick={() => openEdit(p)}
                  >
                    <EditIcon size={17} style={{ marginRight: 0 }} />
                  </button>
                )}
                {canDeleteProject(p) && (
                  <button
                    type="button"
                    className="row-action danger"
                    title="Delete project"
                    aria-label="Delete project"
                    onClick={() => handleDelete(p)}
                  >
                    <TrashIcon size={17} style={{ marginRight: 0 }} />
                  </button>
                )}
              </div>
            ),
          },
        ]}
      />
    </div>
  );
}
