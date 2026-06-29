// ADR 0030 — direct unit tests for the per-member read-visibility primitives. The
// integration suite (card-visibility.test) exercises these through the DB, but the DB
// column is `visibility text NOT NULL DEFAULT 'family'`, so it can't reach the DEFENSIVE
// edges these functions guard: an undefined visibility, a non-legacy NULL-user credential,
// a missing/empty audience. Those edges are the security contract — pin them here.
import { describe, it, expect } from "vitest";
import { cardVisible, cardVisibilityClause } from "../src/content/visibility.ts";

const legacy = { userId: null, legacy: true };
const owner = { userId: "u1", legacy: false };
const eve = { userId: "u2", legacy: false };
const nullUser = { userId: null, legacy: false }; // a non-legacy credential with no user

describe("cardVisible", () => {
  it("legacy caller reads everything (it authors restricted content, so it must read it)", () => {
    expect(cardVisible({ visibility: "restricted", audience: [] }, legacy)).toBe(true);
  });

  it("family card is visible to any member", () => {
    expect(cardVisible({ visibility: "family" }, eve)).toBe(true);
  });

  it("restricted card is visible to a member in its audience", () => {
    expect(cardVisible({ visibility: "restricted", audience: ["u1"] }, owner)).toBe(true);
  });

  it("restricted card is NOT visible to a member outside its audience", () => {
    expect(cardVisible({ visibility: "restricted", audience: ["u1"] }, eve)).toBe(false);
  });

  it("restricted card with an EMPTY audience is invisible to everyone (fail-closed)", () => {
    expect(cardVisible({ visibility: "restricted", audience: [] }, owner)).toBe(false);
  });

  it("restricted card with a MISSING audience is invisible (fail-closed)", () => {
    expect(cardVisible({ visibility: "restricted" }, owner)).toBe(false);
  });

  it("a non-legacy NULL-user credential CANNOT read restricted content (round-1 P0-2: no NULL→god-mode)", () => {
    expect(cardVisible({ visibility: "restricted", audience: ["u1"] }, nullUser)).toBe(false);
  });

  it("undefined visibility is treated as family-visible — DEFENSIVE only; the DB column is NOT NULL DEFAULT 'family' so this can't arrive via the DB", () => {
    expect(cardVisible({}, eve)).toBe(true);
  });
});

describe("cardVisibilityClause (the SQL form of cardVisible)", () => {
  it("legacy caller gets no filter (reads all)", () => {
    const c = cardVisibilityClause(legacy, 5);
    expect(c.sql).toBe("");
    expect(c.params).toEqual([]);
  });

  it("non-legacy → family OR audience-contains-caller, threading the given param index", () => {
    const c = cardVisibilityClause(owner, 2);
    expect(c.sql).toContain("visibility = 'family'");
    expect(c.sql).toContain("$2 = ANY(audience)");
    expect(c.params).toEqual(["u1"]);
  });

  it("a NULL userId binds a non-null sentinel so it never matches a real audience entry", () => {
    const c = cardVisibilityClause(nullUser, 2);
    expect(c.params).toEqual(["\0"]); // a real user id never equals "\0"
  });
});
