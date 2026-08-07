import axios from "axios";
import { getAccessToken } from "../auth/getAccessToken";

// Backend origin resolves at runtime (deploy-time file first, then build-time env, then this page's
// own origin) so a bundle built anywhere works anywhere -- see config/runtimeConfig.js.
import { BACKEND_BASE } from "../config/runtimeConfig";

const API_BASE = `${BACKEND_BASE}/api`;

const client = axios.create({ baseURL: API_BASE });

client.interceptors.request.use(async (config) => {
  const token = await getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
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
// completed its Final Delta. It ERASES the server and everything under it and returns 204 -- there is
// deliberately no undo counterpart, since there would be nothing left to restore.
export const decommissionServer = (serverId) =>
  client.post(`/servers/${serverId}/decommission`).then((r) => r.data);

export const getProjects = () => client.get("/projects").then((r) => r.data);
export const getProjectDetail = (id) => client.get(`/projects/${id}`).then((r) => r.data);
export const createProject = (payload) => client.post("/projects", payload).then((r) => r.data);
export const updateProjectAssignments = (id, payload) =>
  client.patch(`/projects/${id}/assignments`, payload).then((r) => r.data);
export const updateProjectDetails = (id, payload) =>
  client.patch(`/projects/${id}`, payload).then((r) => r.data);
export const removeProject = (id) => client.delete(`/projects/${id}`).then((r) => r.data);

export const getRoster = () => client.get("/roster").then((r) => r.data);

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

export const uploadEvidence = (file) => {
  const formData = new FormData();
  formData.append("file", file);
  return client
    .post("/upload", formData, { headers: { "Content-Type": "multipart/form-data" } })
    .then((r) => r.data);
};

export const getCurrentUser = () => client.get("/me").then((r) => r.data);

export const getAllowedUsers = () => client.get("/admin/users").then((r) => r.data);
export const upsertAllowedUser = (payload) => client.post("/admin/users", payload).then((r) => r.data);
export const removeAllowedUser = (email) =>
  client.delete(`/admin/users/${encodeURIComponent(email)}`).then((r) => r.data);

export const importUsersCsv = (file, role) => {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("role", role);
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
export const declineSignOff = (combinationId, role, approverEmail) =>
  client.post(`/combinations/${combinationId}/signoffs/${role}/decline`, { approverEmail }).then((r) => r.data);
export const getSignOffApprovals = () => client.get("/signoff-approvals").then((r) => r.data);

export default client;
