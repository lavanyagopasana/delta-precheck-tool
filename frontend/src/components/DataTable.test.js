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

// Covers the `align` column flag. The point of it is that ONE value drives both the header and its
// cells, so a column's label can never end up aligned differently from the data under it -- which is
// what made a single-character count look like it belonged to the neighbouring column.
describe("DataTable column alignment", () => {
  const rows = [{ id: 1, name: "Demo prjct", serverCount: 0 }];

  function renderTable() {
    render(
      <DataTable
        rows={rows}
        rowKey={(r) => r.id}
        columns={[
          { key: "name", label: "Project" },
          { key: "serverCount", label: "No. Server URLs", align: "center" },
        ]}
      />
    );
  }

  it("applies the alignment to the header and its cell together", () => {
    renderTable();
    expect(screen.getByText("No. Server URLs").closest("th")).toHaveStyle({ textAlign: "center" });
    expect(screen.getByText("0").closest("td")).toHaveStyle({ textAlign: "center" });
  });

  it("leaves columns without an alignment to the stylesheet's left default", () => {
    // No inline style at all, rather than an inline "left" -- so the th/td rule in index.css stays
    // the single place the default is decided.
    renderTable();
    expect(screen.getByText("Project").closest("th").style.textAlign).toBe("");
    expect(screen.getByText("Demo prjct").closest("td").style.textAlign).toBe("");
  });
});
