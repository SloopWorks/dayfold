// ADR 0064 — the match predicate is the WHOLE suppression contract, and it is deliberately
// trivial: three string equalities against columns the server already treats as opaque
// identifiers. These tests exist to keep it that way. If a future change makes this function
// need a title, body, label, or note, the design has drifted off content-blindness.
import { describe, it, expect } from "vitest";
import { matchesRule, type ContentResponseRow } from "../src/content/responses.ts";

const base = {
  id: "r1",
  kind: "mute",
  audience_scope: "family",
  user_id: null,
  created_by: "u1",
  label: "opaque",
  sublabel: null,
  note: null,
  version: 1,
} satisfies Partial<ContentResponseRow> as ContentResponseRow;

const rule = (over: Partial<ContentResponseRow>): ContentResponseRow =>
  ({ ...base, ...over }) as ContentResponseRow;

describe("matchesRule — string equality only, never content", () => {
  const subject = { subjectRef: "card:c_1", kind: "weather", source: "mb" };

  it("subject scope matches an exact subject_ref", () => {
    const r = rule({ subject_ref: "card:c_1", match_scope: "subject" });
    expect(matchesRule(subject, r)).toBe(true);
    expect(matchesRule({ ...subject, subjectRef: "card:c_2" }, r)).toBe(false);
  });

  it("kind scope matches the card kind column", () => {
    const r = rule({ subject_ref: "kind:weather", match_scope: "kind" });
    expect(matchesRule(subject, r)).toBe(true);
    expect(matchesRule({ ...subject, kind: "action" }, r)).toBe(false);
  });

  it("source scope matches provenance.source", () => {
    const r = rule({ subject_ref: "source:morning-briefing", match_scope: "source" });
    expect(matchesRule({ ...subject, source: "morning-briefing" }, r)).toBe(true);
    expect(matchesRule({ ...subject, source: "other" }, r)).toBe(false);
  });

  it("a null kind or source never matches a class rule", () => {
    const k = rule({ subject_ref: "kind:weather", match_scope: "kind" });
    const s = rule({ subject_ref: "source:mb", match_scope: "source" });
    const bare = { subjectRef: "card:c_1", kind: null, source: null };
    expect(matchesRule(bare, k)).toBe(false);
    expect(matchesRule(bare, s)).toBe(false);
  });

  it("a done row suppresses its subject like a mute does", () => {
    const r = rule({ kind: "done", subject_ref: "card:c_1", match_scope: "subject" });
    expect(matchesRule(subject, r)).toBe(true);
  });

  // Suppression is EXACT equality. Prefix containment was rejected for dedup in the ranker
  // for the same reason it must be rejected here: a hub-level key is a prefix of every block
  // under that hub, so a prefix match would silently mute the whole hub.
  it("subject scope is not a prefix match", () => {
    const r = rule({ subject_ref: "hub:h1", match_scope: "subject" });
    expect(matchesRule({ subjectRef: "hub:h1/block:b", kind: null, source: null }, r)).toBe(false);
    expect(matchesRule({ subjectRef: "hub:h10", kind: null, source: null }, r)).toBe(false);
    expect(matchesRule({ subjectRef: "hub:h1", kind: null, source: null }, r)).toBe(true);
  });

  // A class rule keys on the CLASS token, never on the row's own subject_ref — otherwise
  // "kind:weather" would also have to appear on the content row for the rule to bite.
  it("a class rule ignores the row's subject_ref entirely", () => {
    const r = rule({ subject_ref: "kind:weather", match_scope: "kind" });
    expect(matchesRule({ subjectRef: "hub:h9/block:b1", kind: "weather", source: null }, r)).toBe(true);
  });
});
