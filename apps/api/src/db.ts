// DB access (M0). Plain node-postgres `pg` everywhere — including Vercel, where
// it connects to Neon's TRANSACTION-MODE POOLER endpoint over TCP (ADR 0018:
// pooler mandatory). No serverless-driver swap, so the F3 timestamptz parser
// below stays valid on every path.
import pg from "pg";
const { Pool, types } = pg;

// [F3] Return timestamptz/timestamp as the EXACT Postgres string (no JS Date
// round-trip) so the sync keyset cursor preserves microsecond precision —
// truncating to JS ms can silently skip a row sharing a millisecond.
types.setTypeParser(1184, (s: string) => s); // timestamptz
types.setTypeParser(1114, (s: string) => s); // timestamp

// On Vercel each function instance keeps ≤1 connection (the pooler multiplexes);
// locally/CI: default sizing.
export const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: process.env.VERCEL ? 1 : 10,
});

export function q(text: string, params?: unknown[]) {
  return pool.query(text, params);
}
