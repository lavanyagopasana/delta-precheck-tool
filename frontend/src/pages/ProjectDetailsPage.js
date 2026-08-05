import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getProjectDetail, updateProjectAssignments, getRoster } from "../api/client";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import EngineerChecklist from "../components/EngineerChecklist";
import ServerUrlsPanel from "../components/ServerUrlsPanel";
import { PlusIcon } from "../components/Icons";

const EMPTY_ROSTER = { migrationManagers: [], engineers: [] };

const initials = (email) => (email || "?").trim().charAt(0).toUpperCase();

// True once the current selection actually differs from what's saved -- order shouldn't count
// as a change, only membership.
function isDirty(current, saved) {
  const a = [...current].sort();
  const b = [...saved].sort();
  return JSON.stringify(a) !== JSON.stringify(b);
}

function AssignmentsCard({ project, roster, canManage, onSaved }) {
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

  return (
    <div className="card">
      <h3 className="section-title">Assignments</h3>

      <div className="subpanel" style={{ marginTop: 16 }}>
        <span style={{ display: "block", fontSize: 12, fontWeight: 500, color: "var(--color-text-muted)", marginBottom: 8 }}>
          Migration Manager
        </span>
        {project.migrationManagerName ? (
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <span className="person-avatar">{initials(project.migrationManagerName)}</span>
            <span style={{ fontSize: 13.5, fontWeight: 500 }}>{project.migrationManagerName}</span>
          </div>
        ) : (
          <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>Not assigned yet</span>
        )}
      </div>

      <div className="subpanel" style={{ marginTop: 14 }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 8 }}>
          <span style={{ fontSize: 12, fontWeight: 500, color: "var(--color-text-muted)" }}>
            Migration Engineers
          </span>
          {canManage && (
            <button
              className="btn"
              style={{ padding: "5px 14px", fontSize: 12.5 }}
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

      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 10, marginBottom: 24 }}>
        <h2 style={{ margin: 0 }}>{project.name}</h2>
        {canManage && (
          <button className="btn" onClick={() => setShowAddServer(true)}>
            <PlusIcon /> Add Server
          </button>
        )}
      </div>

      {error && <div className="inline-hint" style={{ marginTop: 12 }}>{error}</div>}

      <AssignmentsCard project={project} roster={roster} canManage={canManage} onSaved={load} />

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
