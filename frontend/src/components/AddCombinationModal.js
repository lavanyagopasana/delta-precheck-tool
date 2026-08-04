import React, { useRef, useState } from "react";
import { importWorkspacePairsCsvForCombination } from "../api/client";
import Modal from "./Modal";

// Adding a combination is a one-shot popup (name + CSV file, side by side in one row) rather than
// an always-visible draft row -- it only ever creates a *new* combination, so it closes itself as
// soon as the add succeeds and the caller's reload picks it up as a normal persisted row. Picking a
// file only stages it locally; nothing is sent to the backend until "Add Combination" is clicked, so
// choosing the wrong file doesn't immediately create a combination. The CSV format is identical for
// every combination, so "View CSV format"/"Download sample CSV" live once above the server list (see
// ServerUrlsPanel's CsvFormatHelp) instead of being repeated here. Shared by ServerUrlsPanel (per
// server, on the project page) and ServerDetailsPage (per server, on the combination detail page).
export default function AddCombinationModal({ server, open, onClose, onSaved }) {
  const [combination, setCombination] = useState("");
  const [file, setFile] = useState(null);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState(null);
  const fileInputRef = useRef(null);

  if (!open) return null;

  const combinationEntered = combination.trim().length > 0;
  const canAdd = combinationEntered && !!file && !adding;

  const handleFileChange = (e) => {
    setFile(e.target.files[0] || null);
  };

  const handleAdd = async () => {
    if (!canAdd) return;
    setAdding(true);
    setError(null);
    try {
      await importWorkspacePairsCsvForCombination(server.serverId, combination.trim(), file);
      onSaved();
      onClose();
    } catch (err) {
      setError(err.response?.data?.message || "Import failed.");
    } finally {
      setAdding(false);
    }
  };

  const handleClose = () => {
    setCombination("");
    setFile(null);
    setError(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
    onClose();
  };

  return (
    <Modal title={`Add a combination — ${server.serverName}`} onClose={handleClose} width={480} closeIcon>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 10 }}>
        <div style={{ flex: 1 }}>
          <label style={{ display: "block", fontSize: 12, fontWeight: 600, color: "var(--color-text-muted)", marginBottom: 6 }}>
            Combination
          </label>
          <input
            type="text"
            placeholder="Combination (e.g. Google Drive -> OneDrive)"
            value={combination}
            onChange={(e) => setCombination(e.target.value)}
            style={{ width: "100%" }}
          />
        </div>
        <label className="btn secondary" style={{ cursor: "pointer", whiteSpace: "nowrap" }}>
          Choose CSV
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            onChange={handleFileChange}
            style={{ display: "none" }}
          />
        </label>
      </div>
      {file && (
        <div style={{ fontSize: 12.5, color: "var(--color-text-muted)", marginTop: 8 }}>
          Selected file: <strong>{file.name}</strong>
        </div>
      )}
      {!combinationEntered && (
        <div style={{ fontSize: 12, color: "var(--color-text-faint)", marginTop: 8 }}>
          Enter a combination name and choose a CSV file to enable adding.
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
