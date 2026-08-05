import React, { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { getSignOffApprovals, approveSignOff, declineSignOff } from "../api/client";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
import PreCheckPanel from "../components/PreCheckPanel";
import { useToast } from "../components/Toast";
import { useConfirm } from "../components/ConfirmDialog";
import { apiErrorMessage } from "../utils/apiError";
import { emailLocalPart } from "../utils/format";

const ROLE_LABELS = {
  MIGRATION_LEAD: "Migration Manager",
  DEV_LEAD: "Dev Lead",
  QA_LEAD: "QA Lead",
};

const PRODUCT_TYPE_LABELS = { MESSAGE: "Message", EMAIL: "Email", CONTENT: "Content" };

const STEPS = [
  { role: "MIGRATION_LEAD", short: "MM", full: "Migration Manager" },
  { role: "DEV_LEAD", short: "Dev", full: "Dev Lead" },
  { role: "QA_LEAD", short: "QA", full: "QA Lead" },
];

// The API returns one row per (combination, role) -- three rows per combination. For the table we
// only want one row per combination: whichever role is currently active. If the whole chain is
// approved, that's the QA row; if it's blocked by an early decline, show the declined step.
function primaryRowFor(rows) {
  return (
    rows.find((r) => r.turnReady) ||
    rows.find((r) => r.role === "QA_LEAD" && (r.status === "APPROVED" || r.status === "SKIPPED")) ||
    rows.find((r) => r.status === "DECLINED") ||
    rows[0]
  );
}

function OverallStepper({ approval, siblings }) {
  const stepFor = (role) => siblings.find((s) => s.role === role);

  return (
    <div className="approval-stepper" title={approval.overallStatus}>
      {STEPS.map((step, i) => {
        const s = stepFor(step.role);
        const status = s?.status || "PENDING";
        const isTurn = !!s?.turnReady;
        const dotClass =
          status === "APPROVED" ? "approved"
          : status === "SKIPPED" ? "skipped"
          : status === "DECLINED" ? "declined"
          : isTurn ? "turn" : "";
        const label = status === "APPROVED" ? "✓" : status === "SKIPPED" ? "–" : status === "DECLINED" ? "✕" : step.short;
        const title = status === "SKIPPED" ? `${step.full}: Not required` : `${step.full}: ${status}`;
        const prevApproved = i > 0 && ["APPROVED", "SKIPPED"].includes(stepFor(STEPS[i - 1].role)?.status);
        return (
          <React.Fragment key={step.role}>
            {i > 0 && <span className={`approval-stepper-line ${prevApproved ? "done" : ""}`} />}
            <span className={`approval-stepper-dot ${dotClass}`} title={title}>
              {label}
            </span>
          </React.Fragment>
        );
      })}
    </div>
  );
}

function CurrentStatusText({ label }) {
  const isApproved = label.startsWith("Approved") || label.startsWith("Delta Ready");
  const isDeclined = label.startsWith("Declined");
  const color = isApproved ? "var(--color-green)" : isDeclined ? "var(--color-red)" : "var(--color-text-muted)";
  const icon = isApproved ? "✓" : isDeclined ? "✕" : null;
  return (
    <span className="current-status-text" style={{ color }}>
      {icon && <span className="icon">{icon}</span>}
      {label}
    </span>
  );
}

const CheckIcon = () => (
  <svg width="15" height="15" viewBox="0 0 16 16" fill="none">
    <path d="M3 8.5L6.2 11.7L13 4.5" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

const XIcon = () => (
  <svg width="13" height="13" viewBox="0 0 16 16" fill="none">
    <path d="M3 3L13 13M13 3L3 13" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
  </svg>
);

function ActionsCell({ approval, onActed }) {
  const [acting, setActing] = useState(false);
  const [askingQa, setAskingQa] = useState(false);
  const showToast = useToast();
  const confirm = useConfirm();

  if (approval.status !== "PENDING" || !approval.canAct) {
    return <span style={{ color: "var(--color-text-faint)", fontSize: 12 }}>—</span>;
  }

  const roleLabel = ROLE_LABELS[approval.role] || approval.role;

  const label = `${approval.serverName} / ${approval.combinationName}`;

  const runApprove = async (qaRequired) => {
    setActing(true);
    setAskingQa(false);
    try {
      await approveSignOff(approval.combinationId, approval.role, undefined, qaRequired);
      showToast(
        qaRequired === false
          ? `Approved for ${label} -- QA Lead not required, marked Delta Ready.`
          : `Approved for ${label}.`
      );
      onActed();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to approve.");
    } finally {
      setActing(false);
    }
  };

  const handleApproveClick = async () => {
    if (approval.role === "DEV_LEAD") {
      setAskingQa(true);
      return;
    }
    const ok = await confirm({
      title: `Approve as ${roleLabel}?`,
      message: `You're approving the pre-check for ${label}.`,
      confirmLabel: "Approve",
    });
    if (!ok) return;
    runApprove(undefined);
  };

  const handleReject = async () => {
    const ok = await confirm({
      title: `Reject as ${roleLabel}?`,
      message: `This sends ${label} back a step for rework.`,
      confirmLabel: "Reject",
      danger: true,
    });
    if (!ok) return;
    setActing(true);
    try {
      await declineSignOff(approval.combinationId, approval.role);
      showToast(`Rejected for ${label}.`);
      onActed();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to reject.");
    } finally {
      setActing(false);
    }
  };

  return (
    <div style={{ display: "flex", gap: 6, justifyContent: "center" }} onClick={(e) => e.stopPropagation()}>
      <button
        className="btn icon-btn success"
        title={`Approve as ${roleLabel}`}
        aria-label={`Approve as ${roleLabel}`}
        onClick={handleApproveClick}
        disabled={acting}
      >
        <CheckIcon />
      </button>
      <button
        className="btn icon-btn danger"
        title={`Reject as ${roleLabel}`}
        aria-label={`Reject as ${roleLabel}`}
        onClick={handleReject}
        disabled={acting}
      >
        <XIcon />
      </button>

      {askingQa && (
        <Modal title="QA Lead approval needed?" onClose={() => setAskingQa(false)} width={420}>
          <p style={{ fontSize: 13.5, color: "var(--color-text-muted)", marginTop: 0 }}>
            Does <strong>{label}</strong> also need QA Lead approval before it's Delta Ready?
          </p>
          <div className="form-actions" style={{ justifyContent: "flex-end", gap: 8 }}>
            <button className="btn secondary" onClick={() => runApprove(false)} disabled={acting}>
              No — mark Delta Ready
            </button>
            <button className="btn" onClick={() => runApprove(true)} disabled={acting}>
              Yes — send to QA Lead
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

export default function ApprovalsPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const projectParam = searchParams.get("project");
  const statusParam = searchParams.get("status");
  const [approvals, setApprovals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [filter, setFilter] = useState(
    ["PENDING", "APPROVED", "DECLINED"].includes(statusParam) ? statusParam : "ALL"
  );
  const [projectFilter, setProjectFilter] = useState(projectParam || "ALL");
  const [preCheckFor, setPreCheckFor] = useState(null);

  const load = () => {
    setLoading(true);
    getSignOffApprovals()
      .then((data) => {
        setApprovals(data);
        setLoadError(null);
      })
      .catch((err) => setLoadError(apiErrorMessage(err, "Failed to load approvals.")))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  if (loading) return <p>Loading approvals...</p>;

  if (loadError) {
    return (
      <div>
        <h2>Approvals</h2>
        <div className="inline-hint">{loadError}</div>
        <button className="btn secondary" style={{ marginTop: 12 }} onClick={load}>
          Retry
        </button>
      </div>
    );
  }

  const byCombination = new Map();
  approvals.forEach((a) => {
    if (!byCombination.has(a.combinationId)) byCombination.set(a.combinationId, []);
    byCombination.get(a.combinationId).push(a);
  });
  const rows = Array.from(byCombination.values()).map(primaryRowFor);
  // Project filter options are derived from the approvals themselves -- no extra API call needed.
  const projectOptions = Array.from(
    approvals
      .reduce((m, a) => {
        if (a.projectId != null && !m.has(a.projectId)) m.set(a.projectId, a.projectName || String(a.projectId));
        return m;
      }, new Map())
      .entries()
  )
    .map(([id, name]) => ({ id, name }))
    .sort((x, y) => x.name.localeCompare(y.name));
  const filtered = rows.filter(
    (a) =>
      (filter === "ALL" || a.status === filter) &&
      (projectFilter === "ALL" || String(a.projectId) === String(projectFilter))
  );

  return (
    <div>
      <DataTable
        title="Approvals"
        rows={filtered}
        rowKey={(a) => a.combinationId}
        onRowClick={(a) =>
          a.serverId && navigate(`/servers/${a.serverId}?combination=${encodeURIComponent(a.combinationName)}`)
        }
        searchPlaceholder="Filter approvals..."
        emptyMessage="No approval requests yet."
        toolbarRight={
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <select value={projectFilter} onChange={(e) => setProjectFilter(e.target.value)} style={{ minWidth: 150 }}>
              <option value="ALL">All projects</option>
              {projectOptions.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            <select value={filter} onChange={(e) => setFilter(e.target.value)} style={{ minWidth: 150 }}>
              <option value="ALL">All statuses</option>
              <option value="PENDING">Pending</option>
              <option value="APPROVED">Approved</option>
              <option value="DECLINED">Declined</option>
            </select>
          </div>
        }
        columns={[
          { key: "projectName", label: "Project", render: (a) => a.projectName || "-" },
          {
            key: "serverName",
            label: "Server / Combination",
            render: (a) => (
              <div>
                <div style={{ fontWeight: 600, whiteSpace: "nowrap" }}>{a.serverName}</div>
                <div style={{ fontSize: 12, color: "var(--color-text-muted)", whiteSpace: "nowrap" }}>{a.combinationName}</div>
                {a.productType && (
                  <div style={{ fontSize: 11.5, color: "var(--color-text-faint)" }}>
                    {PRODUCT_TYPE_LABELS[a.productType] || a.productType}
                  </div>
                )}
              </div>
            ),
          },
          {
            key: "submittedBy",
            label: "Submitted By",
            render: (a) => (
              <div>
                <div style={{ fontSize: 13, whiteSpace: "nowrap" }} title={a.submittedBy || undefined}>{a.submittedBy ? emailLocalPart(a.submittedBy) : "-"}</div>
                {a.submittedAt && (
                  <div style={{ fontSize: 11, color: "var(--color-text-faint)", whiteSpace: "nowrap" }}>
                    {new Date(a.submittedAt).toLocaleString()}
                  </div>
                )}
              </div>
            ),
          },
          {
            key: "status",
            label: "Status",
            sortable: false,
            render: (a) => (
              <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 7 }}>
                <OverallStepper approval={a} siblings={byCombination.get(a.combinationId) || []} />
                <CurrentStatusText label={a.currentStatus} />
              </div>
            ),
          },
          {
            key: "precheck",
            label: "Pre-Check",
            sortable: false,
            filterable: false,
            render: (a) => (
              <button
                className="btn secondary"
                style={{ padding: "4px 10px", fontSize: 12 }}
                onClick={(e) => {
                  e.stopPropagation();
                  setPreCheckFor(a);
                }}
              >
                Review
              </button>
            ),
          },
          {
            key: "actions",
            label: "Actions",
            sortable: false,
            filterable: false,
            render: (a) => <ActionsCell approval={a} onActed={load} />,
          },
        ]}
      />

      {preCheckFor && (
        <Modal
          title={`Pre-Check — ${preCheckFor.serverName} / ${preCheckFor.combinationName}`}
          onClose={() => setPreCheckFor(null)}
          width={860}
          closeIcon
        >
          <PreCheckPanel combinationId={preCheckFor.combinationId} showBackNav={false} showHeader={false} />
        </Modal>
      )}
    </div>
  );
}
