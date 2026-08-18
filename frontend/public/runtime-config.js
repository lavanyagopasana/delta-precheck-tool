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

  // Hotjar Site ID (digits only, e.g. "3847291") -- found in Hotjar under Settings -> Sites &
  // Organizations, or as `hjid` in the tracking snippet Hotjar shows you. Not a secret: it ships
  // inside client-side JavaScript that any visitor can read, which is why it belongs here.
  //
  // Normally left "" -- the deployed value comes from REACT_APP_HOTJAR_SITE_ID, passed to
  // `docker build --build-arg` (see frontend/Dockerfile). Blank here and blank there means Hotjar is
  // fully off: no script requested, no session recorded, which is the state on developer machines.
  //
  // Set it here only to override a build: because runtime wins over build-time, writing an ID in this
  // file on the server turns recording on for a bundle built without one, and writing "" cannot turn
  // it off (a blank runtime value falls through to the baked-in build value). To switch recording OFF
  // for a build that has it baked in, rebuild without the build-arg.
  hotjarSiteId: "",
};
