import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  getProjectDetail,
  getMetabaseDatabases,
  setProjectMetabaseDatabase,
  removeProjectMetabaseDatabase,
  getProjectMetabaseStatus,
} from "../api/client";
import { useToast } from "../components/Toast";
import { useConfirm } from "../components/ConfirmDialog";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import ServerUrlsPanel from "../components/ServerUrlsPanel";
import MetabaseStatusPanel from "../components/MetabaseStatusPanel";
import { METABASE_UI_ENABLED } from "../config/features";
import SearchableSelect from "../components/SearchableSelect";
import { PlusIcon, FolderIcon, EyeIcon, TrashIcon } from "../components/Icons";

const initials = (email) => (email || "?").trim().charAt(0).toUpperCase();

// "CONTENT" -> "Content". Title case so the product type reads as a value rather than as a second
// uppercase heading next to "Metabase Database".
const titleCase = (s) => (s ? s.charAt(0) + s.slice(1).toLowerCase() : s);

/**
 * Which Metabase databases hold this project's migration data, grouped by product type, plus the
 * button that opens the status dialog.
 *
 * Grouped by product type because a Metabase database only ever holds one product type's data. A
 * type can be spread across SEVERAL databases though -- one customer engagement often is -- so each
 * type carries a list, and the status dialog reports every one of them separately.
 *
 * A dropdown of the real names fetched from Metabase, never free text: the names are per-customer
 * strings nobody remembers exactly, and a typo would point the status panel at nothing.
 *
 * Adding is open to the project's Migration Manager or an assigned engineer; REMOVING is admin-only.
 * The asymmetry is the point: adding widens the figures and the new database is listed right here
 * where anyone can see it contributed, while removing quietly shrinks the numbers a Delta was
 * approved against.
 */
function MetabaseDatabaseRow({ project, canManage, isAdmin, databases, loadError, onSaved, onShowStatus }) {
  // The integration is switched off for now (see config/features.js). Returning null here rather
  // than at each call site keeps the pickers, the "Get process status" button and their loading
  // state in one place, so turning it back on is the flag and nothing else.
  if (!METABASE_UI_ENABLED) {
    return null;
  }
  // productTypes comes from the backend, which reads the project's servers and falls back to PMO's
  // migrationTypes -- so a freshly synced project can have its database chosen during setup rather
  // than being blocked behind creating a server first.
  //
  // A database already added for a type that has since disappeared from both sources still has to
  // appear -- otherwise it keeps reporting with nothing on screen explaining where the numbers came from.
  const productTypes = (() => {
    const seen = [...(project.productTypes || [])];
    for (const row of project.metabaseDatabases || []) {
      if (!seen.includes(row.productType)) seen.push(row.productType);
    }
    return seen.sort();
  })();

  const savedFor = (productType) =>
    (project.metabaseDatabases || [])
      .filter((d) => d.productType === productType && d.databaseName)
      .map((d) => d.databaseName);

  const anySaved = (project.metabaseDatabases || []).some((d) => d.databaseName);

  return (
    <div className="project-metabase">
      {/* The action sits on the label row, top-right, rather than beside the pickers. It belongs to
          the whole block, not to any one product type, and with a type now holding a variable number
          of databases there is no longer a single picker row for it to line up against. */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 12,
          marginBottom: 10,
        }}
      >
        <span className="detail-fact-label">Metabase Databases</span>
        {/* Only offered once at least one is added -- there is nothing to report on before. */}
        {anySaved && (
          <button
            type="button"
            className="btn secondary"
            style={{ padding: "7px 14px", fontSize: 12.5, whiteSpace: "nowrap", flexShrink: 0 }}
            onClick={onShowStatus}
          >
            <EyeIcon size={14} style={{ marginRight: 0 }} /> Get process status
          </button>
        )}
      </div>

      <div className="project-metabase-pickers">
        {!productTypes.length ? (
          <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>
            No product type known for this project yet. It comes from PMO, or from a server's product
            type once one is added.
          </span>
        ) : (
          productTypes.map((productType) => (
            <MetabaseDatabasePicker
              key={productType}
              project={project}
              productType={productType}
              savedValues={savedFor(productType)}
              canManage={canManage}
              isAdmin={isAdmin}
              databases={databases}
              loadError={loadError}
              onSaved={onSaved}
            />
          ))
        )}
      </div>
    </div>
  );
}

/** One product type: the databases already added, and the control to add another. */
function MetabaseDatabasePicker({ project, productType, savedValues, canManage, isAdmin,
                                  databases, loadError, onSaved }) {
  const showToast = useToast();
  const confirm = useConfirm();
  const [value, setValue] = useState("");
  const [saving, setSaving] = useState(false);
  const [removing, setRemoving] = useState(null);
  const [error, setError] = useState(null);
  // The picker stays hidden behind an "Add database" button once one is added, so a type that is
  // already configured reads as a settled fact rather than as an open form.
  const [adding, setAdding] = useState(false);

  const hasAny = savedValues.length > 0;
  const choosing = canManage && (!hasAny || adding);

  // Already-added names are dropped from the list: re-picking one is the single thing the server
  // rejects (409), so it should not be offerable in the first place.
  const options = (databases || [])
    .map((db) => db.name)
    .filter((name) => !savedValues.some((v) => v.toLowerCase() === name.toLowerCase()));

  const handleAdd = async () => {
    const next = value.trim();
    if (!next) return;
    const message = hasAny
      ? `Add "${next}" as another ${productType} Metabase database? Its figures will be counted alongside the ${savedValues.length} already added, and only an admin can remove it later.`
      : `Add "${next}" as this project's ${productType} Metabase database? The figures the team approves against will come from it, and only an admin can remove it later.`;
    if (!(await confirm({ title: "Add Metabase database", message, confirmLabel: "Add" }))) return;

    setSaving(true);
    setError(null);
    try {
      await setProjectMetabaseDatabase(project.id, productType, next);
      showToast(`"${next}" added to ${productType}.`);
      setValue("");
      setAdding(false);
      onSaved();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to add the Metabase database.");
    } finally {
      setSaving(false);
    }
  };

  const handleRemove = async (name) => {
    // Destructive and admin-only: it subtracts from figures that may already have been approved
    // against, so the consequence is spelled out before it happens rather than after.
    const ok = await confirm({
      title: "Remove Metabase database",
      message: `Remove "${name}" from this project's ${productType} databases? Its workspaces and conflicts will stop being counted, which will change the totals the team approves against.`,
      confirmLabel: "Remove",
      danger: true,
    });
    if (!ok) return;

    setRemoving(name);
    setError(null);
    try {
      await removeProjectMetabaseDatabase(project.id, productType, name);
      showToast(`"${name}" removed from ${productType}.`);
      onSaved();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to remove the Metabase database.");
    } finally {
      setRemoving(null);
    }
  };

  return (
    <div>
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
        <span className="metabase-type">{titleCase(productType)}</span>

        {savedValues.map((name) => (
          <span key={name} className="metabase-db-chip">
            <span className="metabase-db-name">{name}</span>
            {isAdmin && (
              <button
                type="button"
                className="metabase-db-remove"
                onClick={() => handleRemove(name)}
                disabled={removing === name}
                title={`Remove ${name}`}
                aria-label={`Remove ${name}`}
              >
                <TrashIcon size={12} style={{ marginRight: 0 }} />
              </button>
            )}
          </span>
        ))}

        {!hasAny && !choosing && (
          <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>Not set yet</span>
        )}

        {choosing ? (
          <>
            {/* Searchable rather than a native <select>: Metabase serves 159 database names, and
                scrolling that list to find one is what this replaces. */}
            <SearchableSelect
              value={value}
              onChange={setValue}
              options={options}
              placeholder="Select a database..."
              loadingLabel={databases === null ? "Loading databases from Metabase..." : null}
              disabled={saving}
              ariaLabel={`${productType} Metabase database`}
            />
            <button
              className="btn"
              style={{ padding: "6px 14px", fontSize: 12.5, whiteSpace: "nowrap" }}
              onClick={handleAdd}
              disabled={saving || !value.trim()}
            >
              {saving ? "Adding..." : "Add"}
            </button>
            {hasAny && (
              <button
                type="button"
                className="btn secondary"
                style={{ padding: "6px 14px", fontSize: 12.5 }}
                onClick={() => { setValue(""); setAdding(false); setError(null); }}
                disabled={saving}
              >
                Cancel
              </button>
            )}
          </>
        ) : (
          canManage && (
            <button type="button" className="metabase-add-btn" onClick={() => setAdding(true)}>
              <PlusIcon size={12} style={{ marginRight: 0 }} /> Add database
            </button>
          )
        )}
      </div>

      {/* The dropdown is the only way in, so a failed list load has to say so rather than silently
          offering an empty one -- there is no text-entry fallback. */}
      {choosing && loadError && (
        <div className="inline-hint" style={{ marginTop: 6 }}>
          {loadError} The list has to load before a database can be picked.
        </div>
      )}
      {hasAny && !isAdmin && (
        <div className="note-hint" style={{ marginTop: 6 }}>
          Anyone on this project can add another database; only an admin can remove one.
        </div>
      )}
      {error && <div className="inline-hint" style={{ marginTop: 6 }}>{error}</div>}
    </div>
  );
}

// The project header: name on the top row, manager beside it. The project's engineers (whoever is
// on the Migration Manager's team) are no longer shown here -- see the Team page in the sidebar
// instead. Third row is the Metabase database picker, unrelated to either.
function ProjectHeader({ project, canManage, onAddServer, metabaseRow }) {
  return (
    <div className="detail-header">
      {/* Top row: the project's name on the left, its manager on the right, primary action last. */}
      <div className="detail-header-top detail-header-top--split">
        <span className="detail-header-icon">
          <FolderIcon size={20} style={{ marginRight: 0 }} />
        </span>
        <h2 className="detail-header-title">{project.name}</h2>

        {/* The manager is a single value, so it fits the top row beside the name. */}
        <div className="project-manager">
          <span className="detail-fact-label">Migration Manager</span>
          {project.migrationManagerName ? (
            <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
              <span className="person-avatar">{initials(project.migrationManagerName)}</span>
              <span style={{ fontSize: 13.5, fontWeight: 600 }}>{project.migrationManagerName}</span>
            </div>
          ) : (
            <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>Not assigned yet</span>
          )}
        </div>

        {canManage && (
          <button className="btn" style={{ flexShrink: 0, alignSelf: "center" }} onClick={onAddServer}>
            <PlusIcon /> Add Server
          </button>
        )}
      </div>

      {/* Second row: the Metabase database this project reports against. */}
      {metabaseRow}
    </div>
  );
}

export default function ProjectDetailsPage() {
  const { id } = useParams();
  const currentUser = useCurrentUser();
  const [project, setProject] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showAddServer, setShowAddServer] = useState(false);
  // The status section is opened by the "Get process status" button and stays open until hidden.
  const [showStatus, setShowStatus] = useState(false);
  const [statusEntries, setStatusEntries] = useState(null);
  const [statusLoading, setStatusLoading] = useState(false);
  const [statusError, setStatusError] = useState(null);
  // null while the Metabase database list is still loading, so the dropdown can say so rather than
  // rendering an empty list that looks like "Metabase has nothing".
  const [databases, setDatabases] = useState(null);
  const [databasesError, setDatabasesError] = useState(null);

  const isAdmin = !AUTH_CONFIGURED || currentUser?.role === "ADMIN";
  const currentUserEmail = AUTH_CONFIGURED ? currentUser?.email || currentUser?.name || "unknown" : "unknown";

  const load = () => {
    getProjectDetail(id)
      .then((data) => {
        setProject(data);
        setError(null);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load project."))
      .finally(() => setLoading(false));
  };

  // Fetched on demand, not on page load: it is several round trips to Metabase (the database list
  // plus an aggregation per product type) and most visits to this page never ask for it.
  const loadStatus = () => {
    setStatusLoading(true);
    setStatusError(null);
    getProjectMetabaseStatus(id)
      .then((data) => setStatusEntries(data || []))
      .catch((err) =>
        setStatusError(err.response?.data?.message || "Couldn't read the migration status from Metabase.")
      )
      .finally(() => setStatusLoading(false));
  };

  const handleShowStatus = () => {
    setShowStatus(true);
    loadStatus();
  };

  useEffect(load, [id]);

  // Loaded once per page visit, not per keystroke -- the list changes when somebody adds a database
  // in Metabase, which is far rarer than opening a project. A failure here is recorded rather than
  // swallowed (unlike the roster above) because the dropdown has to explain why it became a text box.
  useEffect(() => {
    getMetabaseDatabases()
      .then((data) => {
        setDatabases(data || []);
        setDatabasesError(null);
      })
      .catch((err) => {
        setDatabases([]);
        setDatabasesError(
          err.response?.data?.message ||
            "Couldn't load the database list from Metabase -- type the name instead."
        );
      });
  }, []);

  if (loading && !project) return <p>Loading project...</p>;
  if (!project) return <div className="inline-hint">{error || "Project not found."}</div>;

  // Only this project's manager, its team members, its creator, or an admin can add a server or
  // import a CSV -- not just anyone with the Migration Manager/Engineer role globally.
  const canManage =
    isAdmin ||
    (!!project.migrationManagerName && currentUserEmail.toLowerCase() === project.migrationManagerName.toLowerCase()) ||
    (!!project.createdBy && currentUserEmail.toLowerCase() === project.createdBy.toLowerCase()) ||
    (project.engineerEmails || []).some((e) => e.toLowerCase() === currentUserEmail.toLowerCase());

  return (
    <div>
      <Link to="/projects" className="breadcrumb" style={{ display: "inline-block" }}>&larr; Back to Projects</Link>

      <ProjectHeader
        project={project}
        canManage={canManage}
        onAddServer={() => setShowAddServer(true)}
        metabaseRow={
          <MetabaseDatabaseRow
            project={project}
            canManage={canManage}
            isAdmin={isAdmin}
            databases={databases}
            loadError={databasesError}
            onSaved={load}
            onShowStatus={handleShowStatus}
          />
        }
      />

      {error && <div className="inline-hint" style={{ marginTop: 12 }}>{error}</div>}

      <ServerUrlsPanel
        project={project}
        canManage={canManage}
        isAdmin={isAdmin}
        onSaved={load}
        showAddServer={showAddServer}
        onCloseAddServer={() => setShowAddServer(false)}
      />

      {/* A dialog, not a section appended below the servers: this is a rollup of the whole project's
          migration data that you open, read and dismiss. Rendered last only because that is where the
          state lives -- the overlay is fixed, so its position in the tree does not affect layout. */}
      {METABASE_UI_ENABLED && showStatus && (
        <MetabaseStatusPanel
          entries={statusEntries}
          loading={statusLoading}
          error={statusError}
          onRefresh={loadStatus}
          onClose={() => setShowStatus(false)}
        />
      )}
    </div>
  );
}
