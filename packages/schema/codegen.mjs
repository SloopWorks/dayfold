// Codegen: single-source content schema → TS (zod) types.
// Reads specs/domain-model/schemas/content.schema.json ($defs-based) and emits
// a zod module with one schema per $def (sibling $refs resolved by passing the
// full $defs context per call). Kotlin codegen (quicktype) is added next iter.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { jsonSchemaToZod } from "json-schema-to-zod";

const here = dirname(fileURLToPath(import.meta.url));
const schemaPath = resolve(here, "../../specs/domain-model/schemas/content.schema.json");
const schema = JSON.parse(readFileSync(schemaPath, "utf8"));
const defs = schema.$defs;

// Emit in dependency-friendly order; $refs are inlined by the tool.
const order = [
  "Provenance", "Trigger", "Action",
  "LinkPayload", "ChecklistPayload", "DocumentPayload", "MilestonePayload",
  "ContactPayload", "LocationPayload", "BudgetPayload",
  "Block", "Section", "Timeline", "Hub", "BriefingCard", "Place", "SyncResponse",
];

let out = `// GENERATED from specs/domain-model/schemas/content.schema.json — DO NOT EDIT.\n`;
out += `// Regenerate: npm run codegen (root). Source of truth = the JSON schema (ADR 0006).\n`;
out += `import { z } from "zod";\n\n`;

for (const name of order) {
  if (!defs[name]) throw new Error(`missing $def: ${name}`);
  // Pass the def plus full $defs so internal $refs (#/$defs/ulid, etc.) resolve.
  const sub = { ...defs[name], $defs: defs };
  // Block.payload is a oneOf of $refs. json-schema-to-zod does NOT resolve nested
  // $refs inside a oneOf and emits a 7×z.any() superRefine requiring "exactly one to
  // pass" — but every z.any() passes, so ALL structured block payloads are rejected
  // ("Should pass single schema. Passed 7"). The server validates block payloads
  // structurally in content-validation.ts (blockPayloadIssues, ADR 0035), so the TS
  // type is an intentional z.any() stub. Strip the oneOf for the TS emit ONLY; the
  // Kotlin/quicktype pass below keeps the full schema (real per-type union). A new
  // `properties` object is created so the shared `defs` is never mutated.
  if (name === "Block" && sub.properties?.payload?.oneOf) {
    sub.properties = { ...sub.properties, payload: { description: sub.properties.payload.description } };
  }
  const code = jsonSchemaToZod(sub, { name: `${name}Schema`, module: false, type: false });
  out += `export ${code}\n`;
  out += `export type ${name} = z.infer<typeof ${name}Schema>;\n\n`;
}

const tsDir = resolve(here, "../../apps/api/src/generated");
mkdirSync(tsDir, { recursive: true });
const tsPath = resolve(tsDir, "content.ts");
writeFileSync(tsPath, out);
console.log(`TS  codegen OK → ${tsPath} (${out.length} bytes, ${order.length} types)`);

// --- Kotlin (quicktype) ---------------------------------------------------
// quicktype needs a root type; our schema is $defs-only, so build a wrapper
// root that references every top-level entity, then generate kotlinx classes.
import { execSync } from "node:child_process";
const topLevel = ["Hub", "Section", "Block", "BriefingCard", "Place", "SyncResponse"];
const wrapper = {
  $schema: schema.$schema,
  type: "object",
  properties: Object.fromEntries(topLevel.map((n) => [n, { $ref: `#/$defs/${n}` }])),
  $defs: defs,
};
const wrapPath = resolve(here, ".wrapper.schema.json");
writeFileSync(wrapPath, JSON.stringify(wrapper));
const ktDir = resolve(here, "../../packages/schema/kotlin-gen");
mkdirSync(ktDir, { recursive: true });
const ktPath = resolve(ktDir, "Content.kt");
try {
  execSync(
    `npx --yes quicktype -s schema --lang kotlin --framework kotlinx --package com.sloopworks.dayfold.schema -o "${ktPath}" "${wrapPath}"`,
    { stdio: "inherit", cwd: here }
  );
  console.log(`KT  codegen OK → ${ktPath}`);
} finally {
  try { execSync(`rm -f "${wrapPath}"`); } catch {}
}
