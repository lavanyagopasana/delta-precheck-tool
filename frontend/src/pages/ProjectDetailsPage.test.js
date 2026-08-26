import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

// Team 1's manager owns siva; Team 2's owns ramana. The whole point of the feature is that
// picking a manager must not expose the other team's engineers.
const ROSTER = {
  migrationManagers: ["harika.velidi@cloudfuze.com", "raghu.yellani@cloudfuze.com"],
  engineers: [
    "siva.kota@cloudfuze.com",
    "ramana.reddy@cloudfuze.com",
    "swaroop@cloudfuze.com",
  ],
  engineersByManager: {
    "harika.velidi@cloudfuze.com": ["siva.kota@cloudfuze.com"],
    "raghu.yellani@cloudfuze.com": ["ramana.reddy@cloudfuze.com"],
  },
};

// The picker only lists options once opened, so every assertion goes through this. Matched on the
// exact aria-label: a loose /add/i also matches "Add Server" on this page and is ambiguous.
async function openEngineerPicker() {
  const addButton = await waitFor(() => screen.getByRole("button", { name: "Add engineer" }));
  await userEvent.click(addButton);
}

describe("ProjectDetailsPage engineer scoping", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    client.getRoster.mockResolvedValue(ROSTER);
  });

  test("offers only the engineers on the project manager's team", async () => {
    client.getProjectDetail.mockResolvedValue(project());

    renderPage();
    await screen.findByText("Acme Migration");
    await openEngineerPicker();

    expect(await screen.findByText("siva.kota@cloudfuze.com")).toBeInTheDocument();
    // Team 2's engineer and the team-less engineer must both be absent.
    expect(screen.queryByText("ramana.reddy@cloudfuze.com")).not.toBeInTheDocument();
    expect(screen.queryByText("swaroop@cloudfuze.com")).not.toBeInTheDocument();
  });

  test("falls back to every engineer, with a notice, when the manager has no team", async () => {
    // A manager who exists but is absent from engineersByManager. A strict filter would render an
    // empty dropdown here and make assignment impossible with nothing explaining why.
    client.getProjectDetail.mockResolvedValue(
      project({ migrationManagerName: "unassigned.manager@cloudfuze.com" })
    );

    renderPage();
    await screen.findByText("Acme Migration");

    expect(await screen.findByText(/isn't on a team yet/i)).toBeInTheDocument();

    await openEngineerPicker();
    expect(await screen.findByText("siva.kota@cloudfuze.com")).toBeInTheDocument();
    expect(screen.getByText("ramana.reddy@cloudfuze.com")).toBeInTheDocument();
  });

  test("explains the unfiltered list when no manager is assigned at all", async () => {
    client.getProjectDetail.mockResolvedValue(project({ migrationManagerName: null }));

    renderPage();
    await screen.findByText("Acme Migration");

    expect(await screen.findByText(/No Migration Manager is assigned yet/i)).toBeInTheDocument();
  });

  test("keeps an already-assigned engineer visible after they leave the team", async () => {
    // ramana is saved on the project but belongs to Team 2, so the scoped list excludes them.
    // Dropping them from the picker would hide a chip that is still assigned in the database.
    client.getProjectDetail.mockResolvedValue(
      project({ engineerEmails: ["ramana.reddy@cloudfuze.com"] })
    );

    renderPage();
    await screen.findByText("Acme Migration");

    expect(await screen.findByText("ramana.reddy@cloudfuze.com")).toBeInTheDocument();
  });
});
