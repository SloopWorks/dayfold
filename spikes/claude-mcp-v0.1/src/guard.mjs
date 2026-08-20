// Startup guard: structural proof that the spike cannot borrow a real
// credential. If the environment it is handed carries any Dayfold-shaped
// variable, the server refuses to start with a closed code and no detail
// about which variable was seen.

import { CODES } from './codes.mjs';

const FORBIDDEN_NAMES = Object.freeze([
  'DATABASE_URL',
  'DAYFOLD_API',
  'FAMILY_ID',
  'HOUSEHOLD_SECRET',
]);

const FORBIDDEN_PREFIXES = Object.freeze(['AUTH_']);

export function isForbiddenEnvName(name) {
  return FORBIDDEN_NAMES.includes(name) || FORBIDDEN_PREFIXES.some((prefix) => name.startsWith(prefix));
}

/** @param {Record<string, unknown>} environment */
export function assertCleanEnvironment(environment) {
  for (const name of Object.keys(environment)) {
    if (!isForbiddenEnvName(name)) continue;
    const error = new Error(CODES.ENV_CONTAMINATED);
    error.code = CODES.ENV_CONTAMINATED;
    throw error;
  }
}
