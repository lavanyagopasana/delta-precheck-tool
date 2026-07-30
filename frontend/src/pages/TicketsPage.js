import React, { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  getTickets,
  createTicket,
  updateTicket,
  removeTicket,
  resolveTicket,
  validateTicketUrl,
  getServers,
  getProjects,
} from "../api/client";
import { TicketStatusBadge } from "../components/StatusBadge";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
import { useToast } from "../components/Toast";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { EditIcon, TrashIcon, CheckIcon, PlusIcon } from "../components/Icons";
import { useConfirm } from "../components/ConfirmDialog";
import { apiErrorMessage } from "../utils/apiError";
import { emailLocalPart } from "../utils/format";

const EMPTY_FORM = {
  projectId: "",
  serverId: "",
  ticketUrl: "",
  createdBy: "",
  status: "OPEN",
};

// A ticket link only needs to look like an http(s) URL to be submittable; the "Validate link" button
// does the real server-side reachability check on demand.
const isLikelyUrl = (value) => /^https?:\/\/.+/i.test(value.trim());

function TicketForm({ projects, servers, existingTicketUrls, onCreated, existing = null }) {
  const currentUser = useCurrentUser();
  const isEdit = !!existing;
  const [form, setForm] = useState(() =>
    existing
      ? {
          projectId: "",
          serverId: String(existing.serverId ?? ""),
          ticketUrl: existing.ticketUrl || "",
          createdBy: existing.createdBy || "",
          status: existing.status || "OPEN",
        }
      : EMPTY_FORM
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState(null);
  const showToast = useToast();

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  // Changing the URL invalidates any previous validation result.
  const setUrl = (e) => {
    setForm({ ...form, ticketUrl: e.target.value });
    setValidation(null);
  };

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
    isLikelyUrl(form.ticketUrl) && (isEdit || (form.projectId && form.serverId && createdByName));
  const isDuplicate =
    !!form.ticketUrl.trim() &&
    form.ticketUrl.trim().toLowerCase() !== (existing?.ticketUrl || "").toLowerCase() &&
    existingTicketUrls.has(form.ticketUrl.trim().toLowerCase());
  const canSubmit = requiredFilled && !isDuplicate;

  const handleValidate = async () => {
    if (!isLikelyUrl(form.ticketUrl)) return;
    setValidating(true);
    setValidation(null);
    try {
      const result = await validateTicketUrl(form.ticketUrl.trim());
      setValidation(result);
    } catch (err) {
      setValidation({ ok: false, message: "Could not validate the link right now." });
    } finally {
      setValidating(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSaving(true);
    setError(null);
    try {
      const payload = {
        ticketUrl: form.ticketUrl.trim(),
        status: form.status,
      };
      if (isEdit) {
        await updateTicket(existing.id, payload);
        showToast("Ticket updated.", "success");
      } else {
        await createTicket({ ...payload, serverId: Number(form.serverId), createdBy: createdByName });
        showToast("Ticket logged.", "success");
      }
      setForm(EMPTY_FORM);
      setValidation(null);
      onCreated();
    } catch (err) {
      const msg = apiErrorMessage(err, isEdit ? "Failed to update ticket." : "Failed to create ticket.");
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
      </div>

      <label className="sr-only" htmlFor="ticket-url">Ticket URL</label>
      <div style={{ display: "flex", gap: 8, marginBottom: 8 }}>
        <input
          id="ticket-url"
          type="url"
          placeholder="https://your-tracker.example.com/browse/TICKET-123"
          value={form.ticketUrl}
          onChange={setUrl}
          style={{ flex: 1, borderColor: isDuplicate ? "var(--color-red)" : undefined }}
        />
        <button
          type="button"
          className="btn secondary"
          onClick={handleValidate}
          disabled={!isLikelyUrl(form.ticketUrl) || validating}
        >
          {validating ? "Checking..." : "Validate link"}
        </button>
      </div>

      {validation && (
        <div
          className="inline-hint"
          style={{ marginBottom: 10, color: validation.ok ? "#15803d" : "var(--color-red)" }}
        >
          {validation.ok ? "\u2713 " : "\u2717 "}
          {validation.message}
        </div>
      )}

      {isDuplicate && (
        <div className="inline-hint" style={{ marginBottom: 10 }}>
          This ticket link has already been logged.
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

function ResolveControl({ ticket, onResolved }) {
  const [saving, setSaving] = useState(false);
  const showToast = useToast();

  if (ticket.status !== "OPEN") return null;

  const handleResolve = async (e) => {
    e.stopPropagation();
    setSaving(true);
    try {
      await resolveTicket(ticket.id);
      showToast("Ticket resolved.", "success");
      onResolved();
    } catch (err) {
      showToast(apiErrorMessage(err, "Failed to resolve ticket."), "error");
    } finally {
      setSaving(false);
    }
  };

  return (
    <button
      className="btn success"
      style={{ padding: "6px 10px" }}
      title="Mark resolved"
      aria-label="Mark resolved"
      onClick={handleResolve}
      disabled={saving}
    >
      <CheckIcon size={18} style={{ marginRight: 0 }} />
    </button>
  );
}

export default function TicketsPage() {
  const currentUser = useCurrentUser();
  const [tickets, setTickets] = useState([]);
  const [servers, setServers] = useState([]);
  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [searchParams] = useSearchParams();
  const projectParam = searchParams.get("project");
  const statusParam = searchParams.get("status");
  const [filter, setFilter] = useState(statusParam === "OPEN" || statusParam === "RESOLVED" ? statusParam : "ALL");
  const [projectFilter, setProjectFilter] = useState(projectParam || "ALL");
  const [showModal, setShowModal] = useState(false);
  const [editing, setEditing] = useState(null);
  const showToast = useToast();
  const confirm = useConfirm();

  const load = () => {
    setLoading(true);
    Promise.all([getTickets(), getServers(), getProjects()])
      .then(([t, s, p]) => {
        setTickets(t);
        setServers(s);
        setProjects(p);
        setLoadError(null);
      })
      .catch((err) => setLoadError(apiErrorMessage(err, "Failed to load tickets.")))
      .finally(() => setLoading(false));
  };

  // After a ticket mutation only the ticket list can have changed -- the project and server lists
  // (used solely for the Log-Ticket dropdowns and the project filter) are unaffected. Refetch just
  // the tickets and leave servers/projects as-is. Same UI states (loading -> table), fewer calls.
  const reloadTickets = () => {
    setLoading(true);
    getTickets()
      .then((t) => {
        setTickets(t);
        setLoadError(null);
      })
      .catch((err) => setLoadError(apiErrorMessage(err, "Failed to load tickets.")))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const handleDelete = async (ticket) => {
    const ok = await confirm({
      title: "Delete this ticket?",
      message: "This permanently removes the ticket. This can't be undone.",
      confirmLabel: "Delete",
      danger: true,
    });
    if (!ok) return;
    try {
      await removeTicket(ticket.id);
      showToast("Ticket deleted.", "success");
      reloadTickets();
    } catch (err) {
      showToast(apiErrorMessage(err, "Failed to delete ticket."), "error");
    }
  };

  if (loading) return <p>Loading tickets...</p>;

  if (loadError) {
    return (
      <div>
        <h2>Ticket Tracker</h2>
        <div className="inline-hint">{loadError}</div>
        <button className="btn secondary" style={{ marginTop: 12 }} onClick={load}>
          Retry
        </button>
      </div>
    );
  }

  // Tickets carry projectId, so the project filter is a direct match.
  const filtered = tickets.filter(
    (t) =>
      (filter === "ALL" || t.status === filter) &&
      (projectFilter === "ALL" || String(t.projectId) === String(projectFilter))
  );

  // Edit/resolve/delete are limited to the engineer who logged the ticket (createdBy is their email)
  // or an admin. Mirrors the backend rule in TicketService.requireManageable; when auth is off,
  // everything is permitted. This only hides the buttons -- the backend is the real gate.
  const canManage = (t) =>
    !AUTH_CONFIGURED ||
    currentUser?.role === "ADMIN" ||
    (!!currentUser?.email && !!t.createdBy && currentUser.email.toLowerCase() === t.createdBy.toLowerCase());

  return (
    <div>
      <DataTable
        title="Ticket Tracker"
        rows={filtered}
        rowKey={(t) => t.id}
        searchPlaceholder="Filter tickets..."
        emptyMessage="No tickets yet. Click Log Ticket above."
        defaultSort={{ key: "createdAt", dir: "desc" }}
        toolbarRight={
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <select
              value={projectFilter}
              onChange={(e) => setProjectFilter(e.target.value)}
              style={{ minWidth: 150 }}
            >
              <option value="ALL">All projects</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
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
            key: "projectName",
            label: "Project / Server",
            render: (t) => (
              <div>
                <div style={{ fontWeight: 700, whiteSpace: "nowrap" }}>{t.projectName || "—"}</div>
                <div style={{ fontSize: 11.5, color: "var(--color-text-faint)", whiteSpace: "nowrap" }}>{t.serverName}</div>
              </div>
            ),
          },
          {
            key: "ticketUrl",
            label: "Link",
            render: (t) => (
              <div style={{ maxWidth: 360, margin: "0 auto" }}>
                <a
                  href={t.ticketUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={(evt) => evt.stopPropagation()}
                  style={{ fontWeight: 600, wordBreak: "break-all" }}
                >
                  {t.ticketUrl}
                </a>
              </div>
            ),
          },
          {
            key: "createdBy",
            label: "Reported",
            render: (t) => (
              <div>
                <div style={{ fontSize: 13, whiteSpace: "nowrap" }} title={t.createdBy}>{emailLocalPart(t.createdBy)}</div>
                <div style={{ fontSize: 11, color: "var(--color-text-faint)", whiteSpace: "nowrap" }}>
                  {new Date(t.createdAt).toLocaleString()}
                </div>
              </div>
            ),
          },
          {
            key: "status",
            label: "Status",
            render: (t) => <TicketStatusBadge status={t.status} />,
          },
          {
            key: "actions",
            label: "",
            sortable: false,
            filterable: false,
            render: (t) =>
              canManage(t) ? (
                <div
                  style={{ display: "flex", gap: 6, alignItems: "center", justifyContent: "center", flexWrap: "wrap" }}
                  onClick={(evt) => evt.stopPropagation()}
                >
                  <ResolveControl ticket={t} onResolved={reloadTickets} />
                  <button
                    className="btn secondary"
                    style={{ padding: "6px 10px" }}
                    title="Edit ticket"
                    aria-label="Edit ticket"
                    onClick={() => setEditing(t)}
                  >
                    <EditIcon size={18} style={{ marginRight: 0 }} />
                  </button>
                  <button
                    className="btn danger"
                    style={{ padding: "6px 10px" }}
                    title="Delete ticket"
                    aria-label="Delete ticket"
                    onClick={() => handleDelete(t)}
                  >
                    <TrashIcon size={18} style={{ marginRight: 0 }} />
                  </button>
                </div>
              ) : (
                <span style={{ color: "var(--color-text-faint)", fontSize: 12 }}>—</span>
              ),
          },
        ]}
      />

      {showModal && (
        <Modal title="Log a Ticket" onClose={() => setShowModal(false)} width={640} closeIcon>
          <TicketForm
            projects={projects}
            servers={servers}
            existingTicketUrls={new Set(tickets.map((t) => t.ticketUrl.toLowerCase()))}
            onCreated={() => {
              reloadTickets();
              setShowModal(false);
            }}
          />
        </Modal>
      )}

      {editing && (
        <Modal title="Edit Ticket" onClose={() => setEditing(null)} width={640} closeIcon>
          <TicketForm
            projects={projects}
            servers={servers}
            existing={editing}
            existingTicketUrls={new Set(tickets.map((t) => t.ticketUrl.toLowerCase()))}
            onCreated={() => {
              reloadTickets();
              setEditing(null);
            }}
          />
        </Modal>
      )}
    </div>
  );
}
