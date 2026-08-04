// Shared by WorkspacePairsPanel (grouping the Migration Pairs table) and ServerUrlsPanel (deriving
// which combinations already have real, uploaded data for a server) -- both need the same "bucket
// these pairs by their combination value" logic.
export function groupByCombination(pairs) {
  const groups = new Map();
  for (const pair of pairs) {
    const key = (pair.combination || "").trim() || "No combination";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(pair);
  }
  return Array.from(groups.entries()).map(([combination, groupPairs]) => ({ combination, pairs: groupPairs }));
}
