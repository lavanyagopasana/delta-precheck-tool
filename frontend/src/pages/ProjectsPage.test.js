import React from "react";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import ProjectsPage from "./ProjectsPage";
import { ToastProvider } from "../components/Toast";
import { ConfirmProvider } from "../components/ConfirmDialog";
import { CurrentUserContext } from "../auth/CurrentUserContext";
import * as client from "../api/client";

jest.mock("../api/client");

jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => jest.fn(),
}));

const ADMIN = { email: "admin@cloudfuze.com", role: "ADMIN", allowed: true };

/**
 * Deleting a project, and what the page does while it happens.
 *
 * Reported from the deployed app: deleting a project blanked the whole page to a bare
 * "Loading projects..." line, then re-rendered the table without the deleted row. Two separate
 * causes, both fixed here and both pinned down below --
 *
 *   1. `if (loading) return <p>Loading projects...</p>` replaced the entire rendered page on EVERY
 *      refresh, not just the first, so any reload flashed the heading, filters and table away.
 *   2. The delete handler refetched the whole list to be told the row was gone.
 */

function project(overrides = {}) {
  return {
    id: 1,
    name: "Demo prjct",
    serverCount: 3,
    readyServerCount: 0,
    notReadyServerCount: 3,
    openEscalationCount: 0,
    migrationManagers: [],
    engineerEmails: [],
    productTypes: [],
    createdBy: "admin@cloudfuze.com",
    ...overrides,
  };
}

function renderPage(user = ADMIN) {
  return render(
    <MemoryRouter>
      <CurrentUserContext.Provider value={user}>
        <ToastProvider>
          <ConfirmProvider>
            <ProjectsPage />
          </ConfirmProvider>
        </ToastProvider>
      </CurrentUserContext.Provider>
    </MemoryRouter>
  );
}

/**
 * Walks the confirm step the delete button opens. ConfirmDialog renders role="alertdialog" (not
 * "dialog", which is what Modal uses) -- the distinction matters in the refresh test below, where
 * both kinds are on screen.
 */
async function confirmDelete(rowName) {
  const row = screen.getByText(rowName).closest("tr");
  await userEvent.click(within(row).getByRole("button", { name: /delete/i }));
  const dialog = await screen.findByRole("alertdialog");
  await userEvent.click(within(dialog).getByRole("button", { name: /delete project/i }));
}

beforeEach(() => {
  jest.clearAllMocks();
  client.getRoster.mockResolvedValue({ migrationManagers: [], engineers: [] });
  client.getProjects.mockResolvedValue([project(), project({ id: 2, name: "Keep me" })]);
  client.removeProject.mockResolvedValue({});
});

describe("ProjectsPage delete", () => {
  test("removes the row immediately without refetching the list", async () => {
    renderPage();
    expect(await screen.findByText("Demo prjct")).toBeInTheDocument();
    // One call from the initial mount. Anything more is the reload this fix removed.
    expect(client.getProjects).toHaveBeenCalledTimes(1);

    await confirmDelete("Demo prjct");

    await waitFor(() => expect(screen.queryByText("Demo prjct")).not.toBeInTheDocument());
    expect(client.removeProject).toHaveBeenCalledWith(1);
    expect(client.getProjects).toHaveBeenCalledTimes(1);
  });

  test("leaves the other projects on screen", async () => {
    // The row goes, the page does not. Guards against "optimistic" turning into a wholesale reset.
    renderPage();
    await screen.findByText("Demo prjct");

    await confirmDelete("Demo prjct");

    await waitFor(() => expect(screen.queryByText("Demo prjct")).not.toBeInTheDocument());
    expect(screen.getByText("Keep me")).toBeInTheDocument();
    expect(screen.getByText("Projects")).toBeInTheDocument();
  });

  test("keeps the row when the delete fails", async () => {
    // Optimistic removal must be driven by the server having actually succeeded -- the update happens
    // after the await, so a rejection leaves the list untouched rather than hiding a project that is
    // still there.
    client.removeProject.mockRejectedValue({ response: { data: { message: "Nope." } } });
    renderPage();
    await screen.findByText("Demo prjct");

    await confirmDelete("Demo prjct");

    await screen.findByText(/Nope\./);
    expect(screen.getByText("Demo prjct")).toBeInTheDocument();
  });

  test("does not blank the page while a refresh is in flight", async () => {
    // The white screen itself. A refresh that never resolves used to leave nothing but
    // "Loading projects..."; the already-rendered table must survive it.
    let resolveSecond;
    client.getProjects
      .mockResolvedValueOnce([project()])
      .mockReturnValueOnce(new Promise((res) => {
        resolveSecond = res;
      }));

    renderPage();
    await screen.findByText("Demo prjct");

    // Editing is one of the flows that legitimately still refetches: changing the manager rolls the
    // approval chain back, so the server recomputes fields this page displays and there is nothing
    // safe to be optimistic with. (Create would do as well, but it cannot run in a test -- it needs
    // either a manager creator or a picked manager, and creatorIsManager is gated on AUTH_CONFIGURED,
    // which is false without REACT_APP_AZURE_CLIENT_ID.)
    client.updateProjectDetails.mockResolvedValue({});
    await userEvent.click(screen.getAllByRole("button", { name: /edit project/i })[0]);
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: /save changes/i }));

    // Mid-refresh: the table is still there, and the blanking line is not.
    await waitFor(() => expect(client.getProjects).toHaveBeenCalledTimes(2));
    expect(screen.getByText("Demo prjct")).toBeInTheDocument();
    expect(screen.queryByText("Loading projects...")).not.toBeInTheDocument();

    resolveSecond([project(), project({ id: 3, name: "New one" })]);
    expect(await screen.findByText("New one")).toBeInTheDocument();
  });
});
