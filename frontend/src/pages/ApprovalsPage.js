import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getSignOffApprovals, approveSignOff, declineSignOff } from "../api/client";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
import PreCheckPanel from "../components/PreCheckPanel";
import { useToast } from "../components/Toast";
import { useConfirm } from "../components/ConfirmDialog";

const ROLE_LABELS = {
  MIGRATION_LEAD: "Migration Manager",
  DEV_LEAD: "Dev Lead",
  QA_LEAD: "QA Lead",
};

const STEPS = [
  { role: "MIGRATION_LEAD", short: "MM", full: "Migration Manager" },
  { role: "DEV_LEAD", short: "Dev", full: "Dev Lead" },
  { role: "QA_LEAD", short: "QA", full: "QA Lead" },
];

// The API returns one row per (server, role) -- three rows per server. For the table we only
// want one row per server: whichever role is currently active. If the whole chain is approved,
// that's the QA row; if it's blocked by an early decline, show the declined step.
function primaryRowFor(rows) {
  return (
    rows.find((r) => r.turnReady) ||
    rows.find((r) => r.role === "QA_LEAD" && (r.status === "APPROVED" || r.status === "SKIPPED")) ||
    rows.find((r) => r.status === "DECLINED") ||
    rows[0]
  );
}

function OverallStepper({ approval, allApprovals }) {
  const siblings = allApprovals.filter((a) => a.serverId === approval.serverId);
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
  const icon = isApproved ? "✓" : isDeclined ? "✕" : "•";
  return (
    <span className="current-status-text" style={{ color }}>
      <span className="icon">{icon}</span>
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

  const runApprove = async (qaRequired) => {
    setActing(true);
    setAskingQa(false);
    try {
      await approveSignOff(approval.serverId, approval.role, undefined, qaRequired);
      showToast(
        qaRequired === false
          ? `Approved for ${approval.serverName} -- QA Lead not required, marked Delta Ready.`
          : `Approved for ${approval.serverName}.`
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
      message: `You're approving the pre-check for ${approval.serverName}.`,
      confirmLabel: "Approve",
    });
    if (!ok) return;
    runApprove(undefined);
  };

  const handleReject = async () => {
    const ok = await confirm({
      title: `Reject as ${roleLabel}?`,
      message: `This sends ${approval.serverName} back a step for rework.`,
      confirmLabel: "Reject",
      danger: true,
    });
    if (!ok) return;
    setActing(true);
    try {
      await declineSignOff(approval.serverId, approval.role);
      showToast(`Rejected for ${approval.serverName}.`);
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
            Does <strong>{approval.serverName}</strong> also need QA Lead approval before it's Delta Ready?
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
  const [approvals, setApprovals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("ALL");
  const [preCheckFor, setPreCheckFor] = useState(null);

  const load = () => {
    setLoading(true);
    getSignOffApprovals()
      .then(setApprovals)
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  if (loading) return <p>Loading approvals...</p>;

  const bySever = new Map();
  approvals.forEach((a) => {
    if (!bySever.has(a.serverId)) bySever.set(a.serverId, []);
    bySever.get(a.serverId).push(a);
  });
  const rows = Array.from(bySever.values()).map(primaryRowFor);
  const filtered = rows.filter((a) => filter === "ALL" || a.status === filter);

  return (
    <div>
      <DataTable
        title="Approvals"
        rows={filtered}
        rowKey={(a) => a.serverId}
        onRowClick={(a) => a.projectId && navigate(`/projects/${a.projectId}?server=${a.serverId}`)}
        searchPlaceholder="Filter approvals..."
        emptyMessage="No approval requests yet."
        toolbarRight={
          <select value={filter} onChange={(e) => setFilter(e.target.value)} style={{ minWidth: 150 }}>
            <option value="ALL">All statuses</option>
            <option value="PENDING">Pending</option>
            <option value="APPROVED">Approved</option>
            <option value="DECLINED">Declined</option>
          </select>
        }
        columns={[
          { key: "projectName", label: "Project", render: (a) => a.projectName || "-" },
          {
            key: "serverName",
            label: "Server",
            render: (a) => (
              <div>
                <div style={{ fontWeight: 600, whiteSpace: "nowrap" }}>{a.serverName}</div>
                <div style={{ fontSize: 11.5, color: "var(--color-text-faint)" }}>{a.totalPairs} pair(s)</div>
              </div>
            ),
          },
          {
            key: "submittedBy",
            label: "Submitted By",
            render: (a) => (
              <div>
                <div style={{ fontSize: 13, whiteSpace: "nowrap" }}>{a.submittedBy || "-"}</div>
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
                <OverallStepper approval={a} allApprovals={approvals} />
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
          title={`Pre-Check — ${preCheckFor.serverName}`}
          onClose={() => setPreCheckFor(null)}
          width={860}
          closeIcon
        >
          <PreCheckPanel serverId={preCheckFor.serverId} showBackNav={false} showHeader={false} />
        </Modal>
      )}
    </div>
  );
}
