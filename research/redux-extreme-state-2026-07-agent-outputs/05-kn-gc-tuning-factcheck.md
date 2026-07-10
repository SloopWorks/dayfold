# Agent memo 5/5 — Kotlin/Native GC & immutability mitigations (follow-up fact-check)

Follow-up fleet output to `research/2026-07-10-redux-extreme-state-architecture.md`
(extends its §5.3 / R6): operator asked to brainstorm overcoming the K/N
GC bottleneck for per-dispatch immutable-state allocation on iOS. Produced
2026-07-10 by a web-research agent; unedited. Claims labeled `[fact:URL]`
/ `[estimate]` / `[assumption]`.

**Version context:** Dayfold pins Kotlin **2.3.20**. Kotlin **2.4.0 shipped
2026-07-06** and changes the GC default — noted where relevant.
[fact: https://kotlinlang.org/docs/whatsnew24.html]

## 1. GC algorithm status

- **Architecture:** K/N uses a **tracing, non-generational, non-moving,
  stop-the-world-bounded mark + concurrent sweep** collector over a single
  shared heap; GC runs on its own thread, triggered by memory-pressure
  heuristics or a timer. [fact: https://kotlinlang.org/docs/native-memory-manager.html]
- **Variants & binary options** (`kotlin.native.binary.gc=` in `gradle.properties`):
  - `cms` — **concurrent mark** & concurrent sweep: mark phase runs concurrently with app threads; mark queue drained in parallel across app threads + GC thread + marker threads; weak refs processed concurrently. [fact: https://kotlinlang.org/docs/native-memory-manager.html]
  - `pmcs` — parallel mark (all threads paused for the whole mark phase) + concurrent sweep. [fact: https://kotlinlang.org/docs/whatsnew24.html]
  - `noop` — GC disabled entirely; memory grows monotonically; testing/short-lived processes only. [fact: https://kotlinlang.org/docs/native-memory-manager.html]
  - `stwms` (full stop-the-world mark-sweep) existed historically as an internal option; it is not in the current docs page — treat as unsupported. [estimate]
- **Defaults timeline:** CMS introduced experimentally in **2.0.20**; **PMCS is the default on Kotlin 2.2.x/2.3.x (i.e., on Dayfold's 2.3.20)**; **CMS became the default in 2.4.0** ("After processing user feedback and fixing regressions, we are now ready to enable CMS by default, starting with Kotlin 2.4.0"; revert with `kotlin.native.binary.gc=pmcs`). Roadmap item for the flip: **KT-71278** (done). [fact: https://kotlinlang.org/docs/whatsnew24.html] [fact: https://kotlinlang.org/docs/roadmap.html] — Practical implication: on 2.3.20 you can opt in today with `kotlin.native.binary.gc=cms`.
- **Generational / moving / compacting:** **None shipped and none on the public roadmap.** The docs explicitly say the collector "does not separate the heap into generations." The Feb-2026 roadmap lists **no** K/N memory-management items at all (only Swift export Alpha KT-80305, Swift 6.3 KT-84570, debugger KT-84572, SPM import KT-53877). [fact: https://kotlinlang.org/docs/native-memory-manager.html] [fact: https://kotlinlang.org/docs/roadmap.html] The 2021 design blog stated the runtime infrastructure is deliberately built for **pluggable GC algorithms**, leaving generational/moving open as a possibility, but no public YouTrack ticket dedicated to generational GC surfaced — the closest umbrella issues are **KT-42296** ("Prototype a new garbage collector") and **KT-55512** (robustness/perf umbrella), plus feature tickets KT-57771 (parallel mark), KT-57772 (concurrent weak refs), KT-57773 (scheduler). [fact: https://blog.jetbrains.com/kotlin/2021/05/kotlin-native-memory-management-update/] [fact: https://youtrack.jetbrains.com/issue/KT-42296] [fact: https://youtrack.jetbrains.com/issue/KT-55512] [assumption: absence of a ticket ≠ absence of internal exploration]

## 2. Allocator

- The **custom paged allocator is the default since Kotlin 1.9.20**, replacing mimalloc "to make garbage collection more efficient." It divides system memory into **pages swept independently in consecutive order**; each allocation is a block within a size-class-specific page, enabling cheap bump-style allocation and efficient iteration — this is exactly the design that keeps *small short-lived object* allocation cheap in a non-generational world. It also has "stop-the-world protection against sudden allocation spikes." [fact: https://kotlinlang.org/docs/whatsnew1920.html] [fact: https://kotlinlang.org/docs/native-memory-manager.html] [fact: https://github.com/JetBrains/kotlin/blob/master/kotlin-native/runtime/src/alloc/custom/README.md] [fact: https://youtrack.jetbrains.com/issue/KT-55364]
- **Options:** `kotlin.native.binary.pagedAllocator=false` (Experimental, formalized in 2.2.0) reserves memory **per-object** instead of paging — lowers idle footprint, "designed to replace the `-Xallocator=std` compiler option," but disables Apple memory tagging. The old `-Xallocator=mimalloc|std` flags existed in the 1.9.x era; current docs no longer list them (mimalloc removed from the runtime in the 2.x line [estimate]). [fact: https://kotlinlang.org/docs/whatsnew22.html] [fact: https://kotlinlang.org/docs/whatsnew1920.html]
- Related 2.2.0 memory knobs: `kotlin.native.binary.mmapTag` (default 246; Kotlin allocations visible in Xcode VM Tracker), `disableMmap=true` (malloc instead of mmap), `latin1Strings=true` (experimental 1-byte strings). [fact: https://kotlinlang.org/docs/whatsnew22.html]

## 3. Tuning knobs

`kotlin.native.runtime.GC` (formerly `kotlin.native.internal.GC`; `@NativeRuntimeApi`, since 1.9): [fact: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native.runtime/-g-c/]

- `targetHeapBytes`, `minHeapBytes`, `maxHeapBytes`, `targetHeapUtilization`, `autotune` (default true — after each GC, `targetHeapBytes = heapBytes / targetHeapUtilization`, clamped to min/max), `heapTriggerCoefficient`, `regularGCInterval` (timer firing between 1× and 2× the interval), `pauseOnTargetHeapOverflow` (block mutators on overflow until GC finishes).
- `collect()` (force + wait), `schedule()` (request, non-blocking), `lastGCInfo: GCInfo?`.
- **`GCInfo` does expose pause times**: `epoch`, `startTimeNs`/`endTimeNs`, `firstPauseRequestTimeNs`/`firstPauseStartTimeNs`/`firstPauseEndTimeNs`, optional second-pause triple, `postGcCleanupTimeNs`, `rootSet`, `markedCount`, `sweepStatistics` and `memoryUsageBefore/After` per memory pool — pause duration = `firstPauseEndTimeNs − firstPauseStartTimeNs`. [fact: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native.runtime/-g-c-info/]
- `kotlin.native.binary.appStateTracking=enabled` (experimental): **disables timer-based GC while the app is backgrounded**, collecting only on memory pressure. [fact: https://kotlinlang.org/docs/native-ios-integration.html]
- `kotlin.native.binary.gcMarkSingleThreaded=true`: disables mark parallelization (may lengthen pauses on big heaps). [fact: https://kotlinlang.org/docs/native-memory-manager.html]
- **No supported "defer GC during a critical window" API.** `GC.suspend()`/`resume()`/`start()`/`stop()` exist but are **deprecated and unused**. Practical proxies: raise `targetHeapBytes`/lengthen `regularGCInterval` around the window, or call `GC.schedule()` at a convenient moment; `gc=noop` is whole-binary only. [fact: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native.runtime/-g-c/] [estimate on workarounds]

## 4. Observability on iOS

- **`kotlin.native.binary.enableSafepointSignposts=true`** emits GC pauses as os_signpost events; in Instruments use the **os_signpost template, subsystem `org.kotlinlang.native.runtime`, category `safepoint`** — each blob on the track is a GC pause, correlatable with hangs/hitches. [fact: https://kotlinlang.org/docs/native-memory-manager.html]
- GC logging: compiler arg **`-Xruntime-logs=gc=info`** (stderr only). [fact: https://kotlinlang.org/docs/native-memory-manager.html]
- Kotlin-tagged VM regions visible in **VM Tracker** via mmap tag 246 (since 2.2.0). [fact: https://kotlinlang.org/docs/whatsnew22.html]
- Programmatic: `GC.lastGCInfo` in-app (e.g., log pause > threshold). [fact: GCInfo doc above]

## 5. Published performance data

- **JetBrains' only concrete GC pause numbers** are in the Compose Multiplatform 1.7.0 post (with Kotlin 2.0.20 CMS): on the LazyGrid scrolling benchmark, **missed frames cut roughly in half and worst-p25 GC pause dropped 1.7 ms → 0.4 ms**; overall LazyGrid ~9% faster with average frame time under the 8.33 ms 120 Hz budget; VisualEffects 3.6× faster. [fact: https://blog.jetbrains.com/kotlin/2024/10/compose-multiplatform-1-7-0-released/]
- Kotlin 2.4.0 notes CMS "has already demonstrated its effectiveness in benchmarks for UI applications built with Compose Multiplatform" and matters "for latency-critical applications." [fact: https://kotlinlang.org/docs/whatsnew24.html]
- **No JetBrains-published head-to-head of K/N GC vs JVM/ART allocation throughput** was found; community comparisons say KMP-on-iOS is "noticeably slower only on cold start and in bridging-heavy scenarios." [estimate; https://blog.jacobstechtavern.com/p/swift-for-android-vs-kmp] Sub-millisecond typical pauses under CMS for UI-scale heaps is a reasonable expectation from the 1.7.0 data, but per-dispatch allocation cost of Redux-style copying has no published benchmark — measure with `lastGCInfo` + signposts. [assumption]

## 6. kotlinx.collections.immutable

- Multiplatform persistent collections (all K/N targets). `PersistentList` is a bit-partitioned vector trie; `PersistentHashMap/Set` are CHAMP-style hash-array-mapped tries; ordered `persistentMapOf/setOf` defaults preserve insertion order at extra structural cost — use `persistentHashMapOf/persistentHashSetOf` when order is irrelevant. Structural sharing means O(log n) copy-on-write per update. README still calls implementations "prototype." [fact: https://github.com/Kotlin/kotlinx.collections.immutable] [estimate on internal structure names — README doesn't spell out CHAMP]
- **No officially published benchmark numbers**; a benchmark suite exists in-repo (`benchmarks/`, incl. time & memory per op). [fact: https://github.com/Kotlin/kotlinx.collections.immutable/blob/master/benchmarks/commonMain/src/benchmarks/immutableList/Add.kt]
- **No documented K/N-specific GC caveat** was found; known perf traps are API-level and platform-neutral: stdlib operators (`map`, `filter`, `intersect`, `+`) on a `PersistentList` fall back to generic `Iterable` paths and return ordinary lists — `Iterable.intersect` with a `PersistentList` is pathologically slow (issue #64); use `mutate {}`/`builder()` for batch updates. [fact: https://github.com/Kotlin/kotlinx.collections.immutable/issues/64] [fact: https://github.com/Kotlin/kotlinx.collections.immutable/issues/181]

## 7. Swift interop cost

- **Crossing the boundary allocates.** Kotlin primitives in generic/nullable/function-type positions box to `KotlinInt` etc. (NSNumber subclasses); `String` → `NSString` conversion produces an ObjC object which Swift then **copies again** for `Swift.String`; Kotlin collections implicitly convert to Swift `Array/Dictionary` with a per-crossing cost (casting to `NSDictionary`/`NSString` avoids the second Swift-side conversion). [fact: https://kotlinlang.org/docs/native-objc-interop.html] [fact: https://www.powersync.com/blog/building-a-swift-sdk-with-skie-lessons-in-bridging-kotlin-and-swift]
- **A pure-Kotlin Redux loop rendering via Compose Multiplatform never bridges state objects to Swift**, so it avoids all of this — only the app-entry/platform seams cross. [fact: interop docs above; the mapping applies only to framework-exported API] [assumption: dayfold's architecture keeps state out of Swift]
- **Swift export** (direct Kotlin→Swift modules without ObjC headers): first public/experimental in 2.2.20, **promoted to Alpha in 2.4.0** (roadmap KT-80305) with `suspend`→`async` and `Flow`→`AsyncSequence`. No published claims that it reduces *allocation* costs of bridging yet. [fact: https://kotlinlang.org/docs/whatsnew24.html] [fact: https://kotlinlang.org/docs/roadmap.html] [assumption on allocation impact]

## 8. "Mutable inside, immutable outside" / pooling guidance

- **No official JetBrains K/N-specific guidance** recommending object pooling or internal mutability for GC pressure was found. The docs' performance advice is limited to GC/allocator tuning (§3). [fact: https://kotlinlang.org/docs/native-memory-manager.html]
- Community/ecosystem guidance: *Effective Kotlin* Item 47 covers object pooling as "powerful but dangerous" for perf-critical paths; the Compose world's standard pattern is the inverse of pooling — immutable public state + `mutableStateOf`/snapshot state internally, precisely because "each state change is an object allocation" (e.g., tunjid's snapshot-state article proposes generating mutable snapshot variants from immutable interfaces). [fact: https://kt.academy/article/ek-unnecessary-objects] [fact: https://www.tunjid.com/articles/embracing-compose-snapshot-state-for-ui-layer-state-production-6a078c7d64469a118e42eecf]
- For Redux-per-dispatch copies specifically: persistent collections (§6) + K/N's paged allocator + CMS is the supported path; pooling immutable state objects is not an established K/N technique. [estimate]

**Bottom line for Dayfold (Kotlin 2.3.20):** default GC is still PMCS — opt into `kotlin.native.binary.gc=cms` now or upgrade to 2.4.0 where it's default; no generational GC exists or is publicly planned, so short-lived per-dispatch garbage is swept, not promoted — keep state deltas structurally shared (persistent collections), keep the loop out of Swift, and measure with safepoint signposts + `GC.lastGCInfo`.
