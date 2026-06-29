// ADR 0038/0039 — the member-write safety gate. Shared decisions for every content
// write path so the loop/CLI authoring path and the member toggle path stay aligned:
//   - member vs loop/CLI classification (drives the 410-on-tombstone rule)
//   - If-Match optimistic concurrency (mismatch → 412)
//   - visibility-on-write: an invisible-or-absent hub is a uniform 404 (no existence
//     oracle); 403 is reserved for a VISIBLE hub whose scope denies the write.
import { q } from "../db.ts";
import * as hubs from "./hubs.ts";
import { requireScope } from "../auth/scope.ts";

export type Caller = { userId: string | null; legacy: boolean; cred: { id: string; kind?: string } };

// A member write = a non-legacy APP credential acting as a family member. The loop/CLI
// authoring path is the legacy household token OR a `cli`-kind credential — it keeps
// re-create-by-PUT (resurrects a tombstoned id on purpose). Only member writes refuse a
// tombstoned target with 410 (ADR 0038 §6.3 — closes the zombie-content hole).
export function isMemberWrite(a: { legacy: boolean; cred: { kind?: string } }): boolean {
  return !a.legacy && a.cred?.kind === "app";
}

// W4 author-gate (ADR 0038 §W4 / 0030 §7): may this caller delete a block authored by
// `createdBy`? A member may delete ONLY their own authored block; loop/CLI writes (the
// authoring path) may delete anything. Critically — a non-legacy NULL-user credential
// authors nothing, so it must NEVER pass: comparing `createdBy !== userId` alone lets a
// null user match a loop-authored (createdBy = NULL) block (`null !== null` is false), the
// same round-1 P0-2 "NULL → god-mode" hole the visibility checks guard. Guard it here too.
export function memberDeleteForbidden(
  a: { legacy: boolean; userId: string | null; cred: { kind?: string } },
  createdBy: string | null,
): boolean {
  if (!isMemberWrite(a)) return false;                  // loop/CLI may delete any
  return a.userId == null || createdBy !== a.userId;    // null-user → forbidden; else own-only
}

// If-Match optimistic concurrency (ADR 0038 §6.2). Returns true (→ caller should 412)
// iff the header is present AND the caller's base version != the live row version.
// Absent/empty header → no precondition (back-compat: the loop/CLI never sends it).
// Accepts a bare integer or a (weak) quoted ETag form.
export function ifMatchFails(header: string | undefined | null, liveVersion: number | null): boolean {
  if (header == null || header.trim() === "") return false;
  const want = header.replace(/^W\//, "").replace(/^"|"$/g, "").trim();
  return String(liveVersion ?? "") !== want;
}

export type BlockState = { exists: boolean; deleted: boolean; version: number | null };

// A block's lifecycle state in ANY state (incl. tombstoned) — for the 410 + 412 gates.
export async function blockState(familyId: string, id: string): Promise<BlockState> {
  const r = await q(`SELECT version, deleted_at FROM blocks WHERE family_id=$1 AND id=$2`, [familyId, id]);
  if (r.rowCount === 0) return { exists: false, deleted: false, version: null };
  const row = r.rows[0];
  return { exists: true, deleted: row.deleted_at != null, version: Number(row.version) };
}

// Four distinct write-gate outcomes for a hub-parented write:
//   "absent"    — the hub row is gone/never-existed → the caller maps this to its
//                 parent-gone code (409 give-up, ADR 0038 §6.2 — distinct from 412/410).
//   "invisible" — the hub is LIVE but restricted & the caller isn't permitted → 404
//                 (no existence oracle; ADR 0038 visibility-on-write refines ADR 0030's
//                 write-path response from 403→404 for the can't-see case).
//   "denied"    — the hub is VISIBLE but the credential's scope denies the write → 403.
//   "ok"        — visible + scoped.
export type HubGate = "ok" | "absent" | "invisible" | "denied";

export async function hubWriteGate(familyId: string, hubId: string, caller: Caller): Promise<HubGate> {
  const hub = await hubs.getHub(familyId, hubId);
  if (!hub) return "absent";
  const allow = await hubs.allowListFor(familyId, hubId);
  const visible = hubs.hubVisible(
    hub,
    { userId: caller.userId, legacy: caller.legacy },
    () => !!caller.userId && allow.has(caller.userId),
  );
  if (!visible) return "invisible";
  if (!(await requireScope(caller.cred.id, `hub:${hubId}`, "write"))) return "denied";
  return "ok";
}
