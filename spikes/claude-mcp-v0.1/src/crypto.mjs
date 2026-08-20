// Crypto helpers. `node:crypto` only: no JWT library, no JWKS, no key file.
// Every key is generated per process and held in memory.

import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

export function randomKey(bytes = 32) {
  return randomBytes(bytes);
}

/** 32 bytes of randomness, rendered base64url. Used for every opaque credential. */
export function randomSecret(bytes = 32) {
  return randomBytes(bytes).toString('base64url');
}

export function sha256Base64Url(value) {
  return createHash('sha256').update(value, 'utf8').digest('base64url');
}

export function hmacBase64Url(key, value) {
  return createHmac('sha256', key).update(value, 'utf8').digest('base64url');
}

export function constantTimeEquals(left, right) {
  const a = Buffer.from(String(left), 'utf8');
  const b = Buffer.from(String(right), 'utf8');
  if (a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}
