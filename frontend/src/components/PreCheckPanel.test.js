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

// AUTH_CONFIGURED is false in the test env (no REACT_APP_AZURE_CLIENT_ID), which makes the panel
// degrade fully open exactly as it does in local dev -- so role gates like admin-only Withdraw can't
// be observed at all in that mode. Exposed as a flipable getter (read at the use site, not bound at
// import) so the role-gating tests can turn auth on without affecting the rest of the file.
// `var`, not `let`: msalInstance.js reads AUTH_CONFIGURED at import time, which happens before this
// line runs, and a `let` would throw a TDZ ReferenceError there. `var` hoists to undefined (falsy =
// auth off), which is the right import-time answer anyway; beforeEach sets it per test.
// eslint-disable-next-line no-var
var mockAuthConfigured = false;
jest.mock("../auth/authConfig", () => ({
  get AUTH_CONFIGURED() {
    return mockAuthConfigured;
  },
}));

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

const READY_COMBINATION = {
  combinationName: "Box to OneDrive",
  serverName: "S1",
  totalPairs: 1,
  migrationManagerName: "mgr@cloudfuze.com",
  projectId: null,
  projectName: null,
  currentCycleNumber: 1,
  currentDeltaType: null,
  currentDeltaLabel: null,
  completedCycleCount: 0,
  finalDeltaComplete: false,
  blockedByDecline: false,
};

function renderPanel({ user = null } = {}) {
  return render(
    <MemoryRouter>
      <CurrentUserContext.Provider value={user}>
        <ToastProvider>
          <ConfirmProvider>
            <PreCheckPanel combinationId={1} showBackNav={false} showHeader={false} />
          </ConfirmProvider>
        </ToastProvider>
      </CurrentUserContext.Provider>
    </MemoryRouter>
  );
}

describe("PreCheckPanel", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAuthConfigured = false;
    client.getCombinationReadiness.mockResolvedValue(READY_COMBINATION);
    client.getPreCheckSubmission.mockResolvedValue(READY_SUBMISSION);
    // The panel now renders DeltaHistoryPanel, which fetches on mount. The module is auto-mocked, so
    // without this the call returns undefined and .then() throws during the effect.
    client.getDeltaCycles.mockResolvedValue([]);
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

  // ---- multi-cycle Delta behaviour ----

  describe("withdraw is admin-only", () => {
    const SUBMITTED = { ...READY_SUBMISSION, status: "SUBMITTED", submittedBy: "eng@cloudfuze.com", submittedAt: "2026-03-01T09:00:00" };

    beforeEach(() => {
      // Role gating only exists once auth is configured; with it off everything degrades open.
      mockAuthConfigured = true;
      client.getPreCheckSubmission.mockResolvedValue(SUBMITTED);
    });

    test("an admin sees the Withdraw button", async () => {
      renderPanel({ user: { email: "admin@cloudfuze.com", role: "ADMIN" } });
      await screen.findByText("Pre-Check Items");

      expect(screen.getByRole("button", { name: /withdraw/i })).toBeInTheDocument();
    });

    test("the engineer who submitted it does NOT see Withdraw", async () => {
      // Explicit product decision -- engineers no longer withdraw their own submissions, so the button
      // is gone even for the person who submitted it.
      renderPanel({ user: { email: "eng@cloudfuze.com", role: "MIGRATION_ENGINEER" } });
      await screen.findByText("Pre-Check Items");

      expect(screen.queryByRole("button", { name: /withdraw/i })).not.toBeInTheDocument();
    });

    test("the Migration Manager does NOT see Withdraw either", async () => {
      // Managers review: to send something back they decline, they don't withdraw.
      renderPanel({ user: { email: "mgr@cloudfuze.com", role: "MIGRATION_MANAGER" } });
      await screen.findByText("Pre-Check Items");

      expect(screen.queryByRole("button", { name: /withdraw/i })).not.toBeInTheDocument();
    });
  });

  test("a declined pre-check tells a non-admin to ask an admin to withdraw it", async () => {
    // Without this the form just looks locked, with nothing explaining that the engineer is stuck.
    client.getCombinationReadiness.mockResolvedValue({
      ...READY_COMBINATION,
      blockedByDecline: true,
      declinedByRoleLabel: "Migration Manager",
      declinedBy: "mgr@cloudfuze.com",
    });
    client.getPreCheckSubmission.mockResolvedValue({ ...READY_SUBMISSION, status: "SUBMITTED" });

    renderPanel({ user: { email: "eng@cloudfuze.com", role: "MIGRATION_ENGINEER" } });

    expect(await screen.findByText("Declined by Migration Manager")).toBeInTheDocument();
    expect(screen.getByText(/ask an admin to withdraw it/i)).toBeInTheDocument();
  });

  test("a completed Final Delta locks the form and says so", async () => {
    client.getCombinationReadiness.mockResolvedValue({
      ...READY_COMBINATION,
      currentCycleNumber: 3,
      currentDeltaType: "FINAL_DELTA",
      currentDeltaLabel: "Final Delta",
      completedCycleCount: 3,
      finalDeltaComplete: true,
      finalDeltaCompletedBy: "eng@cloudfuze.com",
      finalDeltaCompletedAt: "2026-03-05T17:30:00",
    });
    client.getPreCheckSubmission.mockResolvedValue({ ...READY_SUBMISSION, status: "SUBMITTED" });

    renderPanel({ user: { email: "eng@cloudfuze.com", role: "MIGRATION_ENGINEER" } });
    await screen.findByText("Pre-Check Items");

    expect(screen.getByText("This combination's migration is complete")).toBeInTheDocument();
    // Locked means no submit affordance at all, not merely a disabled one.
    expect(screen.queryByRole("button", { name: /submit for migration manager review/i })).not.toBeInTheDocument();
  });

  test("a reset checklist on cycle 2 says which cycle it belongs to", async () => {
    // A rolled-over checklist is byte-identical to a brand-new one, so the cycle number is the only
    // thing telling the engineer they're on their second pre-delta.
    client.getCombinationReadiness.mockResolvedValue({ ...READY_COMBINATION, currentCycleNumber: 2, completedCycleCount: 1 });
    client.getPreCheckSubmission.mockResolvedValue({ ...READY_SUBMISSION, status: "NOT_STARTED" });

    renderPanel({ user: { email: "eng@cloudfuze.com", role: "MIGRATION_ENGINEER" } });
    await screen.findByText("Pre-Check Items");

    expect(screen.getByText(/Delta cycle 2/)).toBeInTheDocument();
  });
});
