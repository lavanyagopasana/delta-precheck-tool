import axios from "axios";
import { getAccessToken } from "../auth/getAccessToken";
import { endExpiredSession } from "../auth/sessionExpiry";

// Backend origin resolves at runtime (deploy-time file first, then build-time env, then this page's
// own origin) so a bundle built anywhere works anywhere -- see config/runtimeConfig.js.
import { BACKEND_BASE } from "../config/runtimeConfig";
import { MAX_EVIDENCE_FILE_SIZE_LABEL } from "../constants";

const API_BASE = `${BACKEND_BASE}/api`;

const client = axios.create({ baseURL: API_BASE });

client.interceptors.request.use(async (config) => {
  const token = await getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Every error the app surfaces comes from GlobalExceptionHandler as
// {timestamp,status,error,message}, and the UI reads err.response.data.message. A rejection that
// happens BEFORE the request reaches Spring does not follow that shape: a reverse proxy answers
// with its own HTML error page, so the UI finds no message and shows nothing useful. That is not
// hypothetical -- measured on the deployed site 2026-08-12, an evidence upload over the proxy's
// client_max_body_size returned a raw nginx "413 Request Entity Too Large" HTML page and the
// upload appeared to fail for no stated reason.
//
// This normalises those cases into the same shape the rest of the app already handles, so a
// misconfigured proxy produces an error that names itself instead of a mystery.
// A 401 from our own API means the bearer token was rejected, which in practice means it expired --
// authorization failures (not on the allowlist, wrong role) come back as 403, not 401.
//
// This is not a rare edge: MSAL decides whether to renew by looking at the ACCESS token's expiry,
// but what we send is the ID token (see auth/getAccessToken.js for why). A cached entry MSAL still
// considers fresh can therefore hand back an ID token that has already expired, and every call on
// the page starts failing at once.
//
// One forced refresh and one retry. If that still fails the session is genuinely over, and the
// right outcome is the login screen -- not an error on every panel of a page the person can no
// longer load anything into. Before this, an hour-old tab showed "Something went wrong (401)" with
// no way forward but signing out by hand.
client.interceptors.response.use(undefined, async (error) => {
  const original = error.config;
  if (error.response?.status !== 401 || !original) return Promise.reject(error);

  if (!original._retriedAfter401) {
    original._retriedAfter401 = true;
    try {
      const fresh = await getAccessToken(true);
      if (fresh) {
        original.headers = { ...original.headers, Authorization: `Bearer ${fresh}` };
        return await client.request(original);
      }
    } catch (retryError) {
      // Only a second 401 means the session is finished. A 500 or a dropped connection on the
      // retry is a different problem and has to keep its own error, or a flaky network would log
      // people out.
      if (retryError?.response && retryError.response.status !== 401) {
        return Promise.reject(retryError);
      }
    }
  }

  await endExpiredSession();
  // Deliberately never settles. endExpiredSession clears MSAL, which re-renders the app as the
  // login page; resolving or rejecting here would first let every in-flight caller paint its own
  // error message into a UI that is about to be replaced. This is a "we are navigating away"
  // promise, not a leak -- the components holding it are unmounted moments later.
  return new Promise(() => {});
});

client.interceptors.response.use(undefined, (error) => {
  const response = error.response;
  if (!response) return Promise.reject(error);

  const body = response.data;
  const isJsonEnvelope = body && typeof body === "object" && typeof body.message === "string";
  if (isJsonEnvelope) return Promise.reject(error);

  if (response.status === 413) {
    // What the person needs is the limit and that they are over it. The rest -- which proxy answered,
    // client_max_body_size, that three separate limits have to agree -- is operator detail: it cannot
    // be acted on by the engineer filling in a pre-check, and putting it on screen made a routine
    // "file too big" read like a system fault.
    //
    // It is still worth having when the limits genuinely disagree, so it goes to the console for
    // whoever is debugging rather than being thrown away.
    console.warn(
      `Upload rejected with 413. If the file was under ${MAX_EVIDENCE_FILE_SIZE_LABEL}, a proxy in ` +
        `front of the app rejected it before Spring saw it -- raise client_max_body_size to match ` +
        `spring.servlet.multipart.max-file-size.`
    );
    response.data = {
      status: 413,
      error: "Payload Too Large",
      message: `That file is too large. The limit is ${MAX_EVIDENCE_FILE_SIZE_LABEL}.`,
    };
    return Promise.reject(error);
  }

  // Any other non-JSON error body means something upstream of the app answered -- a gateway,
  // a load balancer, a proxy returning its own page. Say so rather than rendering nothing.
  if (typeof body === "string" || body == null) {
    response.data = {
      status: response.status,
      error: response.statusText || "Request failed",
      // Same reasoning as the 413 above: "a proxy or gateway answered instead of the app" is not
      // something the person on the page can do anything with. The status code is kept because it is
      // what they will quote when they report it.
      message: `Something went wrong (${response.status}). Please try again, or report it if it keeps happening.`,
    };
  }
  return Promise.reject(error);
});

export const FILE_BASE = BACKEND_BASE;

export const getDashboardSummary = () => client.get("/dashboard/summary").then((r) => r.data);

export const getServers = () => client.get("/servers").then((r) => r.data);
export const getServerReadiness = (serverId) =>
  client.get(`/servers/${serverId}/readiness`).then((r) => r.data);

// Pre-check/sign-off/Delta lifecycle are per-combination, not per-server -- see
// WorkspaceCombination on the backend. ServerReadinessDto.combinations lists a server's
// combinations (id, name, pairCount, status); fetch one's own readiness here.
export const getCombinationReadiness = (combinationId) =>
  client.get(`/combinations/${combinationId}`).then((r) => r.data);

// This combination's edit history, newest first. Empty today by design, not omission -- nothing
// edits a combination's details yet (no PATCH route exists), so this is wired ready for the day one
// does. Open to any allowlisted caller.
export const getCombinationHistory = (id) => client.get(`/combinations/${id}/history`).then((r) => r.data);
export const startCombinationDelta = (combinationId) =>
  client.post(`/combinations/${combinationId}/delta/start`).then((r) => r.data);
export const finishCombinationDelta = (combinationId) =>
  client.post(`/combinations/${combinationId}/delta/finish`).then((r) => r.data);

// A combination runs any number of Pre-Deltas before one Final Delta, and each finished cycle is
// archived with a frozen copy of its checklist and sign-offs. This returns that history, oldest first.
export const getDeltaCycles = (combinationId) =>
  client.get(`/combinations/${combinationId}/delta-cycles`).then((r) => r.data);

// Decommissioning is per-server (admin-only), available once every combination under a server has
// completed its Final Delta. It ERASES the server and everything under it and returns 204.
export const decommissionServer = (serverId) =>
  client.post(`/servers/${serverId}/decommission`).then((r) => r.data);

// Admin-only delete at any time — same cascade as decommission but no Final-Delta readiness guard.
export const deleteServer = (serverId) =>
  client.delete(`/servers/${serverId}`).then((r) => r.data);

// This server's edit history (product type), newest first. Server records nothing else about its
// own changes -- no creator, no modified-by -- so this trail is the only account of them. Open to
// any allowlisted caller.
export const getServerHistory = (id) => client.get(`/servers/${id}/history`).then((r) => r.data);

// Every user-mapping CSV uploaded against this server, newest first -- who, the filename, and for a
// re-upload how many existing pairs it replaced (those rows exist nowhere else afterwards). Open to
// any allowlisted caller.
export const getServerPairImports = (id) => client.get(`/servers/${id}/pair-imports`).then((r) => r.data);

export const getProjects = () => client.get("/projects").then((r) => r.data);

// Every project ever deleted, newest first. There is no page left for a deleted project, so this is
// the only place its deletion stays visible -- open to any allowlisted caller.
export const getDeletedProjects = () => client.get("/projects/deleted").then((r) => r.data);
export const getProjectDetail = (id) => client.get(`/projects/${id}`).then((r) => r.data);
export const createProject = (payload) => client.post("/projects", payload).then((r) => r.data);
export const updateProjectDetails = (id, payload) =>
  client.patch(`/projects/${id}`, payload).then((r) => r.data);
export const removeProject = (id) => client.delete(`/projects/${id}`).then((r) => r.data);

// This project's edit history (name, Migration Manager), newest first. A GET open to any
// allowlisted caller -- the trail is disclosure, so it is visible to everyone who can see the
// project, not gated to whoever may make the edit.
export const getProjectHistory = (id) => client.get(`/projects/${id}/history`).then((r) => r.data);
// Add a Metabase database to ONE product type on a project. A product type can be spread across
// several databases, so this appends rather than replaces; adding the same name twice is rejected
// (409). Open to the project's Migration Manager, an assigned engineer, or an admin.
export const setProjectMetabaseDatabase = (id, productType, databaseName) =>
  client.patch(`/projects/${id}/metabase`, { productType, databaseName }).then((r) => r.data);

// Remove one database from a product type. ADMIN ONLY -- adding widens the figures visibly, removing
// shrinks the ones a Delta was approved against with nothing on screen to say a source was dropped.
// Query params, not a body: axios drops the body on DELETE by default.
export const removeProjectMetabaseDatabase = (id, productType, databaseName) =>
  client
    .delete(`/projects/${id}/metabase`, { params: { productType, databaseName } })
    .then((r) => r.data);
// The live processStatus breakdown, one entry per product type the project has a database for.
// Fetched on demand (the "Get process status" button), never on page load -- it is several round
// trips to Metabase and a project usually doesn't need it.
export const getProjectMetabaseStatus = (id) =>
  client.get(`/projects/${id}/metabase-status`).then((r) => r.data);
// The databases Metabase can see, for the project page's dropdown. Returns [{id, name, engine}].
// Rejects (503) when Metabase isn't configured, which the caller treats as "fall back to a text
// field" rather than as a hard error -- the database name is still settable by hand without it.
export const getMetabaseDatabases = () => client.get("/metabase/databases").then((r) => r.data);

export const getRoster = () => client.get("/roster").then((r) => r.data);

// Teams scope the project dashboard's engineer picker to the project manager's own people.
// GET is open to any allowlisted caller; every mutation is ADMIN-only server-side.
export const getTeams = () => client.get("/teams").then((r) => r.data);
export const createTeam = (payload) => client.post("/teams", payload).then((r) => r.data);
export const updateTeam = (id, payload) => client.patch(`/teams/${id}`, payload).then((r) => r.data);
export const removeTeam = (id) => client.delete(`/teams/${id}`).then((r) => r.data);
// Sets the person's teams to exactly teamIds -- an empty array takes them off every team, which is
// why it is sent explicitly rather than omitted. Replace, not add: a caller that means "also put
// them on this team" sends the union it wants (see AdminUsersPage's team cards).
export const assignUserTeams = (email, teamIds) =>
  client.post("/teams/assign", { email, teamIds }).then((r) => r.data);

export const importWorkspacePairsCsv = (serverId, file) => {
  const formData = new FormData();
  formData.append("file", file);
  return client
    .post(`/servers/${serverId}/pairs/import`, formData, {
      headers: { "Content-Type": "multipart/form-data" },
    })
    .then((r) => r.data);
};

export const SAMPLE_CSV_COLUMNS = [
  "source_email",
  "source_path",
  "destination_email",
  "destination_path",
  "combination",
];

export const createServerForProject = (projectId, name, productType) =>
  client.post(`/projects/${projectId}/servers`, { name, productType: productType || null }).then((r) => r.data);

export const updateServerProductType = (serverId, productType) =>
  client.patch(`/servers/${serverId}`, { productType }).then((r) => r.data);

// Server + combination are both chosen in the UI before the file is picked, so this CSV carries
// neither a server_url nor a combination column -- just the fields below.
export const SAMPLE_CSV_COLUMNS_COMBINATION = ["source_email", "source_path", "destination_email", "destination_path"];

// Email migrations move mailboxes, not folder trees, so there is no source/destination path to give --
// just the two mailboxes. The backend already accepts this: WorkspacePairService.REQUIRED_COLUMNS is
// only source_email + destination_email, and both path columns are nullable, so a two-column file
// imports unchanged. This constant exists so the sample file and the "View CSV format" table stop
// advertising two columns an email engineer has nothing to put in.
export const SAMPLE_CSV_COLUMNS_COMBINATION_EMAIL = ["source_email", "destination_email"];

// Message's own column names, product-named rather than the generic source/destination email+path
// used everywhere else. Same four generic fields underneath (WorkspacePairService.COLUMN_ALIASES
// maps these header strings onto source_email/source_path/destination_email/destination_path) --
// this is only how the sample file, the export and the "View CSV format" table label and order them.
export const SAMPLE_CSV_COLUMNS_COMBINATION_MESSAGE = [
  "Channel name",
  "Destination Channel name",
  "Destination Team name",
  "Channel type",
];

// Only Email is genuinely two columns now (no folder tree to move). Message used to share this
// shape; it has its own four-column format instead (SAMPLE_CSV_COLUMNS_COMBINATION_MESSAGE), kept as
// a separate predicate rather than folded into usesTwoColumnCsv so a caller that only means "does
// this type skip the two path columns entirely" isn't accidentally also asked about Message's shape.
export const usesTwoColumnCsv = (productType) => productType === "EMAIL";

// Message's shape is distinct enough (its own column names AND a different field order --
// destination email comes second, not fourth) that a boolean can't express it. Callers that build a
// per-type shape should branch on this before falling back to usesTwoColumnCsv.
export const usesMessageCsvShape = (productType) => productType === "MESSAGE";

// The CSV shape for a server's product type.
export const sampleCsvColumnsForProductType = (productType) =>
  usesMessageCsvShape(productType)
    ? SAMPLE_CSV_COLUMNS_COMBINATION_MESSAGE
    : usesTwoColumnCsv(productType)
    ? SAMPLE_CSV_COLUMNS_COMBINATION_EMAIL
    : SAMPLE_CSV_COLUMNS_COMBINATION;

export const importWorkspacePairsCsvForCombination = (serverId, combination, file) => {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("combination", combination);
  return client
    .post(`/servers/${serverId}/pairs/import`, formData, {
      headers: { "Content-Type": "multipart/form-data" },
    })
    .then((r) => r.data);
};

export const deletePairsByCombination = (serverId, combination) =>
  client.delete(`/servers/${serverId}/pairs`, { params: { combination } }).then((r) => r.data);

export const updatePreCheckItem = (combinationId, itemId, payload) =>
  client.post(`/combinations/${combinationId}/precheck-items/${itemId}`, payload).then((r) => r.data);

export const getPreCheckSubmission = (combinationId, viewerEmail) =>
  client.get(`/combinations/${combinationId}/precheck-submission`, { params: { viewerEmail } }).then((r) => r.data);
export const submitPreCheckForReview = (combinationId, payload) =>
  client.post(`/combinations/${combinationId}/precheck-submission/submit`, payload).then((r) => r.data);
export const withdrawPreCheck = (combinationId) =>
  client.post(`/combinations/${combinationId}/precheck-submission/withdraw`).then((r) => r.data);

// onProgress, when given, is called with 0-100 as the body uploads. Needed because the limit is now
// 1GB: a screen recording over a normal office link takes minutes, and without this the UI just sat
// there looking hung with no way to tell "uploading" from "broken". Optional so existing callers that
// don't care are unaffected.
//
// axios only reports progress when the total length is known; it is for a FormData body, but the
// guard keeps this from emitting NaN if that ever stops holding.
export const uploadEvidence = (file, onProgress) => {
  const formData = new FormData();
  formData.append("file", file);
  return client
    .post("/upload", formData, {
      headers: { "Content-Type": "multipart/form-data" },
      onUploadProgress: onProgress
        ? (e) => {
            if (e.total) {
              onProgress(Math.round((e.loaded * 100) / e.total));
            }
          }
        : undefined,
    })
    .then((r) => r.data);
};

export const getCurrentUser = () => client.get("/me").then((r) => r.data);

export const getAllowedUsers = () => client.get("/admin/users").then((r) => r.data);
export const upsertAllowedUser = (payload) => client.post("/admin/users", payload).then((r) => r.data);
export const removeAllowedUser = (email) =>
  client.delete(`/admin/users/${encodeURIComponent(email)}`).then((r) => r.data);

// `role` is the fallback for rows whose own "role" column is blank or absent -- a file that gives
// every person a role needs none. Omitted from the form entirely when not set, rather than sent as
// the string "undefined", which Spring would reject as an unparseable AppUserRole.
export const importUsersCsv = (file, role) => {
  const formData = new FormData();
  formData.append("file", file);
  if (role) {
    formData.append("role", role);
  }
  return client
    .post("/admin/users/import-csv", formData, { headers: { "Content-Type": "multipart/form-data" } })
    .then((r) => r.data);
};

export const getTickets = () => client.get("/tickets").then((r) => r.data);
export const getOpenTicketCount = () =>
  client.get("/tickets/open-count").then((r) => r.data.count);
export const validateTicketUrl = (url) =>
  client.post("/tickets/validate-url", { url }).then((r) => r.data);
export const createTicket = (payload) => client.post("/tickets", payload).then((r) => r.data);
export const updateTicket = (id, payload) =>
  client.put(`/tickets/${id}`, payload).then((r) => r.data);
export const removeTicket = (id) => client.delete(`/tickets/${id}`).then((r) => r.data);

export const approveSignOff = (combinationId, role, approverEmail, qaRequired) =>
  client.post(`/combinations/${combinationId}/signoffs/${role}/approve`, { approverEmail, qaRequired }).then((r) => r.data);
// reason is required by the backend -- a decline ends this Delta cycle and reopens a blank pre-check
// for rework (it does NOT hand back to the previous approver), so without a reason whoever refills
// the checklist has no idea what to fix.
export const declineSignOff = (combinationId, role, reason, approverEmail) =>
  client
    .post(`/combinations/${combinationId}/signoffs/${role}/decline`, { approverEmail, reason })
    .then((r) => r.data);
export const getSignOffApprovals = () => client.get("/signoff-approvals").then((r) => r.data);

export default client;
