import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getProjectDetail, updateProjectAssignments, getRoster } from "../api/client";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import EngineerChecklist from "../components/EngineerChecklist";
import ServerUrlsPanel from "../components/ServerUrlsPanel";
import { PlusIcon, FolderIcon } from "../components/Icons";

const EMPTY_ROSTER = { migrationManagers: [], engineers: [], engineersByManager: {} };

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

  // Only the engineers on THIS project's Migration Manager's team may be assigned. The manager is
  // stored as an email (despite the field name), so it keys straight into engineersByManager.
  //
  //   manager set + on a team  -> that team's engineers only
  //   manager set + no team    -> every engineer, plus a notice (below)
  //   no manager at all        -> every engineer, plus a notice
  //
  // Falling back to the full list rather than an empty one is deliberate: a strict filter would make
  // assignment impossible until an admin fixed the team, with nothing on screen explaining why.
  const managerEmail = (project.migrationManagerName || "").toLowerCase();
  const teamScopedEngineers = managerEmail ? roster.engineersByManager?.[managerEmail] : undefined;
  const engineerOptions = teamScopedEngineers ?? roster.engineers;
  // Anyone already saved on the project stays visible even if they since left the team -- otherwise
  // their chip would vanish from the picker while remaining assigned in the database.
  const optionsWithSaved = Array.from(new Set([...(engineerOptions || []), ...savedEmails]));
  const unscopedReason = !managerEmail
    ? "No Migration Manager is assigned yet, so every engineer is listed."
    : !teamScopedEngineers
    ? `${project.migrationManagerName} isn't on a team yet, so every engineer is listed. An admin can set their team under Admin > Manage Access.`
    : null;

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
            <>
              <EngineerChecklist options={optionsWithSaved} selected={engineerEmails} onChange={setEngineerEmails} />
              {unscopedReason && (
                <div className="inline-hint" style={{ marginTop: 8 }}>{unscopedReason}</div>
              )}
            </>
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
