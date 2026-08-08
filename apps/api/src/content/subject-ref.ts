// ADR 0064 — the canonical subject-ref grammar. This file is the ONLY place the format is
// known; every producer and every matcher goes through it.
//
//   subject_ref := "card:"   <cardId>
//                | "hub:"    <hubId> ["/section:" <sectionId>] "/block:" <blockId>
//                | "kind:"   <cardKind>          -- rule rows only
//                | "source:" <provenanceSource>  -- rule rows only
//
// The first two forms name A SUBJECT; the last two name A CLASS of subject and may only ever
// appear on a rule row (see isRuleRef). This is the ADR 0043 subjectRef taking its third job:
// dedup key -> deep-link key -> SUPPRESSION key.
//
// Parsing is prefix-based and never `split(':')`. Ids are free text and may contain both ':'
// and '/' — the same hazard ADR 0029's scope strings document, where a split mis-attributes
// authority. Here it would mis-key a rule onto the wrong subject, which fails silently.
//
// A byte-identical Kotlin mirror lives at
// apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SubjectRef.kt. The server
// matches client-minted rule keys by string equality, so any divergence between the two
// stops suppression without erroring. Change them together.

export type SubjectRef =
  | { form: "card"; cardId: string }
  | { form: "node"; hubId: string; sectionId?: string; blockId: string }
  | { form: "kind"; value: string }
  | { form: "source"; value: string };

const CARD = "card:";
const HUB = "hub:";
const KIND = "kind:";
const SOURCE = "source:";
const SECTION_MARK = "/section:";
const BLOCK_MARK = "/block:";

/**
 * Reserved substrings that would make a built ref ambiguous. Entity ids reach the write
 * paths from caller-supplied route params, so this is an integrity guard, not a nicety: an
 * id containing "/block:" lets one subject's ref decompose two ways, and the second reading
 * is a DIFFERENT subject — a crafted id could alias a muted subject's key, or dodge a mute
 * by minting a ref that parses to something else. Refuse to build such a ref at all rather
 * than defining a tie-break nobody can audit.
 */
export const RESERVED_REF_MARKERS = [SECTION_MARK, BLOCK_MARK] as const;

export function isSafeRefComponent(id: string): boolean {
  return id.length > 0 && !RESERVED_REF_MARKERS.some((m) => id.includes(m));
}

export class UnsafeSubjectRefComponent extends Error {
  constructor(readonly component: string) {
    super("subject-ref component contains a reserved marker");
  }
}

function assertSafe(id: string): string {
  if (!isSafeRefComponent(id)) throw new UnsafeSubjectRefComponent(id);
  return id;
}

export function buildCardSubjectRef(cardId: string): string {
  return `${CARD}${assertSafe(cardId)}`;
}

export function buildBlockSubjectRef(
  hubId: string,
  sectionId: string | null,
  blockId: string,
): string {
  assertSafe(hubId);
  assertSafe(blockId);
  return sectionId
    ? `${HUB}${hubId}${SECTION_MARK}${assertSafe(sectionId)}${BLOCK_MARK}${blockId}`
    : `${HUB}${hubId}${BLOCK_MARK}${blockId}`;
}

export function buildKindRef(kind: string): string {
  return `${KIND}${kind}`;
}

export function buildSourceRef(source: string): string {
  return `${SOURCE}${source}`;
}

/** True for a class ref (kind:/source:) — legal on a rule row, never on a content row. */
export function isRuleRef(ref: string): boolean {
  return ref.startsWith(KIND) || ref.startsWith(SOURCE);
}

export function parseSubjectRef(ref: string): SubjectRef | null {
  if (!ref) return null;

  if (ref.startsWith(CARD)) {
    const cardId = ref.slice(CARD.length);
    return cardId ? { form: "card", cardId } : null;
  }
  if (ref.startsWith(KIND)) {
    const value = ref.slice(KIND.length);
    return value ? { form: "kind", value } : null;
  }
  if (ref.startsWith(SOURCE)) {
    const value = ref.slice(SOURCE.length);
    return value ? { form: "source", value } : null;
  }
  if (!ref.startsWith(HUB)) return null;

  const rest = ref.slice(HUB.length);
  // LAST marker wins: a block id may itself contain "/block:"-shaped text, and the builder
  // always appends the real marker last.
  const blockAt = rest.lastIndexOf(BLOCK_MARK);
  if (blockAt <= 0) return null; // absent, or nothing before it to be a hub id
  const blockId = rest.slice(blockAt + BLOCK_MARK.length);
  if (!blockId) return null;

  const head = rest.slice(0, blockAt);
  // FIRST marker wins on the head: the hub id comes before it, and a section id may contain
  // "/section:"-shaped text.
  const sectionAt = head.indexOf(SECTION_MARK);
  if (sectionAt === -1) {
    return head ? { form: "node", hubId: head, blockId } : null;
  }
  const hubId = head.slice(0, sectionAt);
  const sectionId = head.slice(sectionAt + SECTION_MARK.length);
  if (!hubId || !sectionId) return null;
  return { form: "node", hubId, sectionId, blockId };
}
