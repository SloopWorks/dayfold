// The two spike tools, and the low-level MCP server that exposes them.
//
// `Server` (not `McpServer`) is deliberate: the low-level server dispatches
// without validating tool input, so the spike owns its hand-authored strict
// schemas and its closed error codes end to end, and imports no zod. (zod is
// still loaded - the SDK's own `types.js` pulls it in, and its request-schema
// prose is the one library string the runbook discloses. The point is that no
// schema the spike authors is a zod schema.)
// Exactly two tools are registered and only the `tools` capability is declared.

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { CallToolRequestSchema, ErrorCode, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';

import { CODES } from './codes.mjs';
import { MCP_SERVER_NAME, MCP_SERVER_VERSION, SCOPES } from './constants.mjs';
import {
  IDENTITY_STATUS,
  RECEIPT_STATUS,
  SCHEMA_VERSION,
  SPIKE_INSTALL_ID,
  TOOLS,
  TOOL_FINISH,
  TOOL_IDENTITY,
  TOOL_SCOPES,
  validateFinishInput,
  validateIdentityInput,
} from './mcp-schema.mjs';

/**
 * Constant synthetic identity plus the run this credential finishes. Nothing
 * here is read from anywhere: the install id and status are fixed strings.
 */
function identity(ctx, credential, args) {
  const validated = validateIdentityInput(args);
  if (!validated.ok) return { code: validated.code };

  const run = ctx.mcp.runs.mint(credential.credentialId);
  return {
    payload: {
      schemaVersion: SCHEMA_VERSION,
      installId: SPIKE_INSTALL_ID,
      status: IDENTITY_STATUS,
      // The supported scope list, not this credential's grant: constant.
      scopes: [...SCOPES],
      spikeRunId: run.runId,
    },
  };
}

/** Records one run outcome. The receipt is closed enums and bounded counts. */
function finish(ctx, credential, args) {
  const validated = validateFinishInput(args);
  if (!validated.ok) return { code: validated.code };
  const input = validated.value;

  const found = ctx.mcp.runs.find(input.runId, credential.credentialId);
  if (!found.ok) return { code: found.code };

  const receipt = {
    schemaVersion: SCHEMA_VERSION,
    runId: input.runId,
    status: RECEIPT_STATUS,
    result: input.result,
    sourcesRecorded: input.sources.length,
    recordsRecorded: input.sources.reduce((total, row) => total + row.recordsReported, 0),
  };
  const recorded = ctx.mcp.runs.record(
    found.run,
    input.clientRequestId,
    { runId: input.runId, result: input.result, sources: input.sources },
    receipt,
  );
  if (!recorded.ok) return { code: recorded.code };
  return { payload: recorded.receipt };
}

/**
 * Scope first, then arguments. Reporting a schema failure to a caller that was
 * never allowed to submit would tell it its narrowed credential *would* have
 * been accepted - which is the thing the scope refusal exists to withhold.
 *
 * @param {string[]} granted the scopes this call actually holds.
 */
function callTool(ctx, credential, granted, params) {
  const required = TOOL_SCOPES.get(params.name);
  if (required === undefined) return { code: CODES.UNKNOWN_TOOL };
  if (!granted.includes(required)) return { code: CODES.SCOPE_INSUFFICIENT };

  if (params.name === TOOL_IDENTITY) return identity(ctx, credential, params.arguments);
  return finish(ctx, credential, params.arguments);
}

/**
 * A fresh server per request - stateless Streamable HTTP keeps no session, so
 * nothing may outlive the request. `record` receives the closed code of a
 * rejected call so the HTTP layer can log the request's real outcome.
 *
 * @param {object} options
 * @param {object} options.ctx
 * @param {object} options.credential the live credential record for this call.
 * @param {string[]} options.granted scopes held by both the token and the record.
 * @param {(code: string) => void} options.record
 */
export function createToolServer({ ctx, credential, granted, record }) {
  const server = new Server(
    { name: MCP_SERVER_NAME, version: MCP_SERVER_VERSION },
    { capabilities: { tools: {} } },
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: [...TOOLS] }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const outcome = callTool(ctx, credential, granted, request.params);
    if (outcome.code !== undefined) {
      record(outcome.code);
      // A tool error is one closed code and nothing else: no field name, no
      // echoed value, no library message.
      return { isError: true, content: [{ type: 'text', text: outcome.code }] };
    }
    return { content: [{ type: 'text', text: JSON.stringify(outcome.payload) }] };
  });

  // Anything with no registered handler - a client probing `prompts/list` or
  // `resources/list` against a tools-only server - keeps the standard
  // method-not-found code but carries a closed message instead of library
  // prose, and is recorded so the probe shows in the log rather than as `ok`.
  server.fallbackRequestHandler = async () => {
    record(CODES.UNKNOWN_METHOD);
    const error = new Error(CODES.UNKNOWN_METHOD);
    error.code = ErrorCode.MethodNotFound;
    throw error;
  };

  return server;
}
