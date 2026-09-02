import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { BlockSchema, BriefingCardSchema } from "../src/generated/content.ts";
import { temporalIssues } from "../src/content-validation.ts";

const id = "01K45ABCDEF0123456789GHJKM";
const id2 = "01K45ABCDEF0123456789GHJKN";
const provenance = { source: "cli", at: "2026-09-01T12:00:00Z" };
const timed = (overrides: Record<string, unknown> = {}) => ({
  id, role: "event", label: "Show", start: "2026-08-28T21:00:00-07:00",
  zone: "America/Los_Angeles", status: "confirmed", ...overrides,
});

describe("ADR 0067 generated temporal contract", () => {
  it("keeps the shared temporal fixture corpus aligned with generated and cross-field validation", () => {
    const corpus = new URL("../../../specs/domain-model/examples/temporal-v1/", import.meta.url);
    for (const name of readdirSync(corpus).filter((file) => file.endsWith(".json"))) {
      const value = JSON.parse(readFileSync(new URL(name, corpus), "utf8"));
      if (name.startsWith("valid-")) {
        const parsed = BlockSchema.safeParse({
          id: `fixture-${name}`, type: "markdown", body_md: "fixture", provenance, ...value,
        });
        expect(parsed.success, name).toBe(true);
        expect(temporalIssues((parsed as any).data), name).toEqual([]);
      } else {
        expect(temporalIssues(value).length, name).toBeGreaterThan(0);
      }
    }
  });

  it("accepts bounded all-day and multiple timed occurrences", () => {
    const block = BlockSchema.safeParse({
      id: "block", type: "markdown", body_md: "Schedule", provenance,
      temporal: { occurrences: [timed(), timed({ id: id2, start: "2026-08-28T23:00:00-07:00" })] },
    });
    expect(block.success).toBe(true);
    expect(temporalIssues((block as any).data)).toEqual([]);

    const card = BriefingCardSchema.safeParse({
      id: "card", kind: "info", title: "Camp", provenance,
      temporal: { occurrences: [{ id, role: "event", label: "Camp", start: "2026-08-28", end: "2026-08-30", status: "confirmed" }] },
    });
    expect(card.success).toBe(true);
    expect(temporalIssues((card as any).data)).toEqual([]);
  });

  it("rejects offset-less, fractional, unknown-offset, and date-only values with zones", () => {
    for (const start of ["2026-08-28T21:00:00", "2026-08-28T21:00:00.123Z", "2026-08-28T21:00:00-00:00"]) {
      expect(BlockSchema.safeParse({ id: "b", type: "markdown", body_md: "x", provenance,
        temporal: { occurrences: [timed({ start })] } }).success).toBe(false);
    }
    expect(temporalIssues({ temporal: { occurrences: [{ ...timed({ start: "2026-08-28" }), zone: "UTC" }] } })
      .map((issue) => issue.code)).toContain("temporal.all-day-zone-forbidden");
  });

  it("enforces actual civil values, exclusive ends, role rules, and unique ids", () => {
    const codes = temporalIssues({ temporal: { occurrences: [
      timed({ start: "2026-02-30T10:00:00Z", zone: "UTC" }),
      timed({ role: "window" }),
      timed({ role: "deadline", end: "2026-08-28T22:00:00-07:00" }),
    ] } }).map((issue) => issue.code);
    expect(codes).toContain("temporal.invalid-start");
    expect(codes).toContain("temporal.window-end-required");
    expect(codes).toContain("temporal.deadline-end-forbidden");
    expect(codes).toContain("temporal.duplicate-id");
  });

  it("resolves one eligible same-item fact trigger and bounds its fixed offset", () => {
    const resource = {
      temporal: { occurrences: [timed()] },
      triggers: [{ when: { fact_ref: `temporal:${id}`, alert_offset: "-PT30M" } }],
    };
    expect(temporalIssues(resource)).toEqual([]);
    expect(temporalIssues({ ...resource, triggers: [{ when: { fact_ref: `temporal:${id}`, alert_offset: "P31D" } }] })
      .map((issue) => issue.code)).toContain("temporal.invalid-alert-offset");
    expect(temporalIssues({ ...resource, triggers: [resource.triggers[0], resource.triggers[0]] })
      .map((issue) => issue.code)).toContain("temporal.multiple-fact-triggers");
  });

  it("rejects dangling, all-day, tentative, cancelled, and reference behavior refs", () => {
    const refs = [
      { occurrence: timed({ start: "2026-08-28", zone: undefined }), code: "temporal.ineligible-fact-ref" },
      { occurrence: timed({ status: "tentative" }), code: "temporal.ineligible-fact-ref" },
      { occurrence: timed({ status: "cancelled" }), code: "temporal.ineligible-fact-ref" },
      { occurrence: timed({ role: "reference" }), code: "temporal.ineligible-fact-ref" },
    ];
    for (const sample of refs) {
      const codes = temporalIssues({ temporal: { occurrences: [sample.occurrence] },
        triggers: [{ when: { fact_ref: `temporal:${id}` } }] }).map((issue) => issue.code);
      expect(codes).toContain(sample.code);
    }
    expect(temporalIssues({ triggers: [{ when: { fact_ref: `temporal:${id}` } }] })
      .map((issue) => issue.code)).toContain("temporal.dangling-fact-ref");
  });
});
