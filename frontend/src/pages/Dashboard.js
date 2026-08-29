import React, { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getDashboardSummary, getProjects } from "../api/client";
import DashboardCharts from "../components/DashboardCharts";
import Modal from "../components/Modal";

const PRODUCT_TYPE_LABELS = { MESSAGE: "Message", EMAIL: "Email", CONTENT: "Content" };

const KPI_TONES = {
  green: "var(--color-green)",
  yellow: "var(--color-yellow)",
  red: "var(--color-red)",
  purple: "var(--color-purple)",
};

/**
 * The rows behind the Servers and Delta Ready tiles.
 *
 * Server names alone were not enough to act on: the same name recurs across projects, and a
 * server carries several combinations each running its own sign-off chain. So every row names its
 * project and product type, and a Delta Ready row names which combinations are actually ready --
 * "server X is ready" is otherwise ambiguous when only two of its four combinations are.
 *
 * Clicking a row opens that project, which is the action somebody reading this list wants next.
 */
function ServerListModal({ title, rows, emptyText, combinationsOf, combinationTone, onClose, onOpenProject }) {
  return (
    <Modal title={title} onClose={onClose} width={720} closeIcon>
      {rows.length === 0 ? (
        <p className="empty-state">{emptyText}</p>
      ) : (
        <div className="dashboard-server-list">
          {rows.map((row) => (
            <button
              type="button"
              key={row.serverId}
              className="dashboard-server-row"
              onClick={() => onOpenProject(row.projectId)}
              title={row.projectName ? `Open ${row.projectName}` : undefined}
            >
              <div className="dashboard-server-row__main">
                <span className="dashboard-server-row__name">{row.serverName}</span>
                <span className="dashboard-server-row__type">
                  {PRODUCT_TYPE_LABELS[row.productType] || row.productType || "--"}
                </span>
              </div>
              <div className="dashboard-server-row__meta">
                {row.projectName || "No project"}
                {/* Which combinations to name differs by list: the Servers list names all of them,
                    the Delta Ready list only the ready ones -- naming all there would imply a server
                    is ready in combinations that are still mid-chain. */}
                {combinationsOf(row).length > 0 && (
                  <span className={`dashboard-server-row__combos${combinationTone ? " " + combinationTone : ""}`}>
                    {combinationsOf(row).join(", ")}
                  </span>
                )}
              </div>
            </button>
          ))}
        </div>
      )}
    </Modal>
  );
}

/**
 * One dashboard metric.
 *
 * `tone` is applied only when the value is non-zero. A coloured zero is a lie in both directions:
 * "0 Delta Ready" in green read as good news when it actually means nothing is ready, and "0 Open
 * Tickets" in green competed for attention with metrics that genuinely needed it. Zero is the resting
 * state, so it stays in the default text colour and the colour means "there is something here".
 */
function Kpi({ label, value, tone, meta, onClick }) {
  const color = value > 0 && tone ? KPI_TONES[tone] : undefined;
  return (
    <div
      className="kpi"
      style={onClick ? { cursor: "pointer" } : undefined}
      onClick={onClick}
      role={onClick ? "button" : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={onClick ? (e) => (e.key === "Enter" || e.key === " ") && onClick() : undefined}
    >
      <div className="kpi__value" style={{ color }}>{value}</div>
      <div className="kpi__label">{label}</div>
      <div className="kpi__meta">{meta}</div>
    </div>
  );
}

export default function Dashboard() {
  const navigate = useNavigate();
  const [summary, setSummary] = useState(null);
  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const decommissionSectionRef = useRef(null);
  // Which list popup is open, if any: "servers" | "deltaReady" | null.
  const [openList, setOpenList] = useState(null);

  const scrollToDecommissionSection = () => {
    decommissionSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  const load = () => {
    setLoading(true);
    setError(null);
    Promise.all([getDashboardSummary(), getProjects()])
      .then(([summaryData, projectsData]) => {
        setSummary(summaryData);
        setProjects(projectsData);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load dashboard data."))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  // Derived dashboard aggregates -- memoized so they only recompute when their input changes, not
  // on every render.
  //
  // Servers and Delta Ready now count the backend's own scoped server list rather than summing
  // per-project counts. Both were correct, but they were two derivations of the same number, and
  // the popup lists these very rows -- so counting the list itself is what guarantees the tile and
  // the list it opens can never disagree.
  const serverRows = useMemo(() => summary?.servers || [], [summary]);
  const deltaReadyRows = useMemo(() => serverRows.filter((s) => s.deltaReady), [serverRows]);

  // Whether the backend actually sent the server list. A backend older than that field returns a
  // summary without it, and reading length off a missing array silently turns a real count into 0 --
  // which is exactly what a metric tile must never do. So the per-project counts stay as the
  // fallback: they were the original source, they are scoped the same way (getProjects applies the
  // same visibility rule), and they are right whether or not the list arrived.
  const hasServerRows = Array.isArray(summary?.servers);
  const totalServers = hasServerRows
    ? serverRows.length
    : projects.reduce((sum, p) => sum + (p.serverCount || 0), 0);
  const readyServers = hasServerRows
    ? deltaReadyRows.length
    : projects.reduce((sum, p) => sum + (p.readyServerCount || 0), 0);
  const openEscalations = useMemo(
    () => projects.reduce((sum, p) => sum + (p.openEscalationCount || 0), 0),
    [projects]
  );
  // Decommissioning is a per-SERVER action now (a server is eligible once every combination under it
  // has completed its Final Delta), so this tile counts servers rather than whole projects as it used
  // to -- and it comes from the backend summary instead of being derived here, since the eligibility
  // rule lives in ServerService and shouldn't be reimplemented client-side.
  const decommissionReady = summary?.serversReadyToDecommission || 0;
  const decommissioned = summary?.serversDecommissioned || 0;

  // Anything with an open ticket, an approval sitting in someone's queue, or a decline -- the three
  // things that actually need a human to act, as opposed to routine in-progress work.
  //
  // Declines were missing, which made this panel state the opposite of the truth: a project whose only
  // problem was a declined pre-check reported "everything's caught up" while the donut on the same
  // screen said "Declined (1)". A decline is the strongest call to action here -- the chain has
  // bounced back and stopped, and it stays stopped until an engineer corrects and resubmits it.
  // Sorted ahead of pending approvals for that reason: pending work is moving, declined work is not.
  const attentionItems = useMemo(
    () =>
      projects
        .map((p) => ({
          ...p,
          pendingApprovals: (p.migrationManagerApprovalsPending || 0) + (p.devApprovalsPending || 0),
          declined: p.combinationsDeclined || 0,
        }))
        .filter((p) => p.pendingApprovals > 0 || p.openEscalationCount > 0 || p.declined > 0)
        .sort(
          (a, b) =>
            b.openEscalationCount - a.openEscalationCount ||
            b.declined - a.declined ||
            b.pendingApprovals - a.pendingApprovals
        ),
    [projects]
  );

  if (loading && !summary) return <p>Loading dashboard...</p>;
  if (error) {
    return (
      <div>
        <h2>Dashboard</h2>
        <div className="inline-hint">{error}</div>
        <button className="btn secondary" style={{ marginTop: 12 }} onClick={load}>
          Retry
        </button>
      </div>
    );
  }
  if (!summary) return null;

  return (
    <div>
      <h2 style={{ margin: 0 }}>Dashboard</h2>
      <p style={{ color: "var(--color-text-muted)", marginTop: 0, marginBottom: 22, fontSize: 13.5 }}>
        A quick look at migration progress across every project.
      </p>

      {/* A grid, not a nowrap flex row: seven cards forced onto one line left each ~150px, so every
          label wrapped and the longest one stretched all seven. See .kpi-grid in index.css.
          Each metric's secondary fact goes in `meta` (its own line) rather than being appended to the
          label with a "·", which is what produced four-line labels. */}
      <div className="kpi-grid">
        <Kpi label="Projects" value={projects.length} onClick={() => navigate("/projects")} />
        <Kpi label="Servers" value={totalServers} onClick={() => setOpenList("servers")} />
        <Kpi
          label="Delta Ready"
          value={readyServers}
          tone="green"
          onClick={() => setOpenList("deltaReady")}
        />
        {/* Straight to the filtered view rather than the whole page -- ApprovalsPage and
            TicketsPage already read ?status= (see their useSearchParams), so the click lands on
            exactly the rows the number counted. */}
        <Kpi
          label="Pending Approvals"
          value={summary.totalApprovalRequests}
          tone="yellow"
          onClick={() => navigate("/approvals?status=PENDING")}
        />
        <Kpi
          label="Open Tickets"
          value={openEscalations}
          tone="red"
          onClick={() => navigate("/tickets?status=OPEN")}
        />
        {/* "Servers" is in the label deliberately. Shortened to just "To Decommission" it read as a
            project count -- the number has always been servers (DashboardService iterates servers and
            counts those whose every combination has completed its Final Delta). */}
        <Kpi
          label="Servers To Decommission"
          value={decommissionReady}
          tone="green"
          meta={decommissioned > 0 ? `${decommissioned} already done` : null}
          onClick={scrollToDecommissionSection}
        />
      </div>

      {openList === "servers" && (
        <ServerListModal
          title={`Servers (${serverRows.length})`}
          rows={serverRows}
          emptyText={
            hasServerRows
              ? "No servers yet."
              : "This list needs a newer backend than the one currently running -- the count above is still correct."
          }
          combinationsOf={(row) => row.combinations || []}
          onClose={() => setOpenList(null)}
          onOpenProject={(id) => id && navigate(`/projects/${id}`)}
        />
      )}
      {openList === "deltaReady" && (
        <ServerListModal
          title={`Delta Ready (${deltaReadyRows.length})`}
          rows={deltaReadyRows}
          emptyText={
            hasServerRows
              ? "No server is Delta Ready yet."
              : "This list needs a newer backend than the one currently running -- the count above is still correct."
          }
          combinationsOf={(row) => row.deltaReadyCombinations || []}
          combinationTone="is-ready"
          onClose={() => setOpenList(null)}
          onOpenProject={(id) => id && navigate(`/projects/${id}`)}
        />
      )}

      <div className="card" style={{ marginTop: 6 }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <strong style={{ fontSize: 14 }}>Needs Attention</strong>
          <button className="btn secondary" onClick={() => navigate("/projects")}>
            View All Projects
          </button>
        </div>

        {attentionItems.length === 0 ? (
          <p className="empty-state">Nothing needs attention right now — everything's caught up.</p>
        ) : (
          <div style={{ marginTop: 10 }}>
            {attentionItems.map((p) => (
              <div key={p.id} className="attention-row" onClick={() => navigate(`/projects/${p.id}`)}>
                <span style={{ fontWeight: 600, fontSize: 13.5 }}>{p.name}</span>
                <div style={{ display: "flex", gap: 8, flexWrap: "wrap", justifyContent: "flex-end" }}>
                  {p.openEscalationCount > 0 && (
                    <button
                      type="button"
                      className="badge red attention-action"
                      title="View this project's open tickets"
                      onClick={(e) => {
                        e.stopPropagation();
                        navigate(`/tickets?project=${p.id}&status=OPEN`);
                      }}
                    >
                      {p.openEscalationCount} ticket{p.openEscalationCount === 1 ? "" : "s"}
                    </button>
                  )}
                  {/* Red like tickets, not yellow like pending approvals: a decline is stopped work,
                      not queued work. Links straight to the declined rows so the next click is the
                      one that fixes it. */}
                  {p.declined > 0 && (
                    <button
                      type="button"
                      className="badge red attention-action"
                      title="View this project's declined approvals"
                      onClick={(e) => {
                        e.stopPropagation();
                        navigate(`/approvals?project=${p.id}&status=DECLINED`);
                      }}
                    >
                      {p.declined} declined
                    </button>
                  )}
                  {p.pendingApprovals > 0 && (
                    <button
                      type="button"
                      className="badge yellow attention-action"
                      title="View this project's pending approvals"
                      onClick={(e) => {
                        e.stopPropagation();
                        navigate(`/approvals?project=${p.id}&status=PENDING`);
                      }}
                    >
                      {p.pendingApprovals} pending approval{p.pendingApprovals === 1 ? "" : "s"}
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <DashboardCharts projects={projects} />

      {/* Bottom of the page on purpose -- this is what the "Servers To Decommission" tile scrolls
          down to, rather than navigating away, so the count and the actual list stay on one screen. */}
      <div className="card" style={{ marginTop: 16 }} ref={decommissionSectionRef}>
        <strong style={{ fontSize: 14 }}>Servers To Decommission</strong>
        {!summary.decommissionReadyServers?.length ? (
          <p className="empty-state">No servers are ready to decommission right now.</p>
        ) : (
          <div style={{ marginTop: 10 }}>
            {summary.decommissionReadyServers.map((s) => (
              <div
                key={s.serverId}
                className="attention-row"
                onClick={() => navigate(`/servers/${s.serverId}`)}
              >
                <div>
                  <div style={{ fontWeight: 600, fontSize: 13.5 }}>{s.serverName}</div>
                  <div style={{ fontSize: 12, color: "var(--color-text-muted)" }}>{s.projectName}</div>
                </div>
                <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
                  {s.productType && (
                    <span className="badge blue">{PRODUCT_TYPE_LABELS[s.productType] || s.productType}</span>
                  )}
                  {s.readySince && (
                    <span style={{ fontSize: 11.5, color: "var(--color-text-faint)" }}>
                      Ready since {new Date(s.readySince).toLocaleDateString()}
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
