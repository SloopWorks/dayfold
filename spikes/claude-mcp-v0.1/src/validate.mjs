// Strict request validation. Every validator is closed: unknown fields,
// repeated fields, missing required fields and oversized values are all
// rejected, and the rejection never names the offending field.

import { CODES } from './codes.mjs';
import { MAX_PARAM_LENGTH } from './constants.mjs';

const INVALID = Object.freeze({ ok: false, code: CODES.SCHEMA_INVALID });

/**
 * @param {URLSearchParams} params
 * @param {{required?: string[], optional?: string[], limits?: Record<string, number>}} spec
 */
export function validateParams(params, spec) {
  const required = spec.required ?? [];
  const optional = spec.optional ?? [];
  const allowed = new Set([...required, ...optional]);
  const limits = spec.limits ?? {};

  const seen = new Set();
  for (const key of params.keys()) {
    if (!allowed.has(key)) return INVALID;
    if (seen.has(key)) return INVALID;
    seen.add(key);
  }

  const value = {};
  for (const key of allowed) {
    const raw = params.get(key);
    if (raw === null) {
      if (required.includes(key)) return INVALID;
      continue;
    }
    if (raw.length === 0) return INVALID;
    if (raw.length > (limits[key] ?? MAX_PARAM_LENGTH)) return INVALID;
    value[key] = raw;
  }
  return { ok: true, value };
}

/**
 * The JSON-body equivalent, used by dynamic client registration.
 * `additionalProperties: false` is the whole point: rejecting a field a real
 * connector client sends is evidence, not a bug.
 *
 * @param {unknown} body
 * @param {{required?: string[], optional?: string[]}} spec
 */
export function validateObject(body, spec) {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) return INVALID;
  const required = spec.required ?? [];
  const allowed = new Set([...required, ...(spec.optional ?? [])]);

  for (const key of Object.keys(body)) {
    if (!allowed.has(key)) return INVALID;
  }
  for (const key of required) {
    if (!(key in body)) return INVALID;
  }
  return { ok: true, value: body };
}

export function isBoundedString(value, maxLength) {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength;
}

/** Space-delimited scope list, every entry drawn from `supported`. */
export function parseScopes(raw, supported) {
  if (raw === undefined) return { ok: true, scopes: [...supported] };
  const requested = raw.split(' ').filter((entry) => entry.length > 0);
  if (requested.length === 0) return { ok: false, code: CODES.SCOPE_INVALID };
  for (const scope of requested) {
    if (!supported.includes(scope)) return { ok: false, code: CODES.SCOPE_INVALID };
  }
  if (new Set(requested).size !== requested.length) return { ok: false, code: CODES.SCOPE_INVALID };
  return { ok: true, scopes: requested };
}
