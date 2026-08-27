import React from "react";
import { TableIcon } from "./Icons";

/**
 * The migration process status pulled from Metabase, shown at the bottom of a project's page --
 * below the servers, because it is a rollup of the whole project rather than something you act on
 * per server.
 *
 * Right now this renders the frame and nothing else: the Metabase query/export is not wired up yet
 * (no auth, no database mapping, no card ids), so `imageUrl` is always null and the panel says so
 * out loud. That is deliberate over faking a chart -- a placeholder that looks like real data is how
 * somebody ends up approving a Delta against numbers nobody fetched.
 *
 * When Metabase is connected, the only change here is passing a real `imageUrl` (Metabase renders a
 * saved question straight to PNG at /api/card/{id}/query/png, so the seam stays an <img>).
 */
export default function MetabaseStatusPanel({ databaseName, imageUrl, loading, error, onClose }) {
  return (
    <div className="card" style={{ marginTop: 20 }}>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <h3 className="section-title" style={{ marginBottom: 0 }}>
          Migration process status
          {databaseName && <span className="badge gray">{databaseName}</span>}
        </h3>
        <button
          type="button"
          className="btn secondary"
          style={{ padding: "6px 14px", fontSize: 12.5 }}
          onClick={onClose}
        >
          Hide
        </button>
      </div>

      <div style={{ marginTop: 16 }}>
        {loading ? (
          <p className="empty-state">
            <span className="spinner" style={{ marginRight: 8 }} />
            Fetching from Metabase...
          </p>
        ) : error ? (
          <div className="inline-hint">{error}</div>
        ) : imageUrl ? (
          <img
            src={imageUrl}
            alt={`Migration process status for ${databaseName || "this project"}`}
            style={{ maxWidth: "100%", display: "block", borderRadius: "var(--radius-sm)" }}
          />
        ) : (
          // The frame with nothing in it yet. Dashed border so it reads as "pending wiring" rather
          // than as a chart that failed to load.
          <div
            style={{
              border: "1px dashed var(--color-border)",
              borderRadius: "var(--radius-sm)",
              padding: "38px 24px",
              textAlign: "center",
              color: "var(--color-text-muted)",
            }}
          >
            <TableIcon size={20} style={{ marginRight: 0, color: "var(--color-text-faint)" }} />
            <p style={{ margin: "10px 0 0", fontSize: 13.5, fontWeight: 600 }}>
              The status image will load here.
            </p>
            <p style={{ margin: "6px 0 0", fontSize: 12.5 }}>
              Reading <strong>{databaseName}</strong> from Metabase isn't connected yet, so there is
              nothing to show. Processed and conflict counts appear here once it is.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
