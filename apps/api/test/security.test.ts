import { describe, it, expect } from "vitest";
import { constantTimeEqual, stripServerManaged, stampProvenance } from "../src/security.ts";
import { BriefingCardSchema } from "../src/generated/content.ts";

describe("household-token compare", () => {
  it("accepts the matching secret", () => {
    expect(constantTimeEqual("s3cr3t-token", "s3cr3t-token")).toBe(true);
  });
  it("rejects a wrong/short secret (no length leak)", () => {
    expect(constantTimeEqual("s3cr3t-token", "wrong")).toBe(false);
    expect(constantTimeEqual("", "s3cr3t-token")).toBe(false);
  });
  it("rejects a same-LENGTH-but-different token (constant-time contract)", () => {
    expect(constantTimeEqual("aaaaaaaaaaaa", "s3cr3t-token")).toBe(false);
  });
});

describe("mass-assignment strip (content writes)", () => {
  it("drops server-managed fields incl. body_ref/provenance/falsy version", () => {
    const out = stripServerManaged({ title: "Party", family_id: "ATTACKER", version: 0, body_ref: "k", provenance: { source: "x" } });
    expect(out).toEqual({ title: "Party" });
  });
  it("does not mutate the input", () => {
    const input = { title: "x", family_id: "y" };
    stripServerManaged(input);
    expect(input).toEqual({ title: "x", family_id: "y" });
  });
});

describe("provenance rebuilt server-side (un-forgeable)", () => {
  it("stamps credential_id, keeps only source+at, drops unknown keys", () => {
    const out = stampProvenance({ provenance: { source: "claude", at: "2026-06-18T10:00:00Z", credential_id: "FORGED", evil: 1 } }, "cred-real");
    expect(out.provenance).toEqual({ credential_id: "cred-real", source: "claude", at: "2026-06-18T10:00:00Z" });
  });
  it("handles array/null/missing provenance without corruption", () => {
    expect(stampProvenance({ provenance: ["FORGED"] }, "c").provenance).toEqual({ credential_id: "c" });
    expect(stampProvenance({ provenance: null }, "c").provenance).toEqual({ credential_id: "c" });
    expect(stampProvenance({}, "c").provenance).toEqual({ credential_id: "c" });
  });
});

describe("schema validation (generated zod from single source)", () => {
  it("accepts a valid briefing card", () => {
    const r = BriefingCardSchema.safeParse({
      id: "01J0000000000000000000000A", kind: "info", title: "Party Sat",
      provenance: { source: "claude", at: "2026-06-18T10:00:00Z" },
    });
    expect(r.success).toBe(true);
  });
  it("rejects an invalid card (bad kind / missing title)", () => {
    const r = BriefingCardSchema.safeParse({ id: "01J0000000000000000000000A", kind: "nope" });
    expect(r.success).toBe(false);
  });
});
