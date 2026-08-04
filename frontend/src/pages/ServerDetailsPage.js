import React, { useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { getServerReadiness } from "../api/client";
import WorkspacePairsPanel from "../components/WorkspacePairsPanel";
import AddCombinationModal from "../components/AddCombinationModal";
import { ServerIcon, SwapIcon, PlusIcon } from "../components/Icons";
import { groupByCombination } from "../utils/pairs";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { useCurrentUser } from "../auth/CurrentUserContext";

const PRODUCT_TYPE_LABELS = { MESSAGE: "Message", EMAIL: "Email", CONTENT: "Content" };

export default function ServerDetailsPage() {
  const { serverId } = useParams();
  const [searchParams] = useSearchParams();
  const initialCombination = searchParams.get("combination") || "";
  const currentUser = useCurrentUser();
  const canImport =
    !AUTH_CONFIGURED || ["ADMIN", "MIGRATION_ENGINEER", "MIGRATION_MANAGER"].includes(currentUser?.role);
  const [server, setServer] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showAddCombination, setShowAddCombination] = useState(false);

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

      <div style={{ marginBottom: 24 }}>
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
          <div>
            {server.projectName && (
              <div style={{ fontSize: 12.5, color: "var(--color-text-muted)", marginBottom: 4 }}>
                Project: <strong style={{ color: "var(--color-text)" }}>{server.projectName}</strong>
              </div>
            )}
            <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
              <ServerIcon size={20} style={{ marginRight: 0, color: "var(--color-primary)" }} />
              <h2 style={{ margin: 0 }}>{server.serverName}</h2>
              {server.productType && (
                <span className="badge gray">{PRODUCT_TYPE_LABELS[server.productType] || server.productType}</span>
              )}
            </div>
            {activeCombination && (
              <div style={{ display: "flex", alignItems: "center", gap: 7, marginTop: 6, color: "var(--color-text-muted)" }}>
                <SwapIcon size={15} style={{ marginRight: 0 }} />
                <span style={{ fontSize: 13.5, fontWeight: 600 }}>{activeCombination.combination}</span>
              </div>
            )}
          </div>
          {canImport && (
            <button className="btn secondary" onClick={() => setShowAddCombination(true)}>
              <PlusIcon /> Add combination
            </button>
          )}
        </div>
      </div>

      {error && <div className="inline-hint" style={{ marginTop: 12 }}>{error}</div>}

      <AddCombinationModal
        server={{ serverId, serverName: server.serverName }}
        open={showAddCombination}
        onClose={() => setShowAddCombination(false)}
        onSaved={load}
      />

      <div className="card">
        <WorkspacePairsPanel
          key={`pairs-${serverId}-${initialCombination}`}
          serverId={serverId}
          showCsvImport={false}
          initialCombination={initialCombination}
        />
      </div>
    </div>
  );
}
