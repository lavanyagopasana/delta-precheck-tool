import React, { useRef, useState } from "react";
import { importWorkspacePairsCsvForCombination } from "../api/client";
import Modal from "./Modal";
import { SwapIcon, UploadIcon, CheckIcon } from "./Icons";

// One catalog per product type, feeding both the Source and Destination dropdowns -- the same list on
// each side, since most platforms can be either (Box to Box and SharePoint to SharePoint are real
// migrations, across tenants).
//
// CONTENT is the 13 distinct platforms from the supported-combination list confirmed on 2026-08-07.
// EMAIL is Gmail and Outlook only (2026-08-06). MESSAGE is still a placeholder awaiting its real set.
//
// Note this offers every source/destination pairing, not only the 51 supported ones -- the two
// dropdowns are independent by design. If a pairing needs to be blocked, that belongs here as a
// source -> allowed-destinations map, not as a longer flat list.
const OPTIONS_BY_PRODUCT_TYPE = {
  CONTENT: [
    "Amazon S3",
    "Amazon workdocs",
    "Azure",
    "Box",
    "Citrix",
    "Dropbox",
    "Egnyte",
    "MyDrive",
    "NFS",
    "OneDrive",
    "Shared Drive",
    "Sharefile",
    "SharePoint",
  ],
  EMAIL: ["Gmail", "Outlook"],
  MESSAGE: ["Slack", "Microsoft Teams", "Google Chat"],
};

function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// Adding a combination is a one-shot popup (source/destination + CSV file) rather than an
// always-visible draft row -- it only ever creates a *new* combination, so it closes itself as
// soon as the add succeeds and the caller's reload picks it up as a normal persisted row. Picking a
// file only stages it locally; nothing is sent to the backend until "Add Combination" is clicked, so
// choosing the wrong file doesn't immediately create a combination. The CSV format differs per product
// type, so it isn't restated here -- the server card's "CSV format" link opens ServerUrlsPanel's
// CsvFormatModal for this server's own shape, sample download included. Source/destination options are
// scoped to the server's product type -- set a product type on the server to enable these dropdowns.
export default function AddCombinationModal({ server, open, onClose, onSaved }) {
  const [source, setSource] = useState("");
  const [destination, setDestination] = useState("");
  const [file, setFile] = useState(null);
  const [dragOver, setDragOver] = useState(false);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState(null);
  const fileInputRef = useRef(null);

  if (!open) return null;

  const options = OPTIONS_BY_PRODUCT_TYPE[server.productType] || [];
  const combination = source && destination ? `${source} to ${destination}` : "";
  const canAdd = !!source && !!destination && !!file && !adding;

  const clearFile = () => {
    setFile(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const handleFileChange = (e) => {
    setFile(e.target.files[0] || null);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    const dropped = e.dataTransfer.files[0];
    if (dropped) setFile(dropped);
  };

  const handleAdd = async () => {
    if (!canAdd) return;
    setAdding(true);
    setError(null);
    try {
      await importWorkspacePairsCsvForCombination(server.serverId, combination, file);
      onSaved();
      onClose();
    } catch (err) {
      setError(err.response?.data?.message || "Import failed.");
    } finally {
      setAdding(false);
    }
  };

  const handleClose = () => {
    setSource("");
    setDestination("");
    clearFile();
    setError(null);
    setDragOver(false);
    onClose();
  };

  return (
    <Modal title={`Add a combination — ${server.serverName}`} onClose={handleClose} width={520} closeIcon>
      {!options.length && (
        <div className="inline-hint" style={{ marginBottom: 16 }}>
          Set a product type on this server to see source/destination options.
        </div>
      )}

      <div style={{ display: "flex", alignItems: "flex-end", gap: 10 }}>
        <div style={{ flex: 1 }}>
          <label style={{ display: "block", fontSize: 12, fontWeight: 600, color: "var(--color-text-muted)", marginBottom: 6 }}>
            Source
          </label>
          <select
            value={source}
            onChange={(e) => setSource(e.target.value)}
            disabled={!options.length}
            style={{ width: "100%" }}
          >
            <option value="">Select...</option>
            {options.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
        </div>

        <SwapIcon
          size={16}
          style={{ marginRight: 0, marginBottom: 10, color: "var(--color-text-faint)", flexShrink: 0 }}
        />

        <div style={{ flex: 1 }}>
          <label style={{ display: "block", fontSize: 12, fontWeight: 600, color: "var(--color-text-muted)", marginBottom: 6 }}>
            Destination
          </label>
          <select
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
            disabled={!options.length}
            style={{ width: "100%" }}
          >
            <option value="">Select...</option>
            {options.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div style={{ marginTop: 20 }}>
        <label style={{ display: "block", fontSize: 12, fontWeight: 600, color: "var(--color-text-muted)", marginBottom: 6 }}>
          Migration Pairs CSV
        </label>
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
                <div style={{ fontSize: 11.5, color: "var(--color-text-faint)" }}>{formatFileSize(file.size)}</div>
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
                onClick={clearFile}
              >
                &times;
              </button>
            </>
          ) : (
            <>
              <UploadIcon size={20} style={{ marginRight: 0, color: "var(--color-text-faint)" }} />
              <label style={{ cursor: "pointer" }}>
                <span style={{ color: "var(--color-primary)", fontWeight: 600 }}>Upload CSV</span>
                {" "}or drag and drop it here
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".csv"
                  onChange={handleFileChange}
                  style={{ display: "none" }}
                />
              </label>
            </>
          )}
        </div>
      </div>

      {!(source && destination) && options.length > 0 && (
        <div style={{ fontSize: 12, color: "var(--color-text-faint)", marginTop: 10 }}>
          Select a source, a destination, and upload a CSV file to enable adding.
        </div>
      )}
      {error && <div className="inline-hint" style={{ marginTop: 10 }}>{error}</div>}
      <div className="form-actions">
        <button className="btn" disabled={!canAdd} onClick={handleAdd}>
          {adding ? "Adding..." : "Add Combination"}
        </button>
      </div>
    </Modal>
  );
}
