import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getDashboardSummary, getProjects } from "../api/client";
import DashboardCharts from "../components/DashboardCharts";

const KPI_TONES = {
  green: "var(--color-green)",
  yellow: "var(--color-yellow)",
  red: "var(--color-red)",
  purple: "var(--color-purple)",
};

/**
 * One dashboard metric.
 *
 * `tone` is applied only when the value is non-zero. A coloured zero is a lie in both directions:
 * "0 Delta Ready" in green read as good news when it actually means nothing is ready, and "0 Open
 * Tickets" in green competed for attention with metrics that genuinely needed it. Zero is the resting
 * state, so it stays in the default text colour and the colour means "there is something here".
 */
function Kpi({ label, value, tone, meta }) {
  const color = value > 0 && tone ? KPI_TONES[tone] : undefined;
  return (
    <div className="kpi">
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

  // Derived dashboard aggregates -- memoized so they only recompute when `projects` changes, not on
  // every render. Expressions are unchanged from their previous inline form.
  const totalServers = useMemo(
    () => projects.reduce((sum, p) => sum + (p.serverCount || 0), 0),
    [projects]
  );
  const readyServers = useMemo(
    () => projects.reduce((sum, p) => sum + (p.readyServerCount || 0), 0),
    [projects]
  );
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
        <Kpi label="Projects" value={projects.length} />
        <Kpi label="Servers" value={totalServers} />
        <Kpi label="Delta Ready" value={readyServers} tone="green" />
        <Kpi label="Pending Approvals" value={summary.totalApprovalRequests} tone="yellow" />
        <Kpi label="Open Tickets" value={openEscalations} tone="red" />
        {/* "Servers" is in the label deliberately. Shortened to just "To Decommission" it read as a
            project count -- the number has always been servers (DashboardService iterates servers and
            counts those whose every combination has completed its Final Delta). */}
        <Kpi
          label="Servers To Decommission"
          value={decommissionReady}
          tone="green"
          meta={decommissioned > 0 ? `${decommissioned} already done` : null}
        />
      </div>

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
    </div>
  );
}
