import { describe, it, expect } from "vitest";
import { crossValidateCard, CONTENT_TYPES } from "../src/content-validation.ts";

// CL-2 (ADR 0022 D1): BriefingCardSchema validates `type` and `payload` INDEPENDENTLY,
// so zod alone accepts {type:"file", payload:{link:…}}. crossValidateCard is the server
// superRefine that enforces the renderer-safety invariant: type+payload appear together
// or not at all, and when present the payload's single variant key equals `type`. It's a
// pure function — exercised directly here (the integration suite covers it through PUT).
const ok = (c: any) => expect(crossValidateCard(c)).toEqual([]);
const bad = (c: any) => expect(crossValidateCard(c).length).toBeGreaterThan(0);

describe("crossValidateCard — type↔payload cross-check (CL-2)", () => {
  it("legacy kind-only cards (neither type nor payload) stay valid", () => {
    ok({});
    ok({ type: null, payload: null });
    ok({ type: undefined, payload: undefined });
    ok({ kind: "info", title: "x" });   // unrelated fields ignored
  });

  it("type and payload must appear together — one without the other is rejected", () => {
    expect(crossValidateCard({ type: "link" })).toEqual([
      { path: ["payload"], message: expect.stringContaining("must carry a matching `payload`") },
    ]);
    expect(crossValidateCard({ payload: { link: { url: "https://x" } } })).toEqual([
      { path: ["type"], message: expect.stringContaining("requires a `type`") },
    ]);
  });

  it("when both present, the payload's single key must equal `type` — for every content type", () => {
    for (const t of CONTENT_TYPES) ok({ type: t, payload: { [t]: { any: "value" } } });
  });

  it("a mismatched variant key is rejected with a descriptive message", () => {
    expect(crossValidateCard({ type: "file", payload: { link: { url: "x" } } })).toEqual([
      { path: ["payload"], message: 'payload variant "link" does not match type "file"' },
    ]);
    bad({ type: "contact", payload: { geo: { lat: 1 } } });
  });

  it("a payload with zero or multiple keys is rejected (must be exactly one, matching)", () => {
    // zero keys → "(none)" in the message
    expect(crossValidateCard({ type: "link", payload: {} })[0].message).toContain('"(none)"');
    // two keys — even if one of them matches the type
    bad({ type: "link", payload: { link: { url: "x" }, file: { ref: "y" } } });
  });
});
