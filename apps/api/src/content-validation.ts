// CL-2 (ADR 0022 D1/D2) — type↔payload cross-validation.
//
// CL-1's BriefingCardSchema validates `type` (enum) and `payload` (a strict
// oneOf of the 6 single-key variants) INDEPENDENTLY — it does NOT enforce that
// the payload's variant key matches `type`. So `{type:"file", payload:{invite:…}}`
// passes zod. The CL-1 commit deferred this cross-check to "CL-2 server
// superRefine". This is it.
//
// Rule (M0, strict for renderer-safety): a card is *typed* iff it carries a
// payload — the two appear together or not at all — and when present the
// payload's single variant key MUST equal `type`. Legacy kind-only cards
// (neither field) stay valid (back-compat). Keeps the client invariant that a
// typed card always has a matching, renderable payload.

export const CONTENT_TYPES = ["file", "link", "invite", "contact", "geo", "email"] as const;
export type ContentType = (typeof CONTENT_TYPES)[number];

export type CrossIssue = { path: (string | number)[]; message: string };

/**
 * Returns [] when the card is consistent, else a zod-issue-shaped list (so the
 * PUT handler can surface it in the same 422 `issues` envelope as zod errors).
 * Operates on the already-zod-parsed card (so `payload`, if present, is a strict
 * single-key object and `type` is a valid enum member or undefined).
 */
export function crossValidateCard(card: { type?: unknown; payload?: unknown }): CrossIssue[] {
  const hasType = card.type != null;
  const hasPayload = card.payload != null;

  if (!hasType && !hasPayload) return []; // legacy kind-only card — valid

  if (hasType !== hasPayload) {
    return [{
      path: [hasType ? "payload" : "type"],
      message: hasType
        ? "a typed card (`type` set) must carry a matching `payload`"
        : "`payload` requires a `type` discriminator",
    }];
  }

  // both present — the payload's single key must equal `type`.
  const keys = Object.keys(card.payload as Record<string, unknown>);
  if (keys.length !== 1 || keys[0] !== card.type) {
    return [{
      path: ["payload"],
      message: `payload variant "${keys[0] ?? "(none)"}" does not match type "${String(card.type)}"`,
    }];
  }
  return [];
}

/**
 * Block-payload structural pre-check (ADR 0035, Option C). The generated
 * `BlockSchema.payload` is `z.any()` (codegen stubbed the per-type `oneOf` $refs),
 * so the server does NOT validate a structured block's payload — a `contact` block
 * with no name, or `payload: "oops"`, stores fine and then can't render. Mirror the
 * CLI's tolerant per-type check: a payload, when present, must be an object carrying
 * its type's core field. TOLERANT — accepts BOTH the canonical schema names and the
 * current client-render names (document `ref`|`docRef`; budget `items`|`total`/`spent`);
 * the single-representation unification is M1 (`OQ-block-payload-schema`). A block with
 * no payload is fine (renders `body_md` or a placeholder).
 */
// An AEAD ciphertext envelope (ADR 0015 EncryptedEnvelope: ct/nonce/alg). At M1 the
// whole block payload arrives as one of these and the server CANNOT introspect it —
// so the tolerant item-structure check below MUST be skipped (gating it to plaintext-M0
// only, ADR 0038 §6.2; running `arr("items")` on ciphertext would force a decrypt the
// zero-knowledge server can't do).
export function isEncryptedEnvelope(p: unknown): boolean {
  if (typeof p !== "object" || p === null || Array.isArray(p)) return false;
  const o = p as Record<string, unknown>;
  return typeof o.ct === "string" && typeof o.nonce === "string" && typeof o.alg === "string";
}

export function blockPayloadIssues(block: { type?: unknown; payload?: unknown; body_md?: unknown }): CrossIssue[] {
  const { type, payload, body_md } = block;
  if (payload == null) return [];
  // Plaintext-M0 gate: a ciphertext payload is opaque — never structurally validated.
  if (isEncryptedEnvelope(payload)) return [];
  if (typeof payload !== "object" || Array.isArray(payload)) {
    return [{ path: ["payload"], message: "payload must be an object" }];
  }
  if (type === "text" || type === "markdown") return [];
  const p = payload as Record<string, unknown>;
  const has = (...keys: string[]) => keys.some((k) => p[k] != null);
  const arr = (k: string) => Array.isArray(p[k]) && (p[k] as unknown[]).length > 0;
  const hasBody = typeof body_md === "string" && body_md.trim().length > 0;
  const ok =
    type === "checklist" ? arr("items") :
    type === "budget" ? arr("items") || has("total", "spent") :
    type === "document" ? has("ref", "docRef") :
    type === "link" ? has("url") :
    type === "contact" ? has("name") :
    type === "location" ? has("label") :
    type === "milestone" ? has("date", "label") || hasBody :
    true; // unknown type already rejected by the enum
  return ok ? [] : [{ path: ["payload"], message: `block ${String(type)}: payload present but missing its core field` }];
}

const ATTACH_KINDS = new Set(["call", "nav", "link", "open"]);

/**
 * Content-blind structural validation for Hub.timeline (ADR 0045).
 * Validates STRUCTURE only — presence, non-empty array, enum membership.
 * Never reads stop title/sub prose (server stays content-blind per ADR 0015/0017).
 */
export function hubTimelineIssues(hub: { timeline?: unknown }): CrossIssue[] {
  const t = hub.timeline;
  if (t == null) return [];
  if (typeof t !== "object" || Array.isArray(t)) return [{ path: ["timeline"], message: "timeline must be an object" }];
  const tl = t as Record<string, unknown>;
  const issues: CrossIssue[] = [];
  if (typeof tl.tz !== "string" || tl.tz.trim() === "") issues.push({ path: ["timeline", "tz"], message: "timeline.tz (IANA) is required" });
  if (!Array.isArray(tl.stops) || tl.stops.length === 0) { issues.push({ path: ["timeline", "stops"], message: "timeline.stops must be a non-empty array" }); return issues; }
  (tl.stops as unknown[]).forEach((s, i) => {
    if (typeof s !== "object" || s === null || Array.isArray(s)) {
      issues.push({ path: ["timeline", "stops", i], message: "stop must be an object" });
      return;
    }
    const stop = s as Record<string, unknown>;
    if (typeof stop.at !== "string" || stop.at.trim() === "") issues.push({ path: ["timeline", "stops", i, "at"], message: "stop.at is required" });
    if (typeof stop.title !== "string" || stop.title.trim() === "") issues.push({ path: ["timeline", "stops", i, "title"], message: "stop.title is required" });
    if (stop.attachments != null) {
      if (!Array.isArray(stop.attachments)) issues.push({ path: ["timeline", "stops", i, "attachments"], message: "attachments must be an array" });
      else (stop.attachments as unknown[]).forEach((a, j) => {
        if (typeof a !== "object" || a === null || Array.isArray(a)) {
          issues.push({ path: ["timeline", "stops", i, "attachments", j], message: "attachment must be an object" });
          return;
        }
        const k = (a as Record<string, unknown>).kind;
        if (typeof k !== "string" || !ATTACH_KINDS.has(k)) issues.push({ path: ["timeline", "stops", i, "attachments", j, "kind"], message: `attachment.kind must be one of ${[...ATTACH_KINDS].join("|")}` });
      });
    }
  });
  return issues;
}
