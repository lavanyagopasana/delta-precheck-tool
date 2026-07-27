import React, { useRef, useState } from "react";

export default function CsvImportPanel({ title, columns, sampleRow, onUpload, onImported }) {
  const [showSchema, setShowSchema] = useState(false);
  const [importing, setImporting] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const fileInputRef = useRef(null);

  const handleFileChange = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
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
          Imported {result.totalRows} row(s): {result.createdCount} created, {result.updatedCount} updated.
          {result.errors.length > 0 && (
            <ul style={{ color: "var(--color-red)", marginTop: 6 }}>
              {result.errors.map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      {error && <div className="inline-hint" style={{ marginTop: 14 }}>{error}</div>}
    </div>
  );
}
