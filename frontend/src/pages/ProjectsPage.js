import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getProjects, createProject, getRoster, removeProject, updateProjectDetails } from "../api/client";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
import { TrashIcon, PlusIcon, EditIcon } from "../components/Icons";
import { useConfirm } from "../components/ConfirmDialog";

const PRODUCT_TYPE_OPTIONS = [
  { value: "MESSAGE", label: "Message" },
  { value: "EMAIL", label: "Email" },
  { value: "CONTENT", label: "Content" },
];

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
  const [productType, setProductType] = useState("");
  const [migrationManagerName, setMigrationManagerName] = useState("");
  const [saving, setSaving] = useState(false);

  const createdByName = AUTH_CONFIGURED ? currentUser?.email || currentUser?.name || "" : "";
  // A Migration Manager creating a project is automatically its manager -- only non-managers
  // (engineers, admins) need to pick one from the roster.
  const creatorIsManager = AUTH_CONFIGURED && currentUser?.role === "MIGRATION_MANAGER";

  // Mirrors ProjectService.canDelete so the button only shows when the backend would allow it:
  // admin, the managing Migration Manager, or the creator while nothing has been APPROVED yet (a
  // just-submitted project is still deletable by its creator -- only an actual approval locks it).
  // The server re-checks and also blocks Delta-initiated projects.
  const canDeleteProject = (p) => {
    if (!AUTH_CONFIGURED) return true;
    const email = currentUser?.email?.toLowerCase();
    const role = currentUser?.role;
    if (!email || !role) return false;
    if (role === "ADMIN") return true;
    if (role === "MIGRATION_MANAGER" && p.migrationManagerName?.toLowerCase() === email) return true;
    const approvalStarted = p.migrationManagerApprovalsDone > 0 || p.devApprovalsDone > 0;
    if (p.createdBy?.toLowerCase() === email && !approvalStarted) return true;
    return false;
  };

  // Mirrors ProjectService.canEditDetails: admin, the current MM, the creator, or an assigned
  // engineer can edit the project's details (name / product type / Migration Manager).
  const canEditProject = (p) => {
    if (!AUTH_CONFIGURED) return true;
    const email = currentUser?.email?.toLowerCase();
    const role = currentUser?.role;
    if (!email || !role) return false;
    if (role === "ADMIN") return true;
    if (role === "MIGRATION_MANAGER") return p.migrationManagerName?.toLowerCase() === email;
    if (role === "MIGRATION_ENGINEER")
      return (
        p.createdBy?.toLowerCase() === email ||
        (p.engineerEmails || []).some((x) => x?.toLowerCase() === email)
      );
    return false;
  };

  const [editing, setEditing] = useState(null);
  const [editName, setEditName] = useState("");
  const [editProductType, setEditProductType] = useState("");
  const [editMM, setEditMM] = useState("");
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState(null);

  const openEdit = (p) => {
    setEditing(p);
    setEditName(p.name || "");
    setEditProductType(p.productType || "");
    setEditMM(p.migrationManagerName || "");
    setEditError(null);
  };

  const handleUpdate = async (e) => {
    e.preventDefault();
    if (!editName.trim() || !editProductType) return;
    setEditSaving(true);
    setEditError(null);
    try {
      await updateProjectDetails(editing.id, {
        name: editName.trim(),
        productType: editProductType,
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
      message: `This permanently removes its ${p.serverCount} server(s) and all their pre-checks, sign-offs, workspace pairs, and escalations. This cannot be undone.`,
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

  const resetForm = () => {
    setShowModal(false);
    setName("");
    setProductType("");
    setMigrationManagerName("");
    setError(null);
  };

  const handleCreate = async (e) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || !productType || (!creatorIsManager && !migrationManagerName)) return;
    setSaving(true);
    setError(null);
    try {
      const project = await createProject({
        name: trimmed,
        productType,
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
              <div style={{ flex: "1 1 160px" }}>
                <label htmlFor="project-product-type" style={{ display: "block", fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>
                  Product Type <span style={{ color: "var(--color-red)" }}>*</span>
                </label>
                <select
                  id="project-product-type"
                  value={productType}
                  onChange={(e) => setProductType(e.target.value)}
                  style={{ width: "100%" }}
                >
                  <option value="">Select...</option>
                  {PRODUCT_TYPE_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {!creatorIsManager && (
              <div style={{ marginTop: 14 }}>
                <label style={{ display: "block", fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>
                  Migration Manager <span style={{ color: "var(--color-red)" }}>*</span>
                </label>
                <select
                  value={migrationManagerName}
                  onChange={(e) => setMigrationManagerName(e.target.value)}
                  style={{ width: "100%", maxWidth: 300 }}
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

            {error && <div className="inline-hint" style={{ marginTop: 14 }}>{error}</div>}

            <div className="form-actions">
              <button
                className="btn"
                type="submit"
                disabled={saving || !name.trim() || !productType || (!creatorIsManager && !migrationManagerName)}
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
            <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
              <div style={{ flex: "1 1 220px" }}>
                <label style={{ display: "block", fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>
                  Project Name <span style={{ color: "var(--color-red)" }}>*</span>
                </label>
                <input type="text" value={editName} onChange={(e) => setEditName(e.target.value)} style={{ width: "100%" }} />
              </div>
              <div style={{ flex: "1 1 160px" }}>
                <label style={{ display: "block", fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>
                  Product Type <span style={{ color: "var(--color-red)" }}>*</span>
                </label>
                <select value={editProductType} onChange={(e) => setEditProductType(e.target.value)} style={{ width: "100%" }}>
                  <option value="">Select...</option>
                  {PRODUCT_TYPE_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
              </div>
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
              <button className="btn" type="submit" disabled={editSaving || !editName.trim() || !editProductType}>
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
          <button className="btn" onClick={() => setShowModal(true)}>
            <PlusIcon />
            Add Project
          </button>
        }
        columns={[
          { key: "name", label: "Project" },
          {
            key: "productType",
            label: "Product Type",
            render: (p) => (p.productType ? PRODUCT_TYPE_OPTIONS.find((o) => o.value === p.productType)?.label || p.productType : "-"),
          },
          { key: "serverCount", label: "No. of Servers", filterable: false },
          {
            key: "migrationManagers",
            label: "Migration Manager",
            sortable: false,
            filterable: false,
            render: (p) => (p.migrationManagers?.length ? p.migrationManagers.join(", ") : "Not assigned yet"),
          },
          {
            key: "createdBy",
            label: "Created By",
            render: (p) => p.createdBy || "-",
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
