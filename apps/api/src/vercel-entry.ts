// Vercel serverless entry (Node runtime — required for `pg`).
// Vercel buffers the request body into `req.body` and consumes the raw stream,
// which makes stream-based adapters (hono/vercel `handle`, getRequestListener)
// hang on any request WITH a body (GET works, PUT/POST hang). So bridge
// manually: take the buffered body (or the stream as fallback), build a Web
// Request, drive Hono's `app.fetch`, and write the Web Response back.
import type { IncomingMessage, ServerResponse } from "node:http";
import { initSwip } from "./swip.ts";

// SWIP (and with it Sentry) boots BEFORE the router is imported — @sentry/node
// instruments modules as they load, and a missing DSN/release must fail the deploy, not
// a request (ADR 0059). `app.ts` is imported dynamically, after, for exactly that reason.
await initSwip({ required: true });
const { app } = await import("./app.ts");

export default async function handler(
  req: IncomingMessage & { body?: unknown },
  res: ServerResponse,
) {
  const method = req.method ?? "GET";

  let body: Buffer | undefined;
  if (method !== "GET" && method !== "HEAD") {
    if (req.body !== undefined && req.body !== null) {
      body = Buffer.from(typeof req.body === "string" ? req.body : JSON.stringify(req.body));
    } else {
      const chunks: Buffer[] = [];
      for await (const c of req) chunks.push(c as Buffer);
      body = chunks.length ? Buffer.concat(chunks) : undefined;
    }
  }

  const headers = new Headers();
  for (const [k, v] of Object.entries(req.headers)) {
    if (Array.isArray(v)) v.forEach((x) => headers.append(k, x));
    else if (v != null) headers.set(k, v);
  }

  const url = `https://${req.headers.host}${req.url ?? "/"}`;
  const response = await app.fetch(new Request(url, { method, headers, body }));

  res.statusCode = response.status;
  response.headers.forEach((v, k) => res.setHeader(k, v));
  res.end(Buffer.from(await response.arrayBuffer()));
}
