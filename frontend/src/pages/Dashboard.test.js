import React from "react";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import Dashboard from "./Dashboard";
import * as client from "../api/client";

jest.mock("../api/client");

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

/**
 * The Dashboard tiles.
 *
 * Every tile is a way in to the work it counts, so what these tests pin down is that the click lands
 * on the rows the number actually counted -- a "Pending Approvals: 3" that opens an unfiltered page
 * makes the reader do the filtering again by hand, and a Servers popup that lists servers the tile
 * did not count is worse than no popup.
 *
 * The counts themselves are scoped server-side (DashboardServiceTest); here the summary is fed in
 * pre-scoped, exactly as the API returns it.
 */

function serverRow(overrides = {}) {
  return {
    serverId: 1,
    serverName: "https://acme.example.com",
    productType: "MESSAGE",
    projectId: 7,
    projectName: "Acme Migration",
    deltaReady: false,
    deltaReadyCombinations: [],
    ...overrides,
  };
}

function summary(overrides = {}) {
  return {
    totalApprovalRequests: 3,
    devApprovalsPending: 1,
    devApprovalsDone: 0,
    migrationManagerApprovalsPending: 2,
    migrationManagerApprovalsDone: 0,
    serversReadyToDecommission: 0,
    serversDecommissioned: 0,
    decommissionReadyServers: [],
    finalDeltasComplete: 0,
    preDeltaCyclesCompleted: 0,
    preDeltasInFlight: 0,
    servers: [],
    ...overrides,
  };
}

function renderDashboard() {
  return render(
    <MemoryRouter>
      <Dashboard />
    </MemoryRouter>
  );
}

/**
 * The tile carrying exactly this label.
 *
 * Matched on the label element with an exact string rather than by accessible name: "Servers" as a
 * name regex also matches "Servers To Decommission", and "Projects" also matches the "View All
 * Projects" button further down the page.
 */
function tile(label) {
  return screen.getByText(label, { selector: ".kpi__label" }).closest(".kpi");
}

beforeEach(() => {
  jest.clearAllMocks();
  client.getDashboardSummary.mockResolvedValue(summary());
  client.getProjects.mockResolvedValue([{ id: 7, name: "Acme Migration", serverCount: 1 }]);
});

describe("Dashboard tile navigation", () => {
  test("the Projects tile opens the Projects page", async () => {
    renderDashboard();
    await screen.findByText("Dashboard");

    await userEvent.click(tile("Projects"));

    expect(mockNavigate).toHaveBeenCalledWith("/projects");
  });

  test("Pending Approvals opens the Approvals page already filtered to pending", async () => {
    // The whole point: the tile counts pending approvals, so the page it opens must show those and
    // not every approval ever recorded.
    renderDashboard();
    await screen.findByText("Dashboard");

    await userEvent.click(tile("Pending Approvals"));

    expect(mockNavigate).toHaveBeenCalledWith("/approvals?status=PENDING");
  });

  test("Open Tickets opens the Tickets page already filtered to open", async () => {
    renderDashboard();
    await screen.findByText("Dashboard");

    await userEvent.click(tile("Open Tickets"));

    expect(mockNavigate).toHaveBeenCalledWith("/tickets?status=OPEN");
  });
});

describe("Dashboard server popups", () => {
  test("the Servers tile lists each server with its product type and project", async () => {
    // A server name alone does not identify it -- the same name recurs across projects.
    client.getDashboardSummary.mockResolvedValue(
      summary({ servers: [serverRow(), serverRow({ serverId: 2, serverName: "https://beta.example.com" })] })
    );
    renderDashboard();
    await screen.findByText("Dashboard");

    await userEvent.click(tile("Servers"));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("https://acme.example.com")).toBeInTheDocument();
    expect(within(dialog).getByText("https://beta.example.com")).toBeInTheDocument();
    expect(within(dialog).getAllByText("Message")).toHaveLength(2);
    expect(within(dialog).getAllByText(/Acme Migration/)).toHaveLength(2);
  });

  test("the Delta Ready tile lists only ready servers, and names which combinations are ready", async () => {
    // "Server X is Delta Ready" is ambiguous when only some of its combinations are, so the row says
    // which -- each combination runs its own independent sign-off chain.
    client.getDashboardSummary.mockResolvedValue(
      summary({
        servers: [
          serverRow({ deltaReady: true, deltaReadyCombinations: ["Teams to Slack"] }),
          serverRow({ serverId: 2, serverName: "https://notready.example.com" }),
        ],
      })
    );
    renderDashboard();
    await screen.findByText("Dashboard");

    await userEvent.click(tile("Delta Ready"));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("https://acme.example.com")).toBeInTheDocument();
    expect(within(dialog).queryByText("https://notready.example.com")).not.toBeInTheDocument();
    expect(within(dialog).getByText("Teams to Slack")).toBeInTheDocument();
  });

  test("the tile's number matches the number of rows its popup lists", async () => {
    // Both come from the same server list for exactly this reason. Two derivations of "how many
    // servers" is how a tile and the list it opens quietly start disagreeing.
    client.getDashboardSummary.mockResolvedValue(
      summary({
        servers: [
          serverRow({ deltaReady: true }),
          serverRow({ serverId: 2, serverName: "https://b.example.com", deltaReady: true }),
          serverRow({ serverId: 3, serverName: "https://c.example.com" }),
        ],
      })
    );
    renderDashboard();
    await screen.findByText("Dashboard");

    expect(tile("Servers")).toHaveTextContent("3");
    expect(tile("Delta Ready")).toHaveTextContent("2");

    await userEvent.click(tile("Delta Ready"));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getAllByRole("button", { name: /example\.com/ })).toHaveLength(2);
  });

  test("clicking a row in the popup opens that server's project", async () => {
    client.getDashboardSummary.mockResolvedValue(summary({ servers: [serverRow()] }));
    renderDashboard();
    await screen.findByText("Dashboard");

    await userEvent.click(tile("Servers"));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: /acme\.example\.com/ }));

    expect(mockNavigate).toHaveBeenCalledWith("/projects/7");
  });

  test("falls back to the project counts when the backend sends no server list", async () => {
    // A backend older than the `servers` field returns a summary without it. Reading .length off a
    // missing array turned a real count into 0 -- a metric tile showing a confident wrong number,
    // which is worse than showing nothing. Caught on a running app: Servers read 0 while the one
    // project on screen plainly had 3.
    const { servers, ...withoutServers } = summary();
    client.getDashboardSummary.mockResolvedValue(withoutServers);
    client.getProjects.mockResolvedValue([
      { id: 7, name: "Demo prjct", serverCount: 3, readyServerCount: 1 },
    ]);
    renderDashboard();
    await screen.findByText("Dashboard");

    expect(tile("Servers")).toHaveTextContent("3");
    expect(tile("Delta Ready")).toHaveTextContent("1");
  });

  test("the popup explains itself when the backend sent no list, instead of claiming none exist", async () => {
    // "No servers yet." under a tile reading 3 would be a straight contradiction.
    const { servers, ...withoutServers } = summary();
    client.getDashboardSummary.mockResolvedValue(withoutServers);
    client.getProjects.mockResolvedValue([{ id: 7, name: "Demo prjct", serverCount: 3 }]);
    renderDashboard();
    await screen.findByText("Dashboard");

    await userEvent.click(tile("Servers"));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/needs a newer backend/i)).toBeInTheDocument();
    expect(within(dialog).queryByText("No servers yet.")).not.toBeInTheDocument();
  });

  test("an empty Delta Ready list says so rather than showing a blank dialog", async () => {
    renderDashboard();
    await screen.findByText("Dashboard");

    await userEvent.click(tile("Delta Ready"));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/No server is Delta Ready yet/i)).toBeInTheDocument();
  });
});
