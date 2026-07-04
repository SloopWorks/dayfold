import { describe, it, expect } from "vitest";
import { HubSchema } from "./content";

describe("generated Hub.timeline", () => {
  it("accepts a hub with a valid timeline", () => {
    const r = HubSchema.safeParse({ id: "01H", type: "starting-college", title: "Maya",
      timeline: { tz: "America/New_York", stops: [{ at: "2026-08-24", title: "Move-in" }] } });
    expect(r.success).toBe(true);
  });
  it("still accepts a hub with no timeline", () => {
    expect(HubSchema.safeParse({ id: "01H", type: "move", title: "X" }).success).toBe(true);
  });
});
