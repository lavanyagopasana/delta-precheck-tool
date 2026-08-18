import React, { useRef, useState } from "react";
import Modal from "./Modal";
import { downloadSampleCsv } from "../utils/csv";

export default function CsvImportPanel({ title, columns, sampleRow, onUpload, onImported, sampleFileName }) {
  const [showSchema, setShowSchema] = useState(false);
  const [importing, setImporting] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [showDuplicates, setShowDuplicates] = useState(false);
  const fileInputRef = useRef(null);

  // Build the same header + example row shown under "View CSV format" as a downloadable file, so
  // users can start from a correctly-shaped template instead of hand-typing the columns.
  const downloadSample = () => {
    const fileName =
      sampleFileName ||
      `${(title || "sample").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "")}-sample.csv`;
    downloadSampleCsv(columns, sampleRow, fileName);
  };

  const handleFileChange = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    setImporting(true);
    setError(null);
    setResult(null);
    try {
      const res = await onUpload(file);
      setResult(res);
      // Auto-open the duplicates popup so a re-uploaded file makes it obvious which rows were skipped.
      if (res?.duplicates?.length) setShowDuplicates(true);
      onImported();
    } catch (err) {
      setError(err.response?.data?.message || "Import failed.");
    } finally {
      setImporting(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  return (
    <div className="card">
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <strong style={{ fontSize: 14 }}>{title}</strong>
        <div style={{ display: "flex", gap: 10 }}>
          <button className="btn secondary" onClick={() => setShowSchema((s) => !s)}>
            {showSchema ? "Hide" : "View"} CSV format
          </button>
          <button className="btn secondary" onClick={downloadSample}>
            Download sample CSV
          </button>
          <label className="btn" style={{ cursor: importing ? "not-allowed" : "pointer" }}>
            {importing ? "Importing..." : "Upload CSV"}
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv"
              onChange={handleFileChange}
              disabled={importing}
              style={{ display: "none" }}
            />
          </label>
        </div>
      </div>

      {showSchema && (
        <div style={{ marginTop: 16, overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                {columns.map((col) => (
                  <th key={col}>{col}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr>
                {sampleRow.map((cell, i) => (
                  <td key={i}>{cell}</td>
                ))}
              </tr>
            </tbody>
          </table>
        </div>
      )}

      {result && (
        <div className="inline-success" style={{ marginTop: 14, display: "block" }}>
          Imported {result.totalRows} row(s): {result.createdCount} created, {result.updatedCount} updated
          {result.duplicateCount > 0 && `, ${result.duplicateCount} duplicate(s) skipped`}.
          {result.duplicateCount > 0 && (
            <button
              type="button"
              className="btn secondary"
              style={{ padding: "4px 10px", marginLeft: 10, fontSize: 12 }}
              onClick={() => setShowDuplicates(true)}
            >
              View duplicates
            </button>
          )}
          {result.errors?.length > 0 && (
            <ul style={{ color: "var(--color-red)", marginTop: 6 }}>
              {result.errors.map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      {error && <div className="inline-hint" style={{ marginTop: 14 }}>{error}</div>}

      {showDuplicates && result?.duplicates?.length > 0 && (
        <Modal title="Duplicate rows skipped" onClose={() => setShowDuplicates(false)} width={520} closeIcon>
          <p style={{ marginTop: 0, color: "var(--color-text-muted)", fontSize: 13 }}>
            These rows already existed exactly as-is, so they were not added again. Everything else in
            the file was imported.
          </p>
          {/* Suppressed from Hotjar recordings: each entry is "Row N: <sourceEmail> -> <destinationEmail>
              already exists (skipped)" (WorkspacePairService.recordDuplicate), so this list carries the
              same customer mailbox addresses the pairs table masks. Without this the suppression on the
              table is defeated by the import flow that produced it. errors[] above is deliberately NOT
              suppressed -- those strings carry row numbers and column names, never cell values. */}
          <ul
            style={{ margin: 0, paddingLeft: 18, maxHeight: 320, overflowY: "auto" }}
            data-hj-suppress=""
          >
            {result.duplicates.map((d, i) => (
              <li key={i} style={{ fontSize: 13, marginBottom: 4 }}>{d}</li>
            ))}
          </ul>
          <div className="form-actions" style={{ justifyContent: "flex-end", marginTop: 16 }}>
            <button type="button" className="btn" onClick={() => setShowDuplicates(false)}>
              Close
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
