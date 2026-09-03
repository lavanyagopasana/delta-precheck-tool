import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import ApprovalsPage from "./ApprovalsPage";
import { ToastProvider } from "../components/Toast";
import { ConfirmProvider } from "../components/ConfirmDialog";
import { CurrentUserContext } from "../auth/CurrentUserContext";
import * as client from "../api/client";

jest.mock("../api/client");
// Pinned ON regardless of what config/features.js currently defaults to. These cases describe how
// the Metabase UI behaves when enabled, and that should not turn green or red because a product
// switch moved -- the flag's value is a separate decision from whether the behaviour is correct.
jest.mock("../config/features", () => ({ METABASE_UI_ENABLED: true }));


function renderPage() {
  return render(
    <MemoryRouter>
      <CurrentUserContext.Provider value={null}>
        <ToastProvider>
          <ConfirmProvider>
            <ApprovalsPage />
          </ConfirmProvider>
        </ToastProvider>
      </CurrentUserContext.Provider>
    </MemoryRouter>
  );
}

describe("ApprovalsPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    client.getSignOffApprovals.mockResolvedValue([]);
  });

  // Regression lock: a rejected load must render a banner + Retry, never a silent empty table.
  test("renders an error banner and Retry when the load rejects", async () => {
    client.getSignOffApprovals.mockRejectedValue({ response: { data: { message: "Boom approvals" } } });

    renderPage();

    expect(await screen.findByText("Boom approvals")).toBeInTheDocument();
    const retry = screen.getByRole("button", { name: /retry/i });
    expect(retry).toBeInTheDocument();

    client.getSignOffApprovals.mockResolvedValue([]);
    await userEvent.click(retry);

    await waitFor(() => expect(screen.queryByText("Boom approvals")).not.toBeInTheDocument());
    expect(await screen.findByText("Approvals")).toBeInTheDocument();
  });

  // --- migration status button -------------------------------------------------------------------

  function approval(overrides = {}) {
    return {
      id: 1,
      projectId: 7,
      projectName: "Demo prjct",
      serverName: "https://demoprjct-server2",
      combinationId: 11,
      combinationName: "MyDrive to OneDrive",
      role: "DEV_LEAD",
      status: "PENDING",
      // The Status column renders CurrentStatusText, which calls label.startsWith -- omitting this
      // throws inside the column renderer and the whole table renders nothing, which surfaces only
      // as "row not found". Required, not decoration.
      currentStatus: "Awaiting Dev Lead",
      submittedBy: "lavanya.gopasana@cloudfuze.com",
      ...overrides,
    };
  }

  test("opening migration status asks for that row's project", async () => {
    // An approver checks the Metabase figures before signing off. It must be THIS row's project --
    // two approvals on this page routinely belong to different ones.
    client.getSignOffApprovals.mockResolvedValue([approval()]);
    client.getProjectMetabaseStatus.mockResolvedValue([]);
    renderPage();
    // Matched as a table cell: the project name also appears as an <option> in the project
    // filter, so a bare findByText is ambiguous.
    await screen.findByRole("cell", { name: "Demo prjct" });

    await userEvent.click(screen.getByRole("button", { name: /migration status/i }));

    await waitFor(() => expect(client.getProjectMetabaseStatus).toHaveBeenCalledWith(7));
  });

  test("does not show one project's figures under another project's name", async () => {
    // The reason entries are cleared before each load. Leaving the previous project's numbers on
    // screen while the next request is in flight would put stale migration figures in front of
    // somebody deciding an approval -- the one mistake this button must not make.
    client.getSignOffApprovals.mockResolvedValue([
      approval(),
      approval({ id: 2, projectId: 9, projectName: "Other prjct", combinationId: 22 }),
    ]);
    client.getProjectMetabaseStatus.mockResolvedValue([
      { productType: "CONTENT", databaseName: "demo-db", statuses: [], total: 0 },
    ]);
    renderPage();
    // Matched as a table cell: the project name also appears as an <option> in the project
    // filter, so a bare findByText is ambiguous.
    await screen.findByRole("cell", { name: "Demo prjct" });

    const buttons = screen.getAllByRole("button", { name: /migration status/i });
    await userEvent.click(buttons[0]);
    await waitFor(() => expect(client.getProjectMetabaseStatus).toHaveBeenCalledWith(7));

    // Second row, with the request left unresolved so the in-flight state is observable.
    let resolveSecond;
    client.getProjectMetabaseStatus.mockReturnValueOnce(
      new Promise((res) => {
        resolveSecond = res;
      })
    );
    await userEvent.click(screen.getAllByRole("button", { name: /migration status/i })[1]);

    await waitFor(() => expect(client.getProjectMetabaseStatus).toHaveBeenCalledWith(9));
    expect(screen.queryByText("demo-db")).not.toBeInTheDocument();

    resolveSecond([]);
  });

  test("the button is disabled when the approval has no project", async () => {
    // Disabled rather than hidden, so the control does not appear and disappear between rows.
    client.getSignOffApprovals.mockResolvedValue([approval({ projectId: null, projectName: null })]);
    renderPage();
    await screen.findByText("MyDrive to OneDrive");

    expect(screen.getByRole("button", { name: /migration status/i })).toBeDisabled();
  });

  test("a failed status read is reported rather than shown as an empty result", async () => {
    // "No migration data" and "we could not reach Metabase" mean opposite things to an approver.
    client.getSignOffApprovals.mockResolvedValue([approval()]);
    client.getProjectMetabaseStatus.mockRejectedValue({
      response: { data: { message: "Could not reach Metabase." } },
    });
    renderPage();
    // Matched as a table cell: the project name also appears as an <option> in the project
    // filter, so a bare findByText is ambiguous.
    await screen.findByRole("cell", { name: "Demo prjct" });

    await userEvent.click(screen.getByRole("button", { name: /migration status/i }));

    expect(await screen.findByText(/Could not reach Metabase/i)).toBeInTheDocument();
  });
});
