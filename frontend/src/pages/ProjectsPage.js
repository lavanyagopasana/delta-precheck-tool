import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getProjects, createProject, getRoster, removeProject, updateProjectDetails, syncPmoProjects } from "../api/client";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
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
      showToast(`Project "${p.name}" deleted.`);
      load();
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

  // Only admins can trigger the pull -- POST /api/pmo/sync is ADMIN-gated in SecurityConfig, so
  // showing this to anyone else would just produce a 403. When auth is off (local dev) there is no
  // role to check, so the button shows.
  const canSyncPmo = !AUTH_CONFIGURED || currentUser?.role === "ADMIN";
  const [syncing, setSyncing] = useState(false);

  const handleSyncPmo = async () => {
    setSyncing(true);
    try {
      const r = await syncPmoProjects();
      const changed = (r.createdCount || 0) + (r.updatedCount || 0);
      if (changed === 0) {
        showToast(`PMO is up to date -- ${r.totalRows} project(s) checked, nothing new.`);
      } else {
        const managers = r.managersAssigned ? `, ${r.managersAssigned} manager(s) assigned` : "";
        showToast(`PMO sync: ${r.createdCount} added, ${r.updatedCount} updated${managers}.`);
      }
      // A PMO project manager with no matching Migration Manager account here leaves that project
      // unassigned, which also makes it invisible to managers/engineers and un-submittable. Say so --
      // otherwise it just looks like the project never arrived.
      if (r.unresolvedManagers?.length) {
        showToast(
          `No Migration Manager account matches ${r.unresolvedManagers.join(", ")} -- ` +
            `those projects need one assigning by hand.`,
          "error"
        );
      }
      // Errors are per-record and non-fatal by design (see PmoSyncResultDto) -- surface the first so
      // a partial failure isn't silently swallowed by a cheerful success toast.
      if (r.errors?.length) {
        showToast(`${r.errors.length} PMO project(s) could not be synced: ${r.errors[0]}`, "error");
      }
      load();
    } catch (err) {
      showToast(err.response?.data?.message || "Could not sync projects from PMO.", "error");
    } finally {
      setSyncing(false);
    }
  };

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

  if (loading) return <p>Loading projects...</p>;

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
            {canSyncPmo && (
              <button
                className="btn secondary"
                onClick={handleSyncPmo}
                disabled={syncing}
                title="Pull the latest project list from the PMO tool. Runs automatically every 5 minutes."
              >
                {syncing ? "Syncing..." : "Sync from PMO"}
              </button>
            )}
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
            render: (p) => (
              <span>
                {p.name}
                {p.externalId && (
                  <span
                    className="badge"
                    style={{ marginLeft: 8, fontSize: 10.5 }}
                    title={`Synced from PMO${p.externalPhase ? ` -- phase ${p.externalPhase}` : ""}`}
                  >
                    PMO
                  </span>
                )}
              </span>
            ),
          },
          {
            key: "externalCustomerName",
            label: "Customer",
            render: (p) => p.externalCustomerName || "-",
          },
          {
            // PMO's own phase for the project (KICKOFF -> ... -> DELTA -> COMPLETED). Read-only: this
            // tool does not drive it, PMO does, and it is refreshed on every poll. Useful here because
            // DELTA is precisely the phase this tracker gates, so it says which projects are close to
            // needing a pre-check. Blank for projects created by hand, which have no PMO phase at all.
            key: "externalPhase",
            label: "PMO Phase",
            render: (p) => (p.externalPhase ? humanizePhase(p.externalPhase) : "-"),
          },
          { key: "serverCount", label: "No. Server URLs", filterable: false },
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
            key: "createdBy",
            label: "Created By",
            render: (p) => (p.createdBy ? <span title={p.createdBy}>{emailLocalPart(p.createdBy)}</span> : "-"),
          },
          {
            key: "actions",
            label: "",
            sortable: false,
            filterable: false,
            render: (p) => (
              <div
                style={{ display: "flex", gap: 6, justifyContent: "center" }}
                onClick={(e) => e.stopPropagation()}
              >
                {canEditProject(p) && (
                  <button
                    type="button"
                    className="btn secondary"
                    style={{ padding: "6px 10px" }}
                    title="Edit project"
                    aria-label="Edit project"
                    onClick={() => openEdit(p)}
                  >
                    <EditIcon size={18} style={{ marginRight: 0 }} />
                  </button>
                )}
                {canDeleteProject(p) && (
                  <button
                    type="button"
                    className="btn danger"
                    style={{ padding: "6px 10px" }}
                    title="Delete project"
                    aria-label="Delete project"
                    onClick={() => handleDelete(p)}
                  >
                    <TrashIcon size={18} style={{ marginRight: 0 }} />
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
