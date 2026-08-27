// Display-only helper: show just the local part of an email address (everything before "@"), so
// "manmadha@cloudfuze.co" renders as "manmadha" and "dan@filefuze.io" as "dan" in lists. Never use
// this on a value that gets sent back to the API -- it's for presentation only.
export function emailLocalPart(value) {
  if (!value) return value;
  const str = String(value);
  const at = str.indexOf("@");
  return at > 0 ? str.slice(0, at) : str;
}

// Display-only helper for PMO's phase vocabulary, which arrives SCREAMING_SNAKE_CASE
// ("FINAL_VALIDATION", "ONETIME_MIGRATION"). Renders it as "Final Validation" / "Onetime Migration"
// so a table column stays readable. Presentation only -- the raw value is what PMO sends and what
// gets stored, and it is never derived back from this.
export function humanizePhase(value) {
  if (!value) return value;
  return String(value)
    .toLowerCase()
    .split("_")
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}
