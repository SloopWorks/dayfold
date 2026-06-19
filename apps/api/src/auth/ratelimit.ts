import { q } from "../db.ts";

// Trusted client IP (Vercel). Never the left-most x-forwarded-for hop (client-set).
export function clientIp(c: any): string {
  return (
    c.req.header("x-vercel-forwarded-for") ||
    c.req.header("x-forwarded-for")?.split(",").pop()?.trim() ||
    "unknown"
  );
}

const winSql = `to_timestamp(floor(extract(epoch from now())/$2)*$2)`;

// Atomic fixed-window counter. Returns the post-increment count + whether <= cap.
export async function hit(key: string, windowSecs: number, cap: number): Promise<{ ok: boolean; count: number }> {
  const r = await q(
    `INSERT INTO rate_limits(key, window_start, count) VALUES ($1, ${winSql}, 1)
     ON CONFLICT (key, window_start) DO UPDATE SET count = rate_limits.count + 1
     RETURNING count`,
    [key, windowSecs],
  );
  const count = r.rows[0].count as number;
  return { ok: count <= cap, count };
}

export async function isLocked(key: string): Promise<boolean> {
  const r = await q(`SELECT 1 FROM rate_limits WHERE key=$1 AND locked_until > now() LIMIT 1`, [key]);
  return (r.rowCount ?? 0) > 0;
}

export async function recordFailure(key: string, windowSecs: number, threshold: number, lockSecs: number): Promise<void> {
  const r = await q(
    `INSERT INTO rate_limits(key, window_start, count) VALUES ($1, ${winSql}, 1)
     ON CONFLICT (key, window_start) DO UPDATE SET count = rate_limits.count + 1
     RETURNING count`,
    [key, windowSecs],
  );
  if ((r.rows[0].count as number) >= threshold) {
    await q(
      `UPDATE rate_limits SET locked_until = now() + ($2 || ' seconds')::interval
       WHERE key=$1 AND window_start = ${winSql.replace("$2", "$3")}`,
      [key, String(lockSecs), windowSecs],
    );
  }
}

export async function resetFailures(key: string): Promise<void> {
  await q(`DELETE FROM rate_limits WHERE key=$1`, [key]);
}
