import React, { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getDashboardSummary, getProjects } from "../api/client";
import DashboardCharts from "../components/DashboardCharts";

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
  // A project is ready to decommission once every one of its servers has finished its Delta.
  const decommissionReady = useMemo(
    () => projects.filter((p) => p.decommissionReady).length,
    [projects]
  );

  // Anything with an open ticket or an approval sitting in someone's queue -- the two things
  // that actually need a human to act, as opposed to routine in-progress work.
  const attentionItems = useMemo(
    () =>
      projects
        .map((p) => ({
          ...p,
          pendingApprovals: (p.migrationManagerApprovalsPending || 0) + (p.devApprovalsPending || 0),
        }))
        .filter((p) => p.pendingApprovals > 0 || p.openEscalationCount > 0)
        .sort(
          (a, b) =>
            b.openEscalationCount - a.openEscalationCount || b.pendingApprovals - a.pendingApprovals
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

      <div className="card-row card-row--nowrap">
        <div className="stat-card">
          <div className="value">{projects.length}</div>
          <div className="label">Projects</div>
        </div>
        <div className="stat-card">
          <div className="value">{totalServers}</div>
          <div className="label">Servers</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: "var(--color-green)" }}>{readyServers}</div>
          <div className="label">Delta Ready</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: summary.totalApprovalRequests > 0 ? "var(--color-yellow)" : undefined }}>
            {summary.totalApprovalRequests}
          </div>
          <div className="label">Pending Approvals</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: openEscalations > 0 ? "var(--color-red)" : "var(--color-green)" }}>
            {openEscalations}
          </div>
          <div className="label">Open Tickets</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: decommissionReady > 0 ? "var(--color-green)" : undefined }}>
            {decommissionReady}
          </div>
          <div className="label">Ready To Decommission</div>
        </div>
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
