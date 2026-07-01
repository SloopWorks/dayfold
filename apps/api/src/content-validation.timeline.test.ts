import { describe, it, expect } from "vitest";
import { hubTimelineIssues } from "./content-validation";

describe("hubTimelineIssues", () => {
  it("no timeline → no issues", () => { expect(hubTimelineIssues({})).toEqual([]); });
  it("valid timeline → no issues", () => {
    expect(hubTimelineIssues({ timeline: { tz: "America/New_York", stops: [{ at: "2026-08-24", title: "Move-in" }] } })).toEqual([]);
  });
  it("missing tz → issue", () => {
    expect(hubTimelineIssues({ timeline: { stops: [{ at: "2026-08-24", title: "X" }] } }).length).toBe(1);
  });
  it("empty stops → issue", () => {
    expect(hubTimelineIssues({ timeline: { tz: "UTC", stops: [] } }).length).toBe(1);
  });
  it("stop missing at → issue", () => {
    expect(hubTimelineIssues({ timeline: { tz: "UTC", stops: [{ title: "X" }] } }).length).toBe(1);
  });
  it("bad attachment kind → issue", () => {
    expect(hubTimelineIssues({ timeline: { tz: "UTC", stops: [{ at: "2026-01-01", title: "X",
      attachments: [{ kind: "df", label: "y" }] }] } }).length).toBe(1);
  });
  it("null stop element → issue, no throw", () => {
    const result = hubTimelineIssues({ timeline: { tz: "UTC", stops: [null] } });
    expect(result.length).toBeGreaterThanOrEqual(1);
  });
  it("null attachment element → issue, no throw", () => {
    const result = hubTimelineIssues({ timeline: { tz: "UTC", stops: [{ at: "2026-01-01", title: "X", attachments: [null] }] } });
    expect(result.length).toBeGreaterThanOrEqual(1);
  });
});
