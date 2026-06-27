# Adversarial Review of the Synthesis

*Raw per-agent output. Domain: hostile adversarial pass on the v1 synthesis. Archived per `processes/research-workflow.md` §4–5. The valid critiques below were folded into the final synthesis; this file preserves the attack as written.*

**Verdict on the verdict:** The NO-GO on full aggregation is well-supported and should stand. But the v1 synthesis had a structural integrity problem: it manufactured a clean binary (full vision vs thin slice) the raw evidence does not support, dropped the middle option its own agents flagged as genuine white space, and propped the CONDITIONAL GO with a "near-zero burden" claim that contradicts its own §3.

## Most serious problems (ranked)

**1. The synthesis silently deleted the middle option its own agents surfaced (worst faithfulness failure).** Strategy agent (05) laid out a *three-row* table: Crawl (per-family ICS) / Walk (curated regional packs) / Run (community-authored hubs). The middle row was graded "Medium" burden, "Marginal" fit — *not* no-go. Prior-art (01) independently called curated discovery the strongest finding ("Nobody removes the discovery step"); data-sourcing (02) confirmed "Discovery is the hard part; format is solved." The v1 synthesis recommended the slice that *keeps* the discovery friction and discarded the option that *addresses* the white space — without telling the reader the middle option existed. **Fix:** restore three-way framing; curated regional packs = explicit DEFER-with-trigger.

**2. "Near-zero legal/ops burden" contradicts the synthesis's own §3.** §0/§2 asserted near-zero burden `[high confidence]`; §3 then lists seven hard build constraints, two with `[needs-counsel]` legal gates, plus a §230 wrinkle called "ADR-class." Near-zero is true only for the *freshness/engineering* axis, false for the *legal/privacy* axis (per-source ToS review + multi-state minors'-privacy review + minors'-data-minimization — brushing Guardrail 1). **Fix:** strike "near-zero legal"; split the axes; reframe as "near-zero freshness/moderation burden but a real one-time legal/privacy design pre-gate."

**3. The CONDITIONAL GO is under-argued against its own kill criteria.** Native subscribe is free + Caldzy already does the thin slice + no WTP proven → these point at NO-GO; the escape hatch ("acquisition probe") is never tested against the strongest counter: *for a learning lab that hasn't shipped core, a probe for the wedge before the wedge exists is premature.* The slice is "sequenced behind core," so it teaches nothing now. The feed feature adds **no new differentiation** (the differentiator is the core wedge's multi-member rendering, not the feed). **Changes verdict to:** "spec-only now; run as an acquisition experiment *after* core ships, ADR-gated, legal pre-gate cleared first, with a defined success bar." NO-GO-for-now is equally defensible.

**4. Three load-bearing claims laundered from analogy/single-source into stronger language.** (a) §230 "plausibly Dayfold's own speech" rests on *zero on-point case law* + a harm scenario (wrong pickup time surfaced as live) with *no documented post-mortem* — demote to speculative theory. (b) Freshness "12–24h laggy" rests on vendor blogs. (c) Burbio "72h cadence" was *COVID-era* (data agent said "~72h during COVID") — the v1 dropped the qualifier and stated it as standing fact.

**5. The Burbio pivot is over-interpreted; "white space = no demand" asserted then used as proven.** The v1 hedged ("white space is absence of a feature, not demand") then reversed in the same paragraph ("the Burbio precedent argues the demand isn't there"). Burbio's consumer→B2B pivot is over-determined: COVID timing (local events cancelled), B2B simply more lucrative per customer, n=1 founder path-dependence, monetization≠demand. Prior-art agent said "caution, not a green light"; v1 escalated to "confirmed." **Fix:** downgrade Burbio to "one strong but over-determined cautionary data point." NO-GO survives regardless (it stands on commoditization + ops burden + off-thesis independent of Burbio).

## Lesser issues
- **Caldzy** used as a NO-GO point in §1, ignored when greenlighting the same mechanic in §2; deserves a competitive note.
- **Jam** (closest competitor to Dayfold's actual wedge — email-forward → LLM extract → reminders, live) was demoted to the source list; belongs in the body.
- **Third-party-minor deletion gap leaks into the thin slice** — a kid's team ICS includes teammates' names; §3 only said "minimize," not "stand up a deletion path."
- **No success bar** for the acquisition hypothesis (no threshold/sample/cost ceiling).
- **Alexa+** ("unverifiable" in raw, a primary commoditization threat per CLAUDE.md) silently dropped from the white-space claim.

## What the synthesis got RIGHT (preserve)
- Full-vision NO-GO is correct + over-determined (survives Burbio downgrade).
- "Auth-gated granular feeds can't be aggregated" is the sharpest finding — it explains *why* aggregation collapses to per-household onboarding.
- Buy-not-build (Burbio license) correctly engaged + rejected on Guardrail-6 spend + commoditization ("freshness scales with headcount, not servers").
- The §3 build constraints are excellent + should be preserved verbatim in any future ADR.
- The honest-freshness-UX-vs-calm-promise tension is a genuine, well-identified strategic insight.

## Bottom line
NO-GO on full aggregation: keep. CONDITIONAL GO on thin BYO-feed: keep direction, downgrade framing (strike near-zero-legal; spec-only-now; success bar; NO-GO-for-now is equally valid). Restore the deleted middle option as DEFER-with-trigger. Fix the three evidence distortions (COVID qualifier; white-space→demand; Jam demotion).
