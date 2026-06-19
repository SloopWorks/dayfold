// DB access (M0). Local/dev uses node-postgres Pool; on Vercel this swaps to
// the Neon serverless driver (HTTP/WS, no held TCP) per ADR 0018 — same query()
// surface, so callers don't change.
import pg from "pg";
const { Pool } = pg;

export const pool = new Pool({ connectionString: process.env.DATABASE_URL });

export function q(text: string, params?: unknown[]) {
  return pool.query(text, params);
}
