// Mirrors backend ServerUrlValidator so the Add Server form can say what's wrong before submitting.
// The backend remains authoritative -- this is feedback, not the gate. Format only: it never calls
// the URL (see the backend class for why a liveness check would be both wrong and an SSRF risk).

const HOSTNAME = /^(?=.{1,253}$)[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;
const IPV4 = /^((25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)$/;

// Returns null when valid, otherwise the reason it isn't.
export function serverUrlError(rawUrl) {
  const url = (rawUrl || "").trim();
  if (!url) return "Server URL is required.";
  if (/\s/.test(url)) return "Server URL can't contain spaces.";
  if (url.length > 255) return "Server URL must be 255 characters or fewer.";

  const scheme = url.match(/^([a-zA-Z][a-zA-Z0-9+.-]*):\/\//);
  if (!scheme) return "Include the protocol, e.g. https://server.example.com";
  const protocol = scheme[1].toLowerCase();
  if (protocol !== "http" && protocol !== "https") return "Only http:// and https:// URLs are supported.";

  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return "That isn't a valid URL. Use the form https://server.example.com";
  }

  if (!parsed.hostname) return `Add the server address after ${protocol}://`;
  if (parsed.username || parsed.password) return "Don't include a username or password in the URL.";

  const host = parsed.hostname;
  const ipv6Literal = host.startsWith("[") && host.endsWith("]");
  if (!ipv6Literal && !IPV4.test(host) && !HOSTNAME.test(host)) {
    return "That server address isn't valid.";
  }
  if (parsed.port && (Number(parsed.port) < 1 || Number(parsed.port) > 65535)) {
    return "That port number isn't valid.";
  }
  return null;
}
