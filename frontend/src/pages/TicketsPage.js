import React, { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  getTickets,
  createTicket,
  updateTicket,
  removeTicket,
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
import { EditIcon, TrashIcon, PlusIcon } from "../components/Icons";
import { useConfirm } from "../components/ConfirmDialog";
import { apiErrorMessage } from "../utils/apiError";
import { emailLocalPart } from "../utils/format";

const EMPTY_CREATE_FORM = {
  projectId: "",
  serverId: "",
  combinationId: "",
  ticketNumber: "",
  createdBy: "",
};

// A ticket link only needs to look like an http(s) URL to be submittable; the "Validate link" button
// does the real server-side reachability check on demand. Only used by the edit form now -- creating
// a ticket no longer takes a raw URL (see JiraService).
const isLikelyUrl = (value) => /^https?:\/\/.+/i.test(value.trim());

// Editing an already-logged ticket still works the old way (raw URL + status) -- only *logging a new*
// ticket was replaced by the ticket-number-fetches-from-Jira flow below.
function EditTicketForm({ existing, existingTicketUrls, onCreated }) {
  const [form, setForm] = useState({ ticketUrl: existing.ticketUrl || "", status: existing.status || "OPEN" });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState(null);
  const showToast = useToast();

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  const setUrl = (e) => {
    setForm({ ...form, ticketUrl: e.target.value });
    setValidation(null);
  };

  const isDuplicate =
    !!form.ticketUrl.trim() &&
    form.ticketUrl.trim().toLowerCase() !== (existing.ticketUrl || "").toLowerCase() &&
    existingTicketUrls.has(form.ticketUrl.trim().toLowerCase());
  const canSubmit = isLikelyUrl(form.ticketUrl) && !isDuplicate;

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
      await updateTicket(existing.id, { ticketUrl: form.ticketUrl.trim(), status: form.status });
      showToast("Ticket updated.", "success");
      onCreated();
    } catch (err) {
      const msg = apiErrorMessage(err, "Failed to update ticket.");
      setError(msg);
      showToast(msg, "error");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit}>
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginBottom: 10 }}>
        <span className="progress-label" style={{ alignSelf: "center" }}>
          Server: <strong>{existing.serverName}</strong>
          {existing.combinationName && (
            <>
              {" "}/ Combination: <strong>{existing.combinationName}</strong>
            </>
          )}
        </span>
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
        <button className="btn" type="submit" disabled={!canSubmit || saving}>
          {saving ? "Saving..." : "Save Changes"}
        </button>
      </div>
    </form>
  );
}

// Logging a new ticket: Project + Server still need picking (Jira has no idea which of our internal
// servers a ticket is about), but the ticket number is all that's typed by hand -- status, reporter,
// summary, and the link itself are fetched from Jira on submit (see JiraService/TicketService).
function LogTicketForm({ projects, servers, onCreated }) {
  const currentUser = useCurrentUser();
  const [form, setForm] = useState(EMPTY_CREATE_FORM);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const showToast = useToast();

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  const setProject = (e) => {
    const projectId = e.target.value;
    setForm({ ...form, projectId, serverId: "", combinationId: "" });
  };

  const setServer = (e) => {
    const serverId = e.target.value;
    setForm({ ...form, serverId, combinationId: "" });
  };

  const serversForProject = form.projectId
    ? servers.filter((s) => String(s.projectId) === String(form.projectId))
    : [];

  // A server can have several combinations (e.g. Box -> OneDrive and Google Drive -> OneDrive both
  // on the same server), each migrated independently -- a ticket has to point at one specific
  // combination, not the whole server, or its open-ticket count would show up against every
  // combination on that server.
  const selectedServer = servers.find((s) => String(s.serverId) === String(form.serverId));
  const combinationsForServer = selectedServer?.combinations || [];

  // Email, not display name -- names collide across employees, email doesn't.
  const createdByName = AUTH_CONFIGURED
    ? currentUser?.email || currentUser?.name || ""
    : form.createdBy.trim();

  const canSubmit = !!(form.projectId && form.serverId && form.combinationId && form.ticketNumber.trim() && createdByName);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSaving(true);
    setError(null);
    try {
      await createTicket({
        combinationId: Number(form.combinationId),
        ticketNumber: form.ticketNumber.trim(),
        createdBy: createdByName,
      });
      showToast("Ticket logged.", "success");
      setForm(EMPTY_CREATE_FORM);
      onCreated();
    } catch (err) {
      const msg = apiErrorMessage(err, "Could not fetch that ticket from Jira.");
      setError(msg);
      showToast(msg, "error");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit}>
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginBottom: 10 }}>
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
          onChange={setServer}
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
        <label className="sr-only" htmlFor="ticket-combination">Combination</label>
        <select
          id="ticket-combination"
          value={form.combinationId}
          onChange={set("combinationId")}
          disabled={!form.serverId}
          style={{ minWidth: 180 }}
        >
          <option value="">
            {!form.serverId
              ? "Select a server first"
              : combinationsForServer.length
              ? "Select combination..."
              : "No combinations yet"}
          </option>
          {combinationsForServer.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
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
      </div>

      <label className="sr-only" htmlFor="ticket-number">Ticket Number</label>
      <input
        id="ticket-number"
        type="text"
        placeholder="Jira ticket number (e.g. PROJ-123)"
        value={form.ticketNumber}
        onChange={set("ticketNumber")}
        style={{ width: "100%", marginBottom: 8 }}
      />
      <div style={{ fontSize: 12, color: "var(--color-text-faint)", marginBottom: 10 }}>
        Status, reporter, summary, and the link are pulled from Jira automatically once you submit.
      </div>

      {error && <div className="inline-hint" style={{ marginBottom: 10 }}>{error}</div>}

      <div className="form-actions" style={{ justifyContent: "flex-end", gap: 10 }}>
        {AUTH_CONFIGURED && createdByName && (
          <span style={{ color: "var(--color-text-faint)", fontSize: 12.5, marginRight: "auto" }}>
            Logging as {createdByName}
          </span>
        )}
        <button className="btn" type="submit" disabled={!canSubmit || saving}>
          {saving ? "Fetching from Jira..." : "Log Ticket"}
        </button>
      </div>
    </form>
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

  // Edit/delete are admin-only -- not even the engineer who logged the ticket can change it
  // themselves. Mirrors the backend rule in TicketService.requireManageable; when auth is off,
  // everything is permitted. This only hides the buttons -- the backend is the real gate.
  const canManage = () => !AUTH_CONFIGURED || currentUser?.role === "ADMIN";

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
            // Slashes read as a URL path in a column whose values include server URLs -- see the
            // matching header on ApprovalsPage.
            label: "Project · Server · Combination",
            render: (t) => (
              <div>
                <div style={{ fontWeight: 700, whiteSpace: "nowrap" }}>{t.projectName || "—"}</div>
                <div style={{ fontSize: 11.5, color: "var(--color-text-faint)", whiteSpace: "nowrap" }}>{t.serverName}</div>
                {t.combinationName && (
                  <div style={{ fontSize: 11.5, color: "var(--color-text-faint)", whiteSpace: "nowrap" }}>
                    {t.combinationName}
                  </div>
                )}
              </div>
            ),
          },
          {
            key: "ticketUrl",
            label: "Ticket",
            render: (t) => (
              <div style={{ maxWidth: 360, margin: "0 auto" }}>
                <a
                  href={t.ticketUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={(evt) => evt.stopPropagation()}
                  style={{ fontWeight: 700, fontSize: 13.5, wordBreak: "break-all" }}
                >
                  {t.jiraKey || t.ticketUrl}
                </a>
                {t.jiraSummary && (
                  <div
                    className="text-clamp-2"
                    title={t.jiraSummary}
                    style={{ fontSize: 12, color: "var(--color-text-muted)", marginTop: 2, textAlign: "left" }}
                  >
                    {t.jiraSummary}
                  </div>
                )}
              </div>
            ),
          },
          {
            key: "createdBy",
            label: "Reported By",
            render: (t) => (
              <div>
                <div style={{ fontSize: 13, whiteSpace: "nowrap" }}>
                  {t.jiraReporter || emailLocalPart(t.createdBy)}
                </div>
                <div style={{ fontSize: 11, color: "var(--color-text-muted)", whiteSpace: "nowrap" }}>
                  {t.createdBy}
                </div>
                <div style={{ fontSize: 11, color: "var(--color-text-faint)", whiteSpace: "nowrap" }}>
                  {new Date(t.jiraCreatedAt || t.createdAt).toLocaleString()}
                </div>
              </div>
            ),
          },
          {
            key: "status",
            // Not sortable -- the toolbar's own "All statuses/Open/Resolved" dropdown already covers
            // this, and a sort toggle reserves an indicator space that visibly knocks this short
            // header off-center next to the longer ones (Project / Server, Reported By).
            sortable: false,
            label: "Status",
            render: (t) => <TicketStatusBadge status={t.status} />,
          },
          {
            key: "actions",
            label: "",
            sortable: false,
            filterable: false,
            render: (t) =>
              canManage() ? (
                <div className="row-actions" onClick={(evt) => evt.stopPropagation()}>
                  <button
                    className="row-action"
                    title="Edit ticket"
                    aria-label="Edit ticket"
                    onClick={() => setEditing(t)}
                  >
                    <EditIcon size={17} style={{ marginRight: 0 }} />
                  </button>
                  <button
                    className="row-action danger"
                    title="Delete ticket"
                    aria-label="Delete ticket"
                    onClick={() => handleDelete(t)}
                  >
                    <TrashIcon size={17} style={{ marginRight: 0 }} />
                  </button>
                </div>
              ) : null,
          },
        ]}
      />

      {showModal && (
        <Modal title="Log a Ticket" onClose={() => setShowModal(false)} width={640} closeIcon>
          <LogTicketForm
            projects={projects}
            servers={servers}
            onCreated={() => {
              reloadTickets();
              setShowModal(false);
            }}
          />
        </Modal>
      )}

      {editing && (
        <Modal title="Edit Ticket" onClose={() => setEditing(null)} width={640} closeIcon>
          <EditTicketForm
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
