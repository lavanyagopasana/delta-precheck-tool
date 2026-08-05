import axios from "axios";
import { getAccessToken } from "../auth/getAccessToken";

// Set REACT_APP_API_BASE in frontend/.env.local (or the deployed build's env) to point at a
// non-localhost backend -- localhost:8080 stays the default for local dev.
const BACKEND_BASE = process.env.REACT_APP_API_BASE || "http://localhost:8080";
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
// neither a server_url nor a combination column -- just the four fields below.
export const SAMPLE_CSV_COLUMNS_COMBINATION = ["source_email", "source_path", "destination_email", "destination_path"];

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
