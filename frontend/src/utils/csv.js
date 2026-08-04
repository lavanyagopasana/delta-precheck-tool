// Shared by CsvImportPanel (the "Download sample CSV" button) and ServerUrlsPanel's per-combination
// download icon -- both need to turn a fixed column list + one example row into a downloadable file.
export function downloadSampleCsv(columns, sampleRow, fileName) {
  const escape = (v) => {
    const s = String(v ?? "");
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const csv = `${columns.map(escape).join(",")}\n${sampleRow.map(escape).join(",")}\n`;
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
