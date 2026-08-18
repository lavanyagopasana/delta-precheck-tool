// Hotjar session recording and heatmaps.
//
// Why this exists: people are added to app_users and given a role, but nothing in the app recorded
// whether they ever actually signed in and used it. AppUser has no lastLoginAt and there is no
// activity table, so "did this person we granted DEV_LEAD to ever show up" had no answer at all.
//
// What this can and cannot tell you, so nobody reads more into it than it holds:
//   - it CAN show that a given person had sessions, roughly how many, and what their screen looked
//     like while they used the app
//   - it CANNOT give an audit-grade count. Hotjar samples and caps recordings by plan tier, so the
//     numbers are directional
//   - it CANNOT answer "list every sign-off this person approved". That lives in the database
//     (SignOff.approvedBy, PreCheckSubmission.submittedBy) and would need its own admin view
//
// The site ID is read at runtime rather than from process.env because react-scripts freezes
// REACT_APP_* into the bundle at build time -- a bundle built with tracking on could never be
// un-tracked without rebuilding. Reading it from runtime-config.js means blanking hotjarSiteId on the
// server turns recording off on the next page load. See config/runtimeConfig.js.

import { HOTJAR_SITE_ID } from "../config/runtimeConfig";

const SCRIPT_ID = "hotjar-snippet";

// Snippet version Hotjar expects in both _hjSettings and the script URL. Bumping this is Hotjar's
// call, not ours -- it changes only when they ship a new loader contract.
const SNIPPET_VERSION = 6;

export function isHotjarEnabled() {
  return Boolean(HOTJAR_SITE_ID);
}

/**
 * Injects the Hotjar snippet. No-ops when no site ID is configured, which is the normal state in
 * local development and on any deploy that has not opted in.
 *
 * Idempotent on purpose: index.js renders under React.StrictMode, which double-invokes effects in
 * development, and two copies of the snippet would open two recordings for one page view.
 *
 * @returns {boolean} true only when this call actually injected the script.
 */
export function initHotjar() {
  if (!isHotjarEnabled()) return false;
  if (typeof window === "undefined" || typeof document === "undefined") return false;
  if (document.getElementById(SCRIPT_ID)) return false;

  // A non-numeric ID would silently request hotjar-NaN.js and fail with nothing in the console
  // pointing at the cause. Say so instead -- a typo'd ID and a deliberately disabled Hotjar should
  // not look identical to whoever is debugging.
  if (!/^\d+$/.test(HOTJAR_SITE_ID)) {
    // eslint-disable-next-line no-console
    console.warn(
      `[analytics] Ignoring hotjarSiteId="${HOTJAR_SITE_ID}": a Hotjar Site ID is digits only ` +
        `(e.g. "3847291"). Find it under Settings -> Sites & Organizations in Hotjar. Recording is off.`
    );
    return false;
  }

  // The queue has to exist before the remote script loads so that calls made during the first render
  // -- identify, in particular, which fires as soon as /api/me resolves -- are replayed instead of
  // dropped on the floor.
  window.hj =
    window.hj ||
    function () {
      (window.hj.q = window.hj.q || []).push(arguments);
    };
  // Number, not string: Hotjar's own snippet emits `hjid:6763513` as a numeric literal, and the
  // remote script reads this value back. The digits-only guard above means Number() cannot
  // produce NaN here.
  window._hjSettings = { hjid: Number(HOTJAR_SITE_ID), hjsv: SNIPPET_VERSION };

  const script = document.createElement("script");
  script.id = SCRIPT_ID;
  script.async = true;
  script.src = `https://static.hotjar.com/c/hotjar-${HOTJAR_SITE_ID}.js?sv=${SNIPPET_VERSION}`;
  document.head.appendChild(script);
  return true;
}

/**
 * Tags the current recording with who is using the app, so recordings can be filtered per person and
 * per role in Hotjar.
 *
 * Email is the identifier deliberately. The question this tracking exists to answer is "was the
 * person we granted this role to ever actually here", and an opaque ID would need a second lookup
 * every time to answer it. Everyone who can sign in is an internal employee (see AZURE_TENANT_ID and
 * the app_users allowlist), so no customer identity is involved in the attribution -- customer data
 * on screen is suppressed separately, see the data-hj-suppress call sites.
 *
 * Lowercased to match the case-insensitive email rule the rest of the codebase follows. Without it
 * one person signing in as Jane.Doe@ and jane.doe@ would appear as two different Hotjar users.
 *
 * Note: filtering recordings by these attributes is a paid Hotjar feature. On a tier that does not
 * include it the call is accepted and ignored, so this stays safe to ship regardless of plan.
 *
 * @param {{email?: string, role?: string, allowed?: boolean}} user the /api/me response
 * @returns {boolean} true only when an identify call was actually sent.
 */
export function identifyHotjarUser(user) {
  if (!isHotjarEnabled()) return false;
  if (typeof window === "undefined" || typeof window.hj !== "function") return false;

  const email = (user?.email || "").trim().toLowerCase();
  if (!email) return false;

  window.hj("identify", email, {
    role: user.role || "UNKNOWN",
    allowed: Boolean(user.allowed),
  });
  return true;
}
