import React from "react";
import { emailLocalPart } from "../utils/format";
import { isReadyToStartDelta, previousDeltasDoneLabel } from "../utils/delta";

// The combination's headline state, its four-stage lifecycle, and the facts that used to be spread
// across five mostly-empty stat cards.
//
// The old strip showed Status / Pairs / Tickets / Delta Started / Delta Finished as equal-weight
// cards -- so for most of a combination's life three of the five read "—", while the data people
// actually ask for (who submitted it, who approved, why an approver sent it back, which delta cycle
// this is) was fetched but never rendered. This lays the same lifecycle out as a rail instead: each
// stage carries its own date AND actor, the stage the combination is sitting on is ringed, and the
// Start/Finish actions live in the stage they belong to rather than inside a metric card.
const STAGE_LABELS = ["Pre-Check", "Approvals", "Delta Started", "Delta Finished"];

function Stage({ index, state, title, meta, actor, action }) {
  // done -> green node with a tick, current -> ringed, blocked -> red ring, final -> purple,
  // pending -> hollow. The connector to the next stage is green only once this one is done.
  const symbol = state === "done" || state === "final" ? "✓" : index + 1;
  return (
    <div className={`combo-stage ${state}`}>
      <span className="combo-stage-node">{symbol}</span>
      <div className="combo-stage-title">{title}</div>
      <div className="combo-stage-meta">
        {meta}
        {actor && (
          <>
            {meta ? " · " : ""}
            <span className="combo-stage-actor">{emailLocalPart(actor)}</span>
          </>
        )}
      </div>
      {action && <div className="combo-stage-action">{action}</div>}
    </div>
  );
}

export default function CombinationOverview({ readiness, preCheckAction, startAction, finishAction }) {
  const fmt = (d) => new Date(d).toLocaleDateString();
  const complete = readiness.finalDeltaComplete;
  const readyToStart = isReadyToStartDelta(readiness);

  // Headline follows the lifecycle timestamps so each state reads plainly at a glance.
  const stage = complete
    ? { label: "Final Delta complete", color: "var(--color-purple)" }
    : readyToStart
    ? { label: "Ready to start", color: "var(--color-green)" }
    : readiness.deltaFinishedAt
    ? { label: "Delta done", color: "var(--color-green)" }
    : readiness.deltaStartedAt
    ? { label: "Migration running", color: "var(--color-blue)" }
    : readiness.readinessStage === "IN_PROGRESS"
    ? {
        label: readiness.readinessDetail || "In review",
        color: readiness.blockedByDecline ? "var(--color-red)" : "var(--color-yellow)",
      }
    : { label: "Pre-check not submitted", color: "var(--color-red)" };
  const submitted = readiness.submissionStatus === "SUBMITTED";

  // Stage 1 -- pre-check. DRAFT means someone has it open and partly filled in.
  const preCheckState = submitted ? "done" : readiness.submissionStatus === "DRAFT" ? "current" : "current";
  const preCheckMeta = submitted
    ? "Submitted"
    : readiness.submissionStatus === "DRAFT"
    ? "In progress"
    : "Not started";

  // Stage 2 -- the sign-off chain. Resolved the moment Delta is initiated (that stamp IS the
  // "chain fully approved" marker), blocked while an approver has sent it back.
  const approvalsState = readiness.deltaInitiatedAt
    ? "done"
    : readiness.blockedByDecline
    ? "blocked"
    : submitted
    ? "current"
    : "pending";
  // Deliberately does NOT repeat readinessDetail ("Migration Manager not approved yet") -- that is
  // the headline above, and printing it again here said the same sentence twice on one screen. The
  // headline names *which* role; this just says the stage is live. Same for a decline: the callout
  // below carries the role, person and reason.
  const approvalsMeta = readiness.deltaInitiatedAt
    ? "Approved"
    : readiness.blockedByDecline
    ? "Sent back"
    : submitted
    ? "In review"
    : "Waiting on pre-check";

  const startedState = readiness.deltaStartedAt ? "done" : readiness.deltaInitiatedAt ? "current" : "pending";
  const finishedState = readiness.deltaFinishedAt
    ? complete
      ? "final"
      : "done"
    : readiness.deltaStartedAt
    ? "current"
    : "pending";

  return (
    <div className="combo-overview">
      <div className="combo-overview-head">
        <div>
          <div className="combo-state" style={{ color: stage.color }}>
            <span className="combo-state-dot" />
            {stage.label}
          </div>
          <div className="combo-state-sub">
            {complete
              ? `Closed after ${readiness.completedCycleCount} delta cycle${
                  readiness.completedCycleCount === 1 ? "" : "s"
                }${readiness.finalDeltaCompletedBy ? ` · ${emailLocalPart(readiness.finalDeltaCompletedBy)}` : ""}`
              : `${readiness.currentDeltaLabel || `Delta ${readiness.currentCycleNumber}`}${previousDeltasDoneLabel(readiness)}`}
          </div>
        </div>

        {/* The pre-check action, on the same line as the state it relates to -- it used to sit in a
            separate row below the panel under a "Pre-Check" heading, which was a heading for a
            single button. */}
        {preCheckAction && <div className="combo-head-action">{preCheckAction}</div>}
      </div>

      {/* The reason an approver bounced this back. The API has carried it all along; this view never
          showed it, so an engineer had to go to Approvals to find out what to fix. */}
      {readiness.blockedByDecline && (
        <div className="combo-decline">
          <svg
            width="17"
            height="17"
            viewBox="0 0 24 24"
            fill="none"
            stroke="var(--color-red)"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            style={{ flexShrink: 0, marginTop: 1 }}
          >
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
            <line x1="12" y1="9" x2="12" y2="13" />
            <line x1="12" y1="17" x2="12.01" y2="17" />
          </svg>
          <div style={{ minWidth: 0 }}>
            <div className="combo-decline-title">
              Sent back by {readiness.declinedByRoleLabel || "an approver"}
            </div>
            <div className="combo-decline-reason">
              {readiness.declineReason || "No reason was recorded."}
            </div>
            <div className="combo-decline-meta">
              {readiness.declinedBy ? emailLocalPart(readiness.declinedBy) : "Unknown"}
              {readiness.declinedAt ? ` · ${new Date(readiness.declinedAt).toLocaleString()}` : ""}
            </div>
          </div>
        </div>
      )}

      <div className="combo-journey">
        <Stage
          index={0}
          state={preCheckState}
          title={STAGE_LABELS[0]}
          meta={preCheckMeta}
          actor={submitted ? readiness.deltaInitiatedBy : null}
        />
        <Stage index={1} state={approvalsState} title={STAGE_LABELS[1]} meta={approvalsMeta} />
        <Stage
          index={2}
          state={startedState}
          title={STAGE_LABELS[2]}
          meta={readiness.deltaStartedAt ? fmt(readiness.deltaStartedAt) : startedState === "current" ? "Ready to start" : "—"}
          actor={readiness.deltaStartedAt ? readiness.deltaStartedBy : null}
          action={!readiness.deltaStartedAt ? startAction : null}
        />
        <Stage
          index={3}
          state={finishedState}
          title={STAGE_LABELS[3]}
          meta={
            readiness.deltaFinishedAt
              ? "Done"
              : finishedState === "current"
              ? "Click Finish when migration is complete"
              : "—"
          }
          actor={readiness.deltaFinishedAt ? readiness.deltaFinishedBy : null}
          action={!readiness.deltaFinishedAt ? finishAction : null}
        />
      </div>
    </div>
  );
}
