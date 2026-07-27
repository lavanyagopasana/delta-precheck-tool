import { InteractionRequiredAuthError } from "@azure/msal-browser";
import { msalInstance } from "./msalInstance";
import { loginRequest } from "./authConfig";

// We only need to identify the signed-in user (no Graph calls, no custom API scope exposed on
// the app registration), so the ID token doubles as the bearer credential presented to our API.
// The backend validates its signature/issuer/audience the same way it would an access token.
export async function getAccessToken() {
  if (!msalInstance) return null;

  const account = msalInstance.getAllAccounts()[0];
  if (!account) return null;

  try {
    const result = await msalInstance.acquireTokenSilent({ ...loginRequest, account });
    return result.idToken;
  } catch (err) {
    if (err instanceof InteractionRequiredAuthError) {
      await msalInstance.acquireTokenRedirect(loginRequest);
      return null;
    }
    throw err;
  }
}
