import React, { useMemo } from "react";

// Lightweight inline-SVG charts for the Dashboard -- no charting dependency, themed with the app's
// own CSS tokens. A donut (pie) supporting a progress ring or multi-segment split, plus a set of
// per-project progress bars. Legends + value labels are always present, so meaning never relies on
// color alone.

const TRACK = "#eceef2";
const GREEN = "var(--color-green)";
const YELLOW = "var(--color-yellow)";
const RED = "var(--color-red)";
const INK = "var(--color-text)";
const MUTED = "var(--color-text-muted)";

function Swatch({ color }) {
  return (
    <span
      aria-hidden="true"
      style={{ display: "inline-block", width: 10, height: 10, borderRadius: 3, background: color, flexShrink: 0 }}
    />
  );
}

// segments: [{ label, value, color }]. total defaults to the sum; pass it explicitly for a progress
// ring (arc = value/total, the rest shows as the track). remainderLabel adds a track legend chip.
function Donut({ title, segments, total, center, centerSub, remainderLabel, size = 140, thickness = 18 }) {
  const sum = segments.reduce((s, x) => s + x.value, 0);
  const whole = total != null ? total : sum;
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  const cx = size / 2;
  const cy = size / 2;
  const active = segments.filter((s) => s.value > 0);
  const gap = whole > 0 && active.length > 1 ? 5 : 0;

  let acc = 0;
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 12, minWidth: 170 }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: INK }}>{title}</div>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={title}>
        <circle cx={cx} cy={cy} r={r} fill="none" stroke={TRACK} strokeWidth={thickness}>
          {remainderLabel && <title>{`${remainderLabel}: ${Math.max(whole - sum, 0)}`}</title>}
        </circle>
        {whole > 0 &&
          segments.map((seg, i) => {
            if (seg.value <= 0) return null;
            const frac = seg.value / whole;
            const len = frac * c;
            const dash = Math.max(len - gap, 0.75);
            const rot = (acc / whole) * 360 - 90;
            acc += seg.value;
            return (
              <circle
                key={i}
                cx={cx}
                cy={cy}
                r={r}
                fill="none"
                stroke={seg.color}
                strokeWidth={thickness}
                strokeDasharray={`${dash} ${c - dash}`}
                transform={`rotate(${rot} ${cx} ${cy})`}
              >
                <title>{`${seg.label}: ${seg.value}`}</title>
              </circle>
            );
          })}
        <text x={cx} y={cy - 1} textAnchor="middle" style={{ fontSize: 26, fontWeight: 800, fill: INK }}>
          {center}
        </text>
        {centerSub && (
          <text x={cx} y={cy + 17} textAnchor="middle" style={{ fontSize: 10.5, fontWeight: 600, fill: MUTED }}>
            {centerSub}
          </text>
        )}
      </svg>
      <div style={{ display: "flex", gap: 14, flexWrap: "wrap", justifyContent: "center" }}>
        {segments.map((seg) => (
          <span key={seg.label} style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12, color: MUTED }}>
            <Swatch color={seg.color} /> {seg.label} ({seg.value})
          </span>
        ))}
        {remainderLabel && whole - sum > 0 && (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12, color: MUTED }}>
            <Swatch color={TRACK} /> {remainderLabel} ({whole - sum})
          </span>
        )}
      </div>
    </div>
  );
}

function ProjectBars({ projects }) {
  const withServers = useMemo(() => projects.filter((p) => (p.serverCount || 0) > 0), [projects]);
  return (
    <div style={{ width: "100%" }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: INK, marginBottom: 14 }}>
        Delta readiness by project
      </div>
      {withServers.length === 0 ? (
        <p className="empty-state" style={{ margin: 0 }}>No servers imported yet.</p>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {withServers.map((p) => {
            const total = p.serverCount || 0;
            const ready = p.readyServerCount || 0;
            const pct = total ? Math.round((ready / total) * 100) : 0;
            return (
              <div key={p.id}>
                <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12.5, marginBottom: 4 }}>
                  <span style={{ color: INK, fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", maxWidth: "70%" }}>
                    {p.name}
                  </span>
                  <span style={{ color: MUTED }}>{ready}/{total} ready</span>
                </div>
                <div
                  title={`${ready} of ${total} servers Delta Ready`}
                  style={{ height: 12, borderRadius: 999, background: TRACK, overflow: "hidden" }}
                >
                  <div style={{ height: "100%", width: `${pct}%`, background: GREEN, borderRadius: 999, transition: "width 0.3s ease" }} />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default function DashboardCharts({ projects }) {
  // Chart aggregates memoized on `projects` -- expressions unchanged from their inline form.
  // Project-level, not server-level. The per-server rollup duplicated the "Delta readiness by project"
  // bars directly below it, and a server total isn't a unit anyone tracks -- "how many projects are
  // clear" is. A project counts as ready only when every one of its servers is, and a project with no
  // servers yet isn't ready (nothing has been imported to be ready about).
  const totalProjects = projects.length;
  const readyProjects = useMemo(
    () => projects.filter((p) => (p.serverCount || 0) > 0 && (p.readyServerCount || 0) === p.serverCount).length,
    [projects]
  );
  // Counted per COMBINATION, not per role-step. One combination has exactly one approval chain
  // (Migration Manager -> Dev Lead -> QA Lead), so each one lands in exactly one bucket and the total
  // reconciles against the Approvals page. The previous version summed
  // migrationManagerApprovals* + devApprovals*, which double counted a combination across two roles and
  // omitted QA Lead entirely -- so a combination waiting on QA showed up as "Pending (0)".
  const approvalsDone = useMemo(
    () => projects.reduce((s, p) => s + (p.combinationsFullyApproved || 0), 0),
    [projects]
  );
  const approvalsPending = useMemo(
    () => projects.reduce((s, p) => s + (p.combinationsAwaitingApproval || 0), 0),
    [projects]
  );
  const approvalsDeclined = useMemo(
    () => projects.reduce((s, p) => s + (p.combinationsDeclined || 0), 0),
    [projects]
  );
  const approvalsTotal = approvalsDone + approvalsPending + approvalsDeclined;

  return (
    <div className="card" style={{ marginTop: 18 }}>
      <strong style={{ fontSize: 14 }}>Overview at a glance</strong>
      <div style={{ display: "flex", gap: 48, flexWrap: "wrap", alignItems: "flex-start", justifyContent: "center", marginTop: 18 }}>
        <Donut
          title="Project readiness"
          total={totalProjects}
          segments={[{ label: "Delta Ready", value: readyProjects, color: GREEN }]}
          remainderLabel="Not ready"
          center={totalProjects > 0 ? `${readyProjects}/${totalProjects}` : "0"}
          centerSub={totalProjects === 1 ? "project ready" : "projects ready"}
        />
        <Donut
          title="Approvals"
          segments={[
            { label: "Fully approved", value: approvalsDone, color: GREEN },
            { label: "Awaiting approval", value: approvalsPending, color: YELLOW },
            { label: "Declined", value: approvalsDeclined, color: RED },
          ]}
          center={approvalsTotal}
          centerSub={approvalsTotal === 1 ? "combination" : "combinations"}
        />
      </div>

      <div style={{ marginTop: 24, paddingTop: 20, borderTop: "1px solid var(--color-border)" }}>
        <ProjectBars projects={projects} />
      </div>
    </div>
  );
}
