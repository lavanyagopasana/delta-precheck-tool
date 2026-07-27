import React, { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  getServerReadiness,
  getPreCheckSubmission,
  updatePreCheckItem,
  submitPreCheckForReview,
  uploadEvidence,
} from "../api/client";
import AttachmentPreview from "./AttachmentPreview";
import { useToast } from "./Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";

const PRECHECK_EDIT_ROLES = ["MIGRATION_ENGINEER", "MIGRATION_MANAGER"];

const STATUS_BADGE = {
  NOT_STARTED: { color: "gray", label: "Not Started" },
  DRAFT: { color: "yellow", label: "Draft" },
  SUBMITTED: { color: "green", label: "Submitted" },
};

const BASE_STATUS_OPTIONS = [
  { value: "NOT_STARTED", label: "Not Started" },
  { value: "IN_PROGRESS", label: "In Progress" },
  { value: "CONFLICTS", label: "Conflicts" },
  { value: "COMPLETED", label: "Completed" },
];

const DELTA_TYPE_ITEM = "Delta Type";

const DELTA_TYPE_STATUS_OPTIONS = [
  { value: "NOT_STARTED", label: "Not Started" },
  { value: "PRE_DELTA", label: "Pre delta" },
  { value: "FINAL_DELTA", label: "Final delta" },
];

function statusOptionsFor(itemName) {
  if (itemName === DELTA_TYPE_ITEM) {
    return DELTA_TYPE_STATUS_OPTIONS;
  }
  if (itemName === "Drive changes") {
    return BASE_STATUS_OPTIONS.map((o) => (o.value === "COMPLETED" ? { ...o, label: "Not up to date" } : o));
  }
  if (itemName === "Pre Delta Migration") {
    return [...BASE_STATUS_OPTIONS, { value: "NOT_AVAILABLE", label: "Not available" }];
  }
  return BASE_STATUS_OPTIONS;
}

// Mirrors the backend's completion rule -- any real choice counts as done, Not Started is the
// only status that blocks submission. (Evidence is still required separately, per item.)
function isItemComplete(item) {
  return item.status !== "NOT_STARTED";
}

// Colors the item card's left accent bar and status pill. Anything other than the explicit
// "in progress"/"blocked" states reads as resolved (green) -- covers the Delta Type item's
// PRE_DELTA/FINAL_DELTA choices and "Not available" without needing a case for each.
const STATUS_VISUAL = {
  NOT_STARTED: { border: "var(--color-border)", badge: "gray", bg: "var(--color-gray-soft)", fg: "var(--color-text-muted)" },
  IN_PROGRESS: { border: "var(--color-yellow)", badge: "yellow", bg: "var(--color-yellow-soft)", fg: "var(--color-yellow)" },
  CONFLICTS: { border: "var(--color-red)", badge: "red", bg: "var(--color-red-soft)", fg: "var(--color-red)" },
};
const STATUS_VISUAL_DEFAULT = { border: "var(--color-green)", badge: "green", bg: "var(--color-green-soft)", fg: "var(--color-green)" };

function statusVisual(status) {
  return STATUS_VISUAL[status] || STATUS_VISUAL_DEFAULT;
}

const MAX_EVIDENCE_FILE_SIZE_MB = 20;

function evidenceDisplayName(item) {
  if (item.evidenceFileName) return item.evidenceFileName;
  if (!item.evidenceFilePath) return null;
  const segments = item.evidenceFilePath.split("/");
  return segments[segments.length - 1];
}

function ItemRow({ item, locked, serverId, editingAs, onSaved }) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState(null);
  const [dragOver, setDragOver] = useState(false);
  const [notesInput, setNotesInput] = useState(item.notes || "");
  const showToast = useToast();

  useEffect(() => {
    setNotesInput(item.notes || "");
  }, [item.notes]);

  const save = async (patch) => {
    setError(null);
    try {
      const updated = await updatePreCheckItem(serverId, item.id, {
        status: item.status,
        notes: item.notes,
        evidenceFilePath: item.evidenceFilePath,
        evidenceFileName: item.evidenceFileName,
        updatedBy: editingAs || undefined,
        ...patch,
      });
      onSaved(updated);
      showToast(`${item.itemName} saved.`);
    } catch (err) {
      setError(err.response?.data?.message || "Failed to save.");
    }
  };

  const handleStatusChange = (e) => {
    if (locked) return;
    save({ status: e.target.value });
  };

  const uploadFile = async (file) => {
    if (!file) return;
    if (file.size > MAX_EVIDENCE_FILE_SIZE_MB * 1024 * 1024) {
      setError(`"${file.name}" is larger than the ${MAX_EVIDENCE_FILE_SIZE_MB}MB attachment limit.`);
      return;
    }
    setUploading(true);
    setError(null);
    try {
      const result = await uploadEvidence(file);
      await save({ evidenceFilePath: result.filePath, evidenceFileName: result.fileName });
    } catch (err) {
      setError("File upload failed.");
    } finally {
      setUploading(false);
    }
  };

  const handleFileChange = (e) => uploadFile(e.target.files[0]);

  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    if (locked || uploading) return;
    uploadFile(e.dataTransfer.files[0]);
  };

  const handleNotesBlur = () => {
    if (locked) return;
    const trimmed = notesInput.trim();
    if (trimmed === (item.notes || "")) return;
    save({ notes: trimmed });
  };

  const displayName = evidenceDisplayName(item);
  const isDeltaType = item.itemName === DELTA_TYPE_ITEM;
  const visual = statusVisual(item.status);

  const removeEvidence = () => {
    if (locked) return;
    save({ evidenceFilePath: null, evidenceFileName: null });
  };

  return (
    <div className="precheck-item-card" style={{ borderLeftColor: visual.border }}>
      <div className="precheck-item-card-header">
        <span id={`precheck-item-label-${item.id}`} className="precheck-item-name">
          {item.itemName}
        </span>
        <label className="sr-only" htmlFor={`precheck-status-${item.id}`}>
          {item.itemName} status
        </label>
        <select
          id={`precheck-status-${item.id}`}
          value={item.status}
          disabled={locked}
          onChange={handleStatusChange}
          className="precheck-status-pill"
          style={{ background: visual.bg, color: visual.fg }}
        >
          {statusOptionsFor(item.itemName).map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>

      {!isDeltaType && (
        <div className="precheck-item-card-body">
          {item.evidenceFilePath ? (
            <AttachmentPreview
              filePath={item.evidenceFilePath}
              fileName={displayName}
              variant="full"
              onRemove={locked ? undefined : removeEvidence}
            />
          ) : (
            !locked && (
              <div
                className={`precheck-dropzone-lg${dragOver ? " drag-over" : ""}`}
                onDragOver={(e) => {
                  e.preventDefault();
                  setDragOver(true);
                }}
                onDragLeave={() => setDragOver(false)}
                onDrop={handleDrop}
              >
                <span className="precheck-dropzone-icon">⬆</span>
                <label style={{ cursor: "pointer" }}>
                  {uploading ? "Uploading..." : "Drop evidence file here, or "}
                  {!uploading && <span style={{ color: "var(--color-primary)", fontWeight: 600 }}>click to browse</span>}
                  <input type="file" onChange={handleFileChange} disabled={uploading} style={{ display: "none" }} />
                </label>
                <span className="progress-label">· up to {MAX_EVIDENCE_FILE_SIZE_MB}MB</span>
              </div>
            )
          )}
          {!locked && !item.evidenceFilePath && (
            <div style={{ fontSize: 11, color: "var(--color-red)", marginTop: 4 }}>Evidence required</div>
          )}

          {locked ? (
            <div className="precheck-note-readonly">
              {item.notes || "No note added"}
            </div>
          ) : (
            <>
              <label className="sr-only" htmlFor={`precheck-notes-${item.id}`}>
                {item.itemName} notes
              </label>
              <textarea
                id={`precheck-notes-${item.id}`}
                className="precheck-note"
                placeholder="Add a note..."
                value={notesInput}
                onChange={(e) => setNotesInput(e.target.value)}
                onBlur={handleNotesBlur}
                rows={1}
              />
              {!notesInput.trim() && (
                <div style={{ fontSize: 11, color: "var(--color-red)", marginTop: 2 }}>Note required</div>
              )}
            </>
          )}

          {error && <div className="inline-hint" style={{ marginTop: 8 }}>{error}</div>}
        </div>
      )}
    </div>
  );
}

function PreCheckBackNav({ fromSignoff, projectId, projectName, serverName }) {
  const navigate = useNavigate();
  if (fromSignoff) {
    return (
      <button className="btn secondary" onClick={() => navigate("/signoff")} style={{ marginBottom: 14 }}>
        ← Back
      </button>
    );
  }
  if (!projectId) {
    return <div className="breadcrumb">{serverName} / Pre-Check</div>;
  }
  return (
    <div className="breadcrumb">
      <Link to={`/projects/${projectId}`}>{projectName || "Project"}</Link> / {serverName} / Pre-Check
    </div>
  );
}

export default function PreCheckPanel({ serverId, showBackNav = true, showHeader = true, fromSignoff = false }) {
  const currentUser = useCurrentUser();
  const [server, setServer] = useState(null);
  const [submission, setSubmission] = useState(null);
  const [loading, setLoading] = useState(true);
  const [submittedByInput, setSubmittedByInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const showToast = useToast();

  // Email, not display name -- names collide across employees, email doesn't.
  const submittedByName = AUTH_CONFIGURED
    ? currentUser?.email || currentUser?.name || ""
    : submittedByInput.trim();

  const load = () => {
    setLoading(true);
    setError(null);
    Promise.all([getServerReadiness(serverId), getPreCheckSubmission(serverId, submittedByName)])
      .then(([serverData, submissionData]) => {
        setServer(serverData);
        setSubmission(submissionData);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load the pre-check form."))
      .finally(() => setLoading(false));
  };

  useEffect(load, [serverId]);

  if (loading) return <p>Loading pre-check form...</p>;
  if (!server || !submission) {
    return (
      <div>
        <div className="inline-hint">{error || "Failed to load the pre-check form."}</div>
        <button className="btn secondary" style={{ marginTop: 12 }} onClick={load}>
          Retry
        </button>
      </div>
    );
  }

  if (submission.lockedByOther) {
    return (
      <div>
        {showBackNav && <PreCheckBackNav fromSignoff={fromSignoff} projectId={server.projectId} projectName={server.projectName} serverName={server.serverName} />}
        {showHeader && <h2>{server.serverName}</h2>}
        <div className="card">
          <strong style={{ fontSize: 14 }}>This pre-check is in progress</strong>
          <p style={{ color: "var(--color-text-muted)", fontSize: 13.5 }}>
            {submission.startedByEmail} is currently filling this out. It'll be visible here once they submit it for
            Migration Manager review.
          </p>
        </div>
      </div>
    );
  }

  const hasMigrationManager = !!server.migrationManagerName;
  const canEdit = !AUTH_CONFIGURED || PRECHECK_EDIT_ROLES.includes(currentUser?.role);

  const items = submission.items;
  const locked = submission.status === "SUBMITTED" || !canEdit;
  const completedCount = items.filter(isItemComplete).length;
  const allCompleted = items.length > 0 && completedCount === items.length;
  const allHaveEvidence = items
    .filter((i) => i.itemName !== DELTA_TYPE_ITEM)
    .every((i) => !!i.evidenceFilePath);
  const allHaveNotes = items
    .filter((i) => i.itemName !== DELTA_TYPE_ITEM)
    .every((i) => !!i.notes?.trim());
  const progressPct = items.length === 0 ? 0 : Math.round((completedCount / items.length) * 100);
  const badge = STATUS_BADGE[submission.status] || STATUS_BADGE.NOT_STARTED;

  const updateItemInPlace = (updated) => {
    setSubmission((prev) => ({
      ...prev,
      items: prev.items.map((i) => (i.id === updated.id ? updated : i)),
    }));
  };

  const handleSubmit = async () => {
    if (!submittedByName) {
      setError("Enter your name before submitting for Migration Manager review.");
      return;
    }
    if (!hasMigrationManager) {
      setError("Assign a Migration Manager to this server before submitting for review.");
      return;
    }
    if (!window.confirm("Submit for Migration Manager review? This will lock the form and no further edits will be possible.")) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await submitPreCheckForReview(serverId, { submittedBy: submittedByName });
      showToast("Submitted for Migration Manager review.");
      load();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to submit for Migration Manager review.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      {showBackNav && <PreCheckBackNav fromSignoff={fromSignoff} projectId={server.projectId} projectName={server.projectName} serverName={server.serverName} />}

      {showHeader && (
        <>
          <h2>{server.serverName}</h2>
          <p style={{ color: "var(--color-text-muted)", marginTop: -10, marginBottom: 20 }}>
            {server.totalPairs} workspace pair(s) on this server share this pre-check.
          </p>
        </>
      )}

      <div className="card precheck-section">
        <h3>
          Pre-Check Items{" "}
          <span style={{ color: "var(--color-text-faint)", fontWeight: 500 }}>
            {completedCount}/{items.length}
          </span>
        </h3>

        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 10 }}>
          <span className={`badge ${badge.color}`}>{badge.label}</span>
          {submission.status === "SUBMITTED" && (
            <span className="progress-label">
              Submitted by {submission.submittedBy} on {new Date(submission.submittedAt).toLocaleString()}
            </span>
          )}
          {!canEdit && submission.status !== "SUBMITTED" && (
            <span className="progress-label">You have view-only access to this pre-check.</span>
          )}
        </div>
        <div className="progress-track">
          <div className="progress-fill" style={{ width: `${progressPct}%` }} />
        </div>

        {!locked && (!allCompleted || !allHaveEvidence || !allHaveNotes) && (
          <div className="inline-hint" style={{ marginTop: 14 }}>
            Every item must have a status selected, an attachment, and a note before this form can be submitted for Migration Manager review.
          </div>
        )}

        {!locked && !hasMigrationManager && (
          <div className="inline-hint" style={{ marginTop: 14 }}>
            This project has no Migration Manager assigned yet, so this form can't be submitted for review.
          </div>
        )}

        <div className="precheck-items-list">
          {items.map((item) => (
            <ItemRow
              key={item.id}
              item={item}
              locked={locked}
              serverId={serverId}
              editingAs={submittedByName}
              onSaved={updateItemInPlace}
            />
          ))}
        </div>

        {error && <div className="inline-hint" style={{ marginTop: 12 }}>{error}</div>}

        {!locked && (
          <div
            style={{
              marginTop: 20,
              paddingTop: 16,
              borderTop: "1px solid var(--color-border)",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              flexWrap: "wrap",
              gap: 10,
            }}
          >
            <span className="progress-label">
              {hasMigrationManager
                ? `Migration Manager: ${server.migrationManagerName}. Fill out every item, then submit for review.`
                : "Fill out every item, then submit for Migration Manager review."}
            </span>
            <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
              {!AUTH_CONFIGURED && (
                <>
                  <label className="sr-only" htmlFor="precheck-submitted-by">Your name</label>
                  <input
                    id="precheck-submitted-by"
                    type="text"
                    placeholder="Your name"
                    value={submittedByInput}
                    onChange={(e) => setSubmittedByInput(e.target.value)}
                    style={{ width: 140 }}
                  />
                </>
              )}
              <button
                className="btn"
                onClick={handleSubmit}
                disabled={busy || !allCompleted || !allHaveEvidence || !allHaveNotes || !hasMigrationManager || !submittedByName}
              >
                Submit for Migration Manager Review
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
