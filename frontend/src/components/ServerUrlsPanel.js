import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  createServerForProject,
  importWorkspacePairsCsvForCombination,
  deletePairsByCombination,
  updateProjectDetails,
  SAMPLE_CSV_COLUMNS_COMBINATION,
} from "../api/client";
import { useToast } from "./Toast";
import { useConfirm } from "./ConfirmDialog";
import Modal from "./Modal";
import CsvImportPanel from "./CsvImportPanel";
import AddCombinationModal from "./AddCombinationModal";
import { DownloadIcon, UploadIcon, TrashIcon, PlusIcon, SwapIcon, ServerIcon } from "./Icons";
import { downloadSampleCsv } from "../utils/csv";
import { groupByCombination } from "../utils/pairs";

const PRODUCT_TYPE_OPTIONS = [
  { value: "MESSAGE", label: "Message" },
  { value: "EMAIL", label: "Email" },
  { value: "CONTENT", label: "Content" },
];

const SAMPLE_ROW_COMBINATION = [
  "jane.doe@source-tenant.com",
  "/jane.doe/My Drive",
  "jane.doe@company.com",
  "/sites/migrated/jane.doe",
];

function sampleFileNameFor(combination) {
  return `${combination.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "")}-sample.csv`;
}

const PRODUCT_TYPE_LABELS = PRODUCT_TYPE_OPTIONS.reduce((acc, opt) => ({ ...acc, [opt.value]: opt.label }), {});

// Rendered as a popup (triggered by the "Add Server" button next to the project title in
// ProjectDetailsPage) rather than an always-visible card, so the page isn't showing an empty add
// form by default -- open/onClose are fully controlled by the parent.
function AddServerModal({ project, canManage, onSaved, open, onClose }) {
  const showToast = useToast();
  const [productType, setProductType] = useState(project.productType || "");
  const [savingProductType, setSavingProductType] = useState(false);
  const [newServerUrl, setNewServerUrl] = useState("");
  const [addingServer, setAddingServer] = useState(false);
  const [serverError, setServerError] = useState(null);

  if (!canManage || !open) return null;

  const handleProductTypeChange = async (value) => {
    const previous = productType;
    setProductType(value);
    setSavingProductType(true);
    try {
      await updateProjectDetails(project.id, {
        name: project.name,
        productType: value,
        migrationManagerName: project.migrationManagerName || null,
      });
      showToast("Product type updated.");
      onSaved();
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to update product type.", "error");
      setProductType(previous);
    } finally {
      setSavingProductType(false);
    }
  };

  const handleAddServer = async () => {
    const trimmed = newServerUrl.trim();
    if (!trimmed) return;
    setAddingServer(true);
    setServerError(null);
    try {
      await createServerForProject(project.id, trimmed);
      showToast(`Server "${trimmed}" added.`);
      setNewServerUrl("");
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
          onChange={(e) => handleProductTypeChange(e.target.value)}
          disabled={savingProductType}
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

// Shown once above the server list (the CSV shape is identical for every combination) instead of
// repeating "View CSV format"/"Download sample CSV" inside every Add Combination popup.
function CsvFormatHelp() {
  const [showSchema, setShowSchema] = useState(false);

  const handleDownloadSample = () => {
    downloadSampleCsv(SAMPLE_CSV_COLUMNS_COMBINATION, SAMPLE_ROW_COMBINATION, "migration-pairs-sample.csv");
  };

  return (
    <div style={{ marginTop: 14 }}>
      <div style={{ display: "flex", gap: 10 }}>
        <button type="button" className="btn secondary" onClick={() => setShowSchema((s) => !s)}>
          {showSchema ? "Hide" : "View"} CSV format
        </button>
        <button type="button" className="btn secondary" onClick={handleDownloadSample}>
          Download sample CSV
        </button>
      </div>
      {showSchema && (
        <div style={{ marginTop: 12, overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                {SAMPLE_CSV_COLUMNS_COMBINATION.map((col) => (
                  <th key={col}>{col}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr>
                {SAMPLE_ROW_COMBINATION.map((cell, i) => (
                  <td key={i}>{cell}</td>
                ))}
              </tr>
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// A combination row only exists once its CSV is actually uploaded -- ServerCard derives it fresh
// from the server's real data on every render (see groupByCombination below), so it keeps showing
// up correctly after a reload.
function CombinationRow({ server, row, onSaved }) {
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

  const handleDownloadSample = () => {
    downloadSampleCsv(SAMPLE_CSV_COLUMNS_COMBINATION, SAMPLE_ROW_COMBINATION, sampleFileNameFor(row.combination));
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
          <strong style={{ fontSize: 13.5 }}>{row.combination}</strong>
          {row.pairCount != null && (
            <span className="badge gray">
              {row.pairCount} pair{row.pairCount === 1 ? "" : "s"}
            </span>
          )}
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          <button className="btn icon-btn secondary" title="Download sample CSV" aria-label="Download sample CSV" onClick={handleDownloadSample}>
            <DownloadIcon style={{ marginRight: 0 }} />
          </button>
          <button
            className="btn icon-btn secondary"
            title="Re-upload CSV"
            aria-label="Re-upload CSV"
            onClick={() => setReuploading((v) => !v)}
          >
            <UploadIcon style={{ marginRight: 0 }} />
          </button>
          <button
            className="btn icon-btn danger"
            title="Delete combination"
            aria-label="Delete combination"
            onClick={handleDelete}
            disabled={deleting}
          >
            <TrashIcon style={{ marginRight: 0 }} />
          </button>
        </div>
      </div>

      {reuploading && (
        <div style={{ marginTop: 10 }}>
          <CsvImportPanel
            title={`Re-upload CSV for "${row.combination}" on ${server.serverName}`}
            columns={SAMPLE_CSV_COLUMNS_COMBINATION}
            sampleRow={SAMPLE_ROW_COMBINATION}
            sampleFileName={sampleFileNameFor(row.combination)}
            onUpload={handleUpload}
            onImported={onSaved}
          />
        </div>
      )}
    </div>
  );
}

function ServerCard({ server, productType, onSaved }) {
  const navigate = useNavigate();
  const [showAddCombination, setShowAddCombination] = useState(false);

  // The combinations that actually have uploaded pairs -- derived fresh from the server's real
  // data every render, so they're correct after any reload, not just for the current session.
  const persistedGroups = groupByCombination(server.pairs || []);

  return (
    <div className="subpanel">
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
        <div
          style={{ display: "flex", alignItems: "center", gap: 10, cursor: "pointer" }}
          onClick={() => navigate(`/servers/${server.serverId}`)}
          title={`View ${server.serverName}`}
        >
          <ServerIcon style={{ marginRight: 0, color: "var(--color-primary)" }} />
          <strong style={{ fontSize: 14 }}>{server.serverName}</strong>
          {productType && <span className="badge gray">{PRODUCT_TYPE_LABELS[productType] || productType}</span>}
        </div>
        <button
          type="button"
          className="btn secondary"
          style={{ padding: "6px 14px", fontSize: 12.5 }}
          onClick={() => setShowAddCombination(true)}
        >
          <PlusIcon /> Add combination
        </button>
      </div>
      {!persistedGroups.length && (
        <div style={{ fontSize: 12.5, color: "var(--color-text-faint)", marginTop: 10 }}>No combinations added yet.</div>
      )}
      {persistedGroups.map((g) => (
        <CombinationRow
          key={`persisted-${g.combination}`}
          server={server}
          row={{ combination: g.combination, pairCount: g.pairs.length }}
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

export default function ServerUrlsPanel({ project, canManage, onSaved, showAddServer, onCloseAddServer }) {
  const [filterText, setFilterText] = useState("");

  if (!canManage) return null;

  const servers = project.servers || [];
  const filteredServers = servers.filter((s) =>
    s.serverName.toLowerCase().includes(filterText.trim().toLowerCase())
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
            <input
              type="text"
              placeholder="Filter server URLs..."
              value={filterText}
              onChange={(e) => setFilterText(e.target.value)}
              style={{ width: 260 }}
            />
          )}
        </div>

        {!!servers.length && <CsvFormatHelp />}

        {!servers.length ? (
          <p className="empty-state">No servers yet. Use "Add Server" above to get started.</p>
        ) : !filteredServers.length ? (
          <p className="empty-state">No servers match "{filterText}".</p>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 14, marginTop: 18 }}>
            {filteredServers.map((server) => (
              <ServerCard
                key={server.serverId}
                server={server}
                productType={project.productType}
                onSaved={onSaved}
              />
            ))}
          </div>
        )}
      </div>
    </>
  );
}
