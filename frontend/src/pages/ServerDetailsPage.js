import React, { useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { getServerReadiness } from "../api/client";
import WorkspacePairsPanel from "../components/WorkspacePairsPanel";
import { ServerIcon, SwapIcon } from "../components/Icons";
import { groupByCombination } from "../utils/pairs";
import { emailLocalPart } from "../utils/format";

const PRODUCT_TYPE_LABELS = { MESSAGE: "Message", EMAIL: "Email", CONTENT: "Content" };

export default function ServerDetailsPage() {
  const { serverId } = useParams();
  const [searchParams] = useSearchParams();
  const initialCombination = searchParams.get("combination") || "";
  const [server, setServer] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  // Facts for the combination the panel below is showing -- see the header's fact row.
  const [comboReadiness, setComboReadiness] = useState(null);

  const load = () => {
    setLoading(true);
    getServerReadiness(serverId)
      .then((data) => {
        setServer(data);
        setError(null);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load server."))
      .finally(() => setLoading(false));
  };

  useEffect(load, [serverId]);

  if (loading) return <p>Loading server...</p>;
  if (!server) return <div className="inline-hint">{error || "Server not found."}</div>;

  // Mirrors WorkspacePairsPanel's own default-selection logic so the combination shown here always
  // matches whichever combination that panel ends up rendering, without needing a picker of its own.
  const groups = groupByCombination(server.pairs || []);
  const activeCombination = groups.find((g) => g.combination === initialCombination) || groups[0];

  return (
    <div>
      <Link
        to={server.projectId ? `/projects/${server.projectId}` : "/projects"}
        className="breadcrumb"
        style={{ display: "inline-block" }}
      >
        &larr; Back to {server.projectName || "Project"}
      </Link>

      {/* Top row: the server URL alone -- it's the page's identity, so nothing shares the line with
          it. Everything else drops to the fact row below. */}
      <div className="detail-header">
        <div className="detail-header-top">
          <span className="detail-header-icon">
            <ServerIcon size={20} style={{ marginRight: 0 }} />
          </span>
          <h2 className="detail-header-title">{server.serverName}</h2>
        </div>

        <div className="detail-header-facts">
          <div className="detail-fact">
            <span className="detail-fact-label">Project</span>
            <span className="detail-fact-value">{server.projectName || "—"}</span>
          </div>
          <div className="detail-fact">
            <span className="detail-fact-label">Product type</span>
            <span className="detail-fact-value">
              {server.productType
                ? PRODUCT_TYPE_LABELS[server.productType] || server.productType
                : "—"}
            </span>
          </div>
          <div className="detail-fact">
            <span className="detail-fact-label">Combination</span>
            <span className="detail-fact-value">
              {activeCombination ? (
                <>
                  <SwapIcon size={14} style={{ marginRight: 0, color: "var(--color-text-faint)" }} />
                  {activeCombination.combination}
                </>
              ) : (
                "—"
              )}
            </span>
          </div>
          {/* Pairs / open tickets / manager live up here with the rest of the identity facts rather
              than in the lifecycle panel below -- that panel is about progress, these are properties
              of the combination. Reported up by WorkspacePairsPanel so there's no second fetch. */}
          {comboReadiness && (
            <>
              <div className="detail-fact">
                <span className="detail-fact-label">Pairs</span>
                <span className="detail-fact-value">{comboReadiness.totalPairs}</span>
              </div>
              <div className="detail-fact">
                <span className="detail-fact-label">Open tickets</span>
                <span
                  className="detail-fact-value"
                  style={{ color: comboReadiness.openEscalationCount > 0 ? "var(--color-red)" : undefined }}
                >
                  {comboReadiness.openEscalationCount}
                </span>
              </div>
              {comboReadiness.migrationManagerName && (
                <div className="detail-fact">
                  <span className="detail-fact-label">Manager</span>
                  <span className="detail-fact-value">
                    {emailLocalPart(comboReadiness.migrationManagerName)}
                  </span>
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {error && <div className="inline-hint" style={{ marginTop: 12 }}>{error}</div>}

      <div className="card">
        <WorkspacePairsPanel
          key={`pairs-${serverId}-${initialCombination}`}
          serverId={serverId}
          showCsvImport={false}
          initialCombination={initialCombination}
          onCombinationReadiness={setComboReadiness}
        />
      </div>
    </div>
  );
}
