import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  getServerReadiness,
  getCombinationReadiness,
  startCombinationDelta,
  finishCombinationDelta,
  importWorkspacePairsCsv,
  SAMPLE_CSV_COLUMNS,
} from "../api/client";
import CsvImportPanel from "./CsvImportPanel";
import DataTable from "./DataTable";
import { useToast } from "./Toast";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { groupByCombination } from "../utils/pairs";

const SAMPLE_ROW = [
  "jane.doe@source-tenant.com",
  "/jane.doe/My Drive",
  "jane.doe@company.com",
  "/sites/migrated/jane.doe",
  "Google Drive -> OneDrive",
];

// The stat-card row + Delta Start/Finish lifecycle for whichever combination is currently selected
// -- each combination has its own independent pre-check/sign-off/Delta lifecycle now, so this is
// re-fetched every time combinationId changes rather than being one server-wide row.
function CombinationStatRow({ combinationId, onChanged }) {
  const currentUser = useCurrentUser();
  const showToast = useToast();
  const [readiness, setReadiness] = useState(null);
  const canRunDelta = !AUTH_CONFIGURED || currentUser?.role === "MIGRATION_ENGINEER" || currentUser?.role === "ADMIN";

  const load = () => {
    getCombinationReadiness(combinationId).then(setReadiness);
  };

  useEffect(load, [combinationId]);

  const handleStartDelta = async () => {
    try {
      await startCombinationDelta(combinationId);
      showToast("Delta migration started.", "success");
      load();
      onChanged();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to start Delta.", "error");
    }
  };

  const handleFinishDelta = async () => {
    try {
      await finishCombinationDelta(combinationId);
      showToast("Delta migration marked finished.", "success");
      load();
      onChanged();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to finish Delta.", "error");
    }
  };

  if (!readiness) return null;

  const fmt = (d) => new Date(d).toLocaleDateString();
  const stage =
    readiness.readinessStage === "READY"
      ? { label: "Delta Ready", pill: "green" }
      : readiness.readinessStage === "IN_PROGRESS"
      ? { label: readiness.readinessDetail || "In review", pill: null, color: "var(--color-yellow)" }
      : { label: "Pre-check not submitted", pill: null, color: "var(--color-red)" };

  return (
    <div className="subpanel" style={{ marginBottom: 20 }}>
      <div className="card-row" style={{ marginBottom: 0 }}>
        <div className="stat-card">
          <div className="value" style={{ fontSize: 14 }}>
            {stage.pill ? (
              <span className={`badge ${stage.pill}`}>{stage.label}</span>
            ) : (
              <span style={{ fontSize: 12, fontWeight: 600, color: stage.color }}>{stage.label}</span>
            )}
          </div>
          <div className="label">Status</div>
        </div>
        <div className="stat-card">
          <div className="value">{readiness.totalPairs}</div>
          <div className="label">Pairs</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: readiness.openEscalationCount > 0 ? "var(--color-red)" : undefined }}>
            {readiness.openEscalationCount}
          </div>
          <div className="label">Tickets</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ fontSize: 16 }}>
            {readiness.deltaStartedAt ? (
              fmt(readiness.deltaStartedAt)
            ) : readiness.deltaInitiatedAt && canRunDelta ? (
              <button className="btn success" style={{ padding: "5px 14px", fontSize: 12.5 }} onClick={handleStartDelta}>
                Start
              </button>
            ) : (
              "—"
            )}
          </div>
          <div className="label">Delta Started</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ fontSize: 16 }}>
            {readiness.deltaFinishedAt ? (
              fmt(readiness.deltaFinishedAt)
            ) : readiness.deltaStartedAt && canRunDelta ? (
              <button className="btn" style={{ padding: "5px 14px", fontSize: 12.5 }} onClick={handleFinishDelta}>
                Finished
              </button>
            ) : (
              "—"
            )}
          </div>
          <div className="label">Delta Finished</div>
        </div>
      </div>
    </div>
  );
}

export default function WorkspacePairsPanel({
  serverId,
  showStats = true,
  showPreCheckLink = true,
  showCsvImport = true,
  initialCombination = "",
}) {
  const navigate = useNavigate();
  const currentUser = useCurrentUser();
  const canImport =
    !AUTH_CONFIGURED || ["ADMIN", "MIGRATION_ENGINEER", "MIGRATION_MANAGER"].includes(currentUser?.role);
  const canFillPreCheck = !AUTH_CONFIGURED || ["MIGRATION_ENGINEER", "MIGRATION_MANAGER"].includes(currentUser?.role);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  // Seeded from initialCombination (e.g. arriving via a "?combination=" link) -- the effect below
  // still falls back to the first group if this doesn't match any real combination.
  const [selectedCombination, setSelectedCombination] = useState(initialCombination);

  const load = () => {
    setLoading(true);
    getServerReadiness(serverId)
      .then(setData)
      .finally(() => setLoading(false));
  };

  useEffect(load, [serverId]);

  const groups = data ? groupByCombination(data.pairs) : [];

  // Default to the first combination whenever the server changes or its combinations change (e.g.
  // right after an import) -- but keep the user's current pick if it's still present. Skipped while
  // data hasn't loaded yet (data === null): groups is transiently [] during that window too, and
  // running this then would wipe out an initialCombination seeded from a "?combination=" link
  // before the real data ever had a chance to confirm or reject it.
  useEffect(() => {
    if (!data) return;
    if (groups.length === 0) {
      if (selectedCombination !== "") setSelectedCombination("");
      return;
    }
    if (!groups.some((g) => g.combination === selectedCombination)) {
      setSelectedCombination(groups[0].combination);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  if (loading) return <p>Loading migration pairs...</p>;

  const activeGroup = groups.find((g) => g.combination === selectedCombination) || groups[0];
  // Pre-check/Delta lifecycle are per-combination now -- resolve the WorkspaceCombination id for
  // whichever combination is currently selected (data.combinations comes from ServerReadinessDto)
  // so the button/stat row link to its own data instead of a server-wide one.
  const activeCombinationSummary = activeGroup
    ? (data.combinations || []).find(
        (c) => c.name.trim().toLowerCase() === activeGroup.combination.trim().toLowerCase()
      )
    : null;

  return (
    <div>
      {showStats && activeCombinationSummary && (
        <CombinationStatRow combinationId={activeCombinationSummary.id} onChanged={load} />
      )}

      {showPreCheckLink && activeCombinationSummary && (
        <div className="server-precheck-row">
          <strong style={{ fontSize: 13.5 }}>Pre-Check</strong>
          <button
            className={`btn ${activeCombinationSummary.submissionStatus === "SUBMITTED" ? "success" : "warning"}`}
            onClick={() => navigate(`/combinations/${activeCombinationSummary.id}/precheck`)}
          >
            {activeCombinationSummary.submissionStatus === "SUBMITTED"
              ? "View Pre-Check Form"
              : !canFillPreCheck
              ? "View Pre-Check Form"
              : activeCombinationSummary.submissionStatus === "DRAFT"
              ? "Continue Pre-Check Form"
              : "Start Pre-Check Form"}
          </button>
        </div>
      )}

      {showCsvImport && canImport && (
        <CsvImportPanel
          title="Import migration pairs from CSV"
          columns={SAMPLE_CSV_COLUMNS}
          sampleRow={SAMPLE_ROW}
          sampleFileName="migration-pairs-sample.csv"
          onUpload={(file) => importWorkspacePairsCsv(serverId, file)}
          onImported={load}
        />
      )}

      <div style={{ marginTop: 24 }}>
        {data.pairs.length === 0 ? (
          <>
            <h3 className="section-title">Migration Pairs</h3>
            <p className="empty-state">
              {canImport ? "No migration pairs yet. Import a CSV above." : "No migration pairs yet."}
            </p>
          </>
        ) : (
          activeGroup && (
            <DataTable
              title="Migration Pairs"
              rows={activeGroup.pairs}
              rowKey={(p) => p.id}
              searchPlaceholder="Filter migration pairs..."
              emptyMessage="No migration pairs yet."
              columns={[
                { key: "sourceEmail", label: "Source Email" },
                { key: "sourcePath", label: "Source Path", render: (p) => p.sourcePath || "-" },
                { key: "destinationEmail", label: "Destination Email" },
                { key: "destinationPath", label: "Destination Path", render: (p) => p.destinationPath || "-" },
              ]}
            />
          )
        )}
      </div>
    </div>
  );
}
