import { msalInstance } from "./msalInstance";

// Survives the re-render that drops the app for the login screen, but not a new tab or a browser
// restart -- "your session expired" is only true about the session you were just in.
const FLAG = "deltaPrecheck.sessionExpired";

// A page issues several requests at once, so an expired token produces a burst of 401s rather than
// one. Without this guard each of them would clear the cache and set the flag again.
let ending = false;

/**
 * Ends a session whose token can no longer be refreshed, and hands the person the login screen
 * instead of an error.
 *
 * Uses clearCache() rather than logoutRedirect(): the session is already dead, so there is nothing
 * to tell Microsoft about, and a redirect to their logout page and back is a slow, confusing way to
 * arrive somewhere the app can render immediately. Clearing the local cache leaves MSAL with no
 * account, which is what <UnauthenticatedTemplate> in App.js watches -- so the login page appears
 * on the next render with no navigation at all.
 */
export async function endExpiredSession() {
  if (ending || !msalInstance) return;
  ending = true;

  // Wrapped because storage can throw outright in a locked-down browser -- and if it does, the
  // sign-out still has to happen. A missing notice is a worse login page; a skipped sign-out is a
  // stuck app.
  try {
    window.sessionStorage.setItem(FLAG, "1");
  } catch {
    // Ignored on purpose: see above.
  }

  try {
    await msalInstance.clearCache();
  } catch {
    // If MSAL cannot clear itself the app would sit on a dead session showing errors, which is the
    // exact state this exists to prevent -- so fall back to the blunt instrument.
    window.location.reload();
  } finally {
    ending = false;
  }
}

/**
 * True once, for the login page, if we arrived here because a session expired rather than because
 * somebody opened the app signed out.
 *
 * Reads and clears in one step so a later manual sign-out does not re-show "your session expired".
 */
export function consumeSessionExpiredNotice() {
  try {
    const had = window.sessionStorage.getItem(FLAG) === "1";
    if (had) window.sessionStorage.removeItem(FLAG);
    return had;
  } catch {
    return false;
  }
}
