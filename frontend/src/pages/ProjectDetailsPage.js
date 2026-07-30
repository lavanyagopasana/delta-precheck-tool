import React, { useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  getProjectDetail,
  updateProjectAssignments,
  getRoster,
  importWorkspacePairsCsvGlobal,
  startDelta,
  finishDelta,
  SAMPLE_CSV_COLUMNS_GLOBAL,
} from "../api/client";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import CsvImportPanel from "../components/CsvImportPanel";
import EngineerChecklist from "../components/EngineerChecklist";
import WorkspacePairsPanel from "../components/WorkspacePairsPanel";

const PRODUCT_TYPE_LABELS = { MESSAGE: "Message", EMAIL: "Email", CONTENT: "Content" };

const EMPTY_ROSTER = { migrationManagers: [], engineers: [] };

const SAMPLE_ROW_GLOBAL = [
  "PROD-MIGRATION-01",
  "jane.doe@source-tenant.com",
  "/jane.doe/My Drive",
  "jane.doe@company.com",
  "/sites/migrated/jane.doe",
  "Google Drive -> OneDrive",
];

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
      <strong style={{ fontSize: 14 }}>Assignments</strong>

      <div style={{ marginTop: 16 }}>
        <span style={{ display: "block", fontSize: 12, fontWeight: 600, color: "var(--color-text-muted)", marginBottom: 8 }}>
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

      <div style={{ marginTop: 20 }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 8 }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: "var(--color-text-muted)" }}>
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
  const [searchParams] = useSearchParams();
  const currentUser = useCurrentUser();
  const showToast = useToast();
  const [project, setProject] = useState(null);
  const [roster, setRoster] = useState(EMPTY_ROSTER);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [selectedServerId, setSelectedServerId] = useState(searchParams.get("server") || "");

  const isAdmin = !AUTH_CONFIGURED || currentUser?.role === "ADMIN";
  const currentUserEmail = AUTH_CONFIGURED ? currentUser?.email || currentUser?.name || "unknown" : "unknown";
  // The post-Delta Start/Finish actions are engineer-driven (admins too).
  const canRunDelta = !AUTH_CONFIGURED || currentUser?.role === "MIGRATION_ENGINEER" || currentUser?.role === "ADMIN";

  const load = () => {
    getProjectDetail(id)
      .then((data) => {
        setProject(data);
        setError(null);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load project."))
      .finally(() => setLoading(false));
  };

  const handleStartDelta = async (server) => {
    try {
      await startDelta(server.serverId);
      showToast("Delta migration started.", "success");
      load();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to start Delta.", "error");
    }
  };

  const handleFinishDelta = async (server) => {
    try {
      await finishDelta(server.serverId);
      showToast("Delta migration marked finished.", "success");
      load();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to finish Delta.", "error");
    }
  };

  useEffect(load, [id]);
  useEffect(() => {
    getRoster()
      .then(setRoster)
      .catch(() => {});
  }, []);

  if (loading) return <p>Loading project...</p>;
  if (!project) return <div className="inline-hint">{error || "Project not found."}</div>;

  const selectedServer = project.servers?.find((s) => String(s.serverId) === String(selectedServerId));

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

      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", flexWrap: "wrap", gap: 10, marginTop: 28, marginBottom: 28 }}>
        <h2 style={{ margin: 0 }}>{project.name}</h2>
        <span className="badge gray">{PRODUCT_TYPE_LABELS[project.productType] || project.productType}</span>
      </div>

      {error && <div className="inline-hint" style={{ marginTop: 12 }}>{error}</div>}

      <AssignmentsCard project={project} roster={roster} canManage={canManage} onSaved={load} />

      {canManage && (
        <CsvImportPanel
          title={`Import servers + migration pairs into ${project.name}`}
          columns={SAMPLE_CSV_COLUMNS_GLOBAL}
          sampleRow={SAMPLE_ROW_GLOBAL}
          sampleFileName="servers-migration-pairs-sample.csv"
          onUpload={(file) => importWorkspacePairsCsvGlobal(file, project.id)}
          onImported={load}
        />
      )}

      <div className="card">
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap" }}>
          <strong style={{ fontSize: 14 }}>Servers</strong>
          {!!project.servers?.length && (
            <select
              value={selectedServerId}
              onChange={(e) => setSelectedServerId(e.target.value)}
              style={{ width: 260 }}
            >
              <option value="">Select a server...</option>
              {project.servers.map((s) => (
                <option key={s.serverId} value={s.serverId}>
                  {s.serverName}
                </option>
              ))}
            </select>
          )}
        </div>

        {!project.servers?.length && (
          <p className="empty-state">
            {canManage ? "No servers yet. Upload a CSV above to add servers and migration pairs." : "No servers yet."}
          </p>
        )}

        {selectedServer && (() => {
          const s = selectedServer;
          const fmt = (d) => new Date(d).toLocaleDateString();
          // Server status follows the workflow: Pending (pre-check not submitted) -> the approval
          // chain's "<Role> not approved yet" while in review -> Delta Ready once all approved.
          const stage =
            s.readinessStage === "READY"
              ? { label: "Delta Ready", pill: "green" }
              : s.readinessStage === "IN_PROGRESS"
              ? { label: s.readinessDetail || "In review", pill: null, color: "var(--color-yellow)" }
              : { label: "Pre-check not submitted", pill: null, color: "var(--color-red)" };
          return (
            <div className="card-row" style={{ marginTop: 20, marginBottom: 0 }}>
              <div className="stat-card">
                <div className="value" style={{ fontSize: 14 }}>
                  {stage.pill ? (
                    <span className={`badge ${stage.pill}`}>{stage.label}</span>
                  ) : (
                    <span style={{ fontSize: 12, fontWeight: 600, color: stage.color }}>{stage.label}</span>
                  )}
                </div>
                <div className="label">Status</div>
              </div>
              <div className="stat-card">
                <div className="value">{s.totalPairs}</div>
                <div className="label">Pairs</div>
              </div>
              <div className="stat-card">
                <div className="value" style={{ color: s.openEscalationCount > 0 ? "var(--color-red)" : undefined }}>
                  {s.openEscalationCount}
                </div>
                <div className="label">Tickets</div>
              </div>
              <div className="stat-card">
                <div className="value" style={{ fontSize: 16 }}>
                  {s.deltaStartedAt ? (
                    fmt(s.deltaStartedAt)
                  ) : s.deltaInitiatedAt && canRunDelta ? (
                    <button className="btn success" style={{ padding: "5px 14px", fontSize: 12.5 }} onClick={() => handleStartDelta(s)}>
                      Start
                    </button>
                  ) : (
                    "—"
                  )}
                </div>
                <div className="label">Delta Started</div>
              </div>
              <div className="stat-card">
                <div className="value" style={{ fontSize: 16 }}>
                  {s.deltaFinishedAt ? (
                    fmt(s.deltaFinishedAt)
                  ) : s.deltaStartedAt && canRunDelta ? (
                    <button className="btn" style={{ padding: "5px 14px", fontSize: 12.5 }} onClick={() => handleFinishDelta(s)}>
                      Finished
                    </button>
                  ) : (
                    "—"
                  )}
                </div>
                <div className="label">Delta Finished</div>
              </div>
            </div>
          );
        })()}

        {selectedServer && (
          <WorkspacePairsPanel
            key={`pairs-${selectedServer.serverId}`}
            serverId={selectedServer.serverId}
            showHeader={false}
            showCsvImport={false}
          />
        )}
      </div>
    </div>
  );
}
