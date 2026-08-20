# Local search — design notes (ADR 0008 exploration)

Built from `designs/DESIGN-BRIEF-local-search.md` (Prompt 1) against
`research/2026-08-19-local-search-exploration.md`. The operator accepted the
reviewed hi-fi, including the teal close-match revision and plain mixed-list
default, and explicitly directed implementation on 2026-08-19. That directive
is the ADR 0008 build authorization for this scope. Prompt 2 (adversarial
completeness) and Prompt 3 (simplification) are recorded below.

**Fixtures are synthetic.** Every family member, Hub, card, block, file, contact
and message in these pages is invented for this exploration. No real user data,
no production content, and no fixture copied from a live database appears
anywhere. The prototypes perform no product network request, persistence,
telemetry, or search: each state is a drawn frame. Like the neighboring
`.dc.html` galleries, their shell loads its runtime and webfonts from a CDN.

## Files

| File | Covers |
|---|---|
| `Index.dc.html` | Gallery: the mark explained, interactive state switcher (light + dark side by side), a four-frame density comparison, links to the interactive boards, and the brief's seven decisions |
| `Search-Phone.dc.html` | The reusable compact component — both entry surfaces and all ten search-route states (props `mode`, `view`) |
| `Result-Arrival.dc.html` | Live result → canonical destination journey, three result kinds, three arrivals; back restores query + list scroll |
| `Adaptive.dc.html` | Compact push, medium two-pane (live keyboard traversal + query narrowing), expanded drawer layout, keyboard/pointer map |
| `Match-Modes.dc.html` | Lexical / bounded fuzzy / semantic-only comparison, what each row may claim, and the rejected treatments |
| `support.js` | The shared `.dc.html` runtime, copied unchanged from `designs/calendar-reconciliation/support.js` |

## State map

`Search-Phone.dc.html` · props: `mode` = `light` \| `dark`, `view` = one of:

| # | Brief requirement | `view` | Notes |
|---|---|---|---|
| 1 | Entry from Now | `now-entry` | Now feed with the new app-bar search action; bottom nav visible |
| 1 | Entry from Hubs | `hubs-entry` | Same action, same route |
| 1 | Focused route, no results before meaningful input | `field-empty` | Keyboard up, placeholder, `On this device`, scope taught by example |
| 1 | One-character threshold | `typing` | `s` is visible; teaching state holds and matching begins after two non-space characters |
| 2 | Exact mixed results — `soccer` | `results` | Hub + card + checklist + section + contact, filter chips, keyboard up |
| 9 | Chip-free baseline density | `results-plain` | Same results, no chips, keyboard dismissed |
| 3 | Deep phrase — `permission slip` | `phrase` | Strongest hit is a block whose Hub title contains neither word |
| 4 | Typo fallback — `socer` | `fuzzy` | Fallback heading, `Close match` per row, mark on the real word, field unchanged |
| 5 | No results — `trombone` | `no-results` | One sentence + one suggestion |
| 6 | Offline | `offline` | Same results and ranking; only the status line swaps |
| 7 | Empty cache | `empty-cache` | Back available, no broken primary action |
| 8 | Archived result | `archived` | `tahoe`: archived Hub ranked on merit, quietly labelled, mixed with live content |

Arrival, adaptive, and match-mode states live in their own files (above).

## The result row

```
[glyph]  Title — dominant, Outfit 16/600, marks where the words really are
 36×36   Hub › Section breadcrumb · optional state label
 tonal   One-to-two-line excerpt built around the strongest actual match
```

- **Leading glyph** — kind by symbol (`today` card, `dashboard` Hub, `segment`
  section, `checklist` list, `sticky_note_2` note, `description` file, `link`,
  `person` contact); **home surface by colour** — primary/coral for anything
  that lives in Now, secondary/teal for anything inside a Hub. The container is
  always `surfaceContainerHigh`, so the only saturated fill in a row is the
  match mark.
- **The mark** — exact text uses `primaryContainer` + an inset 2px `primary`
  rule (`#4E1000` on `#FFDAD2` light; `#FFEDE8` on `#9A2A12` dark). A
  `Close match` uses the same structure with `secondaryContainer` + `secondary`
  (`#00201C` on `#9DF2E4`; `#A8F4E7` on `#005048`). The heading and label remain,
  so hue is never the only cue. Semantic-only rows remain unmarked.
- **State labels** — `Close match` and `Archived`, both in the same
  quiet `surfaceContainerHigh` pill, so no label can outshout the match. No
  score, percentage, or model name exists anywhere in these files.
- **No chevron.** The glyph already reads as "this opens something", and one
  arrow per row is noise multiplied by the list length. Recorded as decision
  question 4 so it can be reversed cheaply.
- **No result count.** A count is a metric, and a metric invites optimising the
  list rather than leaving it.

## M3 → Compose mapping

| Prototype element | Material 3 | Compose |
|---|---|---|
| App-bar search action | Icon button in the top app bar | `IconButton` in `TopAppBar` actions |
| Focused search route | Search bar, expanded | `SearchBar` (expanded) / `DockedSearchBar` at medium+ |
| Trust / status line | Supporting text | `Text(labelMedium)` + `Icon` |
| Scope chips | Filter chips | `FilterChip` in a `LazyRow` |
| Result row | List item, 3-line | `ListItem` inside `LazyColumn` |
| Match mark | Inline annotated span | `AnnotatedString` + `SpanStyle(background, textDecoration = Underline)` |
| State label | Suggestion chip (compact) / label | `Text(labelSmall)` on a tonal `Surface` |
| Fallback heading | List sub-header | `Text(labelMedium)` sticky header |
| No-result / empty-cache | Empty state | `Column` (icon + headline + supporting text) |
| Search → destination | Container transform | `SharedTransitionLayout` (the Now deep-link path) |
| Arrival pulse | Highlight-once | `animateColorAsState` / one-shot `InfiniteTransition`-free tween |
| Medium / expanded panes | List-detail | `ListDetailPaneScaffold` |
| Rail / drawer | Navigation rail, drawer | `NavigationRail`, `PermanentNavigationDrawer` |
| Tooltip on the search action | Plain tooltip | `TooltipBox` (pointer only) |

The bottom `NavigationBar` is deliberately **absent inside the route**: search is
a destination pushed above the tabs, which is what makes "not a third tab" true
in the layout and not only in the copy.

## Motion

| Moment | Treatment | Reduced motion |
|---|---|---|
| Now/Hubs → search route | Fade-through into the expanded search bar (M3 search-bar expand) | Plain cross-fade |
| Query → results | No transition; rows replace in place after the debounce | Same |
| Row → destination | Container transform grown from the tapped row, ~380ms emphasized `cubic-bezier(.2,0,0,1)` | Cross-fade, no scale |
| Arrival on the matched block | One pulse, 1.4s, `cubic-bezier(.4,0,.2,1)`, after a 350ms settle — **once**, never looping | Static 3px outline ring, no animation |
| Back to search | Reverse transform; query and list scroll restored for the session | Cross-fade |

`prefers-reduced-motion` is honoured in every prototype (`@media` rules disable
the pulse and the container enter; the outline ring remains so the arrival is
still legible).

## Accessibility

- Search field carries a spoken label and the clear action is a separate 48dp
  target; `arrow_back`, the field, and `close` are three distinct stops.
- A result announces in DOM order: destination kind (from the glyph's label),
  title, breadcrumb, then the excerpt — no markup tokens, no "highlighted"
  chatter. The mark is a `SpanStyle` in Compose, not a semantic element, so it
  is not read aloud as structure; `Close match` / `Archived` are real text and
  are announced.
- The mark passes contrast in both themes and carries a rule, so it is never
  hue-only. State labels are text, not colour.
- Every row and icon action is ≥48dp; chips keep a 34dp visual inside a 48dp
  target. Rows grow vertically with dynamic
  type: the title wraps rather than truncating, the excerpt is the only element
  that clips, and the field, chips, and labels have no fixed heights that would
  clip at 200% scale.
- Keyboard: ↑ ↓ traverse results without leaving the field, Enter opens, Esc
  clears the open destination and then closes the route, Tab order is field →
  chips → list → detail pane. Focus is a 3px `primary` ring; the medium frame in
  `Adaptive.dc.html` implements this for real.

## Design decisions

1. **A route, not a tab.** One icon in each existing app bar; the route hides the
   bottom bar; back returns to the originating surface. No Search tab, no
   persistent field in the feed.
2. **One mixed relevance order, no type groups.** Group headers fragment
   relevance and cost a scroll on a phone. Rows are ordered title match → body
   match → breadcrumb-only match, which is why the `soccer` board leads with the
   Hub, then the Now card, then the checklist block. This is a small departure
   from the order the brief listed those three examples in; ranking by field
   weight is what the research report specifies, and a list that ranks by
   anything a reader cannot see is the thing that makes search feel arbitrary.
3. **The mark travels.** The same wash-plus-rule structure appears in title,
   breadcrumb, or excerpt — wherever the words really are. Coral means exact;
   teal plus explicit disclosure means a stored-word close match. Its absence is
   information.
4. **A typo is disclosed, never corrected.** The field keeps `socer`, the
   fallback set gets its own heading, and each row carries `Close match` while
   the mark sits on `soccer`.
5. **Case- and punctuation-folding are visible.** `tahoe` marks `Tahoe`, and
   `permission slip` marks `permission-slip` in a filename, because a hyphen is
   a token boundary. Both are honest — the characters are there.
6. **One trust slot, never two claims.** `On this device` normally,
   `Offline · searching saved content` when offline. Never both, never per-row
   privacy warnings, and never "no data leaves your phone" — the search is
   local, the content was already synced.
7. **The pulse marks only what matched.** Block result → scroll and pulse once.
   Hub-title result → open at the top, no interior highlight. Section result →
   land at the header. Inventing a pulse for a container that did not match
   would teach people to distrust the pulse.
8. **Nothing is stored.** No recent searches, no query in logs, analytics, bug
   reports, or the outbox. The empty state teaches scope instead of replaying a
   family's search history.
9. **Semantic stays on the exploration board.** Neutral `Related result` label,
   no mark, ranked under lexical and fuzzy, and explicitly labelled as not
   shipped. The rejected treatments are written down on that board so the
   decision is arguable rather than assumed.
10. **Archived in, hidden out.** Archived Hubs are exactly what people search
    for; device-hidden content stays out of ordinary results, matching the
    research report's default.

## Operator review — 2026-08-19

- Breadcrumb/location context: accepted (“I think so”).
- Compact row: LGTM, including the no-chevron treatment.
- Offline behavior: LGTM.
- Filters: plain mixed list accepted as the default; chips remain exploration
  only.
- Close match: operator requested current-industry review and consideration of
  another highlight color. The gallery now uses a secondary-teal wash/rule plus
  the existing `Close matches for…` heading and per-row `Close match` label. The
  operator accepted that revision and directed implementation.

The close-match synthesis follows three current patterns: typo tolerance is a
baseline mobile-search capability; exact hits stay above typo hits; and the UI
discloses the interpretation instead of silently rewriting the query. Algolia's
current docs rank zero-typo hits above one- and two-typo hits and explicitly let
custom UIs vary typo highlighting; Baymard's mobile research favors keeping
useful corrected results available so people do not have to edit the query.
Sources: [Algolia typo tolerance](https://www.algolia.com/doc/guides/managing-results/optimize-search-results/typo-tolerance),
[Algolia typo highlighting](https://support.algolia.com/hc/en-us/articles/6558731269649-How-do-I-prevent-highlighting-results-with-typos),
[Baymard misspelling UX](https://baymard.com/blog/offer-autocomplete-suggestions-for-misspellings).

## Unresolved questions

1. **Result-kind colour** — coral for Now, teal for Hubs reads well here, but it
   is a third meaning for those two roles in the app. Worth checking against the
   Now/Hubs surfaces before it becomes a convention.
2. **`⌘F` on desktop/tablet** is drawn in the keyboard map but not specified
   anywhere in the product — it needs confirming, not assuming.
3. **Sections as results.** A section row is useful but its excerpt is
   necessarily generic; dogfooding should say whether sections earn their rows or
   should fold into their block hits.

## Prompt 2 adversarial completeness pass

A fresh Claude Code review returned `NEEDS CHANGES`. The corrected findings
were: remove a semantic example that accidentally shared the exact token
`game`; make the block-arrival ring clear after its one-shot pulse; preserve the
same result set offline; add the one-character threshold state; clamp excerpts
and show the tail of long queries; put the 34dp chip visual inside a 48dp target;
remove a nonexistent chip stop from the adaptive keymap; qualify the prototype's
CDN usage; and hide the field caret when the keyboard is dismissed. The long
contact fixture now exercises a wrapping title and a breadcrumb deeper than
`Hub › Section`. The review found no permission leak, prohibited product
network/storage/telemetry work, misleading fuzzy mark, or contrast failure.

## Prompt 3 simplification pass

A second fresh Claude Code review returned `SIMPLIFY`. The gallery now uses the
interactive light/dark switcher as its single source for all required states;
the duplicated full-state wall was reduced to the one comparison that needs
adjacency: chips versus the plain list. Chips now appear only in that comparison,
and the filtered-state duplicate was removed. Numbered state pointers,
decorative masthead badges, repeated engineering tokens, redundant arrival
copy, dead `Offline copy` fixture/copy, and repeated semantic explanations were
cut. Decision answers and edge-state copy were shortened. The section result
was deliberately kept because the arrival board must still make section landing
reviewable; the long contact fixture was kept as the wrap/deep-breadcrumb stress
case. The recommended product default is now unambiguous: plain mixed results,
with filters deferred until evidence shows they improve retrieval.
