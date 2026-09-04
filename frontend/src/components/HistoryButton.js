import React, { useState } from "react";
import Modal from "./Modal";
import { ClockIcon } from "./Icons";
import { useToast } from "./Toast";

// A single "who changed this, from what, to what" line. Shared by every entity's edit trail
// (project, server, combination) since they are all the same shape -- ChangeLogEntryDto.
function ChangeLogRow({ entry }) {
  return (
    <div style={{ padding: "8px 0", borderBottom: "1px solid var(--color-border)", fontSize: 12.5 }}>
      <div>
        <span style={{ fontWeight: 600 }}>{entry.fieldName}</span>
        <span style={{ color: "var(--color-text-muted)" }}>: </span>
        <span style={{ color: "var(--color-text-faint)" }}>{entry.oldValue || "empty"}</span>
        <span style={{ color: "var(--color-text-muted)" }}> {"→"} </span>
        <span style={{ fontWeight: 600 }}>{entry.newValue || "empty"}</span>
      </div>
      <div style={{ color: "var(--color-text-faint)", marginTop: 2 }}>
        {entry.changedBy || "Unknown"}
        {entry.changedByRoleLabel ? ` (${entry.changedByRoleLabel})` : ""}
        {entry.changedAt ? ` · ${new Date(entry.changedAt).toLocaleString()}` : ""}
      </div>
    </div>
  );
}

// One CSV upload/re-upload. Leads with what a re-upload REMOVED, since those rows exist nowhere
// else afterwards -- "42 added" alone would hide that 60 others just disappeared.
function PairImportRow({ entry }) {
  return (
    <div style={{ padding: "8px 0", borderBottom: "1px solid var(--color-border)", fontSize: 12.5 }}>
      <div>
        <span style={{ fontWeight: 600 }}>{entry.fileName || "Unnamed file"}</span>
        {entry.combination && (
          <span style={{ color: "var(--color-text-muted)" }}> ({entry.combination})</span>
        )}
      </div>
      <div style={{ color: "var(--color-text-muted)" }}>{entry.summary}</div>
      <div style={{ color: "var(--color-text-faint)", marginTop: 2 }}>
        {entry.importedBy || "Unknown"}
        {entry.importedByRoleLabel ? ` (${entry.importedByRoleLabel})` : ""}
        {entry.importedAt ? ` · ${new Date(entry.importedAt).toLocaleString()}` : ""}
      </div>
    </div>
  );
}

// One section within the modal: a heading, a count, and its rows (or an empty state).
function HistorySection({ title, entries, loading, emptyText, Row }) {
  return (
    <div style={{ marginBottom: 18 }}>
      <div className="detail-fact-label" style={{ marginBottom: 8 }}>
        {title} {entries ? `(${entries.length})` : ""}
      </div>
      {loading ? (
        <span className="progress-label">Loading...</span>
      ) : !entries || entries.length === 0 ? (
        <span className="progress-label">{emptyText}</span>
      ) : (
        <div>
          {entries.map((entry) => (
            <Row key={entry.id} entry={entry} />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * A "History" button that opens a modal on click and fetches on open, every time -- never cached.
 * The point of this trail is "who changed this," and a stale copy would answer that wrongly the
 * moment somebody else saves.
 *
 * Visible to anyone who can see the page it sits on: every GET behind `sections` is open to any
 * allowlisted caller (SecurityConfig), so this component adds no gating of its own -- the edit
 * trails are disclosure, not a permissioned feature.
 *
 * `sections`: [{ title, fetch: () => Promise<entries>, emptyText, kind: "change" | "import" }]
 */
export default function HistoryButton({ title, sections, label = "History" }) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState(null);
  const { showToast } = useToast();

  const handleOpen = async (e) => {
    e.stopPropagation();
    setOpen(true);
    setLoading(true);
    try {
      const results = await Promise.all(sections.map((s) => s.fetch()));
      setData(results);
    } catch (err) {
      setData(sections.map(() => []));
      showToast("Couldn't load history.", "error");
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className="btn icon-btn secondary"
        title={label}
        aria-label={label}
        onClick={handleOpen}
      >
        <ClockIcon style={{ marginRight: 0 }} />
      </button>

      {open && (
        <Modal title={title} onClose={() => setOpen(false)} width={560} closeIcon>
          {sections.map((section, i) => (
            <HistorySection
              key={section.title}
              title={section.title}
              entries={loading ? null : data?.[i]}
              loading={loading}
              emptyText={section.emptyText}
              Row={section.kind === "import" ? PairImportRow : ChangeLogRow}
            />
          ))}
        </Modal>
      )}
    </>
  );
}
