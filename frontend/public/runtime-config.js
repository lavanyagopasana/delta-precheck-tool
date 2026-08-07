// Runtime configuration -- read by the app when the page loads, NOT compiled into the bundle.
//
// Why this file exists: react-scripts inlines every REACT_APP_* variable into the JavaScript at
// `npm run build` time, so a bundle built on a laptop carries that laptop's URLs forever. That is
// how a production deploy ended up telling Entra ID to redirect users to http://localhost:3000 and
// pointing every API call at localhost:8080. Setting env vars on the server afterwards could not fix
// it, because the values were already frozen inside main.<hash>.js.
//
// This file is copied verbatim into build/ instead (public/ files are not processed by webpack), so
// whoever deploys can edit it on the server -- no rebuild, no toolchain, no Node.js required.
//
// Everything here is OPTIONAL. Leave it exactly as shipped and the app derives its configuration
// from the origin it is served from, which is correct for any normal deployment. Only set a value
// below when that default is wrong for your topology.
window.__APP_CONFIG__ = {
  // Backend origin, no trailing slash and no /api suffix (e.g. "https://api.example.com").
  // Leave "" when the backend is reachable on the SAME origin as this page -- the usual setup
  // behind a reverse proxy that routes /api to the backend. Only set it when the backend lives on a
  // different host or port, which is also the only case where the backend needs the frontend's
  // origin in APP_ALLOWED_ORIGINS for CORS.
  apiBase: "",

  // Where Entra ID sends the user back after sign-in. Leave "" to use this page's own origin, which
  // is what a single-page-application redirect URI must equal anyway. Whatever it resolves to has to
  // be registered on the Azure app registration under Authentication -> Single-page application.
  // Only set it for a deploy served under a path prefix or behind a rewriting proxy.
  azureRedirectUri: "",

  // Entra ID app registration identifiers. Both are public OAuth identifiers, not secrets. Leave ""
  // to use whatever the build was given; set them to point a single build at a different tenant or
  // app registration without rebuilding.
  azureClientId: "",
  azureTenantId: "",
};
