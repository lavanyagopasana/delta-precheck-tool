import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  getServerReadiness,
  getCombinationReadiness,
  startCombinationDelta,
  finishCombinationDelta,
  importWorkspacePairsCsv,
  usesTwoColumnCsv,
  SAMPLE_CSV_COLUMNS,
} from "../api/client";
import CsvImportPanel from "./CsvImportPanel";
import DataTable from "./DataTable";
import DeltaHistoryPanel from "./DeltaHistoryPanel";
import { useToast } from "./Toast";
import { useConfirm } from "./ConfirmDialog";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { groupByCombination } from "../utils/pairs";
import { emailLocalPart } from "../utils/format";

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
  const confirm = useConfirm();
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

  // Finishing is the branch point of the whole multi-cycle flow, and the two outcomes are very
  // different -- one reopens the checklist, the other permanently closes the combination. Confirm
  // explicitly so nobody discovers which one happened after the fact.
  const handleFinishDelta = async () => {
    const isFinal = readiness?.currentDeltaType === "FINAL_DELTA";
    const ok = await confirm({
      title: isFinal ? "Finish the Final Delta?" : `Finish ${readiness?.currentDeltaLabel || "this pre-delta"}?`,
      message: isFinal
        ? "This closes the combination for good: no further pre-checks or deltas, and its server becomes ready to decommission once every combination is done."
        : "The pre-check will be cleared and reopened so the next pre-delta can be filled out. This cycle's checklist, evidence and sign-offs are kept in the Delta History.",
      confirmLabel: isFinal ? "Finish Final Delta" : "Finish & start next cycle",
      danger: isFinal,
    });
    if (!ok) return;
    try {
      await finishCombinationDelta(combinationId);
      showToast(
        isFinal
          ? "Final Delta complete — this combination is done."
          : "Delta finished. The pre-check is reset for the next cycle.",
        "success"
      );
      load();
      onChanged();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to finish Delta.", "error");
    }
  };

  if (!readiness) return null;

  const fmt = (d) => new Date(d).toLocaleDateString();
  // Every stage carries a colour directly now (no pill/no-pill split) so the Status card renders one
  // consistent shape whatever the state. Purple for the irreversible Final Delta milestone, matching the
  // token's documented reservation in index.css.
  const stage =
    readiness.readinessStage === "COMPLETE"
      ? { label: "Final Delta complete", color: "var(--color-purple)" }
      : readiness.readinessStage === "READY"
      ? { label: "Delta Ready", color: "var(--color-green)" }
      : readiness.readinessStage === "IN_PROGRESS"
      ? {
          label: readiness.readinessDetail || "In review",
          color: readiness.blockedByDecline ? "var(--color-red)" : "var(--color-yellow)",
        }
      : { label: "Pre-check not submitted", color: "var(--color-red)" };

  return (
    // Fragment, not a wrapper: the history below is a sibling of the stat strip rather than nested
    // inside it. Nesting it made a card-inside-a-panel-inside-a-card, and the resulting padding left
    // the history barely any width to render in.
    <>
      <div className="subpanel" style={{ marginBottom: 16 }}>
      {/* Single row (card-row--nowrap): the five cards shrink to fit rather than wrapping a lone card
          onto a second line. The former "Current Delta" card was removed as redundant -- the Status
          card already shows "Final Delta complete", the Start Pre-Check button carries the cycle
          number, and the Delta history below lists every completed cycle in full. */}
      <div className="card-row card-row--nowrap" style={{ marginBottom: 0 }}>
        {/* Plain coloured text, not a badge. Every other card in this strip holds bare bold text (a
            count or a date), so a pill here was the odd one out -- and since badges no longer wrap, a
            long status like "Final Delta complete" stretched into a slab that unbalanced the row. The
            colour still carries the same meaning the pill did. */}
        <div className="stat-card">
          <div
            className="value"
            style={{ fontSize: 14, lineHeight: 1.3, color: stage.color }}
          >
            {stage.label}
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

      {/* Per-cycle history, directly under the stat strip. This is what replaces the removed "Current
          Delta · N done" card: instead of a single count, it lists every cycle with its own dates and
          sign-offs. reloadKey changes whenever a Delta is started or finished above, so the history
          refreshes in the same interaction. */}
      <DeltaHistoryPanel
        combinationId={combinationId}
        reloadKey={readiness.deltaFinishedAt || readiness.deltaStartedAt}
      />
    </>
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
  // Mirrors PreCheckPanel.PRECHECK_EDIT_ROLES / SecurityConfig. A manager or lead still gets the link,
  // it just reads "View Pre-Check Form" -- they review, they don't fill it in.
  const canFillPreCheck = !AUTH_CONFIGURED || ["ADMIN", "MIGRATION_ENGINEER"].includes(currentUser?.role);
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

  // Held by SOMEONE ELSE -- your own claim isn't a lock, it's just where you left off, and that
  // already reads as "Continue Pre-Check Form". Compared case-insensitively: email is the identity
  // key everywhere in this app and a case difference here would silently mislabel your own form as
  // someone else's. A submitted form isn't a lock either; it has its own "View" label above.
  const lockedByEmail =
    activeCombinationSummary?.preCheckStartedByEmail &&
    activeCombinationSummary.submissionStatus !== "SUBMITTED" &&
    activeCombinationSummary.preCheckStartedByEmail.toLowerCase() !== (currentUser?.email || "").toLowerCase()
      ? activeCombinationSummary.preCheckStartedByEmail
      : null;

  return (
    <div>
      {showStats && activeCombinationSummary && (
        <CombinationStatRow combinationId={activeCombinationSummary.id} onChanged={load} />
      )}

      {showPreCheckLink && activeCombinationSummary && (
        <div className="server-precheck-row">
          <strong style={{ fontSize: 13.5 }}>Pre-Check</strong>
          {/* Someone else has the form open. A submission is claimed (startedByEmail stamped) the
              moment it is opened, before any item is filled in, so it can sit at NOT_STARTED and
              still be locked -- which is exactly the state that used to render a live "Start
              Pre-Check Form" button leading to a read-only notice with nothing to do on it.
              Label it for what it is and say who holds it. */}
          {lockedByEmail && (
            <span className="precheck-lock-note">
              {emailLocalPart(lockedByEmail)} is filling this out
            </span>
          )}
          {/* Solid primary when there's work to do, outlined when there isn't.
              This was `warning` (dark amber) for the actionable state and `success` (green) for the
              read-only one -- both wrong: starting a pre-check is the routine primary action on this
              page, not a warning, and a solid green button for "go and look at this" pulled more
              attention than the thing you were meant to click. */}
          <button
            className={`btn${
              activeCombinationSummary.finalDeltaComplete
                || activeCombinationSummary.submissionStatus === "SUBMITTED"
                || !canFillPreCheck
                || lockedByEmail
                ? " secondary"
                : ""
            }`}
            onClick={() => navigate(`/combinations/${activeCombinationSummary.id}/precheck`)}
          >
            {/* A finished Final Delta leaves the submission SUBMITTED forever, so it needs its own label
                -- "View Pre-Check Form" would imply there's still a live cycle to look at. Cycle 2+
                says so explicitly, since an empty checklist otherwise looks like a first-time one. */}
            {activeCombinationSummary.finalDeltaComplete
              ? "View Completed Pre-Check"
              : activeCombinationSummary.submissionStatus === "SUBMITTED" || !canFillPreCheck
              ? "View Pre-Check Form"
              : lockedByEmail
              ? "View Pre-Check Form"
              : activeCombinationSummary.submissionStatus === "DRAFT"
              ? "Continue Pre-Check Form"
              : activeCombinationSummary.currentCycleNumber > 1
              ? `Start Pre-Check · Delta ${activeCombinationSummary.currentCycleNumber}`
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
              // Email and Message migrate accounts, not folder trees, so their CSVs have no path
              // columns and every pair's paths are null. Rendering them anyway gave two columns of em
              // dashes that read as missing data rather than "not applicable", and contradicted the
              // two-column format shown under CSV format. Same predicate as the CSV shapes use.
              columns={
                usesTwoColumnCsv(data.productType)
                  ? [
                      { key: "sourceEmail", label: "Source Email" },
                      { key: "destinationEmail", label: "Destination Email" },
                    ]
                  : [
                      { key: "sourceEmail", label: "Source Email" },
                      { key: "sourcePath", label: "Source Path", render: (p) => p.sourcePath || "-" },
                      { key: "destinationEmail", label: "Destination Email" },
                      { key: "destinationPath", label: "Destination Path", render: (p) => p.destinationPath || "-" },
                    ]
              }
            />
          )
        )}
      </div>
    </div>
  );
}
