// The closed error-code set. Every error this spike can return is a constant
// here: a bounded Dayfold-owned string, never a library or provider message and
// never an echo of caller input.
//
// Task 4 extends this module; it does not replace it.

export const CODES = Object.freeze({
  // Transport / routing
  NOT_FOUND: 'spike.not_found',
  METHOD_NOT_ALLOWED: 'spike.method_not_allowed',
  TOO_LARGE: 'spike.too_large',
  SCHEMA_INVALID: 'spike.schema_invalid',
  INTERNAL: 'spike.internal',

  // Authorization request
  UNKNOWN_CLIENT: 'spike.unknown_client',
  REDIRECT_MISMATCH: 'spike.redirect_mismatch',
  RESOURCE_MISMATCH: 'spike.resource_mismatch',
  UNSUPPORTED_RESPONSE_TYPE: 'spike.unsupported_response_type',
  PKCE_REQUIRED: 'spike.pkce_required',
  UNSUPPORTED_PKCE_METHOD: 'spike.unsupported_pkce_method',
  SCOPE_INVALID: 'spike.scope_invalid',
  APPROVAL_INVALID: 'spike.approval_invalid',

  // Token exchange
  UNSUPPORTED_GRANT_TYPE: 'spike.unsupported_grant_type',
  INVALID_GRANT: 'spike.invalid_grant',
  CODE_EXPIRED: 'spike.code_expired',
  CODE_ALREADY_USED: 'spike.code_already_used',
  PKCE_VERIFIER_MISMATCH: 'spike.pkce_verifier_mismatch',

  // MCP surface
  NOT_ACCEPTABLE: 'spike.not_acceptable',
  UNSUPPORTED_MEDIA_TYPE: 'spike.unsupported_media_type',
  TOO_MANY_REQUESTS: 'spike.too_many_requests',
  DEADLINE_EXCEEDED: 'spike.deadline_exceeded',
  UNKNOWN_TOOL: 'spike.unknown_tool',

  // Spike runs
  RUN_UNKNOWN: 'spike.run_unknown',
  RUN_CLOSED: 'spike.run_closed',
  REPLAY_MISMATCH: 'spike.replay_mismatch',

  // Bearer / registration / startup
  UNAUTHORIZED: 'spike.unauthorized',
  DCR_DISABLED: 'spike.dcr_disabled',
  ENV_CONTAMINATED: 'spike.env_contaminated',
});

export const ALL_CODES = Object.freeze(new Set(Object.values(CODES)));
