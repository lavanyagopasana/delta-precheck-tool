import React from "react";
import { render, screen } from "@testing-library/react";
import DataTable from "./DataTable";

// Covers only the `sensitive` column flag. Hotjar strips the contents of a data-hj-suppress element
// in the browser before the DOM is sent, so this attribute is the whole mechanism keeping customer
// mailbox addresses out of session recordings -- see analytics/hotjar.js and WorkspacePairsPanel.
describe("DataTable sensitive columns", () => {
  const rows = [{ id: 1, sourceEmail: "jane.doe@customer-tenant.com", combination: "Teams to Slack" }];

  function renderTable() {
    render(
      <DataTable
        rows={rows}
        rowKey={(r) => r.id}
        columns={[
          { key: "sourceEmail", label: "Source Email", sensitive: true },
          { key: "combination", label: "Combination" },
        ]}
      />
    );
  }

  it("suppresses a sensitive column's cell from recordings", () => {
    renderTable();
    expect(screen.getByText("jane.doe@customer-tenant.com").closest("td")).toHaveAttribute("data-hj-suppress");
  });

  it("leaves a non-sensitive column's cell untouched", () => {
    renderTable();
    expect(screen.getByText("Teams to Slack").closest("td")).not.toHaveAttribute("data-hj-suppress");
  });

  // Suppression must not become a filtering bug: the value still has to be searchable by the person
  // using the app, since only what Hotjar captures is affected.
  it("still renders and filters a sensitive value normally", () => {
    renderTable();
    expect(screen.getByText("jane.doe@customer-tenant.com")).toBeInTheDocument();
  });
});
