// Single resolver for anything whose correct value depends on WHERE the app is deployed.
//
// Precedence, highest first:
//   1. window.__APP_CONFIG__  -- public/runtime-config.js, editable on the server after a build
//   2. process.env.REACT_APP_* -- inlined by react-scripts at build time
//   3. a default derived from window.location.origin, or a localhost value under `npm start`
//
// Runtime wins over build-time on purpose. react-scripts freezes REACT_APP_* into the bundle, so a
// build produced on a developer machine carries that machine's URLs into production and no amount of
// server-side configuration can override them -- that is exactly how a deploy shipped
// http://localhost:3000 as its Entra ID redirect URI. Putting the runtime file first means a
// mis-built bundle is recoverable by editing one file, without a rebuild.

const runtime = typeof window !== "undefined" && window.__APP_CONFIG__ ? window.__APP_CONFIG__ : {};

const isBrowser = typeof window !== "undefined" && Boolean(window.location);

// Own origin is the right default for both URLs below: a SPA redirect URI has to equal the app's
// origin, and a same-origin backend (reverse proxy routing /api) needs no configuration at all.
const origin = isBrowser ? window.location.origin : "";

const LOOPBACK_HOSTS = ["localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0"];

function isLoopback(hostname) {
  return LOOPBACK_HOSTS.includes(String(hostname).toLowerCase());
}

// True when the page itself is being served from a developer machine.
const servedFromLoopback = isBrowser && isLoopback(window.location.hostname);

// A build-time URL pointing at localhost cannot be correct for a page served from a real host: the
// bundle was built somewhere that localhost meant something, and this browser is not that machine.
// react-scripts bakes REACT_APP_* in at build time, so this situation is not hypothetical -- it is
// what shipped, and it broke sign-in in production while working perfectly on every laptop. Ignoring
// such a value (rather than trusting it) means a bundle built with dev settings still works when
// deployed, instead of redirecting users to their own machine.
function isImpossibleHere(url) {
  if (servedFromLoopback) return false;
  try {
    return isLoopback(new URL(url).hostname);
  } catch {
    return false;
  }
}

// Treated as "not set": undefined, null, blank/whitespace, and the placeholder token shape
// ("__SOMETHING__") that container entrypoints commonly substitute at start-up -- an unsubstituted
// placeholder must fall through to the default rather than be used as a literal URL.
function isUnset(candidate) {
  if (typeof candidate !== "string") return true;
  const trimmed = candidate.trim();
  return !trimmed || /^__.*__$/.test(trimmed);
}

// `treatLoopbackAsUnset` is only for values that are URLs; identifiers like the client ID are
// resolved with it off, since the loopback check is meaningless for them.
function resolve(runtimeKey, buildTimeValue, { treatLoopbackAsUnset = false } = {}) {
  const candidates = [
    { source: "runtime-config.js", raw: runtime[runtimeKey] },
    { source: "build-time env", raw: buildTimeValue },
  ];

  for (const { source, raw } of candidates) {
    if (isUnset(raw)) continue;
    const trimmed = raw.trim();
    if (treatLoopbackAsUnset && isImpossibleHere(trimmed)) {
      // eslint-disable-next-line no-console
      console.warn(
        `[config] Ignoring ${runtimeKey}="${trimmed}" from ${source}: it points at localhost, but ` +
          `this page is served from ${origin}. Falling back to this page's own origin. Set ` +
          `${runtimeKey} in runtime-config.js if that is not correct.`
      );
      continue;
    }
    return trimmed;
  }
  return "";
}

export const AZURE_CLIENT_ID = resolve("azureClientId", process.env.REACT_APP_AZURE_CLIENT_ID);
export const AZURE_TENANT_ID = resolve("azureTenantId", process.env.REACT_APP_AZURE_TENANT_ID);

export const AZURE_REDIRECT_URI =
  resolve("azureRedirectUri", process.env.REACT_APP_AZURE_REDIRECT_URI, {
    treatLoopbackAsUnset: true,
  }) || origin;

// The dev server (:3000) and the backend (:8081) are always different ports, so same-origin is never
// the right default for local development. NODE_ENV covers `npm start` even when the page is opened
// via the LAN address react-scripts prints ("On Your Network: http://192.168.x.x:3000"), which is not
// loopback but is still a dev session talking to a backend on the developer's own machine.
// servedFromLoopback additionally covers a production bundle previewed locally (`serve -s build`).
const isLocalDevelopment = process.env.NODE_ENV === "development" || servedFromLoopback;

// Trailing slashes stripped so callers can append "/api" without producing a double slash.
export const BACKEND_BASE = (
  resolve("apiBase", process.env.REACT_APP_API_BASE, { treatLoopbackAsUnset: true }) ||
  // Same-origin is the safe default everywhere else -- and if it is wrong, it fails visibly with
  // 404s against a real host instead of silently reaching for a localhost that isn't running.
  (isLocalDevelopment ? "http://localhost:8081" : origin)
).replace(/\/+$/, "");
