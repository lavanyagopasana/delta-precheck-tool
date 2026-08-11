import React, { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  getCombinationReadiness,
  getPreCheckSubmission,
  updatePreCheckItem,
  submitPreCheckForReview,
  withdrawPreCheck,
  uploadEvidence,
} from "../api/client";
import AttachmentPreview from "./AttachmentPreview";
import { useToast } from "./Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { UndoIcon, SendIcon, ServerIcon, SwapIcon } from "./Icons";
import { useConfirm } from "./ConfirmDialog";
import {
  MAX_EVIDENCE_FILE_SIZE_MB,
  DELTA_TYPE_ITEM,
  DELTA_MESSAGE_SYNC_ITEM,
  isPreDeltaMigrationItem,
} from "../constants";
import DeltaBadge from "./DeltaBadge";
import { emailLocalPart } from "../utils/format";
import { previousDeltasDoneCount } from "../utils/delta";

// Filling out and submitting a pre-check is a MIGRATION_ENGINEER action. MIGRATION_MANAGER was removed
// on 2026-08-06: the manager is the first approver in the sign-off chain, so filling in the form they
// then approve collapses two steps into one person. DEV_LEAD/QA_LEAD were never allowed. ADMIN stays as
// the unblock path for a pre-check locked to an unavailable engineer. Mirrors SecurityConfig's matcher
// for /api/combinations/*/precheck-items/** -- keep the two in step, this only hides the controls; the
// backend is what actually enforces it.
const PRECHECK_EDIT_ROLES = ["ADMIN", "MIGRATION_ENGINEER"];

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

// Choosing "Pre delta" here means this cycle finishes and the checklist reopens for another one;
// choosing "Final delta" ends the combination for good and makes its server decommissionable. The
// descriptions are surfaced next to the dropdown (DeltaTypeHint) because the two options have very
// different consequences and the labels alone don't convey that.
const DELTA_TYPE_STATUS_OPTIONS = [
  { value: "NOT_STARTED", label: "Not Started" },
  { value: "PRE_DELTA", label: "Pre delta" },
  { value: "FINAL_DELTA", label: "Final delta" },
];

// Message-only option sets. A chat migration can move part of the history and not the rest, so
// OneTime Migration gets "Partially completed" and drops Conflicts; Delta Message Sync is a yes/no
// capability rather than a progress state. Both keep "Not Started", which is the unanswered state
// every item needs -- it's the only status that blocks submission.
const MESSAGE_ONETIME_MIGRATION_OPTIONS = [
  { value: "NOT_STARTED", label: "Not Started" },
  { value: "IN_PROGRESS", label: "In Progress" },
  { value: "PARTIALLY_COMPLETED", label: "Partially completed" },
  { value: "COMPLETED", label: "Completed" },
];

const DELTA_MESSAGE_SYNC_OPTIONS = [
  { value: "NOT_STARTED", label: "Not Started" },
  { value: "ENABLED", label: "Enabled" },
  { value: "NOT_ENABLED", label: "Not enabled" },
];

// Scoped by product type as well as item name: "OneTime Migration" exists on all three checklists but
// only Message offers "Partially completed". productType comes from the readiness payload.
function statusOptionsFor(itemName, productType) {
  if (itemName === DELTA_TYPE_ITEM) {
    return DELTA_TYPE_STATUS_OPTIONS;
  }
  if (productType === "MESSAGE" && itemName === DELTA_MESSAGE_SYNC_ITEM) {
    return DELTA_MESSAGE_SYNC_OPTIONS;
  }
  if (productType === "MESSAGE" && itemName === "OneTime Migration") {
    return MESSAGE_ONETIME_MIGRATION_OPTIONS;
  }
  if (itemName === "Drive changes") {
    return BASE_STATUS_OPTIONS.map((o) => (o.value === "COMPLETED" ? { ...o, label: "Not up to date" } : o));
  }
  if (isPreDeltaMigrationItem(itemName)) {
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
  // Message-only. Partially completed is real progress but not done, so it reads like In Progress
  // rather than resolved-green. Not enabled is a legitimate answer, not a failure, so it stays neutral
  // -- green would imply the sync is on, red would imply something went wrong.
  PARTIALLY_COMPLETED: { border: "var(--color-yellow)", badge: "yellow", bg: "var(--color-yellow-soft)", fg: "var(--color-yellow)" },
  NOT_ENABLED: { border: "var(--color-border)", badge: "gray", bg: "var(--color-gray-soft)", fg: "var(--color-text-muted)" },
};
const STATUS_VISUAL_DEFAULT = { border: "var(--color-green)", badge: "green", bg: "var(--color-green-soft)", fg: "var(--color-green)" };

function statusVisual(status) {
  return STATUS_VISUAL[status] || STATUS_VISUAL_DEFAULT;
}

function evidenceDisplayName(item) {
  if (item.evidenceFileName) return item.evidenceFileName;
  if (!item.evidenceFilePath) return null;
  const segments = item.evidenceFilePath.split("/");
  return segments[segments.length - 1];
}

function ItemRow({ item, locked, combinationId, editingAs, productType, onSaved }) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState(null);
  const [dragOver, setDragOver] = useState(false);
  const [notesInput, setNotesInput] = useState(item.notes || "");
  const showToast = useToast();
  const notesSaveTimer = useRef(null);

  useEffect(() => {
    setNotesInput(item.notes || "");
  }, [item.notes]);

  // Saving only on blur meant the Submit button (which depends on every item's saved item.notes,
  // not this component's own draft state) stayed hidden until the user clicked elsewhere, even
  // once every field was actually filled in. Auto-saving shortly after typing stops means it
  // appears as soon as the form is genuinely complete.
  useEffect(() => {
    return () => {
      if (notesSaveTimer.current) clearTimeout(notesSaveTimer.current);
    };
  }, []);

  const save = async (patch) => {
    setError(null);
    try {
      const updated = await updatePreCheckItem(combinationId, item.id, {
        status: item.status,
        notes: item.notes,
        evidenceFilePath: item.evidenceFilePath,
        evidenceFileName: item.evidenceFileName,
        updatedBy: editingAs || undefined,
        ...patch,
      });
      onSaved(updated);
      showToast(`${item.itemName} saved.`, "success");
    } catch (err) {
      const msg = err.response?.data?.message || `Couldn't save ${item.itemName}. Please try again.`;
      setError(msg);
      showToast(msg, "error");
    }
  };

  const handleStatusChange = (e) => {
    if (locked) return;
    save({ status: e.target.value });
  };

  const uploadFile = async (file) => {
    if (!file) return;
    if (file.size > MAX_EVIDENCE_FILE_SIZE_MB * 1024 * 1024) {
      const msg = `"${file.name}" is larger than the ${MAX_EVIDENCE_FILE_SIZE_MB}MB attachment limit.`;
      setError(msg);
      showToast(msg, "error");
      return;
    }
    setUploading(true);
    setError(null);
    try {
      const result = await uploadEvidence(file);
      await save({ evidenceFilePath: result.filePath, evidenceFileName: result.fileName });
    } catch (err) {
      const msg = err.response?.data?.message || `Couldn't upload "${file.name}". Please try again.`;
      setError(msg);
      showToast(msg, "error");
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

  const handleNotesChange = (e) => {
    const value = e.target.value;
    setNotesInput(value);
    if (locked) return;
    if (notesSaveTimer.current) clearTimeout(notesSaveTimer.current);
    notesSaveTimer.current = setTimeout(() => {
      const trimmed = value.trim();
      if (trimmed !== (item.notes || "")) {
        save({ notes: trimmed });
      }
    }, 600);
  };

  const handleNotesBlur = () => {
    if (locked) return;
    if (notesSaveTimer.current) {
      clearTimeout(notesSaveTimer.current);
      notesSaveTimer.current = null;
    }
    const trimmed = notesInput.trim();
    if (trimmed === (item.notes || "")) return;
    save({ notes: trimmed });
  };

  const displayName = evidenceDisplayName(item);
  const isDeltaType = item.itemName === DELTA_TYPE_ITEM;
  const visual = statusVisual(item.status);
  // Spelled out at the point of choice rather than left to a tooltip: "Final delta" permanently closes
  // the combination, and an engineer picking it by mistake can only be undone by an admin.
  const deltaTypeHint = !isDeltaType
    ? null
    : item.status === "PRE_DELTA"
    ? "Another pre-delta will be possible after this one finishes."
    : item.status === "FINAL_DELTA"
    ? "This is the last delta — finishing it closes this combination for good and makes its server ready to decommission."
    : "Choose Pre delta if more deltas will follow, or Final delta if this is the last one.";

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
          style={{ backgroundColor: visual.bg, color: visual.fg }}
        >
          {statusOptionsFor(item.itemName, productType).map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>

      {deltaTypeHint && (
        <div
          style={{
            fontSize: 11.5,
            color: item.status === "FINAL_DELTA" ? "var(--color-purple)" : "var(--color-text-muted)",
            padding: "0 14px 12px",
          }}
        >
          {deltaTypeHint}
        </div>
      )}

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
                onChange={handleNotesChange}
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

// The server and combination deliberately do NOT appear here. serverName is a URL
// ("https://demoprjct-server1"), so slash-joining it with the combination produced
// "https://demoprjct-server1 / Google to One Drive / Pre-Check" -- which reads as one long URL path
// rather than a trail of separate places. PreCheckHeader directly below shows both properly, so the
// crumb only has to answer "where am I and how do I get back".
function PreCheckBackNav({ fromSignoff, projectId, projectName }) {
  const navigate = useNavigate();
  if (fromSignoff) {
    return (
      <button className="btn secondary" onClick={() => navigate("/signoff")} style={{ marginBottom: 14 }}>
        ← Back
      </button>
    );
  }
  if (!projectId) {
    return <div className="breadcrumb">Pre-Check</div>;
  }
  return (
    <div className="breadcrumb">
      <Link to={`/projects/${projectId}`}>{projectName || "Project"}</Link> / Pre-Check
    </div>
  );
}

// Server on its own line with the server icon, combination beneath it with the swap icon -- the same
// shape ServerDetailsPage uses, so the two pages read consistently and a URL-shaped server name never
// sits next to a slash.
function PreCheckHeader({ serverName, combinationName, children }) {
  return (
    <div style={{ marginBottom: 20 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
        <ServerIcon size={20} style={{ marginRight: 0, color: "var(--color-primary)" }} />
        <h2 style={{ margin: 0, wordBreak: "break-all" }}>{serverName}</h2>
        {children}
      </div>
      {combinationName && (
        <div style={{ display: "flex", alignItems: "center", gap: 7, marginTop: 6, color: "var(--color-text-muted)" }}>
          <SwapIcon size={15} style={{ marginRight: 0 }} />
          <span style={{ fontSize: 13.5, fontWeight: 600 }}>{combinationName}</span>
        </div>
      )}
    </div>
  );
}

export default function PreCheckPanel({
  combinationId,
  showBackNav = true,
  showHeader = true,
  fromSignoff = false,
  onChanged,
}) {
  const currentUser = useCurrentUser();
  const [combination, setCombination] = useState(null);
  const [submission, setSubmission] = useState(null);
  const [loading, setLoading] = useState(true);
  const [submittedByInput, setSubmittedByInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const showToast = useToast();
  const confirm = useConfirm();

  // Email, not display name -- names collide across employees, email doesn't.
  const submittedByName = AUTH_CONFIGURED
    ? currentUser?.email || currentUser?.name || ""
    : submittedByInput.trim();

  // load() reads submittedByName to fetch the viewer-scoped submission, but must fire ONLY on
  // combinationId change -- never on every keystroke of the (non-auth) name field, which would
  // refetch and clobber the form on each character. So the name is read through a ref (always the
  // latest value at call time) instead of being a dependency, and load is memoized on combinationId
  // alone. This keeps the exhaustive-deps rule satisfied without suppression and without changing
  // when load runs.
  const submittedByNameRef = useRef(submittedByName);
  submittedByNameRef.current = submittedByName;

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    Promise.all([getCombinationReadiness(combinationId), getPreCheckSubmission(combinationId, submittedByNameRef.current)])
      .then(([combinationData, submissionData]) => {
        setCombination(combinationData);
        setSubmission(submissionData);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load the pre-check form."))
      .finally(() => setLoading(false));
  }, [combinationId]);

  useEffect(() => {
    load();
  }, [load]);

  if (loading) return <p>Loading pre-check form...</p>;
  if (!combination || !submission) {
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
        {showBackNav && (
          <PreCheckBackNav
            fromSignoff={fromSignoff}
            projectId={combination.projectId}
            projectName={combination.projectName}
          />
        )}
        {showHeader && (
          <PreCheckHeader serverName={combination.serverName} combinationName={combination.combinationName} />
        )}
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

  const hasMigrationManager = !!combination.migrationManagerName;
  const canEdit = !AUTH_CONFIGURED || PRECHECK_EDIT_ROLES.includes(currentUser?.role);

  const items = submission.items;
  // finalDeltaComplete is a permanent lock, not the temporary submitted-lock: the migration is over and
  // the backend refuses edits from everyone including admins (PreCheckItemService.requireNotFinalised).
  const locked = submission.status === "SUBMITTED" || !canEdit || combination.finalDeltaComplete;

  // "Pre Delta Migration" only applies once Delta Type has actually been set to "Pre delta" --
  // before that (Not Started) or once it's "Final delta", the item is hidden and not required.
  const deltaTypeItem = items.find((i) => i.itemName === DELTA_TYPE_ITEM);
  const preDeltaMigrationRequired = deltaTypeItem?.status === "PRE_DELTA";
  const visibleItems = items.filter((i) => !isPreDeltaMigrationItem(i.itemName) || preDeltaMigrationRequired);

  // Withdrawing a submitted pre-check is ADMIN-only by explicit product decision -- engineers and
  // Migration Managers no longer have it (managers review: to send something back they decline).
  // Mirrored in PreCheckSubmissionService.withdraw, which enforces it server-side.
  const isAdmin = currentUser?.role === "ADMIN";
  const canWithdraw = submission.status === "SUBMITTED" && (!AUTH_CONFIGURED || isAdmin);
  const completedCount = visibleItems.filter(isItemComplete).length;
  const allCompleted = visibleItems.length > 0 && completedCount === visibleItems.length;
  const allHaveEvidence = visibleItems
    .filter((i) => i.itemName !== DELTA_TYPE_ITEM)
    .every((i) => !!i.evidenceFilePath);
  const allHaveNotes = visibleItems
    .filter((i) => i.itemName !== DELTA_TYPE_ITEM)
    .every((i) => !!i.notes?.trim());
  // Everything filled in correctly -- only then does the Submit button appear.
  const readyToSubmit = allCompleted && allHaveEvidence && allHaveNotes && hasMigrationManager && !!submittedByName;
  const progressPct = visibleItems.length === 0 ? 0 : Math.round((completedCount / visibleItems.length) * 100);
  const badge = STATUS_BADGE[submission.status] || STATUS_BADGE.NOT_STARTED;

  const updateItemInPlace = (updated) => {
    setSubmission((prev) => ({
      ...prev,
      items: prev.items.map((i) => (i.id === updated.id ? updated : i)),
    }));
  };

  const handleSubmit = async () => {
    // Perfect check: collect every blocking reason and surface them all at once, rather than one at
    // a time, so the user knows exactly what's left before the form can be submitted.
    const problems = [];
    if (!submittedByName) problems.push("enter your name");
    if (!hasMigrationManager) problems.push("assign a Migration Manager to this project");
    if (!allCompleted) problems.push("select a status for every item");
    if (!allHaveEvidence) problems.push("attach evidence for every item");
    if (!allHaveNotes) problems.push("add a note for every item");
    if (problems.length > 0) {
      const msg = `Can't submit yet — ${problems.join(", ")}.`;
      setError(msg);
      showToast(msg, "error");
      return;
    }
    const ok = await confirm({
      title: "Submit for review?",
      message: "This locks the form for Migration Manager review — no further edits until it's approved or withdrawn.",
      confirmLabel: "Submit",
    });
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      await submitPreCheckForReview(combinationId, { submittedBy: submittedByName });
      showToast("Pre-check submitted for Migration Manager review.", "success");
      load();
      onChanged?.();
    } catch (err) {
      const msg = err.response?.data?.message || "Something went wrong submitting for review. Please try again.";
      setError(msg);
      showToast(msg, "error");
    } finally {
      setBusy(false);
    }
  };

  const handleWithdraw = async () => {
    const ok = await confirm({
      title: "Withdraw submission?",
      message:
        "The form returns to draft so it can be corrected and resubmitted, and the approval chain is cleared. " +
        "As an admin this also rolls back an already-approved chain or an initiated Delta.",
      confirmLabel: "Withdraw",
      danger: true,
    });
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      await withdrawPreCheck(combinationId);
      showToast("Submission withdrawn — you can edit and resubmit.", "success");
      load();
      onChanged?.();
    } catch (err) {
      const msg = err.response?.data?.message || "Couldn't withdraw the submission. Please try again.";
      setError(msg);
      showToast(msg, "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      {showBackNav && (
        <PreCheckBackNav
          fromSignoff={fromSignoff}
          projectId={combination.projectId}
          projectName={combination.projectName}
        />
      )}

      {showHeader && (
        <>
          <PreCheckHeader serverName={combination.serverName} combinationName={combination.combinationName}>
            {combination.finalDeltaComplete ? (
              <span className="badge purple">Final Delta complete</span>
            ) : (
              // Before submission nothing has settled this cycle's type, so name the cycle by number
              // alone rather than guessing Pre vs Final.
              <DeltaBadge
                deltaType={combination.currentDeltaType}
                deltaPhase={combination.deltaPhase}
                label={combination.currentDeltaLabel}
                fallback={`Delta ${combination.currentCycleNumber}`}
              />
            )}
          </PreCheckHeader>
          {/* Negative top margin pulls this back against the header, which owns the 20px gap below
              itself for the case where no summary line follows it. */}
          <p style={{ color: "var(--color-text-muted)", marginTop: -12, marginBottom: 20 }}>
            {combination.totalPairs} migration pair(s) under this combination share this pre-check.
            {(() => {
              const prior = previousDeltasDoneCount(combination);
              return prior > 0 ? ` ${prior} delta${prior === 1 ? "" : "s"} already completed here.` : "";
            })()}
          </p>
        </>
      )}

      {combination.finalDeltaComplete && (
        <div
          className="card"
          style={{ borderLeft: "3px solid var(--color-purple)", marginBottom: 16 }}
        >
          <strong style={{ fontSize: 14 }}>This combination's migration is complete</strong>
          <p style={{ color: "var(--color-text-muted)", fontSize: 13.5, marginBottom: 0 }}>
            The Final Delta was finished
            {combination.finalDeltaCompletedBy ? ` by ${emailLocalPart(combination.finalDeltaCompletedBy)}` : ""}
            {combination.finalDeltaCompletedAt
              ? ` on ${new Date(combination.finalDeltaCompletedAt).toLocaleString()}`
              : ""}
            . The checklist below is kept read-only as a record — no further deltas run on this
            combination, and its server can be decommissioned once every combination is done.
          </p>
        </div>
      )}

      {/* A decline is a dead end for the engineer now that withdrawal is admin-only, so say so plainly
          instead of just showing a locked form with no explanation. */}
      {combination.blockedByDecline && !combination.finalDeltaComplete && (
        <div className="card" style={{ borderLeft: "3px solid var(--color-red)", marginBottom: 16 }}>
          <strong style={{ fontSize: 14 }}>
            Declined by {combination.declinedByRoleLabel || "an approver"}
          </strong>
          <p style={{ color: "var(--color-text-muted)", fontSize: 13.5, marginBottom: 0 }}>
            {combination.declinedBy ? `${emailLocalPart(combination.declinedBy)} declined this` : "This was declined"}
            {combination.declinedAt ? ` on ${new Date(combination.declinedAt).toLocaleString()}` : ""}.{" "}
            {isAdmin
              ? "Withdraw it below to unlock the form so it can be corrected and resubmitted."
              : "Ask an admin to withdraw it — that unlocks the form so you can correct and resubmit it."}
          </p>
          {/* The reason, not just the fact. This banner tells the engineer to correct and resubmit, so
              omitting what was objected to left them guessing -- the reason was displayed only in the
              Approvals table, which is not where the correcting happens. Full text, not the table's
              3-line clamp: there is room here and this is the copy they work from. Declines recorded
              before reasons were required have none, so say that rather than rendering an empty box. */}
          <div className="decline-reason-quote">
            {combination.declineReason || (
              <span className="decline-note__missing">No reason was recorded for this decline.</span>
            )}
          </div>
        </div>
      )}

      <div className="card precheck-section">
        <h3>
          Pre-Check Items{" "}
          <span style={{ color: "var(--color-text-faint)", fontWeight: 500 }}>
            {completedCount}/{visibleItems.length}
          </span>
          {/* Which cycle this blank form belongs to. Without it, a freshly reset checklist is
              indistinguishable from a brand-new combination's first one. */}
          {!combination.finalDeltaComplete && combination.currentCycleNumber > 1 && (
            <span style={{ color: "var(--color-text-muted)", fontWeight: 500, fontSize: 13 }}>
              {" "}
              · Delta cycle {combination.currentCycleNumber}
            </span>
          )}
        </h3>

        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 10 }}>
          <span className={`badge ${badge.color}`}>{badge.label}</span>
          {submission.status === "SUBMITTED" && (
            <span className="progress-label">
              Submitted by {submission.submittedBy} on {new Date(submission.submittedAt).toLocaleString()}
            </span>
          )}
          {canWithdraw && (
            <button
              className="btn secondary"
              onClick={handleWithdraw}
              disabled={busy}
              style={{ padding: "4px 12px", fontSize: 12.5 }}
              title="Admin only: un-submit this pre-check so it can be corrected and resubmitted."
            >
              {busy ? (
                <>
                  <span className="spinner" style={{ marginRight: 6 }} />
                  Withdrawing…
                </>
              ) : (
                <>
                  <UndoIcon />
                  Withdraw
                </>
              )}
            </button>
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
          {visibleItems.map((item) => (
            <ItemRow
              key={item.id}
              item={item}
              locked={locked}
              combinationId={combinationId}
              editingAs={submittedByName}
              productType={combination.productType}
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
                ? `Migration Manager: ${combination.migrationManagerName}. Fill out every item, then submit for review.`
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
              {readyToSubmit && (
                <button className="btn" onClick={handleSubmit} disabled={busy}>
                  {busy ? (
                    <>
                      <span className="spinner" style={{ marginRight: 8 }} />
                      Submitting…
                    </>
                  ) : (
                    <>
                      <SendIcon />
                      Submit for Migration Manager Review
                    </>
                  )}
                </button>
              )}
            </div>
          </div>
        )}
      </div>
      {/* No Delta history here. It lives on the server/combination page above the pre-check link
          (WorkspacePairsPanel), which is where people go to review past cycles -- repeating it under
          the live form put a read-only record of finished work at the bottom of a form someone is
          trying to fill in. */}
    </div>
  );
}
