import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getServerReadiness, importWorkspacePairsCsv, SAMPLE_CSV_COLUMNS } from "../api/client";
import CsvImportPanel from "./CsvImportPanel";
import DataTable from "./DataTable";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { useCurrentUser } from "../auth/CurrentUserContext";

const SAMPLE_ROW = [
  "jane.doe@source-tenant.com",
  "/jane.doe/My Drive",
  "jane.doe@company.com",
  "/sites/migrated/jane.doe",
  "Google Drive -> OneDrive",
];

export default function WorkspacePairsPanel({
  serverId,
  showHeader = true,
  showPreCheckLink = true,
  showCsvImport = true,
}) {
  const navigate = useNavigate();
  const currentUser = useCurrentUser();
  const canImport =
    !AUTH_CONFIGURED || ["ADMIN", "MIGRATION_ENGINEER", "MIGRATION_MANAGER"].includes(currentUser?.role);
  const canFillPreCheck = !AUTH_CONFIGURED || ["MIGRATION_ENGINEER", "MIGRATION_MANAGER"].includes(currentUser?.role);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = () => {
    setLoading(true);
    getServerReadiness(serverId)
      .then(setData)
      .finally(() => setLoading(false));
  };

  useEffect(load, [serverId]);

  if (loading) return <p>Loading workspace pairs...</p>;

  return (
    <div>
      {showHeader && <h2>{data.serverName}</h2>}

      {showPreCheckLink && (
        <div className="server-precheck-row">
          <strong style={{ fontSize: 13.5 }}>Pre-Check</strong>
          <button
            className={`btn ${data.submissionStatus === "SUBMITTED" ? "success" : "warning"}`}
            onClick={() => navigate(`/servers/${serverId}/precheck`)}
          >
            {data.submissionStatus === "SUBMITTED"
              ? "View Pre-Check Form"
              : !canFillPreCheck
              ? "View Pre-Check Form"
              : data.submissionStatus === "DRAFT"
              ? "Continue Pre-Check Form"
              : "Start Pre-Check Form"}
          </button>
        </div>
      )}

      {showCsvImport && canImport && (
        <CsvImportPanel
          title="Import workspace pairs from CSV"
          columns={SAMPLE_CSV_COLUMNS}
          sampleRow={SAMPLE_ROW}
          onUpload={(file) => importWorkspacePairsCsv(serverId, file)}
          onImported={load}
        />
      )}

      <div style={{ marginTop: 24 }}>
        <DataTable
          title="Workspace Pairs"
          rows={data.pairs}
          rowKey={(p) => p.id}
          searchPlaceholder="Filter workspace pairs..."
          emptyMessage={canImport ? "No workspace pairs yet. Import a CSV above." : "No workspace pairs yet."}
          columns={[
            { key: "sourceEmail", label: "Source Email" },
            { key: "sourcePath", label: "Source Path", render: (p) => p.sourcePath || "-" },
            { key: "destinationEmail", label: "Destination Email" },
            { key: "destinationPath", label: "Destination Path", render: (p) => p.destinationPath || "-" },
            { key: "combination", label: "Combination", render: (p) => p.combination || "-" },
          ]}
        />
      </div>
    </div>
  );
}
