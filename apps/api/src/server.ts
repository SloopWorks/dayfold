// Local/dev HTTP server. On Vercel the same `app` is exported as the function
// handler instead; this entrypoint is for local dev + the dogfood round-trip.
import { serve } from "@hono/node-server";
import { app } from "./app.ts";

const port = Number(process.env.PORT) || 8787;
serve({ fetch: app.fetch, port });
console.log(`api listening on :${port}`);
