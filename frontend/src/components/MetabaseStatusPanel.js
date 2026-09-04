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
  COMPLETED: "good",
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

function StatusBreakdown({ statuses, total, label = "workspace" }) {
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
        {formatCount(total)} {label}{total === 1 ? "" : "s"} in total
      </div>
    </div>
  );
}

/**
 * Drive changes, counted per status for the customer's own user ids.
 *
 * Content only, so a null `changes` with no error means "not applicable to this product type" and
 * renders nothing at all -- an empty "Drive changes: 0" under Email would be a claim about data that
 * has no collection behind it.
 *
 * Not rendered at all for an empty database either: ProductTypeBlock collapses that case to a single
 * line, and a "Drive changes" heading followed by "none recorded" underneath a database that has no
 * data of any kind was two sentences saying what one word already said.
 *
 * Separated from the workspace tiles by a rule rather than merged into them: these count change
 * records, not workspaces, so adding the two totals together would be meaningless.
 */
function DriveChanges({ changes, total, error, users }) {
  if (!changes && !error) return null;

  return (
    <div className="metabase-subsection">
      <div className="metabase-subsection-head">
        <span className="metabase-subsection-title">Drive changes</span>
        <span className="metabase-collection">DriveChangeIdDetails</span>
      </div>

      {error ? (
        <div className="inline-hint" style={{ marginBottom: 0 }}>{error}</div>
      ) : !changes.length ? (
        <div className="note-hint" style={{ marginBottom: 0 }}>None recorded yet.</div>
      ) : (
        <>
          <StatusBreakdown statuses={changes} total={total} label="change" />
          {/* Two lines, not one. They answer different questions: WHO these changes belong to,
              which is the email, and WHAT the query actually filtered on, which is the id. Running
              them together read as one fact and the id looked like noise after the address --
              naming the filter is what makes the figure reproducible by hand in Metabase.
              Monospaced because a 24-character hex id is checked character by character. */}
          {!!users?.length && (
            <dl className="metabase-facts">
              <dt>Counted for</dt>
              <dd>{users.map((u) => u.email || "(no email on this user)").join(", ")}</dd>
              <dt>Filtered by UserId</dt>
              <dd>
                {users.map((u, i) => (
                  <span key={u.userId}>
                    {i > 0 && ", "}
                    <code>{u.userId}</code>
                  </span>
                ))}
              </dd>
            </dl>
          )}
        </>
      )}
    </div>
  );
}

function ProductTypeBlock({ entry }) {
  // productType is deliberately not read here -- ProductTypeGroup names it once for the whole group.
  const { databaseName, collection, error, statuses, totalWorkspaces,
          ownerEmails, excludedInternalWorkspaces,
          driveChanges, totalDriveChanges, driveChangesError, driveChangeUsers } = entry;

  const noWorkspaces = !statuses?.length;
  const noDriveChanges = !driveChangesError && (!driveChanges || driveChanges.length === 0);
  // Nothing failed, and every query that ran came back with nothing. Worth detecting as its own case
  // rather than rendering the normal layout with empty sections: a database with no data was taking
  // more vertical space than one with data, across two headings and two full-width sentences, and a
  // project with three databases was mostly apologies.
  const isEmpty = !error && noWorkspaces && noDriveChanges;

  return (
    <div className={`metabase-status-block${isEmpty ? " metabase-status-block--empty" : ""}`}>
      <div className="metabase-block-head">
        {/* The product type is named once on the group heading above, not repeated per database --
            three identical "Content" pills stacked down the dialog said nothing the heading had not
            already said. What identifies a block here is which database it read. */}
        <span className="metabase-db-name">{databaseName}</span>
        {/* Which collection produced these numbers, so a surprising figure can be traced back to what
            was actually queried rather than guessed at. */}
        {collection && <span className="metabase-collection">{collection}</span>}
        {isEmpty && <span className="metabase-empty-tag">No data yet</span>}
      </div>

      {isEmpty ? (
        // One short line, not the two paragraphs this used to be. It still says the two things worth
        // saying -- the read succeeded, and the work may simply live in another database -- because
        // "empty" and "wrong database" look identical from here and the reader has to know that.
        <p className="note-hint" style={{ marginBottom: 0 }}>
          Nothing recorded under this database yet — the work may be in another one.
        </p>
      ) : error ? (
        <div className="inline-hint" style={{ marginBottom: 0 }}>{error}</div>
      ) : (
        <>
          {noWorkspaces ? (
            // Explicitly not "0 processed": the query ran and the collection had no customer-owned
            // rows. Kept short here because the Drive changes below it did find something, so this
            // is a detail on a block that otherwise has data.
            <div className="note-hint" style={{ marginBottom: 0 }}>
              No customer-owned workspaces yet.
            </div>
          ) : (
            <>
              <StatusBreakdown statuses={statuses} total={totalWorkspaces} />
              {/* @cloudfuze.com owners are test accounts used to check that a migration is running at
                  all, so their workspaces are excluded from the customer's figures.

                  When the excluded rows OUTNUMBER the counted ones this stops being a footnote.
                  bakkt's content database is 13 test workspaces to 1 real one, so a bare "1
                  processed" would read as a project that has barely started rather than one that is
                  mostly test data. */}
              {excludedInternalWorkspaces > totalWorkspaces && (
                <div className="warn-hint" style={{ marginTop: 12, marginBottom: 0 }}>
                  Mostly test data: {formatCount(excludedInternalWorkspaces)} of{" "}
                  {formatCount(excludedInternalWorkspaces + totalWorkspaces)} workspaces belong to
                  CloudFuze test accounts and are not counted above.
                </div>
              )}
              {!!ownerEmails?.length && (
                <dl className="metabase-facts">
                  <dt>Counted for</dt>
                  <dd>{ownerEmails.join(", ")}</dd>
                </dl>
              )}
            </>
          )}

          <DriveChanges
            changes={driveChanges}
            total={totalDriveChanges}
            error={driveChangesError}
            users={driveChangeUsers}
          />
        </>
      )}
    </div>
  );
}

/**
 * Every database belonging to ONE product type, under a single heading.
 *
 * The grouping is the whole point. With a product type spread across three databases the dialog
 * previously showed three separate blocks each labelled "Content", and nothing said they were three
 * views of the same product type rather than three unrelated things. The heading names the type
 * once, says how many databases are under it, and the blocks below are visibly its contents.
 *
 * The combined total is shown only when EVERY database in the group could be read. A sum that
 * quietly omits a database that failed is worse than no sum: it looks like a complete figure, and
 * this is what a Delta gets approved against. When one fails the group says so instead.
 */
function ProductTypeGroup({ productType, entries }) {
  const failed = entries.filter((e) => e.error).length;
  const combined = entries.reduce((sum, e) => sum + (e.totalWorkspaces || 0), 0);
  const label = titleCase(productType);
  const count = entries.length;

  return (
    <section className="metabase-group">
      <div className="metabase-group-head">
        <span className="metabase-group-title">{label}</span>
        <span className="metabase-group-desc">
          {count === 1
            ? `1 database holds this project's ${label} migration data`
            : `${count} databases hold this project's ${label} migration data — all ${count} are counted below`}
        </span>
        {!failed && count > 1 && (
          <span className="metabase-group-total">
            {formatCount(combined)} workspace{combined === 1 ? "" : "s"} across all {count}
          </span>
        )}
        {!!failed && (
          <span className="warn-hint" style={{ marginBottom: 0 }}>
            {failed} of {count} could not be read — no combined total
          </span>
        )}
      </div>

      {entries.map((entry) => (
        <ProductTypeBlock key={`${entry.productType}:${entry.databaseName}`} entry={entry} />
      ))}
    </section>
  );
}

/** Entries arrive sorted by product type then database name, so grouping is a single pass. */
function groupByProductType(entries) {
  const groups = [];
  for (const entry of entries) {
    const last = groups[groups.length - 1];
    if (last && last.productType === entry.productType) {
      last.entries.push(entry);
    } else {
      groups.push({ productType: entry.productType, entries: [entry] });
    }
  }
  return groups;
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
        <div className="note-hint">
          <TableIcon size={14} style={{ marginRight: 6 }} />
          No Metabase database has been fixed for this project yet. Choose one on the project page
          first.
        </div>
      ) : (
        groupByProductType(entries).map((group) => (
          <ProductTypeGroup
            key={group.productType}
            productType={group.productType}
            entries={group.entries}
          />
        ))
      )}
    </Modal>
  );
}
