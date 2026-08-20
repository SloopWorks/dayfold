// Spike runs and their finish receipts. In memory, one registry per server
// instance, nothing persisted.
//
// A run belongs to exactly one credential. A run held by another credential is
// reported exactly like a run that never existed, so `runId` can never become
// an existence oracle for another tenant's work.

import { CODES } from './codes.mjs';
import { randomSecret, sha256Base64Url } from './crypto.mjs';

export function createRunRegistry() {
  let sequence = 0;
  const runsById = new Map();
  const runIdByCredential = new Map();

  /** One run per credential: a repeat identity call returns the same run. */
  function mint(credentialId) {
    const existing = runIdByCredential.get(credentialId);
    if (existing !== undefined) return runsById.get(existing);

    sequence += 1;
    const run = {
      runId: `run_spike_${sequence}_${randomSecret(8)}`,
      credentialId,
      closed: false,
      receipts: new Map(),
    };
    runsById.set(run.runId, run);
    runIdByCredential.set(credentialId, run.runId);
    return run;
  }

  function find(runId, credentialId) {
    const run = runsById.get(runId);
    if (!run || run.credentialId !== credentialId) return { ok: false, code: CODES.RUN_UNKNOWN };
    return { ok: true, run };
  }

  /**
   * Idempotent finish. The same `clientRequestId` returns the first receipt
   * verbatim; the same id over a changed payload, or a second distinct finish
   * on a closed run, is a closed conflict. Only the payload's digest is kept.
   */
  function record(run, clientRequestId, payload, receipt) {
    const fingerprint = sha256Base64Url(JSON.stringify(payload));
    const previous = run.receipts.get(clientRequestId);
    if (previous) {
      if (previous.fingerprint !== fingerprint) return { ok: false, code: CODES.REPLAY_MISMATCH };
      return { ok: true, receipt: previous.receipt };
    }
    if (run.closed) return { ok: false, code: CODES.RUN_CLOSED };

    run.receipts.set(clientRequestId, { fingerprint, receipt });
    run.closed = true;
    return { ok: true, receipt };
  }

  return { mint, find, record };
}
