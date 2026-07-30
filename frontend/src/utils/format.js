// Display-only helper: show just the local part of an email address (everything before "@"), so
// "manmadha@cloudfuze.co" renders as "manmadha" and "dan@filefuze.io" as "dan" in lists. Never use
// this on a value that gets sent back to the API -- it's for presentation only.
export function emailLocalPart(value) {
  if (!value) return value;
  const str = String(value);
  const at = str.indexOf("@");
  return at > 0 ? str.slice(0, at) : str;
}
