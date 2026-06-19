// GENERATED from specs/domain-model/schemas/content.schema.json — DO NOT EDIT.
// Regenerate: npm run codegen (root). Source of truth = the JSON schema (ADR 0006).
import { z } from "zod";

export const ProvenanceSchema = z.object({ "source": z.string().describe("claude | email | user | <url>"), "at": z.any(), "credential_id": z.string().describe("which credential pushed this (audit)").optional() }).strict()
export type Provenance = z.infer<typeof ProvenanceSchema>;

export const TriggerSchema = z.any().superRefine((x, ctx) => {
    const schemas = [z.object({ "geo": z.object({ "place_ref": z.string().optional(), "lat": z.number().optional(), "lng": z.number().optional(), "radius_m": z.number().int().default(150), "label": z.string().optional() }) }).strict(), z.object({ "when": z.object({ "at": z.any().optional(), "window": z.record(z.string(), z.any()).optional(), "relative": z.string().optional(), "recurring": z.string().optional(), "alert_offset": z.string().optional() }) }).strict(), z.object({ "activity": z.object({ "kind": z.enum(["walking","running","biking","driving"]).optional() }) }).strict().describe("schema slot; matching DEFERRED")];
    const { errors, failed } = schemas.reduce<{
      errors: z.core.$ZodIssue[];
      failed: number;
    }>(
      ({ errors, failed }, schema) =>
        ((result) =>
          result.error
            ? {
                errors: [...errors, ...result.error.issues],
                failed: failed + 1,
              }
            : { errors, failed })(
          schema.safeParse(x),
        ),
      { errors: [], failed: 0 },
    );
    const passed = schemas.length - failed;
    if (passed !== 1) {
      ctx.addIssue(errors.length ? {
        path: [],
        code: "invalid_union",
        errors: [errors],
        message: "Invalid input: Should pass single schema. Passed " + passed,
      } : {
        path: [],
        code: "custom",
        errors: [errors],
        message: "Invalid input: Should pass single schema. Passed " + passed,
      });
    }
  }).describe("ADR 0014 — matched ON-DEVICE; live position never leaves.")
export type Trigger = z.infer<typeof TriggerSchema>;

export const ActionSchema = z.object({ "label": z.string(), "action_id": z.string(), "params": z.record(z.string(), z.any()).optional() }).strict().describe("ADR 0016 RESERVED (bounded-now: buttons + structured asks; not built at MVP).")
export type Action = z.infer<typeof ActionSchema>;

export const LinkPayloadSchema = z.object({ "url": z.string().url(), "label": z.string().optional(), "source": z.string().optional() }).strict()
export type LinkPayload = z.infer<typeof LinkPayloadSchema>;

export const ChecklistPayloadSchema = z.object({ "items": z.array(z.object({ "text": z.string(), "done": z.boolean().default(false), "due": z.any().optional(), "assignee": z.string().optional() }).strict()) }).strict()
export type ChecklistPayload = z.infer<typeof ChecklistPayloadSchema>;

export const DocumentPayloadSchema = z.object({ "ref": z.string().describe("url | fileRef (links+small refs at MVP)"), "label": z.string().optional(), "kind": z.string().optional() }).strict()
export type DocumentPayload = z.infer<typeof DocumentPayloadSchema>;

export const MilestonePayloadSchema = z.object({ "date": z.any(), "label": z.string() }).strict()
export type MilestonePayload = z.infer<typeof MilestonePayloadSchema>;

export const ContactPayloadSchema = z.object({ "name": z.string(), "role": z.string().optional(), "phone": z.string().optional(), "email": z.string().optional() }).strict()
export type ContactPayload = z.infer<typeof ContactPayloadSchema>;

export const LocationPayloadSchema = z.object({ "label": z.string(), "address": z.string().optional(), "mapUrl": z.string().optional() }).strict()
export type LocationPayload = z.infer<typeof LocationPayloadSchema>;

export const BudgetPayloadSchema = z.object({ "items": z.array(z.object({ "label": z.string(), "amount": z.number(), "paid": z.boolean().default(false) }).strict()) }).strict()
export type BudgetPayload = z.infer<typeof BudgetPayloadSchema>;

export const BlockSchema = z.object({ "id": z.any(), "type": z.enum(["text","markdown","link","checklist","document","milestone","contact","location","budget"]), "ord": z.number().int().default(0), "version": z.any().optional(), "body_md": z.string().max(1048576).describe("long-form markdown (text/markdown blocks); inline ≤1MB at M0, else spill to body_ref (06, M1)").optional(), "body_ref": z.string().describe("object-storage KEY when spilled (M1); never a URL; XOR with body_md").optional(), "payload": z.any().superRefine((x, ctx) => {
    const schemas = [z.any(), z.any(), z.any(), z.any(), z.any(), z.any(), z.any()];
    const { errors, failed } = schemas.reduce<{
      errors: z.core.$ZodIssue[];
      failed: number;
    }>(
      ({ errors, failed }, schema) =>
        ((result) =>
          result.error
            ? {
                errors: [...errors, ...result.error.issues],
                failed: failed + 1,
              }
            : { errors, failed })(
          schema.safeParse(x),
        ),
      { errors: [], failed: 0 },
    );
    const passed = schemas.length - failed;
    if (passed !== 1) {
      ctx.addIssue(errors.length ? {
        path: [],
        code: "invalid_union",
        errors: [errors],
        message: "Invalid input: Should pass single schema. Passed " + passed,
      } : {
        path: [],
        code: "custom",
        errors: [errors],
        message: "Invalid input: Should pass single schema. Passed " + passed,
      });
    }
  }).describe("structured fields for non-markdown block types; variant by `type` (see $comment)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "provenance": z.any() }).strict().and(z.any())
export type Block = z.infer<typeof BlockSchema>;

export const SectionSchema = z.object({ "id": z.any(), "title": z.string().describe("[CONTENT/E2E-hole]").optional(), "ord": z.number().int().default(0), "version": z.any().optional(), "blocks": z.array(z.any()).optional() }).strict()
export type Section = z.infer<typeof SectionSchema>;

export const HubSchema = z.object({ "id": z.any(), "type": z.string().describe("bounded template-catalog key (ADR 0004/0006): vacation|starting-college|move|party-event|new-baby|medical|school-year — app-validated"), "title": z.string().describe("[CONTENT/E2E-hole]"), "status": z.enum(["planning","active","archived"]).default("active"), "start_at": z.any().optional(), "end_at": z.any().optional(), "countdown_to": z.any().optional(), "version": z.any().optional(), "sections": z.array(z.any()).optional() }).strict()
export type Hub = z.infer<typeof HubSchema>;

export const BriefingCardSchema = z.object({ "id": z.any(), "kind": z.enum(["action","info","weather","countdown"]).default("info"), "title": z.string().describe("[CONTENT/E2E-hole]"), "body_md": z.string().describe("[CONTENT/E2E-hole] limited inline markdown only").optional(), "target": z.object({ "hubId": z.string().optional(), "sectionId": z.string().optional(), "blockId": z.string().optional() }).strict().describe("deep-link into a hub (resolved client-side vs local cache, nearest-ancestor)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "not_before": z.any().optional(), "expires_at": z.any().optional(), "version": z.any().optional(), "provenance": z.any() }).strict().describe("the 'Now' surface")
export type BriefingCard = z.infer<typeof BriefingCardSchema>;

export const PlaceSchema = z.object({ "id": z.any(), "label": z.string(), "kind": z.enum(["home","school","store","other"]).describe("category (drives the place icon in the UI; design alignment)").default("other"), "lat": z.number(), "lng": z.number(), "radius_m": z.number().int().default(150), "version": z.any().optional() }).strict().describe("ADR 0014 reusable named place; family content (encrypted at rest, never live position)")
export type Place = z.infer<typeof PlaceSchema>;

export const SyncResponseSchema = z.object({ "changes": z.object({ "hubs": z.array(z.any()).optional(), "sections": z.array(z.any()).optional(), "blocks": z.array(z.any()).optional(), "cards": z.array(z.any()).optional(), "places": z.array(z.any()).optional() }), "tombstones": z.array(z.object({ "type": z.enum(["hub","section","block","card","place"]), "id": z.string() }).strict()), "next_cursor": z.string().optional(), "has_more": z.boolean() }).strict().describe("GET /families/{fid}/sync (03 §sync)")
export type SyncResponse = z.infer<typeof SyncResponseSchema>;

