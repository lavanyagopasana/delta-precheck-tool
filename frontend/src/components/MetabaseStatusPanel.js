import React from "react";
import { TableIcon } from "./Icons";
import Modal from "./Modal";

/**
 * The migration process status read out of Metabase, shown as a dialog over a project's page.
 *
 * A dialog rather than an inline section: it is a rollup of the whole project's migration data that
 * you open, read and dismiss, not something you act on per server. One block per product type,
 * because a Metabase database only ever holds one product type's data, so a project spanning types
 * reads from more than one database.
 *
 * Each block is the same breakdown the Metabase UI gives for "filter OwnerEmailId, Summarize > Count,
 * Group by ProcessStatus" -- how many workspaces sit in each state.
 *
 * **Nothing here turns a failure into a zero.** An unset database, an unreachable Metabase and an
 * empty collection are three different things, and rendering any of them as "0 processed" would read
 * as "no migration has happened" -- the worst possible wrong answer for a figure a Delta gets approved
 * against. Each gets its own visible state.
 */

// Grouped so a reader sees "done / needs attention / still going" rather than eleven equal rows.
// Covers all three product types' vocabularies at once: email says PROCESSED_WITH_CONFLICTS and PAUSE
// where message says PROCESSED_WITH_SOME_CONFLICTS and SUSPENDED.
const TONE_BY_STATUS = {
  PROCESSED: "good",
  PROCESSED_WITH_SOME_CONFLICTS: "warn",
  PROCESSED_WITH_CONFLICTS: "warn",
  PROCESSED_WITH_FOLDER_CONFLICT: "warn",
  CONFLICT: "bad",
  IN_PROGRESS: "info",
  NOT_PROCESSED: "muted",
  NO_MESSAGE: "muted",
  SUSPENDED: "muted",
  PAUSE: "muted",
  CANCEL: "muted",
};

// This codebase has no --color-success/--color-warning/--color-danger tokens; an earlier version of
// this file named them and silently fell back to hardcoded off-palette hex, so the tiles did not match
// any other status colour in the app. These are the real tokens from index.css.
const TONE_COLOR = {
  good: "var(--color-green)",
  warn: "var(--color-yellow)",
  bad: "var(--color-red)",
  info: "var(--color-primary)",
  muted: "var(--color-text-muted)",
};

const TONE_BG = {
  good: "var(--color-green-soft)",
  warn: "var(--color-yellow-soft)",
  bad: "var(--color-red-soft)",
  info: "var(--color-primary-soft)",
  muted: "var(--color-gray-soft)",
};

// "PROCESSED_WITH_SOME_CONFLICTS" -> "Processed with some conflicts". Applied to whatever Metabase
// sends, including values this build has never seen, so an unrecognised status still reads as words.
function humanizeStatus(status) {
  if (!status) return "Unknown";
  const words = status.replace(/_/g, " ").toLowerCase().trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

const formatCount = (n) => (typeof n === "number" ? n.toLocaleString() : n);

// "CONTENT" -> "Content", matching the picker above so one product type looks the same in both places.
const titleCase = (s) => (s ? s.charAt(0) + s.slice(1).toLowerCase() : s);

function StatusBreakdown({ statuses, total }) {
  return (
    <div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 10 }}>
        {statuses.map(({ status, count }) => {
          const tone = TONE_BY_STATUS[status] || "muted";
          const share = total > 0 ? (count / total) * 100 : 0;
          return (
            <div
              key={status}
              style={{
                // Tinted by tone rather than uniformly bordered: with eight statuses on screen, the
                // ones that need attention have to be findable without reading every label.
                background: TONE_BG[tone],
                border: "1px solid transparent",
                borderRadius: "var(--radius-sm)",
                padding: "12px 16px",
                minWidth: 150,
                flex: "0 1 auto",
              }}
            >
              <div style={{ fontSize: 22, fontWeight: 700, color: TONE_COLOR[tone], lineHeight: 1.1 }}>
                {formatCount(count)}
              </div>
              <div style={{ fontSize: 12.5, fontWeight: 600, color: "var(--color-text)", marginTop: 4 }}>
                {humanizeStatus(status)}
              </div>
              {/* The share matters as much as the count: 75,547 conflicts is very different against
                  3.6M processed than against 300,000. */}
              <div style={{ fontSize: 11.5, color: "var(--color-text-faint)", marginTop: 2 }}>
                {share.toFixed(share < 1 && share > 0 ? 2 : 1)}%
              </div>
            </div>
          );
        })}
      </div>
      <div style={{ marginTop: 10, fontSize: 12.5, color: "var(--color-text-muted)" }}>
        {formatCount(total)} workspace{total === 1 ? "" : "s"} in total
      </div>
    </div>
  );
}

function ProductTypeBlock({ entry }) {
  const { productType, databaseName, collection, error, statuses, totalWorkspaces,
          ownerEmails, excludedInternalWorkspaces } = entry;

  return (
    <div className="metabase-status-block">
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", marginBottom: 12 }}>
        {/* Same label-vs-value split as the picker above: the product type is which of the project's
            types this block reports on, the database name is the thing it read. */}
        <span className="metabase-type">{titleCase(productType)}</span>
        <span className="metabase-db-name">{databaseName}</span>
        {/* Which collection produced these numbers, so a surprising figure can be traced back to what
            was actually queried rather than guessed at. */}
        {collection && <span className="metabase-collection">{collection}</span>}
      </div>

      {error ? (
        <div className="inline-hint">{error}</div>
      ) : !statuses?.length ? (
        // Explicitly not "0 processed": the query ran and the collection had no customer-owned rows.
        <div className="inline-hint">
          No customer-owned workspaces in this database yet. Nothing has been migrated, or the work is
          recorded under a different database.
        </div>
      ) : (
        <>
          <StatusBreakdown statuses={statuses} total={totalWorkspaces} />
          {/* @cloudfuze.com owners are test accounts used to check that a migration is running at all,
              so their workspaces are excluded from the customer's figures. The excluded count is shown
              rather than hidden: on one real database it was 53 workspaces carrying 47 conflicts.

              When the excluded rows OUTNUMBER the counted ones this stops being a footnote. bakkt's
              content database is 13 test workspaces to 1 real one, so a bare "1 processed" would read
              as a project that has barely started rather than one that is mostly test data. */}
          {excludedInternalWorkspaces > totalWorkspaces && (
            <div className="inline-hint" style={{ marginTop: 10 }}>
              Most of this database is test data: {formatCount(excludedInternalWorkspaces)} of{" "}
              {formatCount(excludedInternalWorkspaces + totalWorkspaces)} workspaces belong to
              CloudFuze test accounts and are not counted above.
            </div>
          )}
          {!!ownerEmails?.length && (
            <div style={{ marginTop: 10, fontSize: 12, color: "var(--color-text-faint)" }}>
              Counted for: {ownerEmails.join(", ")}
              {excludedInternalWorkspaces > 0 &&
                ` — ${formatCount(excludedInternalWorkspaces)} CloudFuze test workspace${
                  excludedInternalWorkspaces === 1 ? "" : "s"
                } excluded`}
            </div>
          )}
        </>
      )}
    </div>
  );
}

export default function MetabaseStatusPanel({ entries, loading, error, onRefresh, onClose }) {
  return (
    // A dialog rather than a section appended to the page: this is a read-only rollup you open,
    // read and dismiss, and rendering it inline pushed the servers -- the things you actually act
    // on -- off screen, with no indication the new block had appeared further down.
    // Wide because a product type's breakdown is a row of eleven-odd status tiles; at the default
    // 640 they wrapped into a column and the shape of the breakdown was lost.
    <Modal title="Migration process status" onClose={onClose} width={900} closeIcon>
      {/* Refresh sits opposite a caption rather than alone against a dead band of whitespace. The
          caption is not filler: the one thing the tiles never say is where the numbers came from or
          how current they are, and a figure a Delta gets approved against should say both. */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 16,
          marginBottom: 16,
        }}
      >
        <p style={{ margin: 0, fontSize: 12.5, color: "var(--color-text-muted)", lineHeight: 1.45 }}>
          Workspace counts by process status, read from this project's Metabase database when you
          opened this.
        </p>
        {/* flexShrink so a long caption squeezes the text, never the button's label. */}
        <button
          type="button"
          className="btn secondary"
          style={{ padding: "6px 14px", fontSize: 12.5, flexShrink: 0 }}
          onClick={onRefresh}
          disabled={loading}
        >
          {loading ? "Refreshing..." : "Refresh"}
        </button>
      </div>

      {loading ? (
        <p className="empty-state">
          <span className="spinner" style={{ marginRight: 8 }} />
          Reading from Metabase...
        </p>
      ) : error ? (
        <div className="inline-hint">{error}</div>
      ) : !entries?.length ? (
        <div className="inline-hint">
          <TableIcon size={14} style={{ marginRight: 6 }} />
          No Metabase database has been fixed for this project yet. Choose one on the project page
          first.
        </div>
      ) : (
        entries.map((entry) => <ProductTypeBlock key={entry.productType} entry={entry} />)
      )}
    </Modal>
  );
}
