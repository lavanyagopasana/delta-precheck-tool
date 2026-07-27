const clientId = process.env.REACT_APP_AZURE_CLIENT_ID || "";
const tenantId = process.env.REACT_APP_AZURE_TENANT_ID || "";
const redirectUri = process.env.REACT_APP_AZURE_REDIRECT_URI || "http://localhost:3000";

export const AUTH_CONFIGURED = Boolean(clientId);

// Blank = no domain restriction (temporary, for testing with non-cloudfuze.com accounts). Set
// REACT_APP_ALLOWED_EMAIL_DOMAIN=cloudfuze.com to re-enable it. The admin-managed allowlist
// (Manage Access page) still applies regardless of this setting.
export const ALLOWED_EMAIL_DOMAIN = (process.env.REACT_APP_ALLOWED_EMAIL_DOMAIN || "").toLowerCase();

// If the app registration is single-tenant ("Accounts in this organizational directory only"),
// REACT_APP_AZURE_TENANT_ID is required — Microsoft rejects sign-in attempts against the generic
// "organizations" endpoint for a single-tenant app. If it's multi-tenant, leave the tenant ID
// unset and it falls back to "organizations" (any work/school account, any tenant), with the
// @cloudfuze.com restriction enforced by the app itself (see DomainGate) instead of by Azure.
export const msalConfig = {
  auth: {
    clientId,
    authority: `https://login.microsoftonline.com/${tenantId || "organizations"}`,
    redirectUri,
    postLogoutRedirectUri: redirectUri,
  },
  cache: {
    cacheLocation: "sessionStorage",
    storeAuthStateInCookie: false,
  },
};

// Also spread into every silent token acquisition (see getAccessToken.js) -- keep this to just
// scopes. "prompt" and other interactive-only params belong on interactiveLoginRequest below,
// never here, since acquireTokenSilent rejects/misbehaves with interactive params mixed in.
export const loginRequest = {
  scopes: ["openid", "profile", "email"],
};

// Used only for the actual sign-in button click (loginRedirect), never for silent calls. Without
// "select_account", a live Microsoft SSO session in the browser (left over from a previous sign-in,
// even after this app's own local sign-out) makes Azure AD skip straight to re-authenticating that
// same account instead of letting you pick a different one.
export const interactiveLoginRequest = {
  ...loginRequest,
  prompt: "select_account",
};
