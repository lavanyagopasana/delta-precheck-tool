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
});
