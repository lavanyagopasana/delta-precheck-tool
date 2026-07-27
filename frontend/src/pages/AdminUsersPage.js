import React, { useEffect, useState } from "react";
import { getAllowedUsers, upsertAllowedUser, removeAllowedUser, importUsersCsv } from "../api/client";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { useToast } from "../components/Toast";
import DataTable from "../components/DataTable";
import Modal from "../components/Modal";

const ROLE_OPTIONS = [
  { value: "MIGRATION_ENGINEER", label: "Migration Engineer" },
  { value: "MIGRATION_MANAGER", label: "Migration Manager" },
  { value: "DEV_LEAD", label: "Dev Lead" },
  { value: "QA_LEAD", label: "QA Lead" },
  { value: "ADMIN", label: "Admin" },
];

const roleLabel = (role) => ROLE_OPTIONS.find((r) => r.value === role)?.label || role;

export default function AdminUsersPage() {
  const currentUser = useCurrentUser();
  const showToast = useToast();
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("MIGRATION_ENGINEER");
  const [saving, setSaving] = useState(false);
  const [removingEmail, setRemovingEmail] = useState(null);
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
      showToast(`${parts.join(", ")} as ${roleLabel(csvRole)}.`);
      closeCsvModal();
      load();
    } catch (err) {
      setCsvError(err.response?.data?.message || "Failed to import CSV.");
    } finally {
      setCsvSaving(false);
    }
  };

  const handleRoleChange = async (user, newRole) => {
    if (newRole === user.role) return;
    if (!window.confirm(`Change ${user.email}'s role from ${roleLabel(user.role)} to ${roleLabel(newRole)}?`)) {
      return;
    }
    setError(null);
    try {
      await upsertAllowedUser({ email: user.email, role: newRole });
      showToast(`${user.email} is now ${roleLabel(newRole)}.`);
      load();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to update role.");
    }
  };

  const handleRemove = async (user) => {
    if (!window.confirm(`Remove ${user.email}? They will lose access immediately.`)) return;
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

      <div className="card">
        <strong style={{ fontSize: 14 }}>Add or update a user</strong>
        <form onSubmit={handleAdd} style={{ marginTop: 12, display: "flex", gap: 10, flexWrap: "wrap" }}>
          <input
            type="text"
            placeholder="name@cloudfuze.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            style={{ width: 260 }}
          />
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            {ROLE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
          <button className="btn" type="submit" disabled={saving || !email.trim()}>
            {saving ? "Saving..." : "Add / Update"}
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
              <select value={u.role} onChange={(e) => handleRoleChange(u, e.target.value)}>
                {ROLE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            ),
          },
          { key: "addedBy", label: "Added By", render: (u) => u.addedBy || "-" },
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
            render: (u) => (
              <button
                className="btn secondary"
                onClick={() => handleRemove(u)}
                disabled={removingEmail === u.email}
              >
                {removingEmail === u.email ? "Removing..." : "Remove"}
              </button>
            ),
          },
        ]}
      />

      {csvModalOpen && (
        <Modal title="Add Users via CSV" onClose={closeCsvModal} width={420} closeIcon>
          <form onSubmit={handleCsvImport}>
            <div style={{ marginBottom: 14 }}>
              <label style={{ display: "block", fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
                Role for everyone in this file
              </label>
              <select
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
            <p style={{ fontSize: 12, color: "var(--color-text-faint)", marginTop: 0 }}>
              One email per row. A header row is optional (a column named "email" is auto-detected);
              otherwise the first column of every row is used.
            </p>

            {csvError && <div className="inline-hint" style={{ marginBottom: 12 }}>{csvError}</div>}

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
