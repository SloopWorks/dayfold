// Entrypoint. The only place the process environment is read, and the only
// place the four SPIKE_* knobs are turned into configuration.

import { CODES } from './codes.mjs';
import { DEFAULT_PORT } from './constants.mjs';
import { randomSecret } from './crypto.mjs';
import { LOG_CLASS, LOG_OUTCOME, createLogger } from './log.mjs';
import { createSpikeServer } from './server.mjs';

const environment = process.env;

/** An unset or blank knob falls back to the factory default. */
function text(name) {
  const value = environment[name];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function port() {
  const value = Number.parseInt(text('SPIKE_PORT') ?? '', 10);
  return Number.isInteger(value) && value >= 0 && value <= 65535 ? value : DEFAULT_PORT;
}

try {
  await createSpikeServer({
    port: port(),
    resourceOrigin: text('SPIKE_RESOURCE_ORIGIN'),
    redirectUri: text('SPIKE_REDIRECT_URI'),
    dcr: text('SPIKE_DCR') === 'on',
    env: environment,
  });
} catch (error) {
  createLogger({ testRunId: randomSecret(8) })(LOG_CLASS.SERVER_START, LOG_OUTCOME.REJECTED);
  process.exitCode = 1;
  // The startup guard is a deliberate refusal, not a crash: it reports one
  // closed code and nothing about the environment it saw.
  if (error?.code !== CODES.ENV_CONTAMINATED) throw error;
}
