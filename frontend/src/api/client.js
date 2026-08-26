import axios from "axios";
import { getAccessToken } from "../auth/getAccessToken";

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
client.interceptors.response.use(undefined, (error) => {
  const response = error.response;
  if (!response) return Promise.reject(error);

  const body = response.data;
  const isJsonEnvelope = body && typeof body === "object" && typeof body.message === "string";
  if (isJsonEnvelope) return Promise.reject(error);

  if (response.status === 413) {
    response.data = {
      status: 413,
      error: "Payload Too Large",
      message:
        `That file is larger than the server accepts (limit ${MAX_EVIDENCE_FILE_SIZE_LABEL}). ` +
        `If the file is under that, a proxy in front of the app is rejecting it first and its ` +
        `client_max_body_size needs raising to match.`,
    };
    return Promise.reject(error);
  }

  // Any other non-JSON error body means something upstream of the app answered -- a gateway,
  // a load balancer, a proxy returning its own page. Say so rather than rendering nothing.
  if (typeof body === "string" || body == null) {
    response.data = {
      status: response.status,
      error: response.statusText || "Request failed",
      message:
        `The server returned ${response.status} without an application error. This usually means a ` +
        `proxy or gateway answered instead of the app.`,
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

export const getProjects = () => client.get("/projects").then((r) => r.data);
export const getProjectDetail = (id) => client.get(`/projects/${id}`).then((r) => r.data);
export const createProject = (payload) => client.post("/projects", payload).then((r) => r.data);
export const updateProjectAssignments = (id, payload) =>
  client.patch(`/projects/${id}/assignments`, payload).then((r) => r.data);
export const updateProjectDetails = (id, payload) =>
  client.patch(`/projects/${id}`, payload).then((r) => r.data);
export const removeProject = (id) => client.delete(`/projects/${id}`).then((r) => r.data);

// Pulls the project list from the PMO tool on demand (ADMIN only). A background poll already does
// this every 5 minutes -- this is for an admin who has just created a project over there and doesn't
// want to wait. Returns { totalRows, createdCount, updatedCount, unchangedCount,
// skippedByStatusCount, errors }.
export const syncPmoProjects = () => client.post("/pmo/sync").then((r) => r.data);

export const getRoster = () => client.get("/roster").then((r) => r.data);

// Teams scope the project dashboard's engineer picker to the project manager's own people.
// GET is open to any allowlisted caller; every mutation is ADMIN-only server-side.
export const getTeams = () => client.get("/teams").then((r) => r.data);
export const createTeam = (payload) => client.post("/teams", payload).then((r) => r.data);
export const updateTeam = (id, payload) => client.patch(`/teams/${id}`, payload).then((r) => r.data);
export const removeTeam = (id) => client.delete(`/teams/${id}`).then((r) => r.data);
// teamId: null takes the person off every team, which is why it is sent explicitly rather than omitted.
export const assignUserTeam = (email, teamId) =>
  client.post("/teams/assign", { email, teamId }).then((r) => r.data);

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

// Email and Message migrate accounts, not folder trees, so both take only the two columns; Content is
// the only type with paths. One predicate rather than an inline `=== "EMAIL"` in each place, because
// that check had already been written in four spots and adding Message meant finding all of them.
export const usesTwoColumnCsv = (productType) => productType === "EMAIL" || productType === "MESSAGE";

// The CSV shape for a server's product type.
export const sampleCsvColumnsForProductType = (productType) =>
  usesTwoColumnCsv(productType) ? SAMPLE_CSV_COLUMNS_COMBINATION_EMAIL : SAMPLE_CSV_COLUMNS_COMBINATION;

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
