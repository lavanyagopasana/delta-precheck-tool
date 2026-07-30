import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import PreCheckPanel from "./PreCheckPanel";
import { ToastProvider } from "./Toast";
import { ConfirmProvider } from "./ConfirmDialog";
import { CurrentUserContext } from "../auth/CurrentUserContext";
import * as client from "../api/client";

jest.mock("../api/client");

const READY_SUBMISSION = {
  status: "DRAFT",
  lockedByOther: false,
  submittedBy: null,
  submittedAt: null,
  startedByEmail: null,
  totalCount: 1,
  completedCount: 1,
  items: [
    {
      id: 1,
      itemName: "Item A",
      status: "COMPLETED",
      evidenceFilePath: "/uploads/e.png",
      evidenceFileName: "e.png",
      notes: "looks good",
    },
  ],
};

const READY_SERVER = {
  serverName: "S1",
  totalPairs: 1,
  migrationManagerName: "mgr@cloudfuze.com",
  projectId: null,
  projectName: null,
};

function renderPanel() {
  return render(
    <MemoryRouter>
      <CurrentUserContext.Provider value={null}>
        <ToastProvider>
          <ConfirmProvider>
            <PreCheckPanel serverId={1} showBackNav={false} showHeader={false} />
          </ConfirmProvider>
        </ToastProvider>
      </CurrentUserContext.Provider>
    </MemoryRouter>
  );
}

describe("PreCheckPanel", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    client.getServerReadiness.mockResolvedValue(READY_SERVER);
    client.getPreCheckSubmission.mockResolvedValue(READY_SUBMISSION);
  });

  test("submits for review, disables while busy, and shows success", async () => {
    let resolveSubmit;
    client.submitPreCheckForReview.mockReturnValue(new Promise((res) => { resolveSubmit = res; }));

    renderPanel();
    await screen.findByText("Pre-Check Items");

    // Submit only appears once everything (incl. the submitter's name) is filled in.
    expect(screen.queryByRole("button", { name: /submit for migration manager review/i })).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Your name"), "Tester");

    await userEvent.click(screen.getByRole("button", { name: /submit for migration manager review/i }));
    // Confirmation dialog -> confirm.
    await userEvent.click(screen.getByRole("button", { name: "Submit" }));

    await waitFor(() => expect(screen.getByRole("button", { name: /submitting/i })).toBeDisabled());
    expect(client.submitPreCheckForReview).toHaveBeenCalledWith(1, { submittedBy: "Tester" });

    resolveSubmit({ status: "SUBMITTED" });
    expect(await screen.findByText("Pre-check submitted for Migration Manager review.")).toBeInTheDocument();
  });

  test("shows an error when the submit fails", async () => {
    client.submitPreCheckForReview.mockRejectedValue({ response: { data: { message: "MM missing" } } });

    renderPanel();
    await screen.findByText("Pre-Check Items");
    await userEvent.type(screen.getByLabelText("Your name"), "Tester");
    await userEvent.click(screen.getByRole("button", { name: /submit for migration manager review/i }));
    await userEvent.click(screen.getByRole("button", { name: "Submit" }));

    // Surfaced in both the inline hint and a toast -- assert at least one.
    expect((await screen.findAllByText("MM missing")).length).toBeGreaterThan(0);
  });
});
