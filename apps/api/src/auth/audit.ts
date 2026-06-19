import { q } from "../db.ts";

// Append-only audit for the device-grant + refresh takeover surface.
export async function audit(
  event: string,
  opts: { actorUserId?: string; familyId?: string; detail?: Record<string, unknown> } = {},
): Promise<void> {
  await q(
    `INSERT INTO audit_log(event, actor_user_id, family_id, detail) VALUES ($1,$2,$3,$4)`,
    [event, opts.actorUserId ?? null, opts.familyId ?? null, JSON.stringify(opts.detail ?? {})],
  );
}
