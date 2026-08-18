// hotjar.js reads HOTJAR_SITE_ID from runtimeConfig at import time, so every case re-imports the
// module after setting up the window.__APP_CONFIG__ it should see -- same pattern as
// config/runtimeConfig.test.js.

const SCRIPT_ID = "hotjar-snippet";

function loadWith({ siteId } = {}) {
  jest.resetModules();

  if (siteId === undefined) {
    delete window.__APP_CONFIG__;
  } else {
    window.__APP_CONFIG__ = { hotjarSiteId: siteId };
  }
  process.env.REACT_APP_HOTJAR_SITE_ID = "";

  return require("./hotjar");
}

function snippetTags() {
  return document.querySelectorAll(`#${SCRIPT_ID}`);
}

const ORIGINAL_ENV = { ...process.env };
let warnSpy;

beforeEach(() => {
  warnSpy = jest.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  warnSpy.mockRestore();
  delete window.__APP_CONFIG__;
  delete window.hj;
  delete window._hjSettings;
  document.head.innerHTML = "";
  process.env = { ...ORIGINAL_ENV };
});

describe("when no site ID is configured", () => {
  it("reports Hotjar as disabled", () => {
    expect(loadWith().isHotjarEnabled()).toBe(false);
  });

  it("loads no script and leaves window.hj undefined", () => {
    const { initHotjar } = loadWith();

    expect(initHotjar()).toBe(false);
    expect(snippetTags()).toHaveLength(0);
    expect(window.hj).toBeUndefined();
  });

  it("treats a blank site ID the same as an absent one", () => {
    const { initHotjar, isHotjarEnabled } = loadWith({ siteId: "   " });

    expect(isHotjarEnabled()).toBe(false);
    expect(initHotjar()).toBe(false);
    expect(snippetTags()).toHaveLength(0);
  });

  it("sends no identify call", () => {
    const { identifyHotjarUser } = loadWith();

    expect(identifyHotjarUser({ email: "lavanya.gopasana@cloudfuze.com", role: "ADMIN" })).toBe(false);
  });
});

describe("when a site ID is configured", () => {
  it("injects exactly one snippet pointing at that site", () => {
    const { initHotjar } = loadWith({ siteId: "3847291" });

    expect(initHotjar()).toBe(true);

    const tags = snippetTags();
    expect(tags).toHaveLength(1);
    expect(tags[0].src).toBe("https://static.hotjar.com/c/hotjar-3847291.js?sv=6");
    // Numeric, matching the literal Hotjar's own install snippet emits.
    expect(window._hjSettings).toEqual({ hjid: 3847291, hjsv: 6 });
  });

  // StrictMode double-invokes effects in development. Two snippets would open two recordings for one
  // page view, which inflates exactly the session counts this feature exists to report.
  it("does not inject a second snippet when called again", () => {
    const { initHotjar } = loadWith({ siteId: "3847291" });

    expect(initHotjar()).toBe(true);
    expect(initHotjar()).toBe(false);
    expect(snippetTags()).toHaveLength(1);
  });

  it("queues calls made before the remote script has loaded", () => {
    const { initHotjar, identifyHotjarUser } = loadWith({ siteId: "3847291" });
    initHotjar();

    expect(identifyHotjarUser({ email: "dev.lead@cloudfuze.com", role: "DEV_LEAD", allowed: true })).toBe(true);
    expect(window.hj.q).toHaveLength(1);
    expect(Array.from(window.hj.q[0])).toEqual([
      "identify",
      "dev.lead@cloudfuze.com",
      { role: "DEV_LEAD", allowed: true },
    ]);
  });

  // The rest of the codebase matches emails case-insensitively; without this the same person signing
  // in as Jane.Doe@ and jane.doe@ would show up as two separate Hotjar users.
  it("lowercases the email so one person is one Hotjar user", () => {
    const { initHotjar, identifyHotjarUser } = loadWith({ siteId: "3847291" });
    initHotjar();
    identifyHotjarUser({ email: "  QA.Lead@CloudFuze.com  ", role: "QA_LEAD" });

    expect(window.hj.q[0][1]).toBe("qa.lead@cloudfuze.com");
  });

  it("still identifies a user who was denied access, since that session is the interesting one", () => {
    const { initHotjar, identifyHotjarUser } = loadWith({ siteId: "3847291" });
    initHotjar();
    identifyHotjarUser({ email: "not.added@cloudfuze.com", role: null, allowed: false });

    expect(Array.from(window.hj.q[0])).toEqual([
      "identify",
      "not.added@cloudfuze.com",
      { role: "UNKNOWN", allowed: false },
    ]);
  });

  it("sends nothing when there is no email to attribute the session to", () => {
    const { initHotjar, identifyHotjarUser } = loadWith({ siteId: "3847291" });
    initHotjar();

    expect(identifyHotjarUser({ email: null, role: "ADMIN" })).toBe(false);
    expect(identifyHotjarUser(null)).toBe(false);
    expect(window.hj.q).toBeUndefined();
  });
});

describe("when the site ID is malformed", () => {
  // A non-numeric ID would otherwise request hotjar-NaN.js and fail with nothing pointing at the
  // cause, making a typo indistinguishable from Hotjar being deliberately off.
  it("refuses to load and says why", () => {
    const { initHotjar } = loadWith({ siteId: "not-a-site-id" });

    expect(initHotjar()).toBe(false);
    expect(snippetTags()).toHaveLength(0);
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining("digits only"));
  });
});
