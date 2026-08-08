import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
export const EXAMPLES = path.join(ROOT, "specs/domain-model/examples/routines");

const SCHEMA_FILES = {
  manifest: "routine-manifest.schema.json",
  changeset: "routine-changeset.schema.json",
  finish: "routine-run-finish.schema.json",
};
const EMPTY_SOURCE_OUTCOMES = new Set(["zero_results", "reauth_required", "admin_blocked", "unavailable"]);
const OBSERVED_SOURCE_OUTCOMES = new Set(["observed", "partial"]);
const HEALTHY_SOURCE_OUTCOMES = new Set(["observed", "zero_results"]);

const readJson = (file) => JSON.parse(fs.readFileSync(file, "utf8"));

/** Compile the producer contracts with strict Draft 2020-12 semantics. */
export function compileContracts() {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  const validators = {};
  for (const [kind, name] of Object.entries(SCHEMA_FILES)) {
    const file = path.join(ROOT, "specs/domain-model/schemas", name);
    const schema = readJson(file);
    if (!ajv.validateSchema(schema)) {
      throw new Error(`${name}: schema is invalid`);
    }
    validators[kind] = ajv.compile(schema);
  }
  return validators;
}

function structuralErrors(kind, value, validate) {
  if (validate(value)) return [];
  return (validate.errors ?? []).map((error) => ({
    path: `/${kind}${error.instancePath}`,
    code: `schema.${error.keyword}`,
  }));
}

/**
 * Policy checks which JSON Schema cannot express across manifest, changeset, and
 * finish documents. Errors deliberately include only a path and stable code.
 */
export function scenarioErrors(scenario, validators = compileContracts()) {
  const errors = [];
  const allowedScenarioKeys = new Set(["manifest", "changeset", "finish", "current", "expectedDiff"]);
  if (scenario == null || typeof scenario !== "object" || Array.isArray(scenario)) {
    return [{ path: "/", code: "fixture.object_required" }];
  }
  for (const key of Object.keys(scenario)) {
    if (!allowedScenarioKeys.has(key)) errors.push({ path: "/", code: "fixture.unknown_field" });
  }
  if (!("manifest" in scenario)) errors.push({ path: "/manifest", code: "fixture.required" });
  if (!("changeset" in scenario)) errors.push({ path: "/changeset", code: "fixture.required" });
  if (scenario.current !== undefined) {
    const current = scenario.current;
    if (current == null || typeof current !== "object" || Array.isArray(current)) {
      errors.push({ path: "/current", code: "fixture.object_required" });
    } else {
      for (const key of Object.keys(current)) {
        if (key !== "cards") errors.push({ path: "/current", code: "fixture.unknown_field" });
      }
      if (!Array.isArray(current.cards)) errors.push({ path: "/current/cards", code: "fixture.array_required" });
    }
  }
  if (scenario.expectedDiff !== undefined) {
    const expected = scenario.expectedDiff;
    const expectedKeys = new Set(["create", "no_change", "conflict"]);
    if (expected == null || typeof expected !== "object" || Array.isArray(expected)) {
      errors.push({ path: "/expectedDiff", code: "fixture.object_required" });
    } else {
      for (const key of Object.keys(expected)) {
        if (!expectedKeys.has(key)) errors.push({ path: "/expectedDiff", code: "fixture.unknown_field" });
      }
      for (const key of expectedKeys) {
        if (!Number.isInteger(expected[key]) || expected[key] < 0 || expected[key] > 25) {
          errors.push({ path: `/expectedDiff/${key}`, code: "fixture.count" });
        }
      }
    }
  }
  if (errors.length) return errors;

  errors.push(...structuralErrors("manifest", scenario.manifest, validators.manifest));
  errors.push(...structuralErrors("changeset", scenario.changeset, validators.changeset));
  if (scenario.finish !== undefined) {
    errors.push(...structuralErrors("finish", scenario.finish, validators.finish));
  }
  if (errors.length) return errors;

  const manifest = scenario.manifest;
  const changeset = scenario.changeset;
  const finish = scenario.finish;
  const selectedHub = manifest.selectedTargetHubIds[0];

  if (manifest.familyId !== changeset.familyId) {
    errors.push({ path: "/changeset/familyId", code: "policy.family_mismatch" });
  }
  if (manifest.routineId !== changeset.routineId) {
    errors.push({ path: "/changeset/routineId", code: "policy.routine_mismatch" });
  }
  if (changeset.operations.length > manifest.maxOperations) {
    errors.push({ path: "/changeset/operations", code: "policy.operation_cap" });
  }
  if (scenario.expectedDiff !== undefined) {
    const expected = scenario.expectedDiff;
    if (expected.create + expected.no_change + expected.conflict !== changeset.operations.length) {
      errors.push({ path: "/expectedDiff", code: "fixture.diff_count" });
    }
  }

  const opIds = new Set();
  const resourceIds = new Set();
  changeset.operations.forEach((operation, index) => {
    const base = `/changeset/operations/${index}`;
    if (operation.targetHubId !== selectedHub) {
      errors.push({ path: `${base}/targetHubId`, code: "policy.target_hub" });
    }
    if (opIds.has(operation.opId)) {
      errors.push({ path: `${base}/opId`, code: "policy.duplicate_op_id" });
    }
    opIds.add(operation.opId);
    const resourceKey = `briefing_card:${operation.card.id}`;
    if (resourceIds.has(resourceKey)) {
      errors.push({ path: `${base}/card/id`, code: "policy.duplicate_resource" });
    }
    resourceIds.add(resourceKey);
  });

  if (finish !== undefined) {
    if (finish.familyId !== manifest.familyId) {
      errors.push({ path: "/finish/familyId", code: "policy.family_mismatch" });
    }
    if (finish.routineId !== manifest.routineId) {
      errors.push({ path: "/finish/routineId", code: "policy.routine_mismatch" });
    }
    const requestedSources = new Set(manifest.requestedSources);
    const finishedSources = new Set();
    finish.sourceOutcomes.forEach((sourceOutcome, index) => {
      const base = `/finish/sourceOutcomes/${index}`;
      if (!requestedSources.has(sourceOutcome.source)) {
        errors.push({ path: `${base}/source`, code: "policy.unexpected_source" });
      }
      if (finishedSources.has(sourceOutcome.source)) {
        errors.push({ path: `${base}/source`, code: "policy.duplicate_source" });
      }
      finishedSources.add(sourceOutcome.source);
      if (EMPTY_SOURCE_OUTCOMES.has(sourceOutcome.outcome) && sourceOutcome.recordsObserved !== 0) {
        errors.push({ path: `${base}/recordsObserved`, code: "policy.source_outcome_count" });
      }
      if (OBSERVED_SOURCE_OUTCOMES.has(sourceOutcome.outcome) && sourceOutcome.recordsObserved < 1) {
        errors.push({ path: `${base}/recordsObserved`, code: "policy.source_outcome_count" });
      }
    });
    for (const source of requestedSources) {
      if (!finishedSources.has(source)) {
        errors.push({ path: "/finish/sourceOutcomes", code: "policy.missing_source" });
      }
    }
    const counts = finish.counts;
    const classified = counts.creates + counts.noChanges + counts.conflicts + counts.rejected;
    if (counts.operationsProposed !== changeset.operations.length) {
      errors.push({ path: "/finish/counts/operationsProposed", code: "policy.proposed_count" });
    }
    if (classified !== counts.operationsProposed) {
      errors.push({ path: "/finish/counts", code: "policy.count_sum" });
    }
    const healthySources = finish.sourceOutcomes.every(({ outcome }) => HEALTHY_SOURCE_OUTCOMES.has(outcome));
    const degradedSources = finish.sourceOutcomes.some(({ outcome }) => !HEALTHY_SOURCE_OUTCOMES.has(outcome));
    const hasRecovery = finish.recovery !== undefined;
    const resultCoherent = (() => {
      switch (finish.result) {
        case "success":
          return healthySources && !hasRecovery && counts.creates > 0 && counts.conflicts === 0 && counts.rejected === 0;
        case "no_changes":
          return healthySources && !hasRecovery && counts.creates === 0 && counts.conflicts === 0 && counts.rejected === 0;
        case "partial":
          return hasRecovery && (degradedSources || counts.conflicts > 0 || counts.rejected > 0);
        case "rejected":
          return hasRecovery && counts.operationsProposed > 0 && counts.creates === 0 && counts.noChanges === 0 &&
            counts.conflicts === 0 && counts.rejected === counts.operationsProposed;
        case "failed":
          return hasRecovery && counts.operationsProposed === 0 && counts.creates === 0 && counts.noChanges === 0 &&
            counts.conflicts === 0 && counts.rejected === 0;
        default:
          return false;
      }
    })();
    if (!resultCoherent) {
      errors.push({ path: "/finish/result", code: "policy.result_coherence" });
    }
  }

  return errors;
}

export function fixtureFiles(expected) {
  const dir = path.join(EXAMPLES, expected);
  return fs.readdirSync(dir)
    .filter((name) => name.endsWith(".json"))
    .sort()
    .map((name) => path.join(dir, name));
}

export { readJson };
