// Local/dev HTTP server. On Vercel the same `app` is exported as the function
// handler instead; this entrypoint is for local dev + the dogfood round-trip.
//
// SWIP boots first (before `app.ts` is imported — see swip.ts), but is OPTIONAL here:
// no SENTRY_NODE_EU_DSN in the environment ⇒ no error reporting, loudly logged. To run
// it WITH reporting (the error smoke), use the BUNDLED server, not `node src/server.ts`:
// SWIP ships as TypeScript sources and Node refuses to type-strip anything under
// node_modules. See `npm run build:server` + processes/agent-dev-loop.md § API.
import { serve } from "@hono/node-server";
import { initSwip } from "./swip.ts";

await initSwip({ required: false });
const { app } = await import("./app.ts");

const port = Number(process.env.PORT) || 8787;
serve({ fetch: app.fetch, port });
console.log(`api listening on :${port}`);
