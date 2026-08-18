import React from "react";
import ReactDOM from "react-dom/client";
import { MsalProvider } from "@azure/msal-react";
import "./index.css";
import App from "./App";
import { msalInstance } from "./auth/msalInstance";
import { initHotjar } from "./analytics/hotjar";

// Started before the first render, and outside React, for two reasons: the snippet then also covers
// the login screen (so a sign-in that never gets past the access gate is still visible as a session),
// and module scope runs once regardless of StrictMode's double-invoked effects. No-ops entirely when
// no Hotjar site ID is configured, which is the default.
initHotjar();

const root = ReactDOM.createRoot(document.getElementById("root"));

function renderApp() {
  root.render(
    <React.StrictMode>
      {msalInstance ? (
        <MsalProvider instance={msalInstance}>
          <App />
        </MsalProvider>
      ) : (
        <App />
      )}
    </React.StrictMode>
  );
}

if (msalInstance) {
  msalInstance
    .initialize()
    .then(() => msalInstance.handleRedirectPromise())
    .catch(() => {})
    .then(renderApp);
} else {
  renderApp();
}
