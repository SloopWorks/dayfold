import { describe, it, expect } from "vitest";
import { isMemberWrite, ifMatchFails, memberDeleteForbidden } from "../src/content/write-guard.ts";
import { isEncryptedEnvelope, blockPayloadIssues } from "../src/content-validation.ts";

describe("isMemberWrite (member vs loop/CLI classification)", () => {
  it("a non-legacy app credential is a member write", () => {
    expect(isMemberWrite({ legacy: false, cred: { kind: "app" } })).toBe(true);
  });
  it("the legacy household token is NOT a member write (loop/CLI authoring)", () => {
    expect(isMemberWrite({ legacy: true, cred: { kind: "app" } })).toBe(false);
  });
  it("a cli-kind credential is NOT a member write (keeps re-create-by-PUT)", () => {
    expect(isMemberWrite({ legacy: false, cred: { kind: "cli" } })).toBe(false);
  });
});

describe("memberDeleteForbidden (W4 author-gate)", () => {
  const member = (userId: string | null) => ({ legacy: false, userId, cred: { kind: "app" } });
  it("a member may delete a block they authored", () => {
    expect(memberDeleteForbidden(member("alice"), "alice")).toBe(false);
  });
  it("a member may NOT delete another member's block", () => {
    expect(memberDeleteForbidden(member("alice"), "bob")).toBe(true);
  });
  it("a member may NOT delete a loop-authored block (created_by null)", () => {
    expect(memberDeleteForbidden(member("alice"), null)).toBe(true);
  });
  it("a non-legacy NULL-user credential may delete NOTHING — incl. a loop block (P0-2: no null match)", () => {
    expect(memberDeleteForbidden(member(null), null)).toBe(true);   // the closed hole: null !== null is false, but the userId==null guard forbids
    expect(memberDeleteForbidden(member(null), "alice")).toBe(true);
  });
  it("the loop/CLI authoring path (legacy or cli) may delete anything", () => {
    expect(memberDeleteForbidden({ legacy: true, userId: null, cred: { kind: "app" } }, null)).toBe(false);
    expect(memberDeleteForbidden({ legacy: false, userId: "svc", cred: { kind: "cli" } }, "alice")).toBe(false);
  });
});

describe("ifMatchFails (If-Match → 412 decision)", () => {
  it("absent / empty header → no precondition (back-compat for the loop)", () => {
    expect(ifMatchFails(undefined, 5)).toBe(false);
    expect(ifMatchFails("", 5)).toBe(false);
    expect(ifMatchFails("   ", 5)).toBe(false);
  });
  it("matching base version → passes (no 412)", () => {
    expect(ifMatchFails("5", 5)).toBe(false);
    expect(ifMatchFails('"5"', 5)).toBe(false); // quoted ETag form
    expect(ifMatchFails('W/"5"', 5)).toBe(false); // weak ETag form
  });
  it("stale base version → fails (→ 412)", () => {
    expect(ifMatchFails("5", 6)).toBe(true);
    expect(ifMatchFails("5", null)).toBe(true); // target not live → precondition unmet
  });
});

describe("blockPayloadIssues — plaintext-M0 gate (ADR 0038 §6.2)", () => {
  it("a ciphertext envelope payload is opaque → never structurally validated", () => {
    expect(isEncryptedEnvelope({ ct: "x", nonce: "y", alg: "xchacha20poly1305" })).toBe(true);
    // a checklist whose payload is ciphertext: no `items` to check, but NOT an error.
    expect(blockPayloadIssues({ type: "checklist", payload: { ct: "x", nonce: "y", alg: "a" } })).toEqual([]);
  });
  it("a plaintext checklist with items still validates (M0 unchanged)", () => {
    expect(blockPayloadIssues({ type: "checklist", payload: { items: [{ id: "x", text: "a" }] } })).toEqual([]);
    expect(blockPayloadIssues({ type: "checklist", payload: { items: [] } }).length).toBe(1); // empty → flagged
  });
  it("a plaintext object that is NOT an envelope is still type-checked", () => {
    expect(isEncryptedEnvelope({ items: [] })).toBe(false);
  });
});
