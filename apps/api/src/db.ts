// DB access (M0). Plain node-postgres `pg` everywhere — including Vercel, where
// it connects to Neon's TRANSACTION-MODE POOLER endpoint over TCP (ADR 0018:
// pooler mandatory). No serverless-driver swap, so the F3 timestamptz parser
// below stays valid on every path.
import pg from "pg";
import type { PoolClient } from "pg";
import { AsyncLocalStorage } from "node:async_hooks";
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
  // fail fast instead of hanging if the DB is unreachable (serverless).
  connectionTimeoutMillis: 10_000,
});

// A route may pin all of its queries to one connection (for example while holding a
// transaction advisory lock). This matters on Vercel where the pool max is 1: borrowing a
// second connection from inside the lock would deadlock the request.
const requestClient = new AsyncLocalStorage<PoolClient>();

export function currentDbClient(): PoolClient | undefined {
  return requestClient.getStore();
}

export function withDbClient<T>(client: PoolClient, action: () => Promise<T>): Promise<T> {
  return requestClient.run(client, action);
}

export function q(text: string, params?: unknown[]) {
  return (currentDbClient() ?? pool).query(text, params);
}
