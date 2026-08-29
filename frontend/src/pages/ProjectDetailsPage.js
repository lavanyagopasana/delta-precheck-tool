import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  getProjectDetail,
  getMetabaseDatabases,
  setProjectMetabaseDatabase,
  getProjectMetabaseStatus,
} from "../api/client";
import { useToast } from "../components/Toast";
import { useConfirm } from "../components/ConfirmDialog";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import ServerUrlsPanel from "../components/ServerUrlsPanel";
import MetabaseStatusPanel from "../components/MetabaseStatusPanel";
import SearchableSelect from "../components/SearchableSelect";
import { PlusIcon, FolderIcon, EyeIcon, EditIcon } from "../components/Icons";

const initials = (email) => (email || "?").trim().charAt(0).toUpperCase();

// "CONTENT" -> "Content". Title case so the product type reads as a value rather than as a second
// uppercase heading next to "Metabase Database".
const titleCase = (s) => (s ? s.charAt(0) + s.slice(1).toLowerCase() : s);

/**
 * Which Metabase database holds this project's migration data, ONE ROW PER PRODUCT TYPE, plus the
 * button that opens the status section at the bottom of the page.
 *
 * Per product type because a Metabase database only ever holds one product type's data, so a project
 * whose servers span types needs one database per type. The product types offered are derived from
 * the project's own servers -- nobody picks them.
 *
 * A dropdown of the real names fetched from Metabase, never free text: the names are per-customer
 * strings nobody remembers exactly, and a typo would point the status panel at nothing.
 *
 * Confirming is a deliberate second step and it LOCKS that product type's value. The project's
 * Migration Manager or any assigned engineer may make the first choice; after that only an admin can
 * change it, because this decides where the figures a Delta gets approved against come from.
 */
function MetabaseDatabaseRow({ project, canManage, isAdmin, databases, loadError, onSaved, onShowStatus }) {
  // productTypes comes from the backend, which reads the project's servers and falls back to PMO's
  // migrationTypes -- so a freshly synced project can have its database chosen during setup rather
  // than being blocked behind creating a server first. All 79 ACTIVE PMO projects carry a
  // migrationTypes value, including the ones whose NAME doesn't show it (the "(Gmail - Gmail)" suffix
  // is only appended to disambiguate duplicate names, so "cloudsoft" looks typeless but isn't).
  //
  // A database already fixed for a type that has since disappeared from both sources still has to
  // appear -- otherwise it keeps reporting with nothing on screen explaining where the numbers came from.
  const productTypes = (() => {
    const seen = [...(project.productTypes || [])];
    for (const row of project.metabaseDatabases || []) {
      if (!seen.includes(row.productType)) seen.push(row.productType);
    }
    return seen.sort();
  })();

  const savedFor = (productType) =>
    (project.metabaseDatabases || []).find((d) => d.productType === productType)?.databaseName || "";

  const anySaved = (project.metabaseDatabases || []).some((d) => d.databaseName);

  return (
    <div className="project-metabase">
      <span className="detail-fact-label" style={{ display: "block", marginBottom: 10 }}>
        Metabase Database
      </span>

      {/* Pickers on the left, the one project-level action on the right, vertically centred against
          them -- so the button lines up with the picker row instead of floating on the label row above
          it. Stays correct for a project with three product types, where the button belongs to the
          whole block rather than to any single row. */}
      <div className="project-metabase-body">
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
                savedValue={savedFor(productType)}
                canManage={canManage}
                isAdmin={isAdmin}
                databases={databases}
                loadError={loadError}
                onSaved={onSaved}
              />
            ))
          )}
        </div>

        {/* Only offered once at least one database is fixed -- there is nothing to report on before. */}
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
    </div>
  );
}

/** One product type's dropdown, confirm step, and lock. */
function MetabaseDatabasePicker({ project, productType, savedValue, canManage, isAdmin,
                                  databases, loadError, onSaved }) {
  const showToast = useToast();
  const confirm = useConfirm();
  const [value, setValue] = useState(savedValue);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  // Admins get an explicit Change step rather than a live dropdown, so a stray click on a <select>
  // can't re-point a locked project's reporting without the person meaning to.
  const [editing, setEditing] = useState(false);

  const locked = !!savedValue && !isAdmin;
  const choosing = !locked && (!savedValue || editing);

  // Whatever is already saved stays selectable even when Metabase doesn't list it -- a database that
  // was renamed or that this account can't see would otherwise silently reset the dropdown to blank
  // and read as "nobody ever set this".
  const options = (() => {
    const names = (databases || []).map((db) => db.name);
    if (savedValue && !names.some((n) => n.toLowerCase() === savedValue.toLowerCase())) {
      return [savedValue, ...names];
    }
    return names;
  })();

  const handleConfirm = async () => {
    const next = value.trim();
    if (!next) return;
    // A one-way door for everyone but an admin, so it is spelled out before it closes rather than
    // explained afterwards by a 409.
    const message = savedValue
      ? `Change this project's ${productType} Metabase database from "${savedValue}" to "${next}"? The figures the team approves against will come from the new database.`
      : `Fix "${next}" as this project's ${productType} Metabase database? Once confirmed, only an admin can change it.`;
    if (!(await confirm({ title: "Confirm Metabase database", message, confirmLabel: "Confirm" }))) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await setProjectMetabaseDatabase(project.id, productType, next);
      showToast(`${productType} Metabase database fixed as "${next}".`);
      setEditing(false);
      onSaved();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to save the Metabase database.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div>
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
        <span className="metabase-type">{titleCase(productType)}</span>

        {choosing && canManage ? (
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
              style={{ padding: "8px 14px", fontSize: 12.5, whiteSpace: "nowrap" }}
              onClick={handleConfirm}
              disabled={saving || !value.trim() || value.trim() === savedValue}
            >
              {saving ? "Saving..." : savedValue ? "Confirm change" : "Confirm database"}
            </button>
            {editing && (
              <button
                type="button"
                className="btn secondary"
                style={{ padding: "8px 14px", fontSize: 12.5 }}
                onClick={() => { setValue(savedValue); setEditing(false); setError(null); }}
                disabled={saving}
              >
                Cancel
              </button>
            )}
          </>
        ) : savedValue ? (
          <>
            <span className="metabase-db-name">{savedValue}</span>
            <span className="badge gray" title="Fixed -- only an admin can change this">Locked</span>
            {isAdmin && (
              <button
                type="button"
                className="btn secondary"
                style={{ padding: "7px 14px", fontSize: 12.5, whiteSpace: "nowrap" }}
                onClick={() => setEditing(true)}
              >
                <EditIcon size={13} style={{ marginRight: 0 }} /> Change
              </button>
            )}
          </>
        ) : (
          <span style={{ fontSize: 13, color: "var(--color-text-faint)" }}>Not set yet</span>
        )}
      </div>

      {/* The dropdown is the only way in, so a failed list load has to say so rather than silently
          offering an empty one -- there is no text-entry fallback now that the value locks once set. */}
      {choosing && canManage && loadError && (
        <div className="inline-hint" style={{ marginTop: 6 }}>
          {loadError} The list has to load before a database can be picked.
        </div>
      )}
      {locked && (
        <div className="inline-hint" style={{ marginTop: 6 }}>
          Fixed for this project. Ask an admin if it needs to change.
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

      {/* Below the servers on purpose: this is a rollup of the whole project's migration data, not
          something you act on per server. */}
      {showStatus && (
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
