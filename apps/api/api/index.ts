// Vercel serverless entry — wraps the same Hono `app` used locally/in tests.
// vercel.json rewrites all paths here so the app sees the original URL.
// DEPLOY-PATH: verified on first deploy (INB-12); the app itself is fully
// tested locally + in CI.
import { handle } from "hono/vercel";
import { app } from "../src/app.ts";

export default handle(app);
