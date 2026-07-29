import React, { useEffect, useState } from "react";
import { NavLink } from "react-router-dom";
import { useMsal } from "@azure/msal-react";
import { AUTH_CONFIGURED } from "../auth/authConfig";
import { useCurrentUser } from "../auth/CurrentUserContext";
import { getOpenEscalationCount } from "../api/client";
import cloudfuzeLogo from "../assets/cloudfuze-logo.svg";

const ESCALATION_POLL_MS = 30000;

const ICONS = {
  dashboard: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="3" width="7" height="9" rx="1.5" />
      <rect x="14" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="12" width="7" height="9" rx="1.5" />
      <rect x="3" y="16" width="7" height="5" rx="1.5" />
    </svg>
  ),
  projects: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" />
    </svg>
  ),
  escalations: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3 2 20h20L12 3Z" />
      <path d="M12 10v4" />
      <circle cx="12" cy="17" r="0.6" fill="currentColor" />
    </svg>
  ),
  approvals: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="4" width="16" height="17" rx="2" />
      <path d="M8 9h8M8 13h5" />
      <path d="m8.5 17.5 1.5 1.5 3-3" />
    </svg>
  ),
  admin: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3 4 6v6c0 5 3.5 7.5 8 9 4.5-1.5 8-4 8-9V6l-8-3Z" />
    </svg>
  ),
};

const BASE_LINKS = [
  { to: "/", end: true, icon: "dashboard", label: "Dashboard" },
  { to: "/projects", icon: "projects", label: "Projects" },
  { to: "/approvals", icon: "approvals", label: "Approvals" },
  { to: "/escalations", icon: "escalations", label: "Jira Tickets Tracking" },
];

const ADMIN_LINK = { to: "/admin", icon: "admin", label: "Admin" };

const ROLE_LETTER = {
  ADMIN: "A",
  MIGRATION_MANAGER: "M",
  MIGRATION_ENGINEER: "E",
  QA_LEAD: "Q",
  DEV_LEAD: "D",
};
const ROLE_LABEL = {
  ADMIN: "Admin",
  MIGRATION_MANAGER: "Migration Manager",
  MIGRATION_ENGINEER: "Migration Engineer",
  QA_LEAD: "QA Lead",
  DEV_LEAD: "Dev Lead",
};

const SignOutIcon = () => (
  <svg
    width="15"
    height="15"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
    style={{ marginRight: 8, verticalAlign: "-2px" }}
  >
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
    <polyline points="16 17 21 12 16 7" />
    <line x1="21" y1="12" x2="9" y2="12" />
  </svg>
);

function AccountInfo() {
  const { accounts } = useMsal();
  const currentUser = useCurrentUser();
  const account = accounts[0];
  if (!account) return null;

  const role = currentUser?.role;
  const roleLetter = ROLE_LETTER[role] || (role ? role.charAt(0).toUpperCase() : "?");
  const roleLabel = ROLE_LABEL[role] || role || "No role";

  // MSAL's logoutRedirect() either shows Microsoft's hosted "pick an account" page (when multiple
  // accounts share this browser's AAD session) or crashes entirely if given an onRedirectNavigate
  // callback, since MSAL tries to broadcast that function to other tabs via BroadcastChannel and
  // functions aren't structured-clonable. Sidestep both: clear this app's own session (MSAL's cache
  // lives in sessionStorage here, and nothing else in the app uses it) and reload straight to the
  // login screen, without touching the wider Microsoft SSO session at all.
  const handleSignOut = () => {
    window.sessionStorage.clear();
    window.location.assign("/");
  };

  return (
    <div
      style={{
        marginTop: "auto",
        paddingTop: 16,
        borderTop: "1px solid rgba(255, 255, 255, 0.08)",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 12 }}>
        <span
          title={roleLabel}
          aria-label={roleLabel}
          style={{
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            width: 34,
            height: 34,
            borderRadius: "50%",
            flexShrink: 0,
            background: "rgba(255, 255, 255, 0.16)",
            color: "#fff",
            fontWeight: 700,
            fontSize: 14,
          }}
        >
          {roleLetter}
        </span>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 12.5, color: "#fff", fontWeight: 600 }}>{account.name}</div>
          <div style={{ fontSize: 11.5, color: "rgba(255, 255, 255, 0.68)", wordBreak: "break-all" }}>
            {account.username}
          </div>
        </div>
      </div>
      <button
        className="btn secondary"
        style={{ width: "100%", justifyContent: "center" }}
        onClick={handleSignOut}
      >
        <SignOutIcon />
        Sign out
      </button>
    </div>
  );
}

export default function NavBar() {
  const currentUser = useCurrentUser();
  const links = [
    ...BASE_LINKS,
    ...(currentUser?.role === "ADMIN" ? [ADMIN_LINK] : []),
  ];
  const [openEscalationCount, setOpenEscalationCount] = useState(0);

  useEffect(() => {
    let cancelled = false;
    const poll = () => {
      getOpenEscalationCount()
        .then((count) => {
          if (!cancelled) setOpenEscalationCount(count);
        })
        .catch(() => {});
    };
    poll();
    const interval = setInterval(poll, ESCALATION_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  return (
    <div className="sidebar">
      <div className="brand">
        <div className="brand-mark">
          <img src={cloudfuzeLogo} alt="CloudFuze" />
        </div>
        <div className="brand-text">
          <strong>Delta Pre-Check</strong>
          <span>Tool</span>
        </div>
      </div>
      <div className="sidebar-body">
        <nav>
          {links.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              end={link.end}
              className={({ isActive }) => `nav-link${isActive ? " active" : ""}`}
            >
              <span className="nav-icon">{ICONS[link.icon]}</span>
              {link.label}
              {link.to === "/escalations" && openEscalationCount > 0 && (
                <span className="nav-badge">{openEscalationCount}</span>
              )}
            </NavLink>
          ))}
        </nav>
        {AUTH_CONFIGURED && <AccountInfo />}
      </div>
    </div>
  );
}
