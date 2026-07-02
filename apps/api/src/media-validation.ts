// ADR 0036 — shared hardened validation for author-supplied image enrichment.
//
// One rule, mirrored across server (this file) and the Kotlin side
// (packages/linkrules/MediaValidation.kt, srcDir'd into both apps/client
// commonMain and apps/cli). A parser differential IS the vulnerability, so
// keep the two in lock-step (Phase 2 will codegen them from one source).
//
// Phase-1 posture (ADR 0036): https-only, no userinfo, exact-host allowlist
// (NOT suffix), port empty/443, reject SVG, length-cap, curated-icon enum,
// #RRGGBB accent. The allowlist is exactly `upload.wikimedia.org`.

export const ALLOWED_IMAGE_HOSTS: ReadonlySet<string> = new Set(["upload.wikimedia.org"]);

// Curated icon NAMES (18) from the signed-off design (Enrichment.dc.html §D).
// Author sets the NAME; the client maps name→glyph; unknown → fallback tile.
export const CURATED_ICONS: ReadonlySet<string> = new Set([
  "school", "luggage", "medical", "move", "party", "baby",
  "calendar", "location", "link", "document", "contact", "budget",
  "travel", "car", "food", "pet", "sport", "list",
]);

const MAX_URL_LEN = 2048;
const ACCENT_RE = /^#[0-9a-fA-F]{6}$/;

export type MediaIssue = { path: (string | number)[]; message: string };

/**
 * Validate one author-supplied image URL against the ADR 0036 hardened rule.
 * Returns null when acceptable, else a short reason (caller wraps it in a 422
 * issue). Uses the WHATWG URL parser (IDNA/punycode + normalization) then layers
 * the explicit allowlist checks. The Kotlin mirrors apply identical logical steps.
 */
export function imageUrlError(url: unknown): string | null {
  if (typeof url !== "string") return "must be a string";
  if (url.length === 0 || url.length > MAX_URL_LEN) return "url length out of range";
  // reject control chars / whitespace / backslash before parsing (no smuggling).
  if (/[\x00-\x20\x7f\\]/.test(url)) return "url contains illegal characters";

  let u: URL;
  try { u = new URL(url); } catch { return "url does not parse"; }

  if (u.protocol !== "https:") return "scheme must be https";          // blocks http/data/javascript/blob
  if (u.username !== "" || u.password !== "") return "userinfo not allowed";
  if (u.port !== "") return "explicit port not allowed";               // 443 is normalized to ""
  // WHATWG already lowercased + punycode(toASCII)-normalized the host; strip a
  // single trailing dot, then require an EXACT host match (never endsWith).
  const host = u.hostname.replace(/\.$/, "");
  if (!ALLOWED_IMAGE_HOSTS.has(host)) return `host "${host}" is not on the image allowlist`;
  if (u.pathname.toLowerCase().endsWith(".svg")) return "SVG images are not allowed";
  return null;
}

export function iconError(icon: unknown): string | null {
  if (typeof icon !== "string") return "must be a string";
  return CURATED_ICONS.has(icon) ? null : `icon "${icon}" is not in the curated set`;
}

export function accentHexError(hex: unknown): string | null {
  if (typeof hex !== "string") return "must be a string";
  return ACCENT_RE.test(hex) ? null : "accentColor must match #RRGGBB";
}

// ---- object-level validators (return zod-issue-shaped lists) ----------------

function urlField(media: any, key: string, base: (string | number)[], out: MediaIssue[]) {
  if (media[key] == null) return;
  const e = imageUrlError(media[key]);
  if (e) out.push({ path: [...base, key], message: e });
}

/** Hub.media (heroUrl, thumbnailUrl, icon, accentColor). */
export function validateHubMedia(media: unknown): MediaIssue[] {
  if (media == null) return [];
  if (typeof media !== "object") return [{ path: ["media"], message: "must be an object" }];
  const m = media as any, out: MediaIssue[] = [];
  urlField(m, "heroUrl", ["media"], out);
  urlField(m, "thumbnailUrl", ["media"], out);
  if (m.icon != null) { const e = iconError(m.icon); if (e) out.push({ path: ["media", "icon"], message: e }); }
  if (m.accentColor != null) { const e = accentHexError(m.accentColor); if (e) out.push({ path: ["media", "accentColor"], message: e }); }
  return out;
}

/** BriefingCard.media (icon, accentColor, thumbnailUrl). */
export function validateCardMedia(media: unknown): MediaIssue[] {
  if (media == null) return [];
  if (typeof media !== "object") return [{ path: ["media"], message: "must be an object" }];
  const m = media as any, out: MediaIssue[] = [];
  urlField(m, "thumbnailUrl", ["media"], out);
  if (m.icon != null) { const e = iconError(m.icon); if (e) out.push({ path: ["media", "icon"], message: e }); }
  if (m.accentColor != null) { const e = accentHexError(m.accentColor); if (e) out.push({ path: ["media", "accentColor"], message: e }); }
  return out;
}

/** Block payload image fields: link/document thumbnailUrl, contact avatarUrl + accentColor. */
export function validateBlockPayloadMedia(type: unknown, payload: unknown): MediaIssue[] {
  if (payload == null || typeof payload !== "object") return [];
  const p = payload as any, out: MediaIssue[] = [];
  if (type === "link" || type === "document") urlField(p, "thumbnailUrl", ["payload"], out);
  if (type === "contact") {
    urlField(p, "avatarUrl", ["payload"], out);
    if (p.accentColor != null) { const e = accentHexError(p.accentColor); if (e) out.push({ path: ["payload", "accentColor"], message: e }); }
  }
  return out;
}

/** Lowercase the accent on write (in place on a shallow copy is the caller's job). */
export function normalizedAccent(hex: string | null | undefined): string | null {
  return typeof hex === "string" ? hex.toLowerCase() : null;
}
