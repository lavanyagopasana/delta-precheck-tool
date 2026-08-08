import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getProjectDetail, updateProjectAssignments, getRoster } from "../api/client";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import EngineerChecklist from "../components/EngineerChecklist";
import ServerUrlsPanel from "../components/ServerUrlsPanel";
import { PlusIcon, FolderIcon } from "../components/Icons";

const EMPTY_ROSTER = { migrationManagers: [], engineers: [] };

const initials = (email) => (email || "?").trim().charAt(0).toUpperCase();

// True once the current selection actually differs from what's saved -- order shouldn't count
// as a change, only membership.
function isDirty(current, saved) {
  const a = [...current].sort();
  const b = [...saved].sort();
  return JSON.stringify(a) !== JSON.stringify(b);
}

// The whole project header: name + engineers on the top row, manager below. Owns the engineer
// selection state, which is why the header lives here rather than being assembled in the page.
function ProjectHeader({ project, roster, canManage, onSaved, onAddServer }) {
  const showToast = useToast();
  const [engineerEmails, setEngineerEmails] = useState(project.engineerEmails || []);
  const [savedEmails, setSavedEmails] = useState(project.engineerEmails || []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const dirty = isDirty(engineerEmails, savedEmails);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      await updateProjectAssignments(project.id, { engineerEmails });
      setSavedEmails(engineerEmails);
      showToast("Project assignments updated.");
      onSaved();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to update assignments.");
    } finally {
      setSaving(false);
    }
  };

  // The people on this project, shown as the header's second row rather than a separate
  // "Assignments" card. The heading was a label for two fields that already label themselves, and
  // it pushed the project's own team below the fold of its own header.
  return (
    <div className="detail-header">
      {/* Top row: the project's name on the left, its engineers on the right, primary action last. */}
      <div className="detail-header-top detail-header-top--split">
        <span className="detail-header-icon">
          <FolderIcon size={20} style={{ marginRight: 0 }} />
        </span>
        <h2 className="detail-header-title">{project.name}</h2>

        {/* The manager is a single value, so it fits the top row beside the name. */}
        <div className="project-manager">
          <span className="detail-fact-label">Migration Manager</span>
          {project.migrationManagerName ? (
            <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
              <span className="person-avatar">{initials(project.migrationManagerName)}</span>
              <span style={{ fontSize: 13.5, fontWeight: 600 }}>{project.migrationManagerName}</span>
            </div>
          ) : (
            <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>Not assigned yet</span>
          )}
        </div>

        {canManage && (
          <button className="btn" style={{ flexShrink: 0, alignSelf: "center" }} onClick={onAddServer}>
            <PlusIcon /> Add Server
          </button>
        )}
      </div>

      {/* Second row: the engineers -- a chip list that grows, so it gets the full width. */}
      <div className="project-people">
        <div className="project-people-block project-people-block--grow">
          <div className="assign-section-head">
            <span className="detail-fact-label">Migration Engineers</span>
            {canManage && (
              <button
                className="btn"
                style={{ padding: "4px 12px", fontSize: 12 }}
                onClick={handleSave}
                disabled={saving || !dirty}
              >
                {saving ? "Saving..." : dirty ? "Save" : "Saved"}
              </button>
            )}
          </div>
          {canManage ? (
            <EngineerChecklist options={roster.engineers} selected={engineerEmails} onChange={setEngineerEmails} />
          ) : project.engineerEmails?.length ? (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
              {project.engineerEmails.map((email) => (
                <span key={email} className="engineer-chip" style={{ cursor: "default" }}>
                  <span className="person-avatar">{initials(email)}</span>
                  {email}
                </span>
              ))}
            </div>
          ) : (
            <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>None yet</span>
          )}
          {error && <div className="inline-hint" style={{ marginTop: 10 }}>{error}</div>}
        </div>
      </div>
    </div>
  );
}

export default function ProjectDetailsPage() {
  const { id } = useParams();
  const currentUser = useCurrentUser();
  const [project, setProject] = useState(null);
  const [roster, setRoster] = useState(EMPTY_ROSTER);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showAddServer, setShowAddServer] = useState(false);

  const isAdmin = !AUTH_CONFIGURED || currentUser?.role === "ADMIN";
  const currentUserEmail = AUTH_CONFIGURED ? currentUser?.email || currentUser?.name || "unknown" : "unknown";

  const load = () => {
    getProjectDetail(id)
      .then((data) => {
        setProject(data);
        setError(null);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load project."))
      .finally(() => setLoading(false));
  };

  useEffect(load, [id]);
  useEffect(() => {
    getRoster()
      .then(setRoster)
      .catch(() => {});
  }, []);

  if (loading) return <p>Loading project...</p>;
  if (!project) return <div className="inline-hint">{error || "Project not found."}</div>;

  // Only this project's manager, its team members, its creator, or an admin can edit
  // assignments or import a CSV -- not just anyone with the Migration Manager/Engineer role globally.
  const canManage =
    isAdmin ||
    (!!project.migrationManagerName && currentUserEmail.toLowerCase() === project.migrationManagerName.toLowerCase()) ||
    (!!project.createdBy && currentUserEmail.toLowerCase() === project.createdBy.toLowerCase()) ||
    (project.engineerEmails || []).some((e) => e.toLowerCase() === currentUserEmail.toLowerCase());

  return (
    <div>
      <Link to="/projects" className="breadcrumb" style={{ display: "inline-block" }}>&larr; Back to Projects</Link>

      <ProjectHeader
        project={project}
        roster={roster}
        canManage={canManage}
        onSaved={load}
        onAddServer={() => setShowAddServer(true)}
      />

      {error && <div className="inline-hint" style={{ marginTop: 12 }}>{error}</div>}

      <ServerUrlsPanel
        project={project}
        canManage={canManage}
        isAdmin={isAdmin}
        onSaved={load}
        showAddServer={showAddServer}
        onCloseAddServer={() => setShowAddServer(false)}
      />
    </div>
  );
}
