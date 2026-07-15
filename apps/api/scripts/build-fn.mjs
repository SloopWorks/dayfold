// The Vercel function bundle (and, with `--server`, a bundled local server).
//
// This replaced a one-line `esbuild --packages=external` because of ONE constraint:
// the SWIP packages (@sloopworks/*) publish TYPESCRIPT SOURCES — `"main": "src/index.ts"`
// — and Node refuses to type-strip anything under node_modules
// (ERR_UNSUPPORTED_NODE_MODULES_TYPE_STRIPPING). Left external, the function would fail
// to load at runtime, in production, on the first request. So SWIP is BUNDLED and
// everything else stays external.
//
// `@sentry/node` in particular MUST stay external: it is installed from the npm registry
// on Vercel, and its OpenTelemetry instrumentation patches modules at load time — bundling
// it is unsupported by Sentry and would defeat that.
//
// esbuild has no "external except X" flag (`--packages=external` is all-or-nothing), hence
// the plugin. Output stays deterministic: CI rebuilds this and diffs it byte-for-byte
// against the committed apps/api/api/index.js.
import * as esbuild from "esbuild";

/** Everything bare is external EXCEPT @sloopworks/* (TS sources — see the header). */
const externalExceptSwip = {
  name: "external-except-swip",
  setup(build) {
    // Bare specifiers only (a package or a `node:` builtin) — never "./x" or "/x".
    build.onResolve({ filter: /^[^./]/ }, (args) => {
      if (args.path.startsWith("@sloopworks/")) return null; // bundle it
      return { path: args.path, external: true };
    });
  },
};

const server = process.argv.includes("--server");

await esbuild.build({
  entryPoints: [server ? "src/server.ts" : "src/vercel-entry.ts"],
  outfile: server ? "dist/server.js" : "api/index.js",
  bundle: true,
  platform: "node",
  format: "esm",
  plugins: [externalExceptSwip],
});
