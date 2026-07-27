import React, { useState } from "react";
import { useMsal } from "@azure/msal-react";
import { InteractionStatus } from "@azure/msal-browser";
import { interactiveLoginRequest } from "../auth/authConfig";
import cloudfuzeLogo from "../assets/cloudfuze-logo.svg";

const LockIcon = () => (
  <svg width="12" height="12" viewBox="0 0 16 16" fill="none" style={{ marginRight: 5, flexShrink: 0 }}>
    <rect x="3" y="7" width="10" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.3" />
    <path d="M5 7V4.7A3 3 0 0 1 8 1.7a3 3 0 0 1 3 3V7" stroke="currentColor" strokeWidth="1.3" />
  </svg>
);

const MicrosoftLogo = () => (
  <svg width="18" height="18" viewBox="0 0 21 21" style={{ marginRight: 4 }}>
    <rect x="1" y="1" width="9" height="9" fill="#f25022" />
    <rect x="11" y="1" width="9" height="9" fill="#7fba00" />
    <rect x="1" y="11" width="9" height="9" fill="#00a4ef" />
    <rect x="11" y="11" width="9" height="9" fill="#ffb900" />
  </svg>
);

const ChecklistIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
    <rect x="4" y="3" width="16" height="18" rx="2.2" stroke="currentColor" strokeWidth="1.6" />
    <path d="M7.5 8.5h9M7.5 12h9M7.5 15.5h5.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
  </svg>
);

const ApprovalChainIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
    <circle cx="5" cy="5.5" r="2.6" stroke="currentColor" strokeWidth="1.6" />
    <circle cx="12" cy="18.5" r="2.6" stroke="currentColor" strokeWidth="1.6" />
    <circle cx="19" cy="5.5" r="2.6" stroke="currentColor" strokeWidth="1.6" />
    <path d="M7 7.2L10.3 16M17 7.2L13.7 16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
  </svg>
);

const CloudCheckIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
    <path
      d="M7 17.5a4 4 0 0 1-.6-7.95 5 5 0 0 1 9.6-1.8A4.5 4.5 0 0 1 17 17.5H7Z"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinejoin="round"
    />
    <path d="M9 13l2.2 2.2L15 11" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

const STEPS = [
  {
    icon: <ChecklistIcon />,
    title: "Submit the pre-check",
    detail: "Complete the checklist with evidence for every workspace pair on the server.",
  },
  {
    icon: <ApprovalChainIcon />,
    title: "Get it signed off",
    detail: "Migration Manager, then Dev Lead, then QA Lead approve in sequence.",
  },
  {
    icon: <CloudCheckIcon />,
    title: "Go Delta Ready",
    detail: "Once every sign-off clears, the server is cleared to initiate its Delta migration.",
  },
];

export default function LoginPage() {
  const { instance, inProgress } = useMsal();
  const [error, setError] = useState(null);

  const handleSignIn = async () => {
    setError(null);
    try {
      await instance.loginRedirect(interactiveLoginRequest);
    } catch (err) {
      // MSAL's own "an interaction is already running" flag can get stuck after an interrupted
      // previous redirect (a crash, a manual page reload mid-flow, etc.) and then blocks every
      // future sign-in attempt. Clearing local session state and retrying once is the standard
      // recovery -- the flag lives in the same sessionStorage MSAL's cache uses.
      if (err.errorCode === "interaction_in_progress") {
        window.sessionStorage.clear();
        try {
          await instance.loginRedirect(interactiveLoginRequest);
          return;
        } catch (retryErr) {
          setError(retryErr.errorMessage || "Sign-in failed. Please try again.");
          return;
        }
      }
      setError(err.errorMessage || "Sign-in failed. Please try again.");
    }
  };

  const busy = inProgress !== InteractionStatus.None;

  return (
    <div className="login-shell">
      <div className="login-illustration-panel">
        <div className="login-illustration-eyebrow">CloudFuze</div>

        <div className="login-illustration-copy">
          <h1>
            Know exactly what's<br />Delta Ready.
          </h1>
          <p>
            Every server's pre-check, approval chain, and Delta status in one place -- from
            submission through Migration Manager, Dev Lead, and QA Lead sign-off.
          </p>
        </div>

        <div className="login-steps">
          {STEPS.map((step, i) => (
            <div className="login-step" key={step.title}>
              <span className="login-step-icon">{step.icon}</span>
              <div>
                <strong>{step.title}</strong>
                <p>{step.detail}</p>
              </div>
              {i < STEPS.length - 1 && <span className="login-step-connector" aria-hidden="true" />}
            </div>
          ))}
        </div>
      </div>

      <div className="login-form-panel">
        <div className="login-form-glow top" aria-hidden="true" />
        <div className="login-form-glow bottom" aria-hidden="true" />

        <div className="card login-card">
          <img src={cloudfuzeLogo} alt="CloudFuze" className="login-card-logo" />
          <div className="login-card-eyebrow">Delta Pre-Check Tool</div>
          <h2 style={{ justifyContent: "center", marginBottom: 4 }}>Welcome back</h2>
          <p style={{ color: "var(--color-text-muted)", fontSize: 13.5, marginTop: 0, marginBottom: 28 }}>
            Sign in with your CloudFuze Microsoft account to continue.
          </p>

          <button
            className="btn login-signin-btn"
            onClick={handleSignIn}
            disabled={busy}
            style={{ width: "100%", justifyContent: "center" }}
          >
            <MicrosoftLogo />
            {busy ? "Signing in..." : "Sign in with Microsoft"}
          </button>

          {error && <div className="inline-hint" style={{ marginTop: 16, justifyContent: "center" }}>{error}</div>}

          <div className="login-footer-note">
            <LockIcon />
            Secured by Microsoft Azure AD &middot; CloudFuze employees only
          </div>
        </div>
      </div>
    </div>
  );
}
