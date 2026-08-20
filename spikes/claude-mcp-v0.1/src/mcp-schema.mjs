// The two tool schemas and the validators that enforce them.
//
// The JSON Schema published in `tools/list` and the runtime check live in one
// file on purpose: the advertised contract and the enforced contract cannot
// drift apart. The low-level MCP server does not validate tool input, so this
// module is the only gate, and every rejection is one closed code that names
// neither the offending field nor its value.

import { CODES } from './codes.mjs';
import { isBoundedString, validateObject } from './validate.mjs';

export const TOOL_IDENTITY = 'dayfold_spike_identity';
export const TOOL_FINISH = 'dayfold_spike_finish';

export const SCHEMA_VERSION = 1;
export const SPIKE_INSTALL_ID = 'inst_spike_constant';
export const IDENTITY_STATUS = 'ready_first_run';
export const RECEIPT_STATUS = 'recorded';

const RESULTS = Object.freeze(['draft', 'no_changes', 'failed']);
const REQUESTED_SOURCES = Object.freeze(['gmail']);
const SOURCE_OUTCOMES = Object.freeze([
  'reported_observed',
  'reported_zero_results',
  'reported_unavailable',
]);
/** The one outcome that may carry a non-zero count. */
const OBSERVED_OUTCOME = 'reported_observed';
const MAX_RUN_ID_LENGTH = 128;

export const MAX_RECORDS_REPORTED = 100;
export const MAX_CLIENT_REQUEST_ID_LENGTH = 64;

const CLIENT_REQUEST_ID = /^[A-Za-z0-9_-]+$/;
const INVALID = Object.freeze({ ok: false, code: CODES.SCHEMA_INVALID });
const SCHEMA_VERSION_PROPERTY = Object.freeze({ type: 'integer', const: SCHEMA_VERSION });

const IDENTITY_INPUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['schemaVersion'],
  properties: { schemaVersion: SCHEMA_VERSION_PROPERTY },
};

const FINISH_INPUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['schemaVersion', 'runId', 'result', 'sources', 'clientRequestId'],
  properties: {
    schemaVersion: SCHEMA_VERSION_PROPERTY,
    runId: { type: 'string', minLength: 1, maxLength: MAX_RUN_ID_LENGTH },
    result: { type: 'string', enum: [...RESULTS] },
    sources: {
      type: 'array',
      minItems: REQUESTED_SOURCES.length,
      maxItems: REQUESTED_SOURCES.length,
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['source', 'outcome', 'recordsReported'],
        properties: {
          source: { type: 'string', enum: [...REQUESTED_SOURCES] },
          outcome: { type: 'string', enum: [...SOURCE_OUTCOMES] },
          recordsReported: { type: 'integer', minimum: 0, maximum: MAX_RECORDS_REPORTED },
        },
      },
    },
    clientRequestId: {
      type: 'string',
      minLength: 1,
      maxLength: MAX_CLIENT_REQUEST_ID_LENGTH,
      pattern: CLIENT_REQUEST_ID.source,
    },
  },
};

export const TOOLS = Object.freeze([
  {
    name: TOOL_IDENTITY,
    description:
      'Returns the synthetic install identity of this compatibility spike and the run id to finish. Reads no mailbox, calendar or household data.',
    inputSchema: IDENTITY_INPUT_SCHEMA,
  },
  {
    name: TOOL_FINISH,
    description:
      'Records the outcome of one synthetic spike run. Stores closed enum values and bounded counts only; carries no message, subject or body text.',
    inputSchema: FINISH_INPUT_SCHEMA,
  },
]);

function hasSchemaVersion(input) {
  return input.schemaVersion === SCHEMA_VERSION;
}

export function validateIdentityInput(input) {
  const validated = validateObject(input, { required: ['schemaVersion'] });
  if (!validated.ok) return INVALID;
  if (!hasSchemaVersion(input)) return INVALID;
  return { ok: true, value: { schemaVersion: SCHEMA_VERSION } };
}

function validateSourceRow(row, seen) {
  const validated = validateObject(row, { required: ['source', 'outcome', 'recordsReported'] });
  if (!validated.ok) return null;
  if (!REQUESTED_SOURCES.includes(row.source) || seen.has(row.source)) return null;
  if (!SOURCE_OUTCOMES.includes(row.outcome)) return null;

  const count = row.recordsReported;
  if (!Number.isInteger(count) || count < 0 || count > MAX_RECORDS_REPORTED) return null;
  if (row.outcome !== OBSERVED_OUTCOME && count !== 0) return null;

  seen.add(row.source);
  return { source: row.source, outcome: row.outcome, recordsReported: count };
}

export function validateFinishInput(input) {
  const validated = validateObject(input, { required: FINISH_INPUT_SCHEMA.required });
  if (!validated.ok) return INVALID;
  if (!hasSchemaVersion(input)) return INVALID;
  if (!isBoundedString(input.runId, MAX_RUN_ID_LENGTH)) return INVALID;
  if (!RESULTS.includes(input.result)) return INVALID;
  if (!isBoundedString(input.clientRequestId, MAX_CLIENT_REQUEST_ID_LENGTH)) return INVALID;
  if (!CLIENT_REQUEST_ID.test(input.clientRequestId)) return INVALID;
  if (!Array.isArray(input.sources) || input.sources.length !== REQUESTED_SOURCES.length) return INVALID;

  const seen = new Set();
  const sources = [];
  for (const row of input.sources) {
    const source = validateSourceRow(row, seen);
    if (!source) return INVALID;
    sources.push(source);
  }

  return {
    ok: true,
    value: {
      schemaVersion: SCHEMA_VERSION,
      runId: input.runId,
      result: input.result,
      sources,
      clientRequestId: input.clientRequestId,
    },
  };
}
