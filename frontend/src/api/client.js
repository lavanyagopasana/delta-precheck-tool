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
export const assignServerProject = (serverId, projectId) =>
  client.post(`/servers/${serverId}/project`, { projectId }).then((r) => r.data);

export const getProjects = () => client.get("/projects").then((r) => r.data);
export const getProjectDetail = (id) => client.get(`/projects/${id}`).then((r) => r.data);
export const createProject = (payload) => client.post("/projects", payload).then((r) => r.data);
export const updateProjectAssignments = (id, payload) =>
  client.patch(`/projects/${id}/assignments`, payload).then((r) => r.data);

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

export const importWorkspacePairsCsvGlobal = (file, projectId) => {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("projectId", projectId);
  return client
    .post("/pairs/import", formData, { headers: { "Content-Type": "multipart/form-data" } })
    .then((r) => r.data);
};

export const SAMPLE_CSV_COLUMNS_GLOBAL = ["server_name", ...SAMPLE_CSV_COLUMNS];

export const updatePreCheckItem = (serverId, itemId, payload) =>
  client.post(`/servers/${serverId}/precheck-items/${itemId}`, payload).then((r) => r.data);
export const checkAllPreCheckItems = (serverId, status, updatedBy) =>
  client
    .post(`/servers/${serverId}/precheck-items/check-all`, null, { params: { status, updatedBy } })
    .then((r) => r.data);

export const getPreCheckSubmission = (serverId, viewerEmail) =>
  client.get(`/servers/${serverId}/precheck-submission`, { params: { viewerEmail } }).then((r) => r.data);
export const submitPreCheckForReview = (serverId, payload) =>
  client.post(`/servers/${serverId}/precheck-submission/submit`, payload).then((r) => r.data);

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

export const getEscalations = () => client.get("/escalations").then((r) => r.data);
export const getOpenEscalationCount = () =>
  client.get("/escalations/open-count").then((r) => r.data.count);
export const createEscalation = (payload) => client.post("/escalations", payload).then((r) => r.data);
export const resolveEscalation = (id, resolutionNotes) =>
  client.patch(`/escalations/${id}/resolve`, { resolutionNotes }).then((r) => r.data);

export const approveSignOff = (serverId, role, approverEmail, qaRequired) =>
  client.post(`/servers/${serverId}/signoffs/${role}/approve`, { approverEmail, qaRequired }).then((r) => r.data);
export const declineSignOff = (serverId, role, approverEmail) =>
  client.post(`/servers/${serverId}/signoffs/${role}/decline`, { approverEmail }).then((r) => r.data);
export const getSignOffApprovals = () => client.get("/signoff-approvals").then((r) => r.data);

export default client;
