// The one browser surface: a consent page that is unmistakably synthetic and
// carries no script, no style, and no third-party asset (the CSP forbids all
// three). The client name is escaped and truncated before it is rendered.

import { MAX_CLIENT_NAME_DISPLAY_LENGTH } from './constants.mjs';

const ESCAPES = Object.freeze({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
});

export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ESCAPES[character]);
}

export function boundedClientName(clientName) {
  return String(clientName).slice(0, MAX_CLIENT_NAME_DISPLAY_LENGTH);
}

export function renderApprovalPage({ clientName, scopes, ticket }) {
  const name = escapeHtml(boundedClientName(clientName));
  const scopeList = scopes.map((scope) => `<li>${escapeHtml(scope)}</li>`).join('');
  return `<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>SYNTHETIC SPIKE - not a Dayfold authorization</title></head>
<body>
<h1>SYNTHETIC SPIKE - NOT A DAYFOLD AUTHORIZATION</h1>
<p>This is a throwaway compatibility spike. There is no account here, no mailbox,
no family and no real data. Approving mints a synthetic token that can reach
nothing but this local spike process.</p>
<p>Requesting client (as registered): <code>${name}</code></p>
<p>Scopes requested:</p>
<ul>${scopeList}</ul>
<form method="post" action="/oauth/approve">
<input type="hidden" name="approval" value="${escapeHtml(ticket)}">
<button type="submit">Approve this synthetic request</button>
</form>
</body>
</html>
`;
}
