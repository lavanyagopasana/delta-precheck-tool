import React, { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  createServerForProject,
  updateServerProductType,
  importWorkspacePairsCsvForCombination,
  deletePairsByCombination,
  decommissionServer,
  SAMPLE_CSV_COLUMNS_COMBINATION,
  sampleCsvColumnsForProductType,
  usesTwoColumnCsv,
} from "../api/client";
import { useToast } from "./Toast";
import { useConfirm } from "./ConfirmDialog";
import Modal from "./Modal";
import AddCombinationModal from "./AddCombinationModal";
import DeltaBadge from "./DeltaBadge";
import { DownloadIcon, UploadIcon, TrashIcon, PlusIcon, SwapIcon, ServerIcon, EditIcon, TableIcon, CheckIcon } from "./Icons";
import { downloadSampleCsv, downloadCsv } from "../utils/csv";
import { groupByCombination } from "../utils/pairs";

const PRODUCT_TYPE_OPTIONS = [
  { value: "MESSAGE", label: "Message" },
  { value: "EMAIL", label: "Email" },
  { value: "CONTENT", label: "Content" },
];

// The CSV shape per product type: header columns plus one example row, kept together so the two can
// never disagree about column count (a 4-column header over a 2-value row writes a broken sample file).
//
// Email and Message both take just the two accounts -- neither moves a folder tree, so there is no
// path to give. Only Content carries source/destination paths.
const TWO_COLUMN_SHAPE = {
  columns: sampleCsvColumnsForProductType("EMAIL"),
  row: ["jane.doe@source-tenant.com", "jane.doe@company.com"],
};

const CSV_SHAPES = {
  CONTENT: {
    label: "Content",
    columns: SAMPLE_CSV_COLUMNS_COMBINATION,
    row: ["jane.doe@source-tenant.com", "/jane.doe/My Drive", "jane.doe@company.com", "/sites/migrated/jane.doe"],
  },
  EMAIL: { label: "Email", ...TWO_COLUMN_SHAPE },
  MESSAGE: { label: "Message", ...TWO_COLUMN_SHAPE },
};

function csvSampleFor(productType) {
  return CSV_SHAPES[productType] || CSV_SHAPES.CONTENT;
}

// Values for a single pair row, matched to the shape above so an export's rows always line up with its
// header. Email omits the path fields rather than exporting two permanently empty columns.
function csvRowForPair(productType, pair) {
  return usesTwoColumnCsv(productType)
    ? [pair.sourceEmail, pair.destinationEmail]
    : [pair.sourceEmail, pair.sourcePath, pair.destinationEmail, pair.destinationPath];
}

function slugFor(combination) {
  return combination.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

function exportFileNameFor(combination) {
  return `${slugFor(combination)}-export.csv`;
}

const PRODUCT_TYPE_LABELS = PRODUCT_TYPE_OPTIONS.reduce((acc, opt) => ({ ...acc, [opt.value]: opt.label }), {});

// Rendered as a popup (triggered by the "Add Server" button next to the project title in
// ProjectDetailsPage) rather than an always-visible card, so the page isn't showing an empty add
// form by default -- open/onClose are fully controlled by the parent.
function AddServerModal({ project, canManage, onSaved, open, onClose }) {
  const showToast = useToast();
  const [productType, setProductType] = useState("");
  const [newServerUrl, setNewServerUrl] = useState("");
  const [addingServer, setAddingServer] = useState(false);
  const [serverError, setServerError] = useState(null);

  if (!canManage || !open) return null;

  const handleAddServer = async () => {
    const trimmed = newServerUrl.trim();
    if (!trimmed) return;
    setAddingServer(true);
    setServerError(null);
    try {
      await createServerForProject(project.id, trimmed, productType);
      showToast(`Server "${trimmed}" added.`);
      setNewServerUrl("");
      setProductType("");
      onSaved();
      onClose();
    } catch (err) {
      setServerError(err.response?.data?.message || "Failed to add server.");
    } finally {
      setAddingServer(false);
    }
  };

  return (
    <Modal title="Add a server" onClose={onClose} width={440} closeIcon>
      <div>
        <label style={{ display: "block", fontSize: 12, fontWeight: 600, color: "var(--color-text-muted)", marginBottom: 6 }}>
          Product Type
        </label>
        <select
          value={productType}
          onChange={(e) => setProductType(e.target.value)}
          style={{ width: "100%" }}
        >
          <option value="">Select...</option>
          {PRODUCT_TYPE_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>
      <div style={{ marginTop: 14 }}>
        <label style={{ display: "block", fontSize: 12, fontWeight: 600, color: "var(--color-text-muted)", marginBottom: 6 }}>
          Server URL
        </label>
        <input
          type="text"
          placeholder="https://..."
          value={newServerUrl}
          onChange={(e) => setNewServerUrl(e.target.value)}
          style={{ width: "100%" }}
        />
      </div>
      {serverError && <div className="inline-hint" style={{ marginTop: 10 }}>{serverError}</div>}
      <div className="form-actions">
        <button className="btn" disabled={addingServer || !newServerUrl.trim()} onClick={handleAddServer}>
          <PlusIcon /> {addingServer ? "Adding..." : "Add"}
        </button>
      </div>
    </Modal>
  );
}

/**
 * The CSV formats in use across a project, in a popup.
 *
 * Two earlier versions were wrong in opposite directions. A single inline table above the server list
 * expanded a full-width block that buried the servers, and showed one shape for a project that might
 * have several. Moving it to a button per server card fixed the correctness but repeated the identical
 * table once per server -- four Content servers, four buttons, one format.
 *
 * The shape is a property of the product type, not of the server, so this groups by shape: one button
 * for the project, one section per distinct format actually present, each with its own sample download.
 */
function CsvFormatModal({ servers, onClose }) {
  // One entry per DISTINCT shape in the project, not per server. Four Content servers share one format,
  // so repeating the identical table four times (and putting a button on every card to reach it) was
  // noise. Servers with no product type fall back to the Content shape, so they collapse into that same
  // entry rather than producing a duplicate.
  // Deduped by the COLUMNS, not the product-type label. Email and Message have identical shapes, so
  // keying on the label rendered the same two-column table twice under two headings -- the same
  // repetition that moved this out of the per-server cards in the first place. Types that share a
  // shape share one section, named for all of them ("Email / Message").
  const shapes = [];
  servers.forEach((s) => {
    const shape = csvSampleFor(s.productType);
    const signature = shape.columns.join("|");
    const existing = shapes.find((sh) => sh.signature === signature);
    if (existing) {
      if (!existing.labels.includes(shape.label)) {
        existing.labels.push(shape.label);
      }
    } else {
      shapes.push({ ...shape, signature, labels: [shape.label] });
    }
  });

  return (
    <Modal
      title={shapes.length > 1 ? "CSV formats" : `CSV format — ${shapes[0]?.labels.join(" / ")}`}
      onClose={onClose}
      width={720}
      closeIcon
    >
      {shapes.map((shape) => (
        <div key={shape.signature} style={{ marginBottom: 18 }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 10,
              marginBottom: 8,
              flexWrap: "wrap",
            }}
          >
            <strong style={{ fontSize: 13.5 }}>{shape.labels.join(" / ")}</strong>
            <button
              type="button"
              className="btn secondary"
              style={{ padding: "5px 12px", fontSize: 12 }}
              onClick={() =>
                downloadSampleCsv(
                  shape.columns,
                  shape.row,
                  `${shape.labels.join("-").toLowerCase()}-migration-pairs-sample.csv`
                )
              }
            >
              <DownloadIcon size={13} /> Download sample
            </button>
          </div>
          {/* csv-format-table lets the sample values wrap. The Content shape is four columns and its
              DESTINATION_PATH sample ran past the modal's width, so the last example was cut off by
              default -- in the one popup whose entire job is to show people the columns. Wrapping is
              the fix rather than a wider modal: a wider modal only moves the threshold, and these are
              paths, which can be arbitrarily long. */}
          <div style={{ overflowX: "auto" }}>
            <table className="csv-format-table">
              <thead>
                <tr>
                  {shape.columns.map((col) => (
                    <th key={col}>{col}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                <tr>
                  {shape.row.map((cell, i) => (
                    <td key={i}>{cell}</td>
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
          {!shape.labels.includes("Content") && (
            <div style={{ fontSize: 12, color: "var(--color-text-faint)", marginTop: 6 }}>
              Only the two accounts — there are no source or destination paths.
            </div>
          )}
        </div>
      ))}
    </Modal>
  );
}

/**
 * Re-upload a combination's CSV, in a popup.
 *
 * Replaces an inline CsvImportPanel that expanded underneath the combination row. That panel carried
 * its own "View CSV format" and "Download sample CSV" buttons, which now duplicate the single CSV
 * format button in the Server URLs header -- the same reference table, reachable two ways, one of them
 * pushing the whole server list down the page. This is upload only: drag a file or browse, see what
 * happened, done.
 */
function ReuploadCsvModal({ server, combination, onUpload, onImported, onClose }) {
  const [file, setFile] = useState(null);
  const [dragOver, setDragOver] = useState(false);
  const [importing, setImporting] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const fileInputRef = useRef(null);

  const handleUpload = async () => {
    if (!file || importing) return;
    setImporting(true);
    setError(null);
    setResult(null);
    try {
      const res = await onUpload(file);
      setResult(res);
      onImported();
    } catch (err) {
      setError(err.response?.data?.message || "Import failed.");
    } finally {
      setImporting(false);
    }
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    if (importing) return;
    const dropped = e.dataTransfer.files[0];
    if (dropped) setFile(dropped);
  };

  return (
    <Modal title={`Re-upload CSV — ${combination}`} onClose={onClose} width={560} closeIcon>
      <p style={{ marginTop: 0, fontSize: 13, color: "var(--color-text-muted)" }}>
        Replaces the migration pairs for this combination on <strong>{server.serverName}</strong>. Rows
        that already exist exactly as-is are skipped rather than duplicated.
      </p>

      <div
        className={`precheck-dropzone-lg${dragOver ? " drag-over" : ""}`}
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
      >
        {file ? (
          <>
            <CheckIcon size={18} style={{ marginRight: 0, color: "var(--color-green)" }} />
            <div style={{ textAlign: "left" }}>
              <div style={{ fontWeight: 600, fontSize: 13.5 }}>{file.name}</div>
              <div style={{ fontSize: 11.5, color: "var(--color-text-faint)" }}>
                {(file.size / 1024).toFixed(1)} KB
              </div>
            </div>
            <button
              type="button"
              className="modal-close-icon"
              style={{
                width: 26,
                height: 26,
                marginLeft: "auto",
                color: "var(--color-red)",
                borderColor: "var(--color-red)",
                background: "var(--color-red-soft)",
              }}
              title="Remove file"
              aria-label="Remove file"
              onClick={() => {
                setFile(null);
                if (fileInputRef.current) fileInputRef.current.value = "";
              }}
            >
              &times;
            </button>
          </>
        ) : (
          <>
            <UploadIcon size={20} style={{ marginRight: 0, color: "var(--color-text-faint)" }} />
            <label style={{ cursor: "pointer" }}>
              <span style={{ color: "var(--color-primary)", fontWeight: 600 }}>Choose a CSV</span> or drag
              and drop it here
              <input
                ref={fileInputRef}
                type="file"
                accept=".csv"
                onChange={(e) => setFile(e.target.files[0] || null)}
                style={{ display: "none" }}
              />
            </label>
          </>
        )}
      </div>

      {result && (
        <div className="inline-success" style={{ marginTop: 14, display: "block" }}>
          Imported {result.totalRows} row(s): {result.createdCount} created, {result.updatedCount} updated
          {result.duplicateCount > 0 && `, ${result.duplicateCount} duplicate(s) skipped`}.
          {result.duplicates?.length > 0 && (
            <ul style={{ margin: "8px 0 0", paddingLeft: 18, maxHeight: 160, overflowY: "auto" }}>
              {result.duplicates.map((d, i) => (
                <li key={i} style={{ fontSize: 12.5, marginBottom: 3 }}>{d}</li>
              ))}
            </ul>
          )}
          {result.errors?.length > 0 && (
            <ul style={{ color: "var(--color-red)", marginTop: 6, paddingLeft: 18 }}>
              {result.errors.map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      {error && <div className="inline-hint" style={{ marginTop: 14 }}>{error}</div>}

      <div className="form-actions">
        <button className="btn" disabled={!file || importing} onClick={handleUpload}>
          <UploadIcon /> {importing ? "Importing…" : "Upload CSV"}
        </button>
      </div>
    </Modal>
  );
}

// A combination row only exists once its CSV is actually uploaded -- ServerCard derives it fresh
// from the server's real data on every render (see groupByCombination below), so it keeps showing
// up correctly after a reload.
function CombinationRow({ server, row, isAdmin, canManage, onSaved }) {
  const navigate = useNavigate();
  const showToast = useToast();
  const confirm = useConfirm();
  const [reuploading, setReuploading] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const handleUpload = async (file) => {
    const result = await importWorkspacePairsCsvForCombination(server.serverId, row.combination.trim(), file);
    onSaved();
    setReuploading(false);
    return result;
  };

  // Exports the migration pairs actually uploaded under this combination -- not the sample
  // template (that's still available above via "Download sample CSV").
  // Exported columns follow the server's product type, so an Email export doesn't ship two
  // always-empty path columns that then look like missing data when reopened.
  const handleDownload = () => {
    const { columns } = csvSampleFor(server.productType);
    const rows = (row.pairs || []).map((p) => csvRowForPair(server.productType, p));
    downloadCsv(columns, rows, exportFileNameFor(row.combination));
  };

  const handleDelete = async () => {
    const ok = await confirm({
      title: `Delete combination "${row.combination}"?`,
      message: `This permanently removes every migration pair imported under this combination for ${server.serverName}. This cannot be undone.`,
      confirmLabel: "Delete Combination",
      danger: true,
    });
    if (!ok) return;
    setDeleting(true);
    try {
      await deletePairsByCombination(server.serverId, row.combination);
      showToast(`Combination "${row.combination}" deleted.`);
      onSaved();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to delete combination.", "error");
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="combo-row">
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <div
          style={{ display: "flex", alignItems: "center", gap: 10, minWidth: 0, cursor: "pointer" }}
          onClick={() => navigate(`/servers/${server.serverId}?combination=${encodeURIComponent(row.combination)}`)}
          title={`View ${row.combination} migration pairs`}
        >
          <SwapIcon style={{ marginRight: 0, color: "var(--color-text-muted)" }} />
          <span style={{ fontSize: 13.5, fontWeight: 600 }}>{row.combination}</span>
          {row.pairCount != null && (
            <span className="badge gray">
              {row.pairCount} pair{row.pairCount === 1 ? "" : "s"}
            </span>
          )}
          {/* Where this combination's Delta stands, inline -- the pair count alone said nothing about
              whether the migration had started, finished, or is on its third pre-delta. */}
          {/* One badge for every state. currentDeltaLabel now carries the phase ("Pre-Delta 1 started",
              "Final Delta completed" -- see DeltaType.labelWithPhase), so the separate "Complete" pill
              this used to special-case is redundant: DeltaBadge already renders FINAL_DELTA purple.
              Null label means the pre-check hasn't been submitted, so nothing has settled the cycle's
              type yet and no badge is right. */}
          {row.summary?.currentDeltaLabel && (
            <DeltaBadge
              deltaType={row.summary.currentDeltaType}
              deltaPhase={row.summary.deltaPhase}
              label={row.summary.currentDeltaLabel}
            />
          )}
          {/* "1 delta done" rather than "1 done" -- next to a pair count and a stage badge, a bare
              "done" read as though the combination itself was finished. Green because it's completed
              work: the previous faint grey made real progress look like incidental metadata. Kept as
              text rather than a third badge so it doesn't compete with the pair count and stage pills. */}
          {!row.summary?.finalDeltaComplete && row.summary?.completedCycleCount > 0 && (
            <span style={{ fontSize: 11.5, fontWeight: 600, color: "var(--color-green)" }}>
              {row.summary.completedCycleCount} delta{row.summary.completedCycleCount === 1 ? "" : "s"} done
            </span>
          )}
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          <button className="btn icon-btn secondary" title="Download CSV" aria-label="Download CSV" onClick={handleDownload}>
            <DownloadIcon style={{ marginRight: 0 }} />
          </button>
          {/* Download stays available to everyone who can see the combination -- reading the pair list
              is exactly what a reviewing lead needs. Re-upload replaces it, so it's manage-only. */}
          {canManage && (
            <button
              className="btn icon-btn secondary"
              title="Re-upload CSV"
              aria-label="Re-upload CSV"
              onClick={() => setReuploading((v) => !v)}
            >
              <UploadIcon style={{ marginRight: 0 }} />
            </button>
          )}
          {isAdmin && (
            <button
              className="btn icon-btn danger"
              title="Delete combination"
              aria-label="Delete combination"
              onClick={handleDelete}
              disabled={deleting}
            >
              <TrashIcon style={{ marginRight: 0 }} />
            </button>
          )}
        </div>
      </div>

      {reuploading && (
        <ReuploadCsvModal
          server={server}
          combination={row.combination}
          onUpload={handleUpload}
          onImported={onSaved}
          onClose={() => setReuploading(false)}
        />
      )}
    </div>
  );
}

// Product type is chosen once, when the server is added -- this badge is a static label for
// everyone. Only an admin gets a pencil icon to correct it afterward (e.g. it was set wrong, or
// wasn't set at all before this field existed).
function ServerProductTypeBadge({ server, isAdmin, onSaved }) {
  const showToast = useToast();
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);

  const handleChange = async (e) => {
    const value = e.target.value;
    setSaving(true);
    try {
      await updateServerProductType(server.serverId, value || null);
      onSaved();
      setEditing(false);
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to update product type.", "error");
    } finally {
      setSaving(false);
    }
  };

  if (editing) {
    return (
      <select
        autoFocus
        value={server.productType || ""}
        disabled={saving}
        onChange={handleChange}
        onBlur={() => setEditing(false)}
        onClick={(e) => e.stopPropagation()}
        style={{ fontSize: 12 }}
      >
        <option value="">Select...</option>
        {PRODUCT_TYPE_OPTIONS.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>
    );
  }

  // Neutral, not blue: the product type never changes and isn't a status, so it shouldn't compete with
  // the Delta phase badge sitting beside it. Colour is reserved for things that move.
  if (!isAdmin) {
    return server.productType ? (
      <span className="badge gray">{PRODUCT_TYPE_LABELS[server.productType] || server.productType}</span>
    ) : null;
  }

  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
      {/* Gray whether set or not -- the admin view showed the same label in blue, so the two views
          disagreed on how loud the product type should be. Yellow when unset, because an untyped server
          silently gets the Content checklist and CSV shape, which is worth flagging rather than muting. */}
      <span className={`badge ${server.productType ? "gray" : "yellow"}`}>
        {server.productType ? PRODUCT_TYPE_LABELS[server.productType] || server.productType : "No product type"}
      </span>
      <button
        type="button"
        className="btn icon-btn secondary"
        title="Edit product type"
        aria-label="Edit product type"
        style={{ padding: 4, width: 22, height: 22 }}
        onClick={(e) => {
          e.stopPropagation();
          setEditing(true);
        }}
      >
        <EditIcon size={12} style={{ marginRight: 0 }} />
      </button>
    </span>
  );
}

// Admin-only, and deliberately destructive: decommissioning ERASES the server and everything under it
// (see ServerService.decommission). There is no undo, so the confirm spells out exactly what goes
// rather than asking a generic "are you sure?". Rendered for every server rather than only ready ones,
// disabled with the reason as its tooltip -- an admin looking for the action shouldn't have to guess
// whether it's missing because of permissions or because work is outstanding.
function DecommissionButton({ server, onSaved }) {
  const [busy, setBusy] = useState(false);
  const showToast = useToast();
  const confirm = useConfirm();

  const ready = Boolean(server.decommissionReady);

  const handleClick = async () => {
    // Leads with the consequence, then lists exactly what goes, then the irreversibility. The previous
    // version ran all three together in one block that read as boilerplate -- easy to click past on the
    // one action in this app that cannot be undone.
    const ok = await confirm({
      title: `Permanently erase ${server.serverName}?`,
      message:
        "This deletes the server and everything under it:\n\n" +
        "•  All combinations and migration pairs\n" +
        "•  Every pre-check item and uploaded evidence file\n" +
        "•  The full sign-off history and Delta cycles\n" +
        "•  All tickets raised against it\n\n" +
        "This cannot be undone. Export anything you need to keep before continuing.",
      confirmLabel: "Erase server",
      danger: true,
    });
    if (!ok) return;

    setBusy(true);
    try {
      await decommissionServer(server.serverId);
      showToast(`${server.serverName} decommissioned and erased.`, "success");
      onSaved();
    } catch (err) {
      showToast(err?.response?.data?.message || "Could not decommission this server.", "error");
    } finally {
      setBusy(false);
    }
  };

  // A server with no combinations is never ready either: ServerService.allFinalDeltasComplete requires
  // a non-empty list, so a freshly created server can't report itself decommissionable. That rule is
  // deliberate and unchanged -- but it used to share the "Final Deltas still outstanding" message,
  // which is plainly untrue when there is nothing on the server at all. It named a blocker that didn't
  // exist and gave no hint what to do about it. Only the two cases are told apart here.
  const disabledReason =
    (server.combinations || []).length === 0
      ? "Nothing to decommission — this server has no combinations yet"
      : "Final Deltas still outstanding";

  // Solid red, same treatment as the delete-combination button, because it does the same kind of
  // thing -- it erases the server and everything under it. An outlined secondary button understated
  // the most destructive action in the app.
  //
  // Tooltips are kept short on purpose: a native title renders as one unwrapped strip, so the previous
  // "Every combination on this server must complete its Final Delta first" stretched most of the way
  // across the window. The disabled state carries the short version; the full explanation lives in the
  // confirm dialog, where there is room for it.
  return (
    <button
      type="button"
      className={ready ? "btn danger" : "btn secondary"}
      style={{ padding: "6px 14px", fontSize: 12.5, whiteSpace: "nowrap" }}
      disabled={!ready || busy}
      title={ready ? "Erase this server permanently" : disabledReason}
      onClick={handleClick}
    >
      {busy ? (
        <>
          <span className="spinner" style={{ marginRight: 6 }} />
          Erasing…
        </>
      ) : (
        <>
          <TrashIcon size={13} />
          Decommission
        </>
      )}
    </button>
  );
}

function ServerCard({ server, isAdmin, canManage, onSaved }) {
  const [showAddCombination, setShowAddCombination] = useState(false);

  // The combinations that actually have uploaded pairs -- derived fresh from the server's real
  // data every render, so they're correct after any reload, not just for the current session.
  const persistedGroups = groupByCombination(server.pairs || []);

  return (
    <div className="subpanel">
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <ServerIcon style={{ marginRight: 0, color: "var(--color-primary)" }} />
          <span
            style={{
              fontSize: 14,
              fontWeight: 600,
              textDecoration: server.decommissioned ? "line-through" : undefined,
              color: server.decommissioned ? "var(--color-text-muted)" : undefined,
            }}
          >
            {server.serverName}
          </span>
          <ServerProductTypeBadge server={server} isAdmin={isAdmin} onSaved={onSaved} />
          {/* Shown to every role, not just admins -- knowing a server's migration work is finished is
              useful to anyone working the project; only the action below it is admin-gated. There is no
              "Decommissioned" state to render anymore: decommissioning erases the server, so a
              decommissioned one no longer exists to be listed. */}
          {server.decommissionReady && <span className="badge green">Ready to decommission</span>}
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          {/* No CSV format button here. The shape is per product type, not per server, so a project with
              four Content servers repeated the same table behind four identical buttons. It lives once in
              the Server URLs header instead. */}
          {canManage && (
            <button
              type="button"
              className="btn secondary"
              style={{ padding: "6px 14px", fontSize: 12.5 }}
              onClick={() => setShowAddCombination(true)}
            >
              <PlusIcon /> Add combination
            </button>
          )}
          {/* Destructive action last, set slightly apart from the routine ones. Sitting flush between
              two things people click often made an irreversible erase easy to hit by accident. */}
          {isAdmin && (
            <span style={{ marginLeft: 6, display: "inline-flex" }}>
              <DecommissionButton server={server} onSaved={onSaved} />
            </span>
          )}
        </div>
      </div>
      {!persistedGroups.length && (
        <div style={{ fontSize: 12.5, color: "var(--color-text-faint)", marginTop: 10 }}>No combinations added yet.</div>
      )}
      {persistedGroups.map((g) => (
        <CombinationRow
          key={`persisted-${g.combination}`}
          server={server}
          row={{
            combination: g.combination,
            pairCount: g.pairs.length,
            pairs: g.pairs,
            // Combinations are derived from the pairs' free-text column, so the WorkspaceCombination
            // record carrying the Delta state has to be matched back by name (case-insensitively,
            // exactly as the backend does).
            summary: (server.combinations || []).find(
              (c) => c.name.trim().toLowerCase() === g.combination.trim().toLowerCase()
            ),
          }}
          isAdmin={isAdmin}
          canManage={canManage}
          onSaved={onSaved}
        />
      ))}

      <AddCombinationModal
        server={server}
        open={showAddCombination}
        onClose={() => setShowAddCombination(false)}
        onSaved={onSaved}
      />
    </div>
  );
}

export default function ServerUrlsPanel({ project, canManage, isAdmin, onSaved, showAddServer, onCloseAddServer }) {
  const [filterText, setFilterText] = useState("");
  const [productTypeFilter, setProductTypeFilter] = useState("ALL");
  const [showCsvFormat, setShowCsvFormat] = useState(false);

  // Deliberately NOT gated on canManage. This used to `return null` for anyone outside the project's
  // manager/creator/engineers, which meant a Dev Lead or QA Lead opening the project saw only the
  // Assignments card -- no servers, no combinations, no Delta state. They are the people asked to
  // approve that work, so they need to read it; canManage now only controls the write actions inside
  // (add server, add combination, CSV re-upload), which stay hidden for them.
  const servers = project.servers || [];
  const filteredServers = servers.filter(
    (s) =>
      s.serverName.toLowerCase().includes(filterText.trim().toLowerCase()) &&
      (productTypeFilter === "ALL" || s.productType === productTypeFilter)
  );

  return (
    <>
      <AddServerModal project={project} canManage={canManage} onSaved={onSaved} open={showAddServer} onClose={onCloseAddServer} />

      <div className="card">
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap" }}>
          <h3 className="section-title">
            Server URLs
            {!!servers.length && <span className="badge gray">{servers.length}</span>}
          </h3>
          {!!servers.length && (
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
              {/* One button for the whole project, opening a popup that lists each DISTINCT format in
                  use here. The CSV shape depends on product type, not on the individual server, so a
                  button per card repeated the same table once per server. */}
              <button
                type="button"
                className="btn secondary"
                style={{ padding: "8px 14px", fontSize: 12.5, whiteSpace: "nowrap" }}
                title="View the CSV columns used in this project"
                onClick={() => setShowCsvFormat(true)}
              >
                <TableIcon size={13} /> CSV format
              </button>
              <select
                value={productTypeFilter}
                onChange={(e) => setProductTypeFilter(e.target.value)}
                style={{ minWidth: 150 }}
              >
                <option value="ALL">All product types</option>
                {PRODUCT_TYPE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
              <input
                type="text"
                placeholder="Filter server URLs..."
                value={filterText}
                onChange={(e) => setFilterText(e.target.value)}
                style={{ width: 260 }}
              />
            </div>
          )}
        </div>

        {showCsvFormat && <CsvFormatModal servers={servers} onClose={() => setShowCsvFormat(false)} />}

        {!servers.length ? (
          <p className="empty-state">
            {canManage
              ? 'No servers yet. Use "Add Server" above to get started.'
              : "No servers have been added to this project yet."}
          </p>
        ) : !filteredServers.length ? (
          <p className="empty-state">No servers match the current filters.</p>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 14, marginTop: 18 }}>
            {filteredServers.map((server) => (
              <ServerCard
                key={server.serverId}
                server={server}
                isAdmin={isAdmin}
                canManage={canManage}
                onSaved={onSaved}
              />
            ))}
          </div>
        )}
      </div>
    </>
  );
}
