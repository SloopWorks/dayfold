# Two-way interaction — formal spec notes

**Status: SPEC / ADR-0008 sign-off-ready.** These screens are the formalized, sign-off
versions of the member-writes interactions (the W1–W5 engine). The open calls from the
exploratory pass (`designs/exploratory/two-way/`) are now made and each decided variant
is collapsed to its single chosen design. Visuals + motion only — no app code, schema,
or ADR edits. One screen, **Add context (W3)**, is held back as **EXPERIMENTAL** (see below).

Open `Index.dc.html` to click through all six screens (each is light + dark via the
in-canvas toggle, M3-Expressive, Compose-named).

## Screens & status

| File | Problem | Status |
|---|---|---|
| `States.dc.html` | **P1** | SPEC · the five-rung optimistic vocabulary, reused everywhere. |
| `Todo.dc.html` | **P2 / P3** | SPEC · tap → overshoot → strike → batch fold; conflict storyboard. |
| `Delete-Hide.dc.html` | **P4 / P5** | SPEC · ACL-aware delete sheet; swipe-to-hide + overflow fallback. |
| `Author.dc.html` | **P5 (W2)** | SPEC · three on-commit editors + the no-author absence. |
| `Add-Context.dc.html` | **P6 (W3)** | **EXPERIMENTAL** · testable, not committed to ship. |
| `Media-Update.dc.html` | **P7 (W1)** | SPEC · strip → encrypt → R2 → enriched hero; per-platform. |

## Baked decisions (resolving the exploratory open calls)

- **States (P1)** — the five-rung optimistic vocabulary (synced / saving / offline /
  retrying / couldn't-save) is kept as-is. It is the spec the other screens cite.
- **Todo (P2/P3)** — tap → overshoot → strike → fold is kept. Resolved questions:
  - Completed rows **batch into one fold on a single burst-end debounce (~2s after the
    last tap)**, not staggered per-row.
  - The "N done" section **sorts by completion time** (newest first) and **collapses to a
    count-only line past ~20 done**.
  - The layout shift / fold is **deferred until the touch ends** (kept) — a row never
    moves under a finger. Conflict reconciles by byline, never a "your change was
    discarded" dialog.
- **Delete / Hide (P4/P5)**
  - **Delete** = one calm, ACL-aware bottom sheet (not a red alert), **author-only** —
    the option is *absent* (not disabled) when you didn't author the card.
  - **Hide** = local-only, personal, reversible. Trigger = **swipe → collapsed "Hidden
    for you" section (variant A)**, with the **overflow-menu path kept as the explicit
    accessibility fallback**. The long-press / combined Hide+Delete variant is **dropped**.
    Each hidden item carries a one-line **"You hid this"** self-reminder; there is
    deliberately no family-visible signal.
- **Author / W2 (P5)** — members author **only into hubs they can already see**, via three
  on-commit editors (markdown / to-do / link) plus the deliberate no-author empty state.
  **No member-created hubs. Single writer per block** (no concurrent rich-text co-edit UI;
  no merge story). The member byline is provenance.
- **Add context / W3 (P6)** — **EXPERIMENTAL**, not sign-off. Collapsed to the **free-form
  composer** (structured-vs-free-form side-by-side dropped). Reframed precisely: free-form
  text is a **bounded remote command to a key-holding Claude loop** — the loop **researches
  and authors a result card on a later run**; it **does not reply** and is **not a chat**.
  States: composer → "Dayfold is working on this…" calm shimmer (not a chat bubble) →
  AI-authored result card with "Added by Claude · from your note" (or "from the family"
  when sources span members). It **may fetch external info** to fulfil the command, and it
  **acts only within what you can see**. No reply thread, ever.
- **Media / W1 (P7)** — pick/capture → client-side **EXIF/location strip → encrypt → upload
  to Cloudflare R2** → enriched hero (cover/contain/fallback ladder). The full flow ships on
  **Android & iOS**; on **Web**, where no capture pipeline exists yet, the hero shows a
  graceful **"add a photo from the app"** absence (no broken/disabled control).
- **Remote-change provenance** — byline-only ("Mom · just now"), never a toast/notification.

## Honesty (ADR 0022 D4) — the only claims made

Honest chips appear only where a real client-side boundary enforces them:
- "Shared with your family · synced when online", "You're offline — saved, will sync"
- "Hidden for you · your family still sees these" + "You hid this"
- "Processed by Claude on your device" + "Acts only within what you can see" (W3 — on-device
  processing and the bounded-to-submitter guard are the true boundaries)
- "Location & EXIF removed before it leaves your phone" (W1 — an enforced client strip)
- "Only your family can see this" (W1 — sharing scope, true)

Deliberately **avoided**: "stored only on your device" (the encrypted copy lives in
Cloudflare R2 so the family can see it), and any trigger-engine claim on a checklist.

## Per-screen Compose + accessibility mapping

**States (P1)** — optimistic write: `remember` + `LaunchedEffect`; saving hairline:
`LinearProgressIndicator`; offline banner: `TopAppBar` sub-row; queue pill: `AssistChip`;
undo/failed: `SnackbarHost`. State is never colour-alone (glyph + label + strike); Retry is
a focusable `Button`; reduced-motion drops the sweep + overshoot.

**Todo (P2/P3)** — row: `ListItem(48dp)` + `Checkbox`; overshoot: `animateFloatAsState`
spring; strike: `drawWithContent` + `animateFloat`; fold-away: `AnimatedVisibility`
(`expandV`); done section: expandable `Surface` (`Role.Button`); remote diff:
`collectAsState` + `animateItem`. Each row exposes `Role.Checkbox` + state; remote actor in
the accessible label ("checked by Mom, just now"); no notification, streak, or per-person score.

**Delete-Hide (P4/P5)** — delete warn: `ModalBottomSheet`; action sheet: `ModalBottomSheet`
+ `ListItem`; swipe to hide: `SwipeToDismissBox`; overflow path: `DropdownMenu` + `ListItem`;
hide fold: `AnimatedVisibility`; hidden section: expandable dashed `Surface`; ACL gate:
`enabled = canDelete`. Delete button labelled with the consequence, focus lands on "Keep it";
Hide reachable by swipe *and* overflow; each hidden item announces "You hid this".

**Author (P5/W2)** — note editor: `OutlinedTextField` + Markdown; live preview:
`AndroidView` / `RichText`; to-do builder: `LazyColumn` + `TextField`; link add: `TextField`
+ `UrlPreviewCard`; commit: `Button onClick → repo.save()`; no-author: `if (canWrite)
FloatingActionButton`. Standard `TextField` semantics; Save announces "Saved, shared with
your family"; the read-only hub exposes no disabled controls; one commit, one confirmation.

**Add-Context (P6/W3)** — composer: single `TextField`; working state: placeholder + shimmer
`Brush`; result card: `ElevatedCard` + `AssistChip`; provenance: "Added by Claude" label;
bounded command: scoped to submitter ACL; async loop: `WorkManager` + tool-use run. The
working placeholder announces politely and is not framed as a chat awaiting reply; honesty
chips are real text in the tree; no notification on resolve.

**Media-Update (P7/W1)** — picker: `PhotoPicker` (system); strip + crypto: `ExifInterface`
+ Tink; upload: encrypted → Cloudflare R2; pending hero: `Box` +
`LinearProgressIndicator`; accent extract: `Palette.from(bitmap)`; hero: `AsyncImage` ·
`ContentScale`; Web absence: `if (!canCapture) PromptFromApp`. "Set hero photo" is a labelled
`Button`; pending announces "Removing location, encrypting, uploading"; the privacy chip is
real text; on Web the "add from app" prompt is text, never a dead button.

## Constraints (non-negotiable, carried throughout)

Honest chips only where a real boundary enforces them (ADR 0022 D4). Accessibility:
`Role.Checkbox` + state, never colour alone, ≥48dp targets, reduced-motion drops overshoot,
haptics honour the OS. Calm / constitution: no streaks, nags, scoreboards, per-person
completion rates, member-activity notifications, or conflict modals. Reuse — never reinvent —
the M3 Expressive system. Seed colors + type unchanged: Coral #FF5436 / Teal #11B5A4 /
Violet; Outfit + Figtree + Material Symbols Rounded; light is hero, dark first-class.
