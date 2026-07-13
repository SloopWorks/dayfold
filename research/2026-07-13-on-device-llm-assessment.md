# Research Report — On-Device LLM APIs on Android (Gemini Nano / ML Kit GenAI), and Their Fit for a Local Dayfold Agent

**Date:** 2026-07-13 · **Answers:** operator question — *"review the available Android
local-LLM APIs (Gemini Nano via ML Kit GenAI); can we use them for a local, secure,
private agent over the Dayfold data already synced to the device?"* · **Opens:**
`OQ-ondevice-k2` · **Method:** two parallel cited research agents (ML Kit/Nano/Apple
availability; runtimes + hard perf/quality numbers), cross-checked against the repo's
own constraints (ADR 0015/0016/0041/0042/0043/0044, `NowDerive.kt`/`NowRank.kt`).
**Class:** research + a reserved architecture slot — **no ADR proposed, nothing to build
now.**

```
=== VERDICT ===
As an on-device AGENT over family data:   NO-GO  (HIGH confidence)
As a narrow on-device TEXT UTILITY:       CONDITIONAL  (worth a cheap spike, not now)
Architecture slot ("K2 = the member's own phone"):  REAL, and currently EMPTY — reserve it
Revisit trigger: Gemini Nano 4 ships structured output + tool calling (Google-promised,
                 "during the preview period" — i.e. plausibly Q4 2026)
Sleeper finding: the best on-device-AI opportunity in Dayfold is NOT an LLM — it is an
                 on-device EMBEDDING model for the client-side search ADR 0015 §3 forces.
===============
```

---

## 1. What actually exists on Android today

**ML Kit GenAI** — all beta (no GA, no SLA, "changes may break backward compatibility"),
free, no API key, `minSdk 26`, model weights live in AICore and are **shared across apps**
(you ship no weights):

| API | Status | Artifact |
|---|---|---|
| Summarization / Proofreading / Rewriting / Image Description | beta | `com.google.mlkit:genai-*:1.0.0-beta1` (2025-05-14) |
| **Prompt** (free-form) | beta | `genai-prompt:1.0.0-beta2` (alpha 2025-10-30 → beta 2026-01-28 → beta2 2026-04-02) |
| Speech Recognition | alpha | `genai-speech-recognition:1.0.0-alpha1` |

The free-form path is real and needs **no allowlist**: `Generation.getClient()` →
`generateContent()` / `generateContentStream()`, text or image+text in, text out.
Availability is checked at runtime — `checkStatus()` → `AVAILABLE | DOWNLOADABLE |
DOWNLOADING | UNAVAILABLE`, with a `download()` Flow emitting progress. The older Google
AI Edge SDK "experimental access" path (Pixel 9, dev-only) is **deprecated** in favor of
this. [fact:https://developers.google.com/ml-kit/genai]
[fact:https://developers.google.com/ml-kit/genai/prompt/android/get-started]
[fact:https://developer.android.com/ai/gemini-nano]

### The privacy story is genuinely excellent — and it is not marketing

AICore is **Private Compute Core-compliant**: it is package-binding-restricted, **cannot
directly access the internet**, and retains no record of inputs/outputs after a request.
Inference is $0, works fully offline, and structurally never leaves the device.
[fact:https://developer.android.com/ai/gemini-nano]
[fact:https://android-developers.googleblog.com/2024/10/introduction-to-privacy-and-safety-gemini-nano.html]

> **Trap to record:** **Firebase AI Logic hybrid inference deliberately breaks this.**
> `PREFER_ON_DEVICE` silently **falls back to cloud Gemini** when Nano is absent
> (`firebase-ai` + `firebase-ai-ondevice`, experimental). If Dayfold ever touches
> on-device inference it must call **ML Kit directly, never Firebase AI Logic** — the
> hybrid SDK would route family content to a hosted LLM by default, violating ADR 0015
> and hard guardrail #3 with a single wrong enum.
> [fact:https://firebase.google.com/docs/ai-logic/hybrid/android/get-started]

### The caps are what kill it

- **Input < 4,000 tokens** (~3,000 words) — that is the *entire* request.
- **Output ≤ 256 tokens** — Google: *"use cases that require long output (more than 256
  tokens) should be avoided."*
- **No JSON schema / structured output. No tool calling. No multi-turn.** Firebase's
  hybrid doc lists all three explicitly as *unavailable* for on-device inference.
- **Foreground-only** inference.
- **English + Korean** validated only.
- **No app-supplied LoRA / fine-tuning** (adapters exist but are Google-authored, per-
  feature, inside AICore).
- Safety classifiers are **non-configurable** — it can refuse, and you cannot dial it down.
- **"Variations in hardware… may lead to differences in Gemini Nano base model versions
  and, consequently, in ML Kit GenAI API outputs."** ← load-bearing, see §3.

All four missing capabilities (tool calling, structured output, system prompts, thinking
mode) are **announced, not shipped** — promised "throughout the preview period," arriving
with **Gemini Nano 4** on new flagships.
[fact:https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview]

### Reach

Gemini Nano is on **"over 140 million devices"** (2026-04-02) — against ~3B+ active
Android devices that is **~4–5%** [estimate]. Flagship-only, high-RAM, new. **No Exynos**
support for Nano 4. **Not supported on unlocked-bootloader devices** → no emulator/rooted
dev loop. `checkStatus()` is the only honest gate; do not use RAM heuristics.
[fact:https://developer.android.com/blog/posts/gemma-4-the-new-standard-for-local-agentic-intelligence-on-android]
[fact:https://www.androidauthority.com/gemini-nano-4-benchmarks-3655763/]

---

## 2. The BYO-model alternative (LiteRT-LM), and why it's worse for us

The stack moved: **MediaPipe LLM Inference is now maintenance-only**; Google steers all
new work to **LiteRT-LM** (v0.14.0), which *does* have what Nano lacks — first-class
Kotlin **tool calling** (`@Tool`/`@ToolParam`), 128K context, Gemma 4 E2B/E4B (**Apache
2.0**, unlike Gemma 3n's custom license), GPU/NPU backends, a Kotlin Flow API.
[fact:https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android]
[fact:https://developers.google.com/edge/litert-lm/android]

The price is disqualifying for a calm household dashboard:

| Cost | Number |
|---|---|
| Gemma 4 E2B `.litertlm` model file | **2,583 MB** |
| Play "AI Pack" single-pack limit | **1.5 GB** → E2B **doesn't fit**; you'd split packs or self-host |
| Engine cold load | **"up to 10 seconds"** |
| Resident memory | 676 MB (GPU) – **1.73 GB** (CPU RSS) |
| Under 8 GB RAM | Android **lmkd** kills the process mid-inference — non-graceful, bypasses lifecycle save; then you eat the 10 s reload |

[fact:https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm]
[fact:https://developer.android.com/google/play/on-device-ai]
[fact:https://developer.android.com/topic/performance/vitals/lmk]

**Speed is no longer the blocker.** On an S26 Ultra GPU, E2B does 3,808 tok/s prefill /
52 tok/s decode, TTFT 0.3 s. **Quality, sustained thermals, and install size are the
blockers.** Thermal throttling is severe and fast: an iPhone 16 Pro loses **41.5% of peak
throughput within 3 iterations**; a field measurement on an S24 Ultra saw **8 tok/s → 1.2
tok/s after 90 seconds** of sustained generation as the governor dropped the GPU from
680 MHz to 231 MHz at 78 °C. *"The first response is impressive, and then physics shows
up."* [fact:https://arxiv.org/html/2603.23640v2]
[fact:https://www.edge-ai-vision.com/2026/01/on-device-llms-in-2026-what-changed-what-matters-whats-next/]

Solo-dev viability of the rest: **ExecuTorch** is a credible #2 (1.0 GA, Hexagon NPU,
official mobile SDKs); **llama.cpp** = permanent NDK maintenance; **MLC** = per-target
compilation; **ONNX Runtime GenAI** = wrong tool for phone LLMs (fine for
embeddings/classifiers); **Qualcomm GenieX** = Snapdragon lock-in; **Samsung** ships no
usable third-party LLM SDK. KMP wrappers (Llamatik, Cactus-kotlin) exist but are
bus-factor-1 / beta.

**KMP shape, if ever built:** there is no Google-blessed KMP LLM API. It is an
`expect`/`actual`-free **interfaces-in-`commonMain`** seam (the same device-glue pattern
Now Phase B already uses): `LlmEngine { load / prompt / stream / tools }` in `commonMain`,
`androidMain` → ML Kit or LiteRT-LM, `iosMain` → Apple Foundation Models.

---

## 3. Why it fails Dayfold specifically — four blockers

### 3.1 It breaks the one defensible wedge

Validation round 1 found exactly **one** defensible surface: the **multi-member
family-tenant briefing** — *one shared briefing, same content, every member*. On-device
generation is **per-device**, and Google itself warns that output varies with the device's
Nano base-model version. Two parents would read materially different text derived from
identical hub content. Generate-once-and-sync would fix that — but then it is a write path,
it re-enters the server/E2EE model, and you have rebuilt the key-holder loop around a far
weaker model. **This is the strongest single argument against.**

### 3.2 The job we'd want is the job small models are worst at

"Read a week of hubs, cards, and dates → produce prioritized recommendations" is
**long-context multi-record reasoning**. That is the measured weak axis:

| Benchmark | Gemma 4 E2B | Gemma 4 E4B | Frontier |
|---|---|---|---|
| MRCR v2 @128k (multi-needle long-context) | **19.1%** | **25.4%** | 66.4% (Gemma 31B) |
| MMLU-Pro | 60.0 | 69.4 | ~89.8–91.5 |
| GPQA-Diamond | 43.4 | 58.6 | ~84 |

The advertised **128K context is a storage claim, not a reasoning claim**. The gap on
judgment-shaped work is ~20–30 points of MMLU-Pro and ~40 of GPQA — a different *class* of
reasoner, not a slightly worse one. Google's own agentic framing for edge Gemma 4 is
narrow, pre-defined skills ("4,000 input tokens across 2 distinct skills in under 3
seconds") — **not** "look at my week and tell me what matters."
[fact:https://ai.google.dev/gemma/docs/core/model_card_4]
[fact:https://developers.googleblog.com/bring-state-of-the-art-agentic-skills-to-the-edge-with-gemma-4/]

And the output contract is unreliable *by default*, which matters because a Dayfold card
**is** a schema:

- Unconstrained, prompt-only JSON: **0% output accuracy** (correct answer *and* valid
  JSON) across all tested models — **even GPT-4o** — because of systematic markdown-fence
  wrapping. [fact:https://arxiv.org/abs/2605.02363]
- Constrained decoding fixes the syntax but costs **3.6×–8.2× latency** and "degrades task
  performance substantially" — and **Nano doesn't offer it at all**.
- Gemma 3 4B: 100% JSON *parse* rate but **87% schema compliance**, and it wraps
  extraneous commentary around the JSON in **100% of runs**. Valid JSON ≠ correct JSON;
  a validator + retry is mandatory regardless.
  [fact:https://ascentcore.com/2026/04/01/small-llm-performance-benchmark/]

### 3.3 Foreground-only kills the surface we'd most want

ADR 0044 Phase B is **background** — headless geofence + exact-alarm local notifications
while the app is closed. On-device inference **cannot run there**. The one surface where a
smarter "why does this matter right now?" would earn its keep is the one surface the API
forbids.

### 3.4 It loses to the incumbent we already shipped

`NowDerive.kt` (250 LOC) + `NowRank.kt` (187 LOC): pure, deterministic, injected-clock,
unit-tested, zero cost, zero latency, and guarded by **131 golden snapshots** (CL-SNAP).
An LLM in that path is slower, non-deterministic, **per-device-variable**, and would blow
up the snapshot gate outright. ADR 0043's seam — *"author the irreducible, derive the
structural"* — already answered this question correctly, and an on-device LLM would be a
**third** on-device projection over hub content, softening the dumb-client posture further
(the exact concern ADR 0046 flagged as the operator's call).

---

## 4. The genuinely interesting finding: **K2 is an empty slot**

ADR 0042 / the two-way engine design name the key-holder tiers where the AI loop may
decrypt and reason:

- **K1** — operator machine (M0/dogfood default; free, must be online)
- **K3** — dedicated controlled host (M1-correct for a real second family)
- **K4** — hosted, relaxed-E2EE (reserved, ADR-gated, disclosure-bearing, never default)

**There is no K2.** And the member's own phone is *already a key-holder* — at M1 it holds
the FCK and the decrypted content in SQLCipher (ADR 0015 §5). An on-device LLM is
precisely that missing tier. It would make ADR 0042's honesty chip — *"Processed by Claude
on your device"* — **literally, structurally true**, with **zero recurring spend** and
**zero E2EE compromise**. That is the right idea, and it is worth reserving the name.

Two things stop it from being the answer *today*:

1. **Capability** (§3.2) — Nano cannot reliably emit a card-shaped schema, and cannot
   reason across a family's week.
2. **W3's flagship use case is device-hostile by design.** *"Research dorm rooms and make
   a card"* requires **outbound fetch**, which ADR 0042 §4 explicitly forbids on the device
   (SSRF isolation stays **in the loop**, never on the member's phone — fetching a
   member-supplied URL from the phone both SSRFs the member's own network and leaks the
   intent). So even a *perfect* on-device model would not take W3's research path.

**K2's honest scope, if it ever opens:** bounded, single-record, schema-constrained,
no-network work — e.g. structuring a member's own free-text note into a card *without*
research. Not briefing authoring. Not W3-with-research.

---

## 5. The sleeper: the best on-device AI opportunity here isn't an LLM

**ADR 0015 §3 explicitly sacrifices server-side FTS** (`tsvector`/GIN over `body_md`) to
E2EE, and hands us **"client-side search only"** as a known, accepted cost.

An on-device **embedding** model fills exactly that hole — and it is a *far* better fit
than an LLM on every axis that broke above: no 4k input cap, no 256-token output cap, no
hallucination surface, no schema-compliance problem, no JSON parsing, tiny (tens of MB, not
2.5 GB), fast, deterministic-enough, and it runs fine on the paths that are *bad* for LLMs
(ONNX Runtime, LiteRT). It has no device-variance problem because we would ship and pin the
model ourselves.

**Recorded, not proposed.** It is a different investigation (embedding model choice, index
shape, KMP seam, recall quality at family-data volume) and it only becomes load-bearing
when E2EE actually flips at M1. But it is the answer to "what *should* we run locally?" and
it should not be lost.

---

## 6. Recommendation

**Build now: nothing.** No ADR, no spike, no seam. The incumbent (`NowDerive`/`NowRank`)
is better at the job on every axis that matters, and the constitution's fences (ADR
0041/0042) are not the constraint here — **capability is**.

**Reserve:** the **K2 = member's-own-device key-holder tier** (§4), as an open question,
not a plan.

**Revisit trigger:** **Gemini Nano 4 ships structured output + tool calling + system
prompts.** Google has promised all three "during the preview period." At that point Nano
becomes a plausible K2 for *bounded, single-record, schema-constrained, no-network* work —
still **not** for briefing authoring, and still gated on §3.1 (per-device output variance
vs. the shared-briefing wedge), which **no capability improvement fixes**.

**Two narrow candidates worth a cheap spike if the mood strikes, in priority order:**

1. **On-device embeddings for client-side search** (§5) — the real opportunity; scoped by
   M1/E2EE, not by Nano's roadmap.
2. **Hub-body summarization** via the ML Kit **Summarization** API — one short document in,
   ≤256 tokens out, free, offline, within the caps *by construction*. The single shape Nano
   is actually good at. Would still need a "summarized on your device" provenance chip
   (ADR 0043 honesty guardrail) and a deterministic fallback for the ~95% of devices
   without Nano.

---

## 7. iOS parity (for completeness — it is ahead of Android)

Apple's **Foundation Models** framework (iOS 26, shipping): ~3B on-device model, free,
offline, **guided generation** via `@Generable` (real constrained decoding), **first-class
tool calling**, streaming, **custom LoRA adapters** (rank-32 training toolkit), context
4,096 → **8,192** in the iOS 27 wave. Reach: Apple-Intelligence-class hardware only
(iPhone 15 Pro / A17 Pro+, ≥8 GB). Does **not** run in the Simulator.
[fact:https://developer.apple.com/documentation/technotes/tn3193-managing-the-on-device-foundation-model-s-context-window]
[fact:https://machinelearning.apple.com/research/introducing-third-generation-of-apple-foundation-models]

**Net: Apple's on-device stack is materially more capable than Android's** — 2× context,
real structured output, real tool calling, custom adapters, **zero model download, zero
disk** — and behind on nothing except reach. Apple also explicitly warns its own ~3B model
is *"not designed for general world knowledge"* and unsuitable for factual Q&A, math, or
code. **Believe the vendor when they undersell.** None of that changes §3.1 — a
per-device-generated briefing still breaks the shared-family-briefing wedge, on either OS.

---

## 8. Confidence & what could flip this

**HIGH confidence on the NO-GO.** It rests on published vendor caps (4k in / 256 out / no
schema / no tools / foreground-only) and published benchmarks (MRCR 19–25%), not on
opinion.

**What would flip it:**

- Nano 4 ships structured output + tool calling **and** long-context reasoning on small
  models improves materially (MRCR@128k well north of 50%) → K2 opens for bounded work.
- Dayfold abandons the shared multi-member briefing as the wedge → §3.1 evaporates and
  per-device generation becomes acceptable.
- **Neither is likely inside 12 months.** [estimate]

**Unverified / verify before ever committing:** whether the ML Kit Prompt API (outside
Firebase) is *itself* foreground-only (the restriction is stated in Firebase's hybrid docs
and not repeated on ML Kit's own pages — assume yes until tested); the exact Nano weight
download size (only the ~11 GB *transient update* spike is Google-documented); whether
Kotlin LiteRT-LM exposes JSON-schema constrained decoding outside `ExperimentalFlags`.
