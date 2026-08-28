import React from "react";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import ProjectDetailsPage from "./ProjectDetailsPage";
import { ToastProvider } from "../components/Toast";
import { ConfirmProvider } from "../components/ConfirmDialog";
import { CurrentUserContext } from "../auth/CurrentUserContext";
import * as client from "../api/client";

jest.mock("../api/client");

// The page reads :id from the route, so it has to be rendered under a matching path.
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useParams: () => ({ id: "1" }),
}));

const ADMIN = { email: "admin@cloudfuze.com", role: "ADMIN", allowed: true };

function renderPage(user = ADMIN) {
  return render(
    <MemoryRouter>
      <CurrentUserContext.Provider value={user}>
        <ToastProvider>
          <ConfirmProvider>
            <ProjectDetailsPage />
          </ConfirmProvider>
        </ToastProvider>
      </CurrentUserContext.Provider>
    </MemoryRouter>
  );
}

function project(overrides = {}) {
  return {
    id: 1,
    name: "Acme Migration",
    createdBy: "admin@cloudfuze.com",
    migrationManagerName: "harika.velidi@cloudfuze.com",
    engineerEmails: [],
    servers: [],
    ...overrides,
  };
}

// Engineers moved to the Team page in the sidebar -- this page only shows the project's name and
// its Migration Manager, with no engineer list and no way to assign one.
describe("ProjectDetailsPage header", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // The page loads Metabase's database list on mount for the Metabase Database dropdown.
    // Stubbed empty here because these tests are about the header, not that dropdown -- but it has
    // to be a resolved promise, since an auto-mock returns undefined and the page would throw on
    // .then().
    client.getMetabaseDatabases.mockResolvedValue([]);
  });

  test("shows the project name and its Migration Manager, with no engineers section", async () => {
    client.getProjectDetail.mockResolvedValue(project());

    renderPage();
    await screen.findByText("Acme Migration");

    expect(screen.getByText("harika.velidi@cloudfuze.com")).toBeInTheDocument();
    expect(screen.queryByText(/Migration Engineers/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /add engineer/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^save$/i })).not.toBeInTheDocument();
  });

  test("shows a placeholder when no manager is assigned", async () => {
    client.getProjectDetail.mockResolvedValue(project({ migrationManagerName: null }));

    renderPage();
    await screen.findByText("Acme Migration");

    expect(screen.getByText("Not assigned yet")).toBeInTheDocument();
  });
});
