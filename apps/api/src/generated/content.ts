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

export const LinkPayloadSchema = z.object({ "url": z.string().url(), "label": z.string().optional(), "source": z.string().optional(), "thumbnailUrl": z.string().url().max(2048).describe("link preview image; https + allowlisted host (ADR 0036)").optional(), "thumbnailAlt": z.string().max(256).describe("a11y alt for thumbnailUrl").optional() }).strict()
export type LinkPayload = z.infer<typeof LinkPayloadSchema>;

export const ChecklistPayloadSchema = z.object({ "items": z.array(z.object({ "id": z.any(), "text": z.string().describe("loop-authoritative at M0"), "done": z.boolean().describe("member-mutable (the done-triple)").default(false), "doneBy": z.string().describe("NEW (ADR 0038) — user id who toggled (display byline)").optional(), "doneAt": z.any().optional(), "ord": z.number().int().describe("NEW (ADR 0038) — order; loop-authoritative at M0 (§5.3); doneBy/doneAt/id are the new ADR-0038 fields").default(0), "due": z.any().optional(), "assignee": z.string().describe("loop-authoritative at M0").optional() }).strict()) }).strict()
export type ChecklistPayload = z.infer<typeof ChecklistPayloadSchema>;

export const DocumentPayloadSchema = z.object({ "ref": z.string().describe("url | fileRef (links+small refs at MVP)"), "label": z.string().optional(), "kind": z.string().optional(), "thumbnailUrl": z.string().url().max(2048).describe("document preview image; https + allowlisted host (ADR 0036)").optional(), "thumbnailAlt": z.string().max(256).describe("a11y alt for thumbnailUrl").optional() }).strict()
export type DocumentPayload = z.infer<typeof DocumentPayloadSchema>;

export const MilestonePayloadSchema = z.object({ "date": z.any(), "label": z.string() }).strict()
export type MilestonePayload = z.infer<typeof MilestonePayloadSchema>;

export const ContactPayloadSchema = z.object({ "name": z.string(), "role": z.string().optional(), "phone": z.string().optional(), "email": z.string().optional(), "avatarUrl": z.string().url().max(2048).describe("contact avatar photo; https + allowlisted host (ADR 0036); falls back to initials").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed (ADR 0036)").optional() }).strict()
export type ContactPayload = z.infer<typeof ContactPayloadSchema>;

export const LocationPayloadSchema = z.object({ "label": z.string(), "address": z.string().optional(), "mapUrl": z.string().optional() }).strict()
export type LocationPayload = z.infer<typeof LocationPayloadSchema>;

export const BudgetPayloadSchema = z.object({ "items": z.array(z.object({ "label": z.string(), "amount": z.number(), "paid": z.boolean().default(false) }).strict()) }).strict()
export type BudgetPayload = z.infer<typeof BudgetPayloadSchema>;

export const BlockSchema = z.object({ "id": z.any(), "type": z.enum(["text","markdown","link","checklist","document","milestone","contact","location","budget"]), "ord": z.number().int().default(0), "version": z.any().optional(), "body_md": z.string().max(1048576).describe("long-form markdown (text/markdown blocks); inline ≤1MB at M0, else spill to body_ref (06, M1)").optional(), "body_ref": z.string().describe("object-storage KEY when spilled (M1); never a URL; XOR with body_md").optional(), "payload": z.any().describe("structured fields for non-markdown block types; variant by `type` (see $comment)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "provenance": z.any() }).strict().and(z.any())
export type Block = z.infer<typeof BlockSchema>;

export const SectionSchema = z.object({ "id": z.any(), "title": z.string().describe("[CONTENT/E2E-hole]").optional(), "ord": z.number().int().default(0), "version": z.any().optional(), "blocks": z.array(z.any()).optional() }).strict()
export type Section = z.infer<typeof SectionSchema>;

export const HubSchema = z.object({ "id": z.any(), "type": z.string().describe("bounded template-catalog key (ADR 0004/0006): vacation|starting-college|move|party-event|new-baby|medical|school-year — app-validated"), "title": z.string().describe("[CONTENT/E2E-hole]"), "status": z.enum(["planning","active","archived"]).default("active"), "start_at": z.any().optional(), "end_at": z.any().optional(), "countdown_to": z.any().optional(), "version": z.any().optional(), "sections": z.array(z.any()).optional(), "media": z.object({ "heroUrl": z.string().url().max(2048).describe("hero image (Hub detail header + list-row fallback). https + allowlisted host.").optional(), "thumbnailUrl": z.string().url().max(2048).describe("list-row 1:1 thumbnail; absent → falls back to heroUrl client-side.").optional(), "heroFit": z.enum(["cover","contain"]).describe("cover=photo edge-to-edge crop; contain=logo letterboxed on accent tint.").optional(), "imageAlt": z.string().max(256).describe("a11y alt → contentDescription (else derived from title).").optional(), "icon": z.string().max(40).describe("curated icon NAME, server-validated vs the bundled set (ADR 0036); unknown → fallback tile.").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed (edge/tile/chip/scrim); never body text (WCAG 1.4.1). Lowercased on write.").optional() }).strict().describe("visual enrichment (ADR 0036; all optional, absent = unenriched/today's look). URLs are https + allowlisted-host (ADR 0036 shared validator); icon ∈ curated set; accentColor is decorative-only.").optional() }).strict()
export type Hub = z.infer<typeof HubSchema>;

export const BriefingCardSchema = z.object({ "id": z.any(), "kind": z.enum(["action","info","weather","countdown"]).default("info"), "title": z.string().max(4096), "body_md": z.string().max(1048576).describe("limited inline markdown only (1MB cap, F8)").optional(), "target": z.object({ "hubId": z.string().optional(), "sectionId": z.string().optional(), "blockId": z.string().optional() }).strict().describe("deep-link into a hub (resolved client-side vs local cache, nearest-ancestor)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "not_before": z.any().optional(), "expires_at": z.any().optional(), "version": z.any().optional(), "provenance": z.any(), "type": z.enum(["file","link","invite","contact","geo","email"]).describe("content type (ADR 0022 D1) — drives the Now-card / detail layout. OPTIONAL for back-compat with kind-only M0 cards.").optional(), "media": z.object({ "icon": z.string().max(40).describe("curated icon NAME (server-validated); unknown → fallback.").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed; never body text. Lowercased on write.").optional(), "thumbnailUrl": z.string().url().max(2048).describe("optional leading thumbnail; https + allowlisted host.").optional(), "imageAlt": z.string().max(256).describe("a11y alt for thumbnailUrl.").optional(), "imageFit": z.enum(["cover","contain"]).optional() }).strict().describe("card visual enrichment (ADR 0036; all optional). icon+accent on the kind chip + optional leading thumbnail. Same shared URL/host/icon/hex validation as Hub.media.").optional(), "hubRef": z.string().describe("parent Hub id — the adaptive supporting pane's 'PART OF THIS HUB' (ADR 0022; CL-10). Optional.").optional(), "relatedKicker": z.string().describe("section header for the RELATED rows (e.g. 'FROM THE SAME EMAIL'). CL-8.").optional(), "related": z.array(z.object({ "relation": z.string().describe("same-email | same-thread | same-hub | same-trip | attachment | contact-of"), "targetId": z.string(), "targetType": z.enum(["file","link","invite","contact","geo","email"]), "title": z.string().optional(), "sub": z.string().optional() }).strict()).describe("cross-links to other cards in THIS family (CL-8). targetId resolves client-side vs the local cache; title/sub are author-denormalized so a row renders without resolving. Same-tenant only (rides authorizeTenant).").optional(), "privacy": z.object({ "storage": z.enum(["on_device","in_browser","location_local","matched_on_device"]).optional() }).strict().describe("honesty chip (ADR 0014/0015) — a claim allowed ONLY where a real schema/API/client boundary enforces it.").optional(), "payload": z.any().superRefine((x, ctx) => {
    const schemas = [z.object({ "file": z.object({ "filename": z.string().optional(), "mime": z.string().optional(), "size": z.number().int().optional(), "pages": z.number().int().optional(), "source": z.string().optional(), "owner": z.string().optional(), "modified": z.string().datetime({ offset: true }).optional(), "sharedWith": z.array(z.string()).optional(), "docRef": z.string().describe("url | opaque storage ref").optional() }).strict() }).strict(), z.object({ "link": z.object({ "url": z.string().url().optional(), "domain": z.string().optional(), "title": z.string().optional(), "ogDesc": z.string().describe("author-stamped OG; server never fetches the URL (no SSRF)").optional(), "favicon": z.string().optional(), "kind": z.enum(["page","form"]).optional(), "fieldCount": z.number().int().optional(), "closesAt": z.string().datetime({ offset: true }).optional(), "savedAt": z.string().datetime({ offset: true }).optional() }).strict() }).strict(), z.object({ "invite": z.object({ "eventName": z.string().optional(), "host": z.string().optional(), "startAt": z.string().datetime({ offset: true }).optional(), "place": z.string().optional(), "rsvpBy": z.string().datetime({ offset: true }).optional(), "rsvpState": z.enum(["yes","no","none"]).describe("display-of-state at M0 (no write path; ADR 0020/0016)").optional(), "guestCount": z.number().int().optional(), "confirmedCount": z.number().int().optional(), "notes": z.string().optional() }).strict() }).strict(), z.object({ "contact": z.object({ "name": z.string().optional(), "company": z.string().optional(), "role": z.string().optional(), "phone": z.string().optional(), "email": z.string().optional(), "address": z.string().optional(), "hours": z.string().optional(), "linkedEventId": z.string().optional(), "deliveryWindow": z.string().optional() }).strict() }).strict(), z.object({ "geo": z.object({ "label": z.string().optional(), "address": z.string().optional(), "lat": z.number().optional(), "lng": z.number().optional(), "etaMin": z.number().int().optional(), "distance": z.string().optional(), "travelMode": z.string().optional(), "parking": z.string().optional(), "leaveBy": z.string().datetime({ offset: true }).optional(), "linkedEventId": z.string().optional() }).strict() }).strict(), z.object({ "email": z.object({ "from": z.string().optional(), "fromAddr": z.string().optional(), "subject": z.string().optional(), "date": z.string().datetime({ offset: true }).optional(), "threadLen": z.number().int().optional(), "bodyExcerpt": z.string().describe("[E2E-ciphertext] authored over the operator's OWN mail (CLI/Claude) — never a server-side Gmail restricted-scope read (Guardrail 3)").optional(), "attachments": z.array(z.object({ "name": z.string().optional(), "mime": z.string().optional(), "size": z.number().int().optional() }).strict()).optional(), "labels": z.array(z.string()).optional() }).strict() }).strict()];
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
  }).describe("[E2E-ciphertext at M1] typed content payload, variant selected by `type` (ADR 0022 D1). Inline oneOf (no internal $ref) so codegen emits TYPED variants, never z.any.").optional() }).strict().describe("the 'Now' surface")
export type BriefingCard = z.infer<typeof BriefingCardSchema>;

export const PlaceSchema = z.object({ "id": z.any(), "label": z.string(), "kind": z.enum(["home","school","store","other"]).describe("category (drives the place icon in the UI; design alignment)").default("other"), "lat": z.number(), "lng": z.number(), "radius_m": z.number().int().default(150), "version": z.any().optional() }).strict().describe("ADR 0014 reusable named place; family content (encrypted at rest, never live position)")
export type Place = z.infer<typeof PlaceSchema>;

export const SyncResponseSchema = z.object({ "changes": z.object({ "hubs": z.array(z.any()).optional(), "sections": z.array(z.any()).optional(), "blocks": z.array(z.any()).optional(), "cards": z.array(z.any()).optional(), "places": z.array(z.any()).optional() }), "tombstones": z.array(z.object({ "type": z.enum(["hub","section","block","card","place"]), "id": z.string() }).strict()), "next_cursor": z.string().optional(), "has_more": z.boolean() }).strict().describe("GET /families/{fid}/sync (03 §sync)")
export type SyncResponse = z.infer<typeof SyncResponseSchema>;

