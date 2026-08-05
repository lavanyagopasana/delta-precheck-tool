function escapeCsvValue(v) {
  const s = String(v ?? "");
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function buildCsv(columns, rows) {
  const lines = [columns.map(escapeCsvValue).join(",")];
  for (const row of rows) {
    lines.push(row.map(escapeCsvValue).join(","));
  }
  return `${lines.join("\n")}\n`;
}

function triggerDownload(csv, fileName) {
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  // Revoking the object URL synchronously can race the browser's own (async) read of the blob for
  // the download it just started, producing a truncated/empty/unopenable file on disk -- deferring
  // it gives that read time to finish first.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// Shared by CsvImportPanel (the "Download sample CSV" button) -- a fixed column list + one example
// row, so users can start from a correctly-shaped template instead of hand-typing the columns.
export function downloadSampleCsv(columns, sampleRow, fileName) {
  triggerDownload(buildCsv(columns, [sampleRow]), fileName);
}

// Used by ServerUrlsPanel's per-combination download icon -- exports the migration pairs actually
// uploaded under that combination, in the same column shape as the sample template.
export function downloadCsv(columns, rows, fileName) {
  triggerDownload(buildCsv(columns, rows), fileName);
}
