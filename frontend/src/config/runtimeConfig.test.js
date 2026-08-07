// runtimeConfig reads window at import time, so every case re-imports the module after setting up
// the origin and window.__APP_CONFIG__ it should see.

function loadWith({ href, appConfig, env } = {}) {
  jest.resetModules();

  if (href) {
    const url = new URL(href);
    delete window.location;
    window.location = { origin: url.origin, hostname: url.hostname, href };
  }

  if (appConfig === undefined) {
    delete window.__APP_CONFIG__;
  } else {
    window.__APP_CONFIG__ = appConfig;
  }

  process.env.REACT_APP_AZURE_REDIRECT_URI = "";
  process.env.REACT_APP_API_BASE = "";
  process.env.REACT_APP_AZURE_CLIENT_ID = "";
  Object.assign(process.env, env || {});

  return require("./runtimeConfig");
}

const ORIGINAL_LOCATION = window.location;
const ORIGINAL_ENV = { ...process.env };
let warnSpy;

beforeEach(() => {
  warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  warnSpy.mockRestore();
  delete window.__APP_CONFIG__;
  process.env = { ...ORIGINAL_ENV };
});

afterAll(() => {
  delete window.location;
  window.location = ORIGINAL_LOCATION;
});

describe("with nothing configured", () => {
  it("uses the page's own origin as the redirect URI in production", () => {
    const cfg = loadWith({ href: "https://delta.example.com/projects" });
    expect(cfg.AZURE_REDIRECT_URI).toBe("https://delta.example.com");
  });

  it("uses the page's own origin as the backend base in production", () => {
    const cfg = loadWith({ href: "https://delta.example.com/" });
    expect(cfg.BACKEND_BASE).toBe("https://delta.example.com");
  });

  it("still points at the :8080 backend when served from localhost", () => {
    const cfg = loadWith({ href: "http://localhost:3000/" });
    expect(cfg.BACKEND_BASE).toBe("http://localhost:8080");
    expect(cfg.AZURE_REDIRECT_URI).toBe("http://localhost:3000");
  });

  // react-scripts prints a LAN address ("On Your Network: http://192.168.x.x:3000") and developers
  // open it to test from a phone or a second machine. That origin is not loopback, but the backend is
  // still on the developer's own :8080 -- same-origin would send /api to the dev server and 404.
  it("still points at the :8080 backend for `npm start` opened via a LAN address", () => {
    const cfg = loadWith({
      href: "http://192.168.1.7:3000/",
      env: { NODE_ENV: "development" },
    });
    expect(cfg.BACKEND_BASE).toBe("http://localhost:8080");
  });

  // The redirect URI must equal whatever origin the page is actually on, LAN address included.
  it("uses the LAN origin as the redirect URI in that same case", () => {
    const cfg = loadWith({
      href: "http://192.168.1.7:3000/",
      env: { NODE_ENV: "development" },
    });
    expect(cfg.AZURE_REDIRECT_URI).toBe("http://192.168.1.7:3000");
  });
});

// The actual production outage: a bundle built on a laptop carried localhost URLs, and no
// server-side setting could override them because react-scripts had already inlined them.
describe("bundle built with localhost baked in, then deployed", () => {
  it("ignores a localhost redirect URI and uses the real origin instead", () => {
    const cfg = loadWith({
      href: "https://delta.example.com/",
      env: { REACT_APP_AZURE_REDIRECT_URI: "http://localhost:3000" },
    });
    expect(cfg.AZURE_REDIRECT_URI).toBe("https://delta.example.com");
    expect(warnSpy).toHaveBeenCalled();
  });

  it("ignores a localhost API base and uses the real origin instead", () => {
    const cfg = loadWith({
      href: "https://delta.example.com/",
      env: { REACT_APP_API_BASE: "http://localhost:8080" },
    });
    expect(cfg.BACKEND_BASE).toBe("https://delta.example.com");
  });

  it("keeps honouring localhost values during local development", () => {
    const cfg = loadWith({
      href: "http://localhost:3000/",
      env: { REACT_APP_AZURE_REDIRECT_URI: "http://localhost:3000" },
    });
    expect(cfg.AZURE_REDIRECT_URI).toBe("http://localhost:3000");
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("treats 127.0.0.1 as loopback too", () => {
    const cfg = loadWith({
      href: "https://delta.example.com/",
      env: { REACT_APP_API_BASE: "http://127.0.0.1:8080" },
    });
    expect(cfg.BACKEND_BASE).toBe("https://delta.example.com");
  });
});

describe("runtime-config.js overrides", () => {
  it("wins over a build-time value", () => {
    const cfg = loadWith({
      href: "https://delta.example.com/",
      appConfig: { apiBase: "https://api.example.com" },
      env: { REACT_APP_API_BASE: "https://stale.example.com" },
    });
    expect(cfg.BACKEND_BASE).toBe("https://api.example.com");
  });

  it("supports a cross-origin backend, which same-origin defaults cannot", () => {
    const cfg = loadWith({
      href: "https://delta.example.com/",
      appConfig: { apiBase: "https://api.example.com/" },
    });
    expect(cfg.BACKEND_BASE).toBe("https://api.example.com");
  });

  it("falls through to build-time when a key is left blank", () => {
    const cfg = loadWith({
      href: "https://delta.example.com/",
      appConfig: { apiBase: "  ", azureClientId: "" },
      env: { REACT_APP_API_BASE: "https://api.example.com", REACT_APP_AZURE_CLIENT_ID: "abc-123" },
    });
    expect(cfg.BACKEND_BASE).toBe("https://api.example.com");
    expect(cfg.AZURE_CLIENT_ID).toBe("abc-123");
  });

  // Container entrypoints that envsubst this file leave the raw token behind when the variable is
  // missing; using "__API_BASE__" as a hostname would be worse than falling back.
  it("ignores an unsubstituted placeholder token", () => {
    const cfg = loadWith({
      href: "https://delta.example.com/",
      appConfig: { apiBase: "__API_BASE__" },
    });
    expect(cfg.BACKEND_BASE).toBe("https://delta.example.com");
  });

  it("strips trailing slashes so callers can append /api safely", () => {
    const cfg = loadWith({
      href: "https://delta.example.com/",
      appConfig: { apiBase: "https://api.example.com///" },
    });
    expect(cfg.BACKEND_BASE).toBe("https://api.example.com");
  });
});
