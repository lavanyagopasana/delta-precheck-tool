import React from "react";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import TicketsPage from "./TicketsPage";
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
            <TicketsPage />
          </ConfirmProvider>
        </ToastProvider>
      </CurrentUserContext.Provider>
    </MemoryRouter>
  );
}

describe("TicketsPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    client.getTickets.mockResolvedValue([]);
    client.getServers.mockResolvedValue([{ serverId: 5, serverName: "S1", projectId: 9 }]);
    client.getProjects.mockResolvedValue([{ id: 9, name: "P1" }]);
  });

  // Regression lock for the silent-empty-table bug: a rejected load must render a banner + Retry.
  test("renders an error banner and Retry when the load rejects", async () => {
    client.getTickets.mockRejectedValue({ response: { data: { message: "Server exploded" } } });

    renderPage();

    expect(await screen.findByText("Server exploded")).toBeInTheDocument();
    const retry = screen.getByRole("button", { name: /retry/i });
    expect(retry).toBeInTheDocument();

    // Retry re-fetches; a successful retry clears the banner and shows the table.
    client.getTickets.mockResolvedValue([]);
    await userEvent.click(retry);

    await waitFor(() => expect(screen.queryByText("Server exploded")).not.toBeInTheDocument());
    expect(await screen.findByText("Ticket Tracker")).toBeInTheDocument();
  });

  test("form submits, disables the button while saving, and shows a success toast", async () => {
    let resolveCreate;
    client.createTicket.mockReturnValue(new Promise((res) => { resolveCreate = res; }));

    renderPage();
    await screen.findByText("Ticket Tracker");
    await userEvent.click(screen.getByRole("button", { name: /log ticket/i }));

    const modal = within(document.querySelector(".modal-card"));
    await userEvent.selectOptions(modal.getByLabelText("Project"), "9");
    await userEvent.selectOptions(modal.getByLabelText("Server"), "5");
    await userEvent.type(modal.getByLabelText("Created by"), "eng@cloudfuze.com");
    await userEvent.type(modal.getByLabelText("Ticket URL"), "https://jira.example.com/browse/T-1");

    const submit = modal.getByRole("button", { name: "Log Ticket" });
    expect(submit).toBeEnabled();
    await userEvent.click(submit);

    await waitFor(() => expect(modal.getByRole("button", { name: /saving/i })).toBeDisabled());
    expect(client.createTicket).toHaveBeenCalledWith(
      expect.objectContaining({
        serverId: 5,
        createdBy: "eng@cloudfuze.com",
        ticketUrl: "https://jira.example.com/browse/T-1",
        status: "OPEN",
      })
    );

    resolveCreate({});
    expect(await screen.findByText("Ticket logged.")).toBeInTheDocument();
  });

  test("form shows an error message when create fails", async () => {
    client.createTicket.mockRejectedValue({ response: { data: { message: "URL not allowed" } } });

    renderPage();
    await screen.findByText("Ticket Tracker");
    await userEvent.click(screen.getByRole("button", { name: /log ticket/i }));

    const modal = within(document.querySelector(".modal-card"));
    await userEvent.selectOptions(modal.getByLabelText("Project"), "9");
    await userEvent.selectOptions(modal.getByLabelText("Server"), "5");
    await userEvent.type(modal.getByLabelText("Created by"), "eng@cloudfuze.com");
    await userEvent.type(modal.getByLabelText("Ticket URL"), "https://jira.example.com/browse/T-1");
    await userEvent.click(modal.getByRole("button", { name: "Log Ticket" }));

    // The message is surfaced in both the inline hint and a toast -- assert at least one.
    expect((await screen.findAllByText("URL not allowed")).length).toBeGreaterThan(0);
  });
});
