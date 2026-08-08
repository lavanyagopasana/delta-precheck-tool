import React from "react";
import { DELTA_TYPE_BADGE, DELTA_PHASE_BADGE_COLOR } from "../constants";

// The "Pre-Delta 2" / "Final Delta" chip. Shared rather than reimplemented per page because it
// appears in four places (pre-check header, approvals table, project Delta Progress table, server
// combination rows) and they must agree -- a Final Delta reading as a routine pre-delta anywhere would
// hide the fact that it's the irreversible one.
//
// `label` is whatever the backend resolved (DeltaType.label) so the numbering rule lives in exactly
// one place; this component only decides the color. A null type means the pre-check hasn't been
// submitted yet, so nothing has settled the cycle's nature -- rendered as an em dash, not guessed.
export default function DeltaBadge({ deltaType, deltaPhase, label, fallback = "—", title }) {
  if (!deltaType) {
    return <span style={{ color: "var(--color-text-faint)" }}>{fallback}</span>;
  }
  const badge = DELTA_TYPE_BADGE[deltaType] || { color: "gray", label: deltaType };
  // Phase wins when the caller passes it — each phase has its own muted badge class.
  const phaseClass = deltaPhase ? DELTA_PHASE_BADGE_COLOR[deltaPhase] : null;
  const color = phaseClass || badge.color;
  return (
    <span className={`badge ${color}`} title={title}>
      {label || badge.label}
    </span>
  );
}
