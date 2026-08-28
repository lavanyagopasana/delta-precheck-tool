import React, { useMemo, useState } from "react";

function defaultCompare(a, b) {
  if (a == null && b == null) return 0;
  if (a == null) return -1;
  if (b == null) return 1;
  if (typeof a === "number" && typeof b === "number") return a - b;
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: "base" });
}

export default function DataTable({
  title,
  columns,
  rows,
  rowKey,
  onRowClick,
  selectedRowKey,
  emptyMessage = "No rows yet.",
  searchPlaceholder = "Filter...",
  defaultSort,
  toolbarRight,
}) {
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState(defaultSort || { key: null, dir: "asc" });

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((row) =>
      columns.some((col) => {
        if (col.filterable === false) return false;
        const value = col.filterValue ? col.filterValue(row) : col.sortValue ? col.sortValue(row) : row[col.key];
        return String(value ?? "").toLowerCase().includes(q);
      })
    );
  }, [rows, query, columns]);

  const sorted = useMemo(() => {
    if (!sort.key) return filtered;
    const col = columns.find((c) => c.key === sort.key);
    if (!col) return filtered;
    const getValue = col.sortValue || ((row) => row[col.key]);
    const copy = [...filtered].sort((a, b) => defaultCompare(getValue(a), getValue(b)));
    if (sort.dir === "desc") copy.reverse();
    return copy;
  }, [filtered, sort, columns]);

  const toggleSort = (key) => {
    setSort((prev) => {
      if (prev.key !== key) return { key, dir: "asc" };
      if (prev.dir === "asc") return { key, dir: "desc" };
      return { key: null, dir: "asc" };
    });
  };

  return (
    <div>
      <div className={`table-toolbar${title ? " table-toolbar--titled" : ""}`}>
        {title && <h2 className="table-toolbar-title">{title}</h2>}
        <label className="sr-only" htmlFor="table-filter-input">
          {searchPlaceholder}
        </label>
        <input
          id="table-filter-input"
          type="text"
          className="table-filter-input"
          placeholder={searchPlaceholder}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {toolbarRight && <div className="table-toolbar-right">{toolbarRight}</div>}
      </div>

      {title && (
        <div className="table-result-count">
          {query.trim()
            ? `${sorted.length} of ${rows.length} found`
            : `${rows.length} total`}
        </div>
      )}

      {sorted.length === 0 ? (
        <p className="empty-state">{query.trim() ? "No matching rows." : emptyMessage}</p>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                {columns.map((col) => {
                  const sortable = col.sortable !== false;
                  const isSorted = sort.key === col.key;
                  return (
                    <th
                      key={col.key}
                      className={sortable ? "sortable-th" : undefined}
                      // col.align sets the header AND its cells from one value, so a column's label
                      // can never drift out of line with the data under it. Left stays the default:
                      // see the th/td comment in index.css for why text columns are left-aligned.
                      style={col.align ? { textAlign: col.align } : undefined}
                      onClick={sortable ? () => toggleSort(col.key) : undefined}
                      aria-sort={isSorted ? (sort.dir === "asc" ? "ascending" : "descending") : undefined}
                    >
                      {col.label}
                      {sortable && (
                        <span className="sort-indicator">{isSorted ? (sort.dir === "asc" ? " ▲" : " ▼") : ""}</span>
                      )}
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {sorted.map((row) => {
                const isSelected = selectedRowKey != null && String(rowKey(row)) === String(selectedRowKey);
                return (
                  <tr
                    key={rowKey(row)}
                    className={[onRowClick ? "clickable-row" : "", isSelected ? "selected-row" : ""]
                      .filter(Boolean)
                      .join(" ") || undefined}
                    onClick={onRowClick ? () => onRowClick(row) : undefined}
                  >
                    {columns.map((col) => (
                      // `sensitive` marks a column whose values must not appear in a Hotjar session
                      // recording -- customer mailbox addresses and paths, specifically. Hotjar
                      // replaces the contents of a data-hj-suppress element with a placeholder before
                      // the DOM ever leaves the browser, so the value is never sent, not merely hidden
                      // on playback. Put here rather than on each call site so every table gets the
                      // same lever; see analytics/hotjar.js.
                      <td
                        key={col.key}
                        data-hj-suppress={col.sensitive ? "" : undefined}
                        style={col.align ? { textAlign: col.align } : undefined}
                      >
                        {col.render ? col.render(row) : row[col.key]}
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
