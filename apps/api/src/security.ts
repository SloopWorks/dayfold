// Security-critical pure logic for the content API (M0). Unit-tested.
// Specs: 03-api §Validation (mass-assignment), 04-auth §M0 household token.
import { createHash, timingSafeEqual } from "node:crypto";

/**
 * Constant-time comparison of the presented household token against the
 * configured secret. Hash both to equal-length digests so timingSafeEqual
 * never throws on length mismatch and leaks no length via timing.
 */
export function constantTimeEqual(presented: string, secret: string): boolean {
  const a = createHash("sha256").update(presented, "utf8").digest();
  const b = createHash("sha256").update(secret, "utf8").digest();
  return timingSafeEqual(a, b);
}

/**
 * Mass-assignment allowlist for CONTENT writes (M0). These fields are
 * server-owned and must be IGNORED if present in a client body — `family_id`
 * comes only from the path, `version` is server-bumped (M0), timestamps are
 * DB-managed. (Auth-resource fields like role/scope/used_count are M1.)
 */
export const SERVER_MANAGED_CONTENT_FIELDS = [
  "family_id",
  "version",
  "created_at",
  "updated_at",
  "deleted_at",
  "body_ref",     // M1 object-storage spill key — never client-set at M0
  // "provenance" is deliberately NOT stripped here: stampProvenance() rebuilds it from an
  // allow-list (source, at) and stamps the server-owned credential_id. Stripping it first
  // silently dropped every card byline (source/at) while blocks kept theirs.
  "subject_ref",  // ADR 0064 suppression key — derived server-side from the id/node path.
                  // An author-chosen key would let a write pick a key no rule matches,
                  // i.e. opt itself out of the family's mutes.
] as const;

/** Returns a copy; never mutates input. Top-level strip (documented). */
export function stripServerManaged<T extends Record<string, unknown>>(body: T): T {
  const out: Record<string, unknown> = { ...body };
  for (const k of SERVER_MANAGED_CONTENT_FIELDS) delete out[k];
  return out as T;
}

/**
 * Rebuild provenance SERVER-SIDE: allowlist only `source` + `at` from the client
 * (rejecting arrays / non-plain objects / unknown keys), and stamp the
 * server-owned `credential_id`. A client can never forge credential_id nor
 * inject arbitrary provenance keys.
 */
export function stampProvenance(
  body: Record<string, unknown>,
  credentialId: string,
): Record<string, unknown> {
  const raw = body.provenance;
  const isPlain = raw != null && typeof raw === "object" && !Array.isArray(raw);
  const src = isPlain ? (raw as Record<string, unknown>) : {};
  const provenance: Record<string, unknown> = { credential_id: credentialId };
  if (typeof src.source === "string") provenance.source = src.source;
  if (typeof src.at === "string") provenance.at = src.at;
  return { ...body, provenance };
}
