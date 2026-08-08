import path from "node:path";
import { compileContracts, EXAMPLES, fixtureFiles, readJson, scenarioErrors } from "./index.mjs";

const validators = compileContracts();
let checked = 0;

for (const file of fixtureFiles("valid")) {
  const errors = scenarioErrors(readJson(file), validators);
  if (errors.length) {
    throw new Error(`${path.basename(file)}: expected valid; got ${JSON.stringify(errors)}`);
  }
  checked += 1;
}

for (const file of fixtureFiles("invalid")) {
  const errors = scenarioErrors(readJson(file), validators);
  if (!errors.length) {
    throw new Error(`${path.basename(file)}: expected at least one validation error`);
  }
  checked += 1;
}

const expectedFinishCodes = new Map([
  ["finish-family-mismatch.json", "policy.family_mismatch"],
  ["finish-missing-source.json", "policy.missing_source"],
  ["finish-observed-zero.json", "schema.minimum"],
  ["finish-unavailable-records.json", "schema.const"],
  ["finish-success-conflict.json", "schema.const"],
  ["finish-partial-without-recovery.json", "schema.required"],
  ["finish-partial-without-degradation.json", "policy.result_coherence"],
  ["finish-rejected-without-rejections.json", "schema.minimum"],
  ["finish-failed-with-proposal.json", "schema.const"],
]);
for (const [name, code] of expectedFinishCodes) {
  const errors = scenarioErrors(readJson(path.join(EXAMPLES, "invalid", name)), validators);
  if (!errors.some((error) => error.code === code)) {
    throw new Error(`${name}: expected ${code}; got ${JSON.stringify(errors)}`);
  }
}

if (checked < 12) throw new Error(`fixture corpus unexpectedly small: ${checked}`);
console.log(`routine-schema: ${checked} sanitized scenarios passed`);
