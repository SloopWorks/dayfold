// ADR 0029 — central content scope gate. A credential's authority is resolved PER
// REQUEST from `credential_grants` (never from the token, ADR 0011 §8; never from
// the vestigial `credentials.scopes[]`). Every content route declares the
// (resource, action) it needs; `requireScope` is the single place that decides.
import { q } from "../db.ts";

// `delete` is RESERVED (ADR 0038/0039) for the W4 soft-delete path — no route gates
// on it yet (Slice 5 wires `content:delete` + the author-gate). Adding it here keeps
// the scope vocabulary in one place so the enforcement slice is a pure wire-up.
type Action = "read" | "write" | "delete";

// All grant scope strings for a credential.
export async function resolveGrants(credId: string): Promise<string[]> {
  const r = await q(`SELECT scope FROM credential_grants WHERE credential_id=$1`, [credId]);
  return r.rows.map((x: any) => x.scope as string);
}

// Does the grant set permit `action` on `resource`? A global `content:<action>`
// grant covers any content resource; a resource-qualified grant covers only its
// exact resource. Matched by EXACT string equality against a constructed key —
// never `split(':')`, since hub ids are free text and may contain ':'.
export function scopeAllows(grants: string[], resource: string, action: Action): boolean {
  if (grants.includes(`content:${action}`)) return true;
  if (resource !== "content" && grants.includes(`${resource}:${action}`)) return true;
  return false;
}

// Convenience: resolve + decide. Returns true iff allowed.
export async function requireScope(credId: string, resource: string, action: Action): Promise<boolean> {
  return scopeAllows(await resolveGrants(credId), resource, action);
}

// For a LIST: null = global `content:<action>` (all hubs); otherwise the explicit
// set of hub ids the credential is resource-granted for (possibly empty = no
// authority). Parsed structurally (no split(':')) so hub ids may contain ':'.
export function grantedHubIds(grants: string[], action: Action): string[] | null {
  if (grants.includes(`content:${action}`)) return null;
  const prefix = "hub:", suffix = `:${action}`;
  return grants
    .filter((g) => g.startsWith(prefix) && g.endsWith(suffix) && g.length > prefix.length + suffix.length)
    .map((g) => g.slice(prefix.length, g.length - suffix.length));
}

// Write grant rows at credential-mint time. Accepts an optional pg client so it can
// run inside the device-redeem transaction. Idempotent.
export async function grantScopes(
  credId: string,
  scopes: string[],
  client?: { query: (text: string, params?: unknown[]) => Promise<unknown> },
): Promise<void> {
  const exec = client
    ? (t: string, p: unknown[]) => client.query(t, p)
    : (t: string, p: unknown[]) => q(t, p);
  for (const s of scopes) {
    await exec(`INSERT INTO credential_grants(credential_id, scope) VALUES ($1,$2) ON CONFLICT DO NOTHING`, [credId, s]);
  }
}
