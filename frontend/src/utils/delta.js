// Lifecycle helpers shared by the combination overview, server list, and pre-check header.
// "Done" means Finish was clicked on a past cycle — never the current cycle awaiting Start.

/** All approvals cleared, engineer has not clicked Start yet. */
export function isReadyToStartDelta(r) {
  return Boolean(
    r?.deltaInitiatedAt && !r?.deltaStartedAt && !r?.deltaFinishedAt && !r?.finalDeltaComplete
  );
}

/** Finished cycles before the current one — zero on Pre-Delta 1 even if a cycle row exists. */
export function previousDeltasDoneCount(r) {
  if (r?.finalDeltaComplete) return 0;
  const done = r?.completedCycleCount || 0;
  const cycle = r?.currentCycleNumber || 1;
  return done > 0 && cycle > 1 ? done : 0;
}

export function previousDeltasDoneShortLabel(count) {
  if (!count) return "";
  return `${count} pre-delta${count === 1 ? "" : "s"} done`;
}

export function previousDeltasDoneLabel(r) {
  const short = previousDeltasDoneShortLabel(previousDeltasDoneCount(r));
  return short ? ` · ${short}` : "";
}
