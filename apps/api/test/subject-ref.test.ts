// ADR 0064 — direct unit tests for the subject-ref grammar. This is the suppression key, so
// the PARSING contract is security-adjacent in the same way ADR 0029's scope strings are:
// ids are free text and may contain ':' and '/', so a naive split() would mis-key a rule
// onto the wrong subject. The builders are additionally mirrored byte-for-byte in Kotlin
// (apps/client/.../SubjectRef.kt) — a divergence there does not error, it silently stops
// suppressing, which is why the exact literals are pinned here.
import { describe, it, expect } from "vitest";
import {
  buildCardSubjectRef,
  buildBlockSubjectRef,
  buildNodeSubjectRef,
  buildKindRef,
  buildSourceRef,
  parseSubjectRef,
  isRuleRef,
  isSafeRefComponent,
  UnsafeSubjectRefComponent,
} from "../src/content/subject-ref.ts";

describe("subject-ref builders", () => {
  it("builds a card ref", () => {
    expect(buildCardSubjectRef("c_123")).toBe("card:c_123");
  });

  it("builds a node ref with and without a section", () => {
    expect(buildBlockSubjectRef("h_9", "s_2", "b_4")).toBe("hub:h_9/section:s_2/block:b_4");
    expect(buildBlockSubjectRef("h_9", null, "b_4")).toBe("hub:h_9/block:b_4");
  });

  it("builds the class refs", () => {
    expect(buildKindRef("weather")).toBe("kind:weather");
    expect(buildSourceRef("morning-briefing")).toBe("source:morning-briefing");
  });
});

describe("parseSubjectRef", () => {
  it("round-trips a card ref", () => {
    expect(parseSubjectRef("card:c_123")).toEqual({ form: "card", cardId: "c_123" });
  });

  it("round-trips a node ref with a section", () => {
    expect(parseSubjectRef("hub:h_9/section:s_2/block:b_4")).toEqual({
      form: "node",
      hubId: "h_9",
      sectionId: "s_2",
      blockId: "b_4",
    });
  });

  // A node ref may stop at any level: `hub:<id>` is what the derived lane keys a hub
  // countdown on, so requiring a block would make that subject unmutable.
  it("round-trips the shorter node refs", () => {
    expect(parseSubjectRef("hub:h_9")).toEqual({ form: "node", hubId: "h_9" });
    expect(parseSubjectRef("hub:h_9/section:s_2")).toEqual({
      form: "node",
      hubId: "h_9",
      sectionId: "s_2",
    });
  });

  it("round-trips a sectionless node ref", () => {
    expect(parseSubjectRef("hub:h_9/block:b_4")).toEqual({
      form: "node",
      hubId: "h_9",
      blockId: "b_4",
    });
  });

  it("round-trips the class refs", () => {
    expect(parseSubjectRef("kind:weather")).toEqual({ form: "kind", value: "weather" });
    expect(parseSubjectRef("source:morning-briefing")).toEqual({
      form: "source",
      value: "morning-briefing",
    });
  });

  // Ids are free text. A split(':') implementation returns "weird" here and keys the rule
  // onto a subject that does not exist — the ADR 0029 privilege-confusion hazard, applied
  // to suppression instead of authority.
  it("does not split on ':' inside an id", () => {
    expect(parseSubjectRef("card:weird:id:here")).toEqual({
      form: "card",
      cardId: "weird:id:here",
    });
  });

  // Parsing stays total for a hand-written ref containing an extra marker — last-marker-wins
  // is well-defined — but such a ref is AMBIGUOUS (it could equally have come from a section
  // id ending "/block:b"), which is why the builders refuse to mint one. See the guard tests.
  it("resolves an extra /block: marker by taking the last one", () => {
    expect(parseSubjectRef("hub:h_9/section:s_2/block:b/block:4")).toEqual({
      form: "node",
      hubId: "h_9",
      sectionId: "s_2/block:b",
      blockId: "4",
    });
  });

  it("round-trips whatever the builders produce", () => {
    const refs = [
      buildCardSubjectRef("c_1"),
      buildBlockSubjectRef("h_1", "s_1", "b_1"),
      buildBlockSubjectRef("h_1", null, "b_1"),
      buildNodeSubjectRef("h_1", null, null),
      buildNodeSubjectRef("h_1", "s_1", null),
      buildKindRef("weather"),
      buildSourceRef("mb"),
    ];
    for (const r of refs) expect(parseSubjectRef(r)).not.toBeNull();
  });

  it("rejects garbage", () => {
    expect(parseSubjectRef("")).toBeNull();
    expect(parseSubjectRef("nope:x")).toBeNull();
    expect(parseSubjectRef("hub:")).toBeNull();
    expect(parseSubjectRef("card:")).toBeNull();
    expect(parseSubjectRef("kind:")).toBeNull();
    expect(parseSubjectRef("hub:h_9/section:s_2/block:")).toBeNull();
    expect(parseSubjectRef("hub:/block:b_4")).toBeNull();
    expect(parseSubjectRef("hub:h_9/section:/block:b_4")).toBeNull();
  });
});

describe("ambiguity guard", () => {
  // Entity ids arrive as caller-supplied route params. An id containing a reserved marker
  // makes the ref decompose two ways, and the second reading is a DIFFERENT subject — so a
  // crafted id could alias a muted subject's key or dodge a mute. Refuse to mint one.
  it("accepts ordinary ids", () => {
    expect(isSafeRefComponent("b_4")).toBe(true);
    expect(isSafeRefComponent("weird:id:here")).toBe(true); // ':' alone is fine
    expect(isSafeRefComponent("a/b")).toBe(true); // '/' alone is fine
  });

  it("rejects ids carrying a reserved marker, and empty ids", () => {
    expect(isSafeRefComponent("b/block:4")).toBe(false);
    expect(isSafeRefComponent("s/section:2")).toBe(false);
    expect(isSafeRefComponent("")).toBe(false);
  });

  it("refuses to build an ambiguous ref", () => {
    expect(() => buildCardSubjectRef("c/block:1")).toThrow(UnsafeSubjectRefComponent);
    expect(() => buildBlockSubjectRef("h_9", "s_2/block:b", "4")).toThrow(UnsafeSubjectRefComponent);
    expect(() => buildBlockSubjectRef("h_9", "s_2", "b/block:4")).toThrow(UnsafeSubjectRefComponent);
    expect(() => buildBlockSubjectRef("h/block:9", null, "b_4")).toThrow(UnsafeSubjectRefComponent);
    expect(() => buildCardSubjectRef("")).toThrow(UnsafeSubjectRefComponent);
  });

  // The property that matters: anything the builders DO mint parses back to its inputs.
  it("round-trips every buildable ref exactly", () => {
    expect(parseSubjectRef(buildCardSubjectRef("weird:id:here"))).toEqual({
      form: "card",
      cardId: "weird:id:here",
    });
    expect(parseSubjectRef(buildBlockSubjectRef("h/9", "s/2", "b/4"))).toEqual({
      form: "node",
      hubId: "h/9",
      sectionId: "s/2",
      blockId: "b/4",
    });
  });
});

describe("isRuleRef", () => {
  // A class ref may only ever appear on a rule row, never on a content row — the write
  // paths stamp subject/node refs only, so this is what keeps the two namespaces apart.
  it("classifies class refs as rule-only", () => {
    expect(isRuleRef("kind:weather")).toBe(true);
    expect(isRuleRef("source:morning-briefing")).toBe(true);
  });

  it("classifies concrete subjects as not rule-only", () => {
    expect(isRuleRef("card:c_123")).toBe(false);
    expect(isRuleRef("hub:h_9/section:s_2/block:b_4")).toBe(false);
  });
});
