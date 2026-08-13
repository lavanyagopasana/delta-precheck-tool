import React, { useEffect, useMemo, useState } from "react";
import { getAllowedUsers, upsertAllowedUser, removeAllowedUser, importUsersCsv } from "../api/client";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { useToast } from "../components/Toast";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";
import { TrashIcon, EditIcon } from "../components/Icons";
import { useConfirm } from "../components/ConfirmDialog";

const ROLE_OPTIONS = [
  { value: "MIGRATION_ENGINEER", label: "Migration Engineer" },
  { value: "MIGRATION_MANAGER", label: "Migration Manager" },
  { value: "DEV_LEAD", label: "Dev Lead" },
  { value: "QA_LEAD", label: "QA Lead" },
  { value: "ADMIN", label: "Admin" },
];

const roleLabel = (role) => ROLE_OPTIONS.find((r) => r.value === role)?.label || role;

// Consistent accent color per role, reused by the summary strip and the row indicators.
const ROLE_COLOR = {
  ADMIN: "var(--color-red)",
  MIGRATION_MANAGER: "var(--color-primary)",
  DEV_LEAD: "var(--color-yellow)",
  QA_LEAD: "var(--color-green)",
  MIGRATION_ENGINEER: "var(--color-text-muted)",
};
const roleColor = (role) => ROLE_COLOR[role] || "var(--color-text-muted)";

export default function AdminUsersPage() {
  const currentUser = useCurrentUser();
  const showToast = useToast();
  const confirm = useConfirm();
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("MIGRATION_ENGINEER");
  const [saving, setSaving] = useState(false);
  const [removingEmail, setRemovingEmail] = useState(null);
  const [editUser, setEditUser] = useState(null);
  const [editRole, setEditRole] = useState("MIGRATION_ENGINEER");
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState(null);
  const [csvModalOpen, setCsvModalOpen] = useState(false);
  const [csvFile, setCsvFile] = useState(null);
  const [csvRole, setCsvRole] = useState("MIGRATION_ENGINEER");
  const [csvSaving, setCsvSaving] = useState(false);
  const [csvError, setCsvError] = useState(null);

  const load = () => {
    setLoading(true);
    getAllowedUsers()
      .then((data) => {
        setUsers(data);
        setError(null);
      })
      .catch((err) => setError(err.response?.data?.message || "Failed to load users."))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const counts = useMemo(() => {
    const c = { total: users.length, ADMIN: 0, MIGRATION_MANAGER: 0, DEV_LEAD: 0, QA_LEAD: 0, MIGRATION_ENGINEER: 0 };
    users.forEach((u) => {
      if (c[u.role] !== undefined) c[u.role] += 1;
    });
    return c;
  }, [users]);

  const handleAdd = async (e) => {
    e.preventDefault();
    const trimmed = email.trim();
    if (!trimmed) return;
    setSaving(true);
    setError(null);
    try {
      await upsertAllowedUser({ email: trimmed, role });
      showToast(`${trimmed} added as ${roleLabel(role)}.`);
      setEmail("");
      setRole("MIGRATION_ENGINEER");
      load();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to add user.");
    } finally {
      setSaving(false);
    }
  };

  const closeCsvModal = () => {
    setCsvModalOpen(false);
    setCsvFile(null);
    setCsvRole("MIGRATION_ENGINEER");
    setCsvError(null);
  };

  const handleCsvImport = async (e) => {
    e.preventDefault();
    if (!csvFile) return;
    setCsvSaving(true);
    setCsvError(null);
    try {
      const result = await importUsersCsv(csvFile, csvRole);
      const parts = [`${result.createdCount} added`, `${result.updatedCount} updated`];
      if (result.errors?.length) parts.push(`${result.errors.length} skipped`);
      // Deliberately no longer claims a single role ("... as Migration Engineer"): rows can now each
      // carry their own, so naming the default would be wrong for any mixed-role file.
      showToast(`${parts.join(", ")}.`);

      // Row errors used to vanish -- the modal closed on success and the toast only counted them, so
      // an admin importing 30 people had no way to see WHICH rows were skipped or why. Keep the modal
      // open and show them; only auto-close when every row landed.
      if (result.errors?.length) {
        setCsvError(result.errors.join("\n"));
        load();
      } else {
        closeCsvModal();
        load();
      }
    } catch (err) {
      setCsvError(err.response?.data?.message || "Failed to import CSV.");
    } finally {
      setCsvSaving(false);
    }
  };

  const openEdit = (user) => {
    setEditUser(user);
    setEditRole(user.role);
    setEditError(null);
  };

  const closeEdit = () => {
    setEditUser(null);
    setEditError(null);
  };

  const handleEditSave = async (e) => {
    e.preventDefault();
    if (!editUser) return;
    if (editRole === editUser.role) {
      closeEdit();
      return;
    }
    setEditSaving(true);
    setEditError(null);
    try {
      await upsertAllowedUser({ email: editUser.email, role: editRole });
      showToast(`${editUser.email} is now ${roleLabel(editRole)}.`);
      closeEdit();
      load();
    } catch (err) {
      setEditError(err.response?.data?.message || "Failed to update role.");
    } finally {
      setEditSaving(false);
    }
  };

  const handleRemove = async (user) => {
    const ok = await confirm({
      title: `Remove ${user.email}?`,
      message: "They will lose access to the app immediately.",
      confirmLabel: "Remove",
      danger: true,
    });
    if (!ok) return;
    setRemovingEmail(user.email);
    setError(null);
    try {
      await removeAllowedUser(user.email);
      showToast(`${user.email} removed.`);
      load();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to remove user.");
    } finally {
      setRemovingEmail(null);
    }
  };

  if (loading) return <p>Loading users...</p>;

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <h2 style={{ margin: 0 }}>Manage Access</h2>
        <button className="btn" onClick={() => setCsvModalOpen(true)}>
          Add Users via CSV
        </button>
      </div>
      <p style={{ color: "var(--color-text-muted)", marginTop: -10, marginBottom: 20 }}>
        Only people added here can sign in, even if their email is @cloudfuze.com.
      </p>

      <div className="card-row card-row--nowrap" style={{ marginBottom: 20 }}>
        <div className="stat-card">
          <div className="value">{counts.total}</div>
          <div className="label">Total Users</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("ADMIN") }}>{counts.ADMIN}</div>
          <div className="label">Admins</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("MIGRATION_MANAGER") }}>{counts.MIGRATION_MANAGER}</div>
          <div className="label">Migration Managers</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("DEV_LEAD") }}>{counts.DEV_LEAD}</div>
          <div className="label">Dev Leads</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("QA_LEAD") }}>{counts.QA_LEAD}</div>
          <div className="label">QA Leads</div>
        </div>
        <div className="stat-card">
          <div className="value" style={{ color: roleColor("MIGRATION_ENGINEER") }}>{counts.MIGRATION_ENGINEER}</div>
          <div className="label">Engineers</div>
        </div>
      </div>

      <div className="card">
        <strong style={{ fontSize: 14 }}>Add a user</strong>
        <form onSubmit={handleAdd} style={{ marginTop: 12, display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 12.5, fontWeight: 600, color: "var(--color-text-muted)" }}>Email</label>
            <input
              type="text"
              placeholder="name@cloudfuze.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              style={{ width: 260 }}
            />
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ fontSize: 12.5, fontWeight: 600, color: "var(--color-text-muted)" }}>Role</label>
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              {ROLE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
          <button className="btn" type="submit" disabled={saving || !email.trim()}>
            {saving ? "Adding..." : "Add"}
          </button>
        </form>
      </div>

      {error && <div className="inline-hint" style={{ marginBottom: 12 }}>{error}</div>}

      <DataTable
        rows={users}
        rowKey={(u) => u.email}
        searchPlaceholder="Search users by email or role..."
        emptyMessage="No users yet."
        columns={[
          {
            key: "email",
            label: "Email",
            render: (u) => (
              <>
                {u.email}
                {currentUser?.email?.toLowerCase() === u.email.toLowerCase() && (
                  <span style={{ color: "var(--color-text-faint)", fontSize: 12 }}> (you)</span>
                )}
              </>
            ),
          },
          {
            key: "role",
            label: "Role",
            filterValue: (u) => roleLabel(u.role),
            render: (u) => (
              <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
                <span
                  aria-hidden="true"
                  style={{
                    width: 9,
                    height: 9,
                    borderRadius: "50%",
                    flexShrink: 0,
                    background: roleColor(u.role),
                  }}
                />
                {roleLabel(u.role)}
              </span>
            ),
          },
          {
            key: "addedAt",
            label: "Added",
            render: (u) => new Date(u.addedAt).toLocaleString(),
          },
          {
            key: "actions",
            label: "",
            sortable: false,
            filterable: false,
            render: (u) => {
              const isSelf = currentUser?.email?.toLowerCase() === u.email.toLowerCase();
              return (
                <div style={{ display: "flex", gap: 8 }}>
                  <button
                    className="btn secondary"
                    style={{ padding: "6px 10px" }}
                    onClick={() => openEdit(u)}
                    disabled={isSelf}
                    title={isSelf ? "You can't change your own role -- another admin must." : "Edit role"}
                    aria-label="Edit role"
                  >
                    <EditIcon size={18} style={{ marginRight: 0 }} />
                  </button>
                  <button
                    className="btn secondary"
                    style={{ padding: "6px 10px" }}
                    onClick={() => handleRemove(u)}
                    disabled={removingEmail === u.email || isSelf}
                    title={isSelf ? "You can't remove your own access -- another admin must." : "Remove user"}
                    aria-label="Remove user"
                  >
                    {removingEmail === u.email ? (
                      <span className="spinner" />
                    ) : (
                      <TrashIcon size={18} style={{ marginRight: 0 }} />
                    )}
                  </button>
                </div>
              );
            },
          },
        ]}
      />

      {editUser && (
        <Modal title="Edit role" onClose={closeEdit} width={420} closeIcon>
          <form onSubmit={handleEditSave}>
            <p style={{ marginTop: 0, marginBottom: 14, color: "var(--color-text-muted)", fontSize: 13 }}>
              {editUser.email}
            </p>
            <div style={{ marginBottom: 8 }}>
              <label style={{ display: "block", fontSize: 13, fontWeight: 600, marginBottom: 6 }}>Role</label>
              <select value={editRole} onChange={(e) => setEditRole(e.target.value)} style={{ width: "100%" }}>
                {ROLE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>

            {editError && <div className="inline-hint" style={{ marginBottom: 12 }}>{editError}</div>}

            <div className="form-actions" style={{ justifyContent: "flex-end", gap: 8 }}>
              <button type="button" className="btn secondary" onClick={closeEdit} disabled={editSaving}>
                Cancel
              </button>
              <button type="submit" className="btn" disabled={editSaving || editRole === editUser.role}>
                {editSaving ? "Saving..." : "Save"}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {csvModalOpen && (
        <Modal title="Add Users via CSV" onClose={closeCsvModal} width={420} closeIcon>
          <form onSubmit={handleCsvImport}>
            {/* Relabelled from "Role for everyone in this file": the file may now carry a per-person
                role column, in which case this is only the fallback for rows that leave it blank. */}
            <div style={{ marginBottom: 14 }}>
              <label htmlFor="csv-default-role" style={{ display: "block", fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
                Default role
              </label>
              <select
                id="csv-default-role"
                value={csvRole}
                onChange={(e) => setCsvRole(e.target.value)}
                style={{ width: "100%" }}
              >
                {ROLE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
              <p style={{ fontSize: 12, color: "var(--color-text-faint)", margin: "6px 0 0" }}>
                Used only for rows that don't specify their own role.
              </p>
            </div>

            <div style={{ marginBottom: 8 }}>
              <label style={{ display: "block", fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
                CSV file
              </label>
              <input
                type="file"
                accept=".csv"
                onChange={(e) => setCsvFile(e.target.files[0] || null)}
                style={{ width: "100%" }}
              />
            </div>
            <p style={{ fontSize: 12, color: "var(--color-text-faint)", marginTop: 0, marginBottom: 8 }}>
              One person per row. Add a <strong>role</strong> column to give each person their own
              role in a single file; leave a cell blank to use the default above. Columns named
              "email" and "role" are auto-detected in any order. With no header row, the first column
              is treated as the email.
            </p>
            <pre
              style={{
                fontSize: 11.5,
                background: "var(--color-gray-soft)",
                color: "var(--color-text-muted)",
                padding: "8px 10px",
                borderRadius: 6,
                margin: "0 0 10px",
                overflowX: "auto",
              }}
            >
{`email,role
asha@cloudfuze.com,Migration Manager
ravi@cloudfuze.com,Dev Lead
priya@cloudfuze.com,QA Lead
sam@cloudfuze.com,`}
            </pre>

            {/* pre-line so per-row errors (joined with \n) each get their own line instead of running
                together, and a max height so a 50-bad-row file scrolls rather than pushing the
                buttons off screen. */}
            {csvError && (
              <div
                className="inline-hint"
                style={{ marginBottom: 12, whiteSpace: "pre-line", maxHeight: 180, overflowY: "auto" }}
              >
                {csvError}
              </div>
            )}

            <div className="form-actions" style={{ justifyContent: "flex-end", gap: 8 }}>
              <button type="button" className="btn secondary" onClick={closeCsvModal} disabled={csvSaving}>
                Cancel
              </button>
              <button type="submit" className="btn" disabled={csvSaving || !csvFile}>
                {csvSaving ? "Creating..." : "Create"}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
