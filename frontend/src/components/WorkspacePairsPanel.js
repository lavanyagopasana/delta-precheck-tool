import React, { useEffect, useRef, useState } from "react";
import {
  getServerReadiness,
  getCombinationReadiness,
  startCombinationDelta,
  finishCombinationDelta,
  importWorkspacePairsCsv,
  usesTwoColumnCsv,
  SAMPLE_CSV_COLUMNS,
} from "../api/client";
import CombinationOverview from "./CombinationOverview";
import CsvImportPanel from "./CsvImportPanel";
import DataTable from "./DataTable";
import DeltaHistoryPanel from "./DeltaHistoryPanel";
import Modal from "./Modal";
import PreCheckPanel from "./PreCheckPanel";
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
function CombinationStatRow({ combinationId, onChanged, onReadiness, preCheckAction }) {
  const currentUser = useCurrentUser();
  const showToast = useToast();
  const confirm = useConfirm();
  const [readiness, setReadiness] = useState(null);
  const canRunDelta = !AUTH_CONFIGURED || currentUser?.role === "MIGRATION_ENGINEER" || currentUser?.role === "ADMIN";

  // onReadiness lets the page above render the combination's facts (pairs / open tickets / manager)
  // in its own header, without a second fetch of the same endpoint. Called on load and after every
  // Start/Finish so those figures stay in step with this panel.
  const load = () => {
    getCombinationReadiness(combinationId).then((data) => {
      setReadiness(data);
      if (onReadiness) onReadiness(data);
    });
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

  return (
    <CombinationOverview
      readiness={readiness}
      preCheckAction={preCheckAction}
      startAction={
        readiness.deltaInitiatedAt && canRunDelta ? (
          <button className="btn success" style={{ padding: "5px 14px", fontSize: 12.5 }} onClick={handleStartDelta}>
            Start
          </button>
        ) : null
      }
      finishAction={
        readiness.deltaStartedAt && canRunDelta ? (
          <button className="btn" style={{ padding: "5px 14px", fontSize: 12.5 }} onClick={handleFinishDelta}>
            Finished
          </button>
        ) : null
      }
    />
  );
}

export default function WorkspacePairsPanel({
  serverId,
  showStats = true,
  showPreCheckLink = true,
  showCsvImport = true,
  initialCombination = "",
  onCombinationReadiness,
}) {
  const currentUser = useCurrentUser();
  const canImport =
    !AUTH_CONFIGURED || ["ADMIN", "MIGRATION_ENGINEER", "MIGRATION_MANAGER"].includes(currentUser?.role);
  // Mirrors PreCheckPanel.PRECHECK_EDIT_ROLES / SecurityConfig. A manager or lead still gets the link,
  // it just reads "View Pre-Check Form" -- they review, they don't fill it in.
  const canFillPreCheck = !AUTH_CONFIGURED || ["ADMIN", "MIGRATION_ENGINEER"].includes(currentUser?.role);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [comboReadiness, setComboReadiness] = useState(null);
  const [preCheckOpen, setPreCheckOpen] = useState(false);
  // Seeded from initialCombination (e.g. arriving via a "?combination=" link) -- the effect below
  // still falls back to the first group if this doesn't match any real combination.
  const [selectedCombination, setSelectedCombination] = useState(initialCombination);
  // Scrolled to by the "View Delta History" button -- the panel it wraps already renders for every
  // role (it's not gated by canFillPreCheck), so no separate visibility toggle is needed here.
  const historyRef = useRef(null);

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

  useEffect(() => {
    setPreCheckOpen(false);
  }, [selectedCombination]);

  const handleCombinationReadiness = (readiness) => {
    setComboReadiness(readiness);
    if (onCombinationReadiness) onCombinationReadiness(readiness);
  };

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
        <CombinationStatRow
          combinationId={activeCombinationSummary.id}
          onChanged={load}
          onReadiness={handleCombinationReadiness}
          // The pre-check action now sits in the overview's header rather than in a separate
          // "Pre-Check" row below it -- the button already names the action, so the standalone
          // label was a heading for a single button. Built here because all the state it depends
          // on (submission status, cycle number, lock, role) lives in this component.
          preCheckAction={
            showPreCheckLink ? (
              <>
                {/* Someone else has the form open. A submission is claimed (startedByEmail stamped)
                    the moment it is opened, before any item is filled in, so it can sit at
                    NOT_STARTED and still be locked -- which is exactly the state that used to
                    render a live "Start Pre-Check Form" button leading to a read-only notice with
                    nothing to do on it. Label it for what it is and say who holds it. */}
                {lockedByEmail && (
                  <span className="precheck-lock-note">
                    {emailLocalPart(lockedByEmail)} is filling this out
                  </span>
                )}
                {/* Solid primary when there's work to do, outlined when there isn't. */}
                <button
                  className={`btn${
                    activeCombinationSummary.finalDeltaComplete
                      || activeCombinationSummary.submissionStatus === "SUBMITTED"
                      || !canFillPreCheck
                      || lockedByEmail
                      ? " secondary"
                      : ""
                  }`}
                  onClick={() => setPreCheckOpen(true)}
                >
                  {/* A finished Final Delta leaves the submission SUBMITTED forever, so it needs its
                      own label -- "View Pre-Check Form" would imply there's still a live cycle to
                      look at. Cycle 2+ says so explicitly, since an empty checklist otherwise looks
                      like a first-time one. */}
                  {activeCombinationSummary.finalDeltaComplete
                    ? "View Completed Pre-Check"
                    : activeCombinationSummary.submissionStatus === "SUBMITTED"
                    ? "View Pre-Check Form"
                    : lockedByEmail
                    ? "View Pre-Check Form"
                    : !canFillPreCheck
                    // A viewer role (Manager/Dev Lead/QA Lead) with nothing submitted and nobody
                    // drafting it yet has nothing to view -- "View Pre-Check Form" here used to
                    // imply there was, leading straight to a blank NOT_STARTED checklist. That
                    // combination reads as "review this" when there's genuinely nothing to review
                    // yet; anything already resolved lives in Delta History instead. Cycle 2+ names
                    // itself explicitly, same reasoning as the engineer-facing label below -- without
                    // it, "not submitted yet" on a combination with prior completed cycles reads as
                    // "nothing has ever been submitted here," not "this cycle hasn't started."
                    ? activeCombinationSummary.currentCycleNumber > 1
                    ? `Not Submitted Yet · Delta ${activeCombinationSummary.currentCycleNumber}`
                    : "Pre-Check Not Submitted Yet"
                    : activeCombinationSummary.submissionStatus === "DRAFT"
                    ? "Continue Pre-Check Form"
                    : activeCombinationSummary.currentCycleNumber > 1
                    ? `Start Pre-Check · Delta ${activeCombinationSummary.currentCycleNumber}`
                    : "Start Pre-Check Form"}
                </button>
                {/* Same visibility as the panel it scrolls to (every role, not gated by
                    canFillPreCheck) -- a Dev/QA Lead landing on a blank current cycle is exactly who
                    most needs a quick way to what they actually approved/declined last time. */}
                <button
                  className="btn secondary"
                  style={{ marginLeft: 8 }}
                  onClick={() => historyRef.current?.scrollIntoView({ behavior: "smooth", block: "start" })}
                >
                  View Delta History
                </button>
              </>
            ) : null
          }
        />
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

      {showStats && activeCombinationSummary && comboReadiness && (
        <div style={{ marginTop: 24 }} ref={historyRef}>
          <DeltaHistoryPanel
            combinationId={activeCombinationSummary.id}
            reloadKey={comboReadiness.deltaFinishedAt || comboReadiness.deltaStartedAt}
          />
        </div>
      )}

      {preCheckOpen && activeCombinationSummary && (
        <Modal
          title={
            activeCombinationSummary.currentDeltaLabel
              || (activeCombinationSummary.currentCycleNumber > 1
                ? `Pre-Check · Delta ${activeCombinationSummary.currentCycleNumber}`
                : "Pre-Check")
          }
          onClose={() => setPreCheckOpen(false)}
          width={880}
          closeIcon
        >
          <PreCheckPanel
            combinationId={activeCombinationSummary.id}
            showBackNav={false}
            showHeader={false}
            onChanged={load}
          />
        </Modal>
      )}
    </div>
  );
}
