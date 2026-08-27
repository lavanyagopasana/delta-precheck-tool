import { emailLocalPart, humanizePhase } from "./format";

// Both helpers are display-only. The tests below pin the real values these see: email addresses from
// app_users, and PMO's phase vocabulary as it actually arrives from /api/external/projects.
describe("emailLocalPart", () => {
  it("keeps only the part before the @", () => {
    expect(emailLocalPart("harika.velidi@cloudfuze.com")).toBe("harika.velidi");
  });

  it("leaves a value with no @ alone", () => {
    // "PMO sync" is stamped on createdBy for synced projects and must render as-is.
    expect(emailLocalPart("PMO sync")).toBe("PMO sync");
  });

  it("passes null and empty through untouched", () => {
    expect(emailLocalPart(null)).toBeNull();
    expect(emailLocalPart("")).toBe("");
  });
});

describe("humanizePhase", () => {
  it("renders every phase PMO actually sends", () => {
    // The full set observed in the live feed on 2026-08-26.
    expect(humanizePhase("KICKOFF")).toBe("Kickoff");
    expect(humanizePhase("CLOUD_ADDING")).toBe("Cloud Adding");
    expect(humanizePhase("PILOT_MIGRATION")).toBe("Pilot Migration");
    expect(humanizePhase("ONETIME_MIGRATION")).toBe("Onetime Migration");
    expect(humanizePhase("DELTA")).toBe("Delta");
    expect(humanizePhase("VALIDATION")).toBe("Validation");
    expect(humanizePhase("FINAL_VALIDATION")).toBe("Final Validation");
    expect(humanizePhase("CLOSURE")).toBe("Closure");
    expect(humanizePhase("COMPLETED")).toBe("Completed");
  });

  it("passes null and empty through, so a hand-created project renders the table's dash", () => {
    expect(humanizePhase(null)).toBeNull();
    expect(humanizePhase(undefined)).toBeUndefined();
    expect(humanizePhase("")).toBe("");
  });

  it("does not choke on an unexpected shape if PMO adds a phase later", () => {
    expect(humanizePhase("SOME_NEW_PHASE")).toBe("Some New Phase");
    // Splits on underscores only, which is all PMO sends -- a space-separated value keeps its own
    // internal casing rather than being title-cased word by word.
    expect(humanizePhase("already nice")).toBe("Already nice");
    expect(humanizePhase("__DOUBLE__UNDERSCORE__")).toBe("Double Underscore");
  });
});
