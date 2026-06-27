import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { BriefingCardSchema, BlockSchema } from "../src/generated/content.ts";

// CL-1 (ADR 0022 D1): the BriefingCard now carries a typed `type` + `payload`
// discriminated by type. Pure zod validation — no DB. Verifies the codegen
// emits TYPED payload variants (a bad payload is rejected, not waved through as
// z.any) and that the change is backward-compatible with kind-only M0 cards.
const here = dirname(fileURLToPath(import.meta.url));
const ex = (n: string) =>
  JSON.parse(readFileSync(resolve(here, "../../../specs/domain-model/examples/" + n), "utf8"));

describe("BriefingCard typed content payload (CL-1)", () => {
  it("accepts the file + invite example cards", () => {
    expect(BriefingCardSchema.safeParse(ex("card-file.json")).success).toBe(true);
    expect(BriefingCardSchema.safeParse(ex("card-invite.json")).success).toBe(true);
  });

  it("stays backward-compatible with a kind-only card (no type/payload)", () => {
    const legacy = {
      id: "01J9ZX8K3QFT7V2N5M6P4R8W0C",
      kind: "weather",
      title: "Rain at soccer 4pm — pack jackets",
      provenance: { source: "claude", at: "2026-06-19T10:00:00Z" },
    };
    expect(BriefingCardSchema.safeParse(legacy).success).toBe(true);
  });

  it("rejects an unknown payload variant (payload is typed, not z.any)", () => {
    const bad = { ...ex("card-file.json"), payload: { bogus: { x: 1 } } };
    expect(BriefingCardSchema.safeParse(bad).success).toBe(false);
  });

  it("rejects an unknown field inside a typed payload variant (strict)", () => {
    const card = ex("card-file.json");
    const bad = { ...card, payload: { file: { ...card.payload.file, notAField: true } } };
    expect(BriefingCardSchema.safeParse(bad).success).toBe(false);
  });

  it("rejects an out-of-enum content type", () => {
    const bad = { ...ex("card-file.json"), type: "spreadsheet" };
    expect(BriefingCardSchema.safeParse(bad).success).toBe(false);
  });

  it("rejects a bad invite rsvpState enum", () => {
    const card = ex("card-invite.json");
    const bad = { ...card, payload: { invite: { ...card.payload.invite, rsvpState: "maybe" } } };
    expect(BriefingCardSchema.safeParse(bad).success).toBe(false);
  });
});

// ADR 0035 / #180: unlike the card, BlockSchema.payload is z.any() — structured blocks
// are validated by blockPayloadIssues (tolerant, per-type), NOT by zod. A codegen
// regression that re-resolved the per-type oneOf emitted a [z.any()×N] superRefine that
// REJECTED every structured payload — the #180 outage (every block PUT 422'd). The
// codegen-staleness CI check can't catch that (content.ts would still match the broken
// generator); this asserts the SEMANTICS: BlockSchema must accept a structured payload.
describe("Block typed content payload (ADR 0035 — permissive payload)", () => {
  const prov = { source: "cli", at: "2026-06-26T00:00:00Z" };
  it("accepts a structured payload (not the rejecting oneOf superRefine)", () => {
    expect(BlockSchema.safeParse({ id: "b1", type: "checklist", ord: 0, provenance: prov, payload: { items: [{ text: "Submit FAFSA" }] } }).success).toBe(true);
    expect(BlockSchema.safeParse({ id: "b2", type: "contact", ord: 1, provenance: prov, payload: { name: "Admissions" } }).success).toBe(true);
  });
  it("accepts a body_md-only block and a no-payload block", () => {
    expect(BlockSchema.safeParse({ id: "b3", type: "markdown", ord: 2, provenance: prov, body_md: "## Timing traps" }).success).toBe(true);
    expect(BlockSchema.safeParse({ id: "b4", type: "checklist", ord: 3, provenance: prov }).success).toBe(true);
  });
  it("still enforces the block `type` enum", () => {
    expect(BlockSchema.safeParse({ id: "b5", type: "spreadsheet", ord: 0, provenance: prov }).success).toBe(false);
  });
});
