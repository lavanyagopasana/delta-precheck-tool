import { InteractionRequiredAuthError } from "@azure/msal-browser";
import { msalInstance } from "./msalInstance";
import { loginRequest } from "./authConfig";

// We only need to identify the signed-in user (no Graph calls, no custom API scope exposed on
// the app registration), so the ID token doubles as the bearer credential presented to our API.
// The backend validates its signature/issuer/audience the same way it would an access token.
// forceRefresh bypasses MSAL's cache and goes back to Azure for a new token. Needed because MSAL
// decides whether to renew by looking at the ACCESS token's expiry, while what we send is the ID
// token -- so a cached-but-expired ID token can be handed back while MSAL considers the entry
// fresh, and our API answers 401. The response interceptor in api/client.js calls this once on a
// 401 before giving up.
export async function getAccessToken(forceRefresh = false) {
  if (!msalInstance) return null;

  const account = msalInstance.getAllAccounts()[0];
  if (!account) return null;

  try {
    const result = await msalInstance.acquireTokenSilent({ ...loginRequest, account, forceRefresh });
    return result.idToken;
  } catch (err) {
    if (err instanceof InteractionRequiredAuthError) {
      await msalInstance.acquireTokenRedirect(loginRequest);
      return null;
    }
    throw err;
  }
}
