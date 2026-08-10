import React, { useEffect, useState } from "react";
import { getDeltaCycles } from "../api/client";
import AttachmentPreview from "./AttachmentPreview";
import DeltaBadge from "./DeltaBadge";
import Modal from "./Modal";
import { emailLocalPart } from "../utils/format";
import { DELTA_CYCLE_STATUS_BADGE } from "../constants";

const DATE_OPTS = { month: "short", day: "numeric", year: "numeric" };

function fmtDate(value) {
  return value ? new Date(value).toLocaleDateString(undefined, DATE_OPTS) : "—";
}

function fmtDateTime(value) {
  return value ? new Date(value).toLocaleString() : "—";
}

// The frozen checklist for one past cycle. Read-only by nature -- a snapshot is never editable, which
// is the whole point of keeping it separately from the live form. Rendered inside a Modal, so it owns
// no panel chrome of its own.
function CycleSnapshot({ cycle }) {
  return (
    <div>
      {!cycle.items?.length ? (
        <div style={{ fontSize: 12.5, color: "var(--color-text-faint)" }}>
          No checklist snapshot was recorded for this cycle.
        </div>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th>Item</th>
                <th>Status</th>
                <th>Note</th>
                <th>Evidence</th>
              </tr>
            </thead>
            <tbody>
              {cycle.items.map((item) => (
                <tr key={item.id}>
                  <td style={{ fontWeight: 600 }}>{item.itemName}</td>
                  <td>{item.status?.replace(/_/g, " ").toLowerCase()}</td>
                  <td style={{ maxWidth: 320, whiteSpace: "pre-wrap" }}>
                    {item.notes || <span style={{ color: "var(--color-text-faint)" }}>—</span>}
                  </td>
                  <td>
                    {item.evidenceFilePath ? (
                      <AttachmentPreview filePath={item.evidenceFilePath} fileName={item.evidenceFileName} />
                    ) : (
                      <span style={{ color: "var(--color-text-faint)" }}>—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/**
 * A combination's Delta history: one row per recorded cycle, expandable to that cycle's frozen
 * checklist and sign-offs.
 *
 * Renders nothing at all when there's no history -- on a first pre-delta this panel would be an empty
 * card adding noise to a form the engineer is trying to fill out.
 *
 * `reloadKey` is bumped by the parent whenever a cycle could have changed (a delta started/finished),
 * since this component owns its own fetch and has no other way to know.
 */
export default function DeltaHistoryPanel({ combinationId, reloadKey }) {
  const [cycles, setCycles] = useState(null);
  const [error, setError] = useState(null);
  const [openCycleId, setOpenCycleId] = useState(null);

  useEffect(() => {
    let cancelled = false;
    getDeltaCycles(combinationId)
      .then((data) => {
        if (!cancelled) setCycles(data);
      })
      .catch((err) => {
        if (!cancelled) setError(err.response?.data?.message || "Couldn't load Delta history.");
      });
    return () => {
      cancelled = true;
    };
  }, [combinationId, reloadKey]);

  if (error) {
    return (
      <div className="card">
        <h3 className="section-title">Delta History</h3>
        <div className="inline-hint">{error}</div>
      </div>
    );
  }

  // Nothing recorded yet (or still loading) -- stay out of the way entirely.
  if (!cycles?.length) return null;

  return (
    <div className="card">
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <h3 className="section-title" style={{ marginBottom: 0 }}>
          Delta History <span className="badge gray">{cycles.length}</span>
        </h3>
        <span className="progress-label">
          Each completed cycle keeps the checklist and sign-offs exactly as they were approved.
        </span>
      </div>

      <div className="delta-cycle-list">
        {cycles.map((cycle) => {
          const status = DELTA_CYCLE_STATUS_BADGE[cycle.status] || { color: "gray", label: cycle.status };
          const isOpen = openCycleId === cycle.id;
          const declined = cycle.status === "DECLINED";
          const ran = `${fmtDate(cycle.deltaStartedAt)} → ${fmtDate(cycle.deltaFinishedAt)}`;
          return (
            <div className="delta-cycle" key={cycle.id}>
              <div className="delta-cycle__row">
                <DeltaBadge deltaType={cycle.deltaType} label={cycle.label} />

                <div className="delta-cycle__facts">
                  <span className="delta-cycle__fact">
                    <span className="delta-cycle__fact-label">Submitted by</span>
                    <span className="delta-cycle__fact-value">{emailLocalPart(cycle.submittedBy) || "—"}</span>
                  </span>
                  {/* A declined cycle never started running, so "Ran" would just read "— → —" --
                      show who declined it and why instead, which is the fact that actually matters
                      here. */}
                  {declined ? (
                    <span className="delta-cycle__fact" title={cycle.declineReason || ""}>
                      <span className="delta-cycle__fact-label">Declined by</span>
                      <span className="delta-cycle__fact-value">
                        {cycle.declinedByRoleLabel} ({emailLocalPart(cycle.declinedBy) || "—"})
                      </span>
                    </span>
                  ) : (
                    // Start and finish read as one fact ("when did this run"), which also keeps two
                    // short dates on one line instead of two columns that each wrapped. Full
                    // timestamps stay available on hover.
                    <span
                      className="delta-cycle__fact"
                      title={`Started ${fmtDateTime(cycle.deltaStartedAt)}\nFinished ${fmtDateTime(cycle.deltaFinishedAt)}`}
                    >
                      <span className="delta-cycle__fact-label">Ran</span>
                      <span className="delta-cycle__fact-value">{ran}</span>
                    </span>
                  )}
                </div>

                <span className={`badge ${status.color}`}>{status.label}</span>
                <button
                  className="btn secondary"
                  style={{ padding: "5px 12px", fontSize: 12, whiteSpace: "nowrap" }}
                  onClick={() => setOpenCycleId(cycle.id)}
                >
                  View checklist
                </button>
              </div>

              {declined && cycle.declineReason && (
                <div style={{ fontSize: 12.5, color: "var(--color-text-muted)", marginTop: 6 }}>
                  “{cycle.declineReason}”
                </div>
              )}

              {/* One chip per role rather than a single joined string -- the joined version was the
                  worst-wrapping cell on the old table. */}
              {cycle.signOffs?.length > 0 && (
                <div className="delta-cycle__approvals">
                  {cycle.signOffs.map((s) => (
                    <span key={s.role}>
                      <span className="delta-cycle__approval-role">{s.roleLabel}</span>
                      {s.status === "SKIPPED"
                        ? "not required"
                        : s.status === "DECLINED"
                        ? `declined${s.declineReason ? ` — ${s.declineReason}` : ""}`
                        : emailLocalPart(s.approvedBy) || "—"}
                    </span>
                  ))}
                </div>
              )}

              {/* A modal rather than an inline expansion: the snapshot is a four-column table of its
                  own, and opening it inside a row that already sits in a nested panel left it with
                  almost no width. Full-screen focus also matches what the checklist is for -- reading
                  one past cycle in detail, not comparing rows. */}
              {isOpen && (
                <Modal
                  title={cycle.label}
                  width={880}
                  closeIcon
                  onClose={() => setOpenCycleId(null)}
                >
                  <CycleSnapshot cycle={cycle} />
                </Modal>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
