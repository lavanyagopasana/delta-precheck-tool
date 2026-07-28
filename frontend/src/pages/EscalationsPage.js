import React, { useEffect, useState } from "react";
import {
  getEscalations,
  createEscalation,
  updateEscalation,
  removeEscalation,
  resolveEscalation,
  getServers,
  getProjects,
  uploadEvidence,
} from "../api/client";
import { EscalationStatusBadge, PriorityBadge } from "../components/StatusBadge";
import AttachmentPreview from "../components/AttachmentPreview";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { EditIcon, TrashIcon, CheckIcon, PlusIcon } from "../components/Icons";
import { useConfirm } from "../components/ConfirmDialog";

const MAX_EVIDENCE_FILE_SIZE_MB = 20;

const PRIORITY_OPTIONS = [
  { value: "LOW", label: "Low", color: "gray" },
  { value: "MEDIUM", label: "Medium", color: "yellow" },
  { value: "HIGH", label: "High", color: "red" },
];

function PriorityPicker({ value, onChange }) {
  return (
    <div style={{ display: "flex", gap: 6 }}>
      {PRIORITY_OPTIONS.map((opt) => (
        <button
          key={opt.value}
          type="button"
          className={`priority-pill ${opt.color}${value === opt.value ? " active" : ""}`}
          onClick={() => onChange(opt.value)}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}

const EMPTY_FORM = {
  projectId: "",
  serverId: "",
  ticketNumber: "",
  description: "",
  reason: "",
  createdBy: "",
  status: "OPEN",
  priority: "MEDIUM",
  resolutionNotes: "",
  evidenceFilePath: "",
  evidenceFileName: "",
};

function TicketForm({ projects, servers, existingTicketNumbers, onCreated, existing = null }) {
  const currentUser = useCurrentUser();
  const isEdit = !!existing;
  const [form, setForm] = useState(() =>
    existing
      ? {
          projectId: "",
          serverId: String(existing.serverId ?? ""),
          ticketNumber: existing.ticketNumber || "",
          description: existing.description || "",
          reason: existing.reason || "",
          createdBy: existing.createdBy || "",
          status: existing.status || "OPEN",
          priority: existing.priority || "MEDIUM",
          resolutionNotes: existing.resolutionNotes || "",
          evidenceFilePath: existing.evidenceFilePath || "",
          evidenceFileName: existing.evidenceFileName || "",
        }
      : EMPTY_FORM
  );
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [error, setError] = useState(null);
  const showToast = useToast();

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  const setProject = (e) => {
    const projectId = e.target.value;
    setForm({ ...form, projectId, serverId: "" });
  };

  const serversForProject = form.projectId
    ? servers.filter((s) => String(s.projectId) === String(form.projectId))
    : [];

  // Email, not display name -- names collide across employees, email doesn't.
  const createdByName = AUTH_CONFIGURED
    ? currentUser?.email || currentUser?.name || ""
    : form.createdBy.trim();

  const requiredFilled =
    form.ticketNumber.trim() &&
    form.description.trim() &&
    form.reason.trim() &&
    (isEdit || (form.projectId && form.serverId && createdByName));
  const isDuplicate =
    !!form.ticketNumber.trim() &&
    form.ticketNumber.trim().toLowerCase() !== (existing?.ticketNumber || "").toLowerCase() &&
    existingTicketNumbers.has(form.ticketNumber.trim().toLowerCase());
  const canSubmit =
    requiredFilled && !isDuplicate && (form.status !== "RESOLVED" || form.resolutionNotes.trim());

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
      setForm((prev) => ({ ...prev, evidenceFilePath: result.filePath, evidenceFileName: result.fileName }));
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
    if (uploading) return;
    uploadFile(e.dataTransfer.files[0]);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSaving(true);
    setError(null);
    try {
      const payload = {
        ticketNumber: form.ticketNumber.trim(),
        description: form.description.trim(),
        reason: form.reason.trim(),
        status: form.status,
        priority: form.priority,
        resolutionNotes: form.resolutionNotes.trim() || null,
        evidenceFilePath: form.evidenceFilePath || null,
        evidenceFileName: form.evidenceFileName || null,
      };
      if (isEdit) {
        await updateEscalation(existing.id, payload);
        showToast(`Ticket ${form.ticketNumber.trim()} updated.`, "success");
      } else {
        await createEscalation({ ...payload, serverId: Number(form.serverId), createdBy: createdByName });
        showToast(`Ticket ${form.ticketNumber.trim()} logged.`, "success");
      }
      setForm(EMPTY_FORM);
      onCreated();
    } catch (err) {
      const msg = err.response?.data?.message || (isEdit ? "Failed to update ticket." : "Failed to create ticket.");
      setError(msg);
      showToast(msg, "error");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit}>
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginBottom: 10 }}>
        {isEdit ? (
          <span className="progress-label" style={{ alignSelf: "center" }}>
            Server: <strong>{existing.serverName}</strong>
          </span>
        ) : (
          <>
            <label className="sr-only" htmlFor="ticket-project">Project</label>
            <select id="ticket-project" value={form.projectId} onChange={setProject} style={{ minWidth: 180 }}>
              <option value="">Select project...</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            <label className="sr-only" htmlFor="ticket-server">Server</label>
            <select
              id="ticket-server"
              value={form.serverId}
              onChange={set("serverId")}
              disabled={!form.projectId}
              style={{ minWidth: 180 }}
            >
              <option value="">{form.projectId ? "Select server..." : "Select a project first"}</option>
              {serversForProject.map((s) => (
                <option key={s.serverId} value={s.serverId}>
                  {s.serverName}
                </option>
              ))}
            </select>
          </>
        )}
        <label className="sr-only" htmlFor="ticket-number">Ticket number</label>
        <input
          id="ticket-number"
          type="text"
          placeholder="Ticket No."
          value={form.ticketNumber}
          onChange={set("ticketNumber")}
          style={{ width: 160, borderColor: isDuplicate ? "var(--color-red)" : undefined }}
        />
        {!AUTH_CONFIGURED && (
          <>
            <label className="sr-only" htmlFor="ticket-created-by">Created by</label>
            <input
              id="ticket-created-by"
              type="text"
              placeholder="Created by"
              value={form.createdBy}
              onChange={set("createdBy")}
              style={{ width: 200 }}
            />
          </>
        )}
        <label className="sr-only" htmlFor="ticket-status">Status</label>
        <select id="ticket-status" value={form.status} onChange={set("status")}>
          <option value="OPEN">Open</option>
          <option value="RESOLVED">Resolved</option>
        </select>
        <PriorityPicker value={form.priority} onChange={(priority) => setForm({ ...form, priority })} />
      </div>

      <label className="sr-only" htmlFor="ticket-description">Description</label>
      <textarea
        id="ticket-description"
        rows={2}
        placeholder="Description of the ticket"
        value={form.description}
        onChange={set("description")}
        style={{ width: "100%", marginBottom: 10, resize: "vertical" }}
      />
      <label className="sr-only" htmlFor="ticket-reason">Reason</label>
      <textarea
        id="ticket-reason"
        rows={2}
        placeholder="Reason"
        value={form.reason}
        onChange={set("reason")}
        style={{ width: "100%", marginBottom: 10, resize: "vertical" }}
      />

      {form.status === "RESOLVED" && (
        <>
          <label className="sr-only" htmlFor="ticket-resolution-notes">Resolution notes</label>
          <textarea
            id="ticket-resolution-notes"
            rows={2}
            placeholder="What was done to resolve it?"
            value={form.resolutionNotes}
            onChange={set("resolutionNotes")}
            style={{ width: "100%", marginBottom: 10, resize: "vertical" }}
          />
        </>
      )}

      <div
        className={`dropzone${dragOver ? " drag-over" : ""}`}
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        style={{ marginBottom: 10 }}
      >
        <label style={{ fontSize: 12.5, color: "var(--color-primary)", cursor: "pointer", fontWeight: 600 }}>
          {form.evidenceFilePath ? "Replace attachment" : "Attach file"}
          <input type="file" onChange={handleFileChange} disabled={uploading} style={{ display: "none" }} />
        </label>
        <span className="progress-label" style={{ marginLeft: 8 }}>
          or drag a file here · any type, up to {MAX_EVIDENCE_FILE_SIZE_MB}MB
        </span>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 10 }}>
        {uploading && <span className="progress-label">Uploading...</span>}
        <AttachmentPreview filePath={form.evidenceFilePath} fileName={form.evidenceFileName} />
      </div>

      {isDuplicate && (
        <div className="inline-hint" style={{ marginBottom: 10 }}>
          Ticket number "{form.ticketNumber.trim()}" has already been logged.
        </div>
      )}
      {error && <div className="inline-hint" style={{ marginBottom: 10 }}>{error}</div>}

      <div className="form-actions" style={{ justifyContent: "flex-end", gap: 10 }}>
        {AUTH_CONFIGURED && createdByName && (
          <span style={{ color: "var(--color-text-faint)", fontSize: 12.5, marginRight: "auto" }}>
            Logging as {createdByName}
          </span>
        )}
        <button className="btn" type="submit" disabled={!canSubmit || saving}>
          {saving ? "Saving..." : isEdit ? "Save Changes" : "Log Ticket"}
        </button>
      </div>
    </form>
  );
}

function ResolveControl({ escalation, onResolved }) {
  const [resolving, setResolving] = useState(false);
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const showToast = useToast();

  if (escalation.status !== "OPEN") return null;

  if (!resolving) {
    return (
      <button
        className="btn secondary"
        style={{ padding: "6px 10px" }}
        title="Mark resolved"
        aria-label="Mark resolved"
        onClick={(e) => {
          e.stopPropagation();
          setResolving(true);
        }}
      >
        <CheckIcon size={18} style={{ marginRight: 0 }} />
      </button>
    );
  }

  const handleConfirm = async () => {
    if (!notes.trim()) return;
    setSaving(true);
    try {
      await resolveEscalation(escalation.id, notes.trim());
      showToast(`Ticket ${escalation.ticketNumber} resolved.`);
      onResolved();
    } finally {
      setSaving(false);
      setResolving(false);
    }
  };

  return (
    <div
      style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6, minWidth: 220, margin: "0 auto" }}
      onClick={(e) => e.stopPropagation()}
    >
      <label className="sr-only" htmlFor={`resolve-notes-${escalation.id}`}>Resolution notes</label>
      <textarea
        id={`resolve-notes-${escalation.id}`}
        rows={2}
        placeholder="What was done to resolve it?"
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
        style={{ resize: "vertical" }}
        autoFocus
      />
      <div style={{ display: "flex", gap: 6 }}>
        <button className="btn" style={{ padding: "4px 10px", fontSize: 12 }} onClick={handleConfirm} disabled={!notes.trim() || saving}>
          Confirm
        </button>
        <button className="btn secondary" style={{ padding: "4px 10px", fontSize: 12 }} onClick={() => setResolving(false)} disabled={saving}>
          Cancel
        </button>
      </div>
    </div>
  );
}

export default function EscalationsPage() {
  const [escalations, setEscalations] = useState([]);
  const [servers, setServers] = useState([]);
  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("ALL");
  const [showModal, setShowModal] = useState(false);
  const [editing, setEditing] = useState(null);
  const showToast = useToast();
  const confirm = useConfirm();

  const load = () => {
    setLoading(true);
    Promise.all([getEscalations(), getServers(), getProjects()])
      .then(([e, s, p]) => {
        setEscalations(e);
        setServers(s);
        setProjects(p);
      })
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const handleDelete = async (escalation) => {
    const ok = await confirm({
      title: `Delete ticket ${escalation.ticketNumber}?`,
      message: "This permanently removes the ticket and its attachment. This can't be undone.",
      confirmLabel: "Delete",
      danger: true,
    });
    if (!ok) return;
    try {
      await removeEscalation(escalation.id);
      showToast(`Ticket ${escalation.ticketNumber} deleted.`, "success");
      load();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to delete ticket.", "error");
    }
  };

  if (loading) return <p>Loading tickets...</p>;

  const filtered = escalations.filter((e) => filter === "ALL" || e.status === filter);

  return (
    <div>
      <DataTable
        title="Jira Tickets Tracking"
        rows={filtered}
        rowKey={(e) => e.id}
        searchPlaceholder="Filter tickets..."
        emptyMessage="No tickets yet. Click Log Ticket above."
        defaultSort={{ key: "createdAt", dir: "desc" }}
        toolbarRight={
          <div style={{ display: "flex", gap: 8 }}>
            <select value={filter} onChange={(e) => setFilter(e.target.value)} style={{ minWidth: 130 }}>
              <option value="ALL">All statuses</option>
              <option value="OPEN">Open</option>
              <option value="RESOLVED">Resolved</option>
            </select>
            <button className="btn" onClick={() => setShowModal(true)}>
              <PlusIcon />
              Log Ticket
            </button>
          </div>
        }
        columns={[
          {
            key: "ticketNumber",
            label: "Ticket",
            render: (e) => (
              <div>
                <div style={{ fontWeight: 700, whiteSpace: "nowrap" }}>{e.ticketNumber}</div>
                <div style={{ fontSize: 11.5, color: "var(--color-text-faint)", whiteSpace: "nowrap" }}>{e.serverName}</div>
              </div>
            ),
          },
          {
            key: "description",
            label: "Details",
            render: (e) => (
              <div style={{ maxWidth: 320, margin: "0 auto" }}>
                <div style={{ fontSize: 13 }}>{e.description}</div>
                {e.reason && (
                  <div style={{ fontSize: 11.5, color: "var(--color-text-faint)", marginTop: 3 }}>
                    Reason: {e.reason}
                  </div>
                )}
              </div>
            ),
          },
          {
            key: "priority",
            label: "Priority",
            sortable: false,
            render: (e) => <PriorityBadge priority={e.priority} />,
          },
          {
            key: "createdBy",
            label: "Reported",
            render: (e) => (
              <div>
                <div style={{ fontSize: 13, whiteSpace: "nowrap" }}>{e.createdBy}</div>
                <div style={{ fontSize: 11, color: "var(--color-text-faint)", whiteSpace: "nowrap" }}>
                  {new Date(e.createdAt).toLocaleString()}
                </div>
              </div>
            ),
          },
          {
            key: "evidence",
            label: "Attachment",
            sortable: false,
            filterable: false,
            render: (e) =>
              e.evidenceFilePath ? (
                <AttachmentPreview filePath={e.evidenceFilePath} fileName={e.evidenceFileName} showName={false} />
              ) : (
                <span style={{ color: "var(--color-text-faint)", fontSize: 12 }}>—</span>
              ),
          },
          {
            key: "status",
            label: "Status",
            render: (e) => <EscalationStatusBadge status={e.status} />,
          },
          {
            key: "actions",
            label: "",
            sortable: false,
            filterable: false,
            render: (e) => (
              <div
                style={{ display: "flex", gap: 6, alignItems: "center", justifyContent: "center", flexWrap: "wrap" }}
                onClick={(evt) => evt.stopPropagation()}
              >
                <ResolveControl escalation={e} onResolved={load} />
                <button
                  className="btn secondary"
                  style={{ padding: "6px 10px" }}
                  title="Edit ticket"
                  aria-label="Edit ticket"
                  onClick={() => setEditing(e)}
                >
                  <EditIcon size={18} style={{ marginRight: 0 }} />
                </button>
                <button
                  className="btn danger"
                  style={{ padding: "6px 10px" }}
                  title="Delete ticket"
                  aria-label="Delete ticket"
                  onClick={() => handleDelete(e)}
                >
                  <TrashIcon size={18} style={{ marginRight: 0 }} />
                </button>
              </div>
            ),
          },
        ]}
      />

      {showModal && (
        <Modal title="Log a Ticket" onClose={() => setShowModal(false)} width={640} closeIcon>
          <TicketForm
            projects={projects}
            servers={servers}
            existingTicketNumbers={new Set(escalations.map((e) => e.ticketNumber.toLowerCase()))}
            onCreated={() => {
              load();
              setShowModal(false);
            }}
          />
        </Modal>
      )}

      {editing && (
        <Modal title={`Edit Ticket ${editing.ticketNumber}`} onClose={() => setEditing(null)} width={640} closeIcon>
          <TicketForm
            projects={projects}
            servers={servers}
            existing={editing}
            existingTicketNumbers={new Set(escalations.map((e) => e.ticketNumber.toLowerCase()))}
            onCreated={() => {
              load();
              setEditing(null);
            }}
          />
        </Modal>
      )}
    </div>
  );
}
