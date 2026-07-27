import { PublicClientApplication } from "@azure/msal-browser";
import { msalConfig, AUTH_CONFIGURED } from "./authConfig";

export const msalInstance = AUTH_CONFIGURED ? new PublicClientApplication(msalConfig) : null;
