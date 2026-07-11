// ADR 0030 — direct unit tests for the hub read-visibility primitive. Like cardVisible
// (visibility-unit.test) the integration suite (hub-api/hub-sync) drives this through the
// DB, which enforces `visibility NOT NULL` and can't reach the defensive edges. hubVisible
// is richer than cardVisible — it also grants the AUTHOR (created_by) and a resource_visibility
// ALLOW-LIST — so those branches + their null edges are pinned here.
import { describe, it, expect } from "vitest";
import { hubVisible, hubWritableByMember } from "../src/content/hubs.ts";

const legacy = { userId: null, legacy: true };
const author = { userId: "u1", legacy: false };
const member = { userId: "u2", legacy: false };
const nullUser = { userId: null, legacy: false };
const noAllow = () => false;
const allowH1 = (hid: string) => hid === "h1"; // u2 permitted on hub h1

describe("hubVisible", () => {
  it("legacy caller reads everything", () => {
    expect(hubVisible({ visibility: "restricted" }, legacy)).toBe(true);
  });

  it("family hub is visible to any member", () => {
    expect(hubVisible({ visibility: "family" }, member)).toBe(true);
  });

  it("restricted hub: the AUTHOR (created_by) sees their own", () => {
    expect(hubVisible({ visibility: "restricted", created_by: "u1", id: "h1" }, author)).toBe(true);
  });

  it("restricted hub: a permitted member (allow-list) sees it", () => {
    expect(hubVisible({ visibility: "restricted", created_by: "u1", id: "h1" }, member, allowH1)).toBe(true);
  });

  it("restricted hub: a non-author, non-permitted member does NOT see it", () => {
    expect(hubVisible({ visibility: "restricted", created_by: "u1", id: "h1" }, member, noAllow)).toBe(false);
  });

  it("a non-legacy NULL-user credential cannot see a restricted hub — even if the allow-list says yes (no NULL→god-mode)", () => {
    expect(hubVisible({ visibility: "restricted", created_by: "u1", id: "h1" }, nullUser, () => true)).toBe(false);
  });

  it("a legacy-authored hub (created_by NULL) does NOT grant a NULL-user via the author branch", () => {
    expect(hubVisible({ visibility: "restricted", created_by: null, id: "h1" }, nullUser, noAllow)).toBe(false);
  });

  it("a restricted hub with no id can't be allow-list-matched (only its author sees it)", () => {
    expect(hubVisible({ visibility: "restricted", created_by: "u1" }, member, () => true)).toBe(false);
  });

  it("undefined visibility → family-visible — DEFENSIVE only; the DB column is NOT NULL DEFAULT 'family'", () => {
    expect(hubVisible({}, member)).toBe(true);
  });
});

// ADR 0053 item 4 — the member-write gate: author OR resource_visibility.role ∈
// {contributor, co_owner} OR legacy (M0, write-exempt). A plain viewer (or a member
// with no allow-list row — role null) may NOT write.
describe("hubWritableByMember", () => {
  const hub = { created_by: "u1" };

  it("the author may write, regardless of role (even null/no row)", () => {
    expect(hubWritableByMember(hub, author, null)).toBe(true);
  });

  it("a contributor may write", () => {
    expect(hubWritableByMember(hub, member, "contributor")).toBe(true);
  });

  it("a co_owner may write", () => {
    expect(hubWritableByMember(hub, member, "co_owner")).toBe(true);
  });

  it("a plain viewer may NOT write", () => {
    expect(hubWritableByMember(hub, member, "viewer")).toBe(false);
  });

  it("a member with no allow-list row at all (role null) on a restricted hub may NOT write", () => {
    expect(hubWritableByMember(hub, member, null)).toBe(false);
  });

  it("legacy (M0) may always write, regardless of role", () => {
    expect(hubWritableByMember(hub, legacy, null)).toBe(true);
  });

  it("a non-legacy NULL-user credential cannot match a loop-authored (created_by NULL) hub via the author branch (P0-2)", () => {
    expect(hubWritableByMember({ created_by: null }, nullUser, null)).toBe(false);
  });
});
