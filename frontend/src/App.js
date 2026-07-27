import React, { useEffect, useState } from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthenticatedTemplate, UnauthenticatedTemplate, useMsal } from "@azure/msal-react";
import NavBar from "./components/NavBar";
import { ToastProvider } from "./components/Toast";
import Dashboard from "./pages/Dashboard";
import ProjectsPage from "./pages/ProjectsPage";
import ProjectDetailsPage from "./pages/ProjectDetailsPage";
import ServerPreCheckPage from "./pages/ServerPreCheckPage";
import EscalationsPage from "./pages/EscalationsPage";
import ApprovalsPage from "./pages/ApprovalsPage";
import AdminUsersPage from "./pages/AdminUsersPage";
import LoginPage from "./pages/LoginPage";
import { AUTH_CONFIGURED, ALLOWED_EMAIL_DOMAIN } from "./auth/authConfig";
import { CurrentUserContext } from "./auth/CurrentUserContext";
import { getCurrentUser } from "./api/client";

// Local-only sign-out (matches NavBar.js): MSAL's logoutRedirect() either shows Microsoft's
// hosted "pick an account" page, or crashes outright if given an onRedirectNavigate callback
// (it tries to broadcast that function to other tabs via BroadcastChannel, and functions aren't
// structured-clonable). Clearing this app's own session and reloading sidesteps both.
function localSignOut() {
  window.sessionStorage.clear();
  window.location.assign("/");
}

function RestrictedScreen({ title, message, account, retry }) {
  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "var(--color-bg)",
      }}
    >
      <div className="card" style={{ width: 380, textAlign: "center", padding: "36px 32px" }}>
        <h2 style={{ justifyContent: "center" }}>{title}</h2>
        <p style={{ color: "var(--color-text-muted)", fontSize: 13.5 }}>
          {message} You're signed in as <strong>{account?.username}</strong>.
        </p>
        <div style={{ display: "flex", gap: 8, justifyContent: "center", marginTop: 10 }}>
          {retry && (
            <button className="btn secondary" onClick={retry}>
              Try again
            </button>
          )}
          <button className="btn" onClick={localSignOut}>
            Sign out
          </button>
        </div>
      </div>
    </div>
  );
}

function AccessGate({ children }) {
  const { accounts } = useMsal();
  const account = accounts[0];
  const email = (account?.username || "").toLowerCase();
  const domainOk = !ALLOWED_EMAIL_DOMAIN || email.endsWith(`@${ALLOWED_EMAIL_DOMAIN}`);

  const [me, setMe] = useState(null);
  // Distinct from "me.allowed === false" -- this means the /api/me call itself failed (backend
  // down mid-restart, network hiccup, etc.), which is NOT the same thing as actually being denied
  // access, and showing "ask an admin to add you" for a transient fetch failure is misleading.
  const [fetchFailed, setFetchFailed] = useState(false);
  const [loading, setLoading] = useState(domainOk);

  const checkAccess = () => {
    setLoading(true);
    setFetchFailed(false);
    getCurrentUser()
      .then(setMe)
      .catch((err) => {
        // A 401 means the token itself was rejected -- almost always an expired/stale sign-in
        // (e.g. the machine slept for hours and the token's lifetime ran out), which no amount of
        // "try again" clicking fixes client-side. Auto sign-out and bounce back to a fresh login
        // instead of leaving the user stuck on a screen that can't recover on its own. Any other
        // failure (network blip, backend genuinely down) still shows the manual retry screen,
        // since signing out wouldn't help there.
        if (err?.response?.status === 401) {
          localSignOut();
          return;
        }
        setFetchFailed(true);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (!domainOk) return;
    checkAccess();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [domainOk]);

  if (!domainOk) {
    return (
      <RestrictedScreen
        title="Access restricted"
        message={`Only @${ALLOWED_EMAIL_DOMAIN} accounts can use this app.`}
        account={account}
      />
    );
  }

  if (loading) return <p style={{ textAlign: "center", marginTop: 80 }}>Checking access...</p>;

  if (fetchFailed) {
    return (
      <RestrictedScreen
        title="Couldn't verify your access"
        message="The server didn't respond -- this is usually temporary."
        account={account}
        retry={checkAccess}
      />
    );
  }

  if (!me || !me.allowed) {
    return (
      <RestrictedScreen
        title="Access pending approval"
        message="Your account hasn't been added to this app yet. Ask an admin to add you."
        account={account}
        retry={checkAccess}
      />
    );
  }

  return <CurrentUserContext.Provider value={me}>{children}</CurrentUserContext.Provider>;
}

function AppShell() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <div className="app-shell">
          <NavBar />
          <div className="main-content">
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/projects" element={<ProjectsPage />} />
              <Route path="/projects/:id" element={<ProjectDetailsPage />} />
              <Route path="/servers/:serverId/precheck" element={<ServerPreCheckPage />} />
              <Route path="/escalations" element={<EscalationsPage />} />
              <Route path="/approvals" element={<ApprovalsPage />} />
              <Route path="/admin" element={<AdminUsersPage />} />
            </Routes>
          </div>
        </div>
      </ToastProvider>
    </BrowserRouter>
  );
}

export default function App() {
  if (!AUTH_CONFIGURED) {
    return <AppShell />;
  }

  return (
    <>
      <UnauthenticatedTemplate>
        <LoginPage />
      </UnauthenticatedTemplate>
      <AuthenticatedTemplate>
        <AccessGate>
          <AppShell />
        </AccessGate>
      </AuthenticatedTemplate>
    </>
  );
}
