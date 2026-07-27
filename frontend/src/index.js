import React from "react";
import ReactDOM from "react-dom/client";
import { MsalProvider } from "@azure/msal-react";
import "./index.css";
import App from "./App";
import { msalInstance } from "./auth/msalInstance";

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
