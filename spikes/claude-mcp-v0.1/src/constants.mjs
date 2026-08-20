// Fixed values for the spike. Nothing here is a production constant: the
// audience is deliberately distinct from the production `dayfold-mcp` so a
// spike token can never be mistaken for a real one.

export const AUDIENCE = 'dayfold-mcp-spike';
export const SUBJECT = 'user_spike_local';

// The two scopes are named individually because the MCP surface enforces them
// individually: `mcp:context.read` reaches `/mcp` and reads, `mcp:draft.submit`
// writes. A credential narrowed to the read scope can still connect and still
// call the read tool - it just cannot submit.
export const SCOPE_CONTEXT_READ = 'mcp:context.read';
export const SCOPE_DRAFT_SUBMIT = 'mcp:draft.submit';

export const SCOPES = Object.freeze([SCOPE_CONTEXT_READ, SCOPE_DRAFT_SUBMIT]);

export const STATIC_CLIENT_ID = 'client_spike_static';
export const STATIC_CLIENT_NAME = 'Spike Synthetic Connector';

export const DEFAULT_REDIRECT_URI = 'https://example.invalid/spike-callback';
export const DEFAULT_HOST = '127.0.0.1';
export const DEFAULT_PORT = 8787;

export const ACCESS_TOKEN_TTL_MS = 5 * 60 * 1000;
export const AUTH_CODE_TTL_MS = 10 * 60 * 1000;
export const APPROVAL_TTL_MS = 10 * 60 * 1000;
export const DCR_CLIENT_TTL_MS = 10 * 60 * 1000;

export const OAUTH_BODY_LIMIT_BYTES = 16 * 1024;
export const MAX_PARAM_LENGTH = 2048;
export const MAX_CLIENT_NAME_LENGTH = 256;
export const MAX_CLIENT_NAME_DISPLAY_LENGTH = 64;

// The MCP surface. The caps are the ones the spike ships with; the factory
// takes the deadline as an option so a test can drive it without waiting.
export const MCP_SERVER_NAME = 'dayfold-spike-claude-mcp';
export const MCP_SERVER_VERSION = '0.1.0';
/** The scope a credential must hold to reach `/mcp` at all. Per-tool scopes are
 * enforced separately, in `src/mcp-schema.mjs` and `src/mcp-tools.mjs`. */
export const MCP_REQUIRED_SCOPE = SCOPE_CONTEXT_READ;
export const MCP_BODY_LIMIT_BYTES = 64 * 1024;
export const MCP_DEADLINE_MS = 10 * 1000;
export const MCP_MAX_CONCURRENT_PER_CREDENTIAL = 4;
