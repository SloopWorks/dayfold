var __defProp = Object.defineProperty;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __esm = (fn, res) => function __init() {
  return fn && (res = (0, fn[__getOwnPropNames(fn)[0]])(fn = 0)), res;
};
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};

// node_modules/@sloopworks/swip-js/src/types.ts
var PESSIMISTIC_CONSENT;
var init_types = __esm({
  "node_modules/@sloopworks/swip-js/src/types.ts"() {
    PESSIMISTIC_CONSENT = {
      analytics: "unknown",
      telemetry: "unknown",
      errors: "unknown"
    };
  }
});

// node_modules/@sloopworks/swip-js/src/ulid.ts
function defaultFillRandom(bytes) {
  crypto.getRandomValues(bytes);
}
function makeUlidFactory(deps) {
  const now = deps?.now ?? Date.now;
  const fillRandom = deps?.fillRandom ?? defaultFillRandom;
  let lastTime = -1;
  const rand = new Uint8Array(10);
  return () => {
    const time = now();
    if (time === lastTime) {
      for (let i = 9; i >= 0; i--) {
        rand[i] = rand[i] + 1 & 255;
        if (rand[i] !== 0) break;
      }
    } else {
      fillRandom(rand);
      lastTime = time;
    }
    let out = "";
    for (let shift = 45; shift >= 0; shift -= 5) {
      out += B32[Math.floor(time / 2 ** shift) & 31];
    }
    let acc = 0;
    let bits = 0;
    for (const byte of rand) {
      acc = acc << 8 | byte;
      bits += 8;
      while (bits >= 5) {
        out += B32[acc >>> bits - 5 & 31];
        bits -= 5;
      }
    }
    return out;
  };
}
var B32;
var init_ulid = __esm({
  "node_modules/@sloopworks/swip-js/src/ulid.ts"() {
    B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  }
});

// node_modules/@sloopworks/swip-js/src/pipeline.ts
var Pipeline;
var init_pipeline = __esm({
  "node_modules/@sloopworks/swip-js/src/pipeline.ts"() {
    init_types();
    Pipeline = class {
      #opts;
      #consent = { ...PESSIMISTIC_CONSENT };
      #preConsent = /* @__PURE__ */ new Map();
      #queue = [];
      #hintUntil = 0;
      #hintTimer = null;
      #batches = [];
      #draining = null;
      #retryTimer = null;
      #tickerTimer = null;
      #ackedIds = /* @__PURE__ */ new Set();
      #deadIds = /* @__PURE__ */ new Set();
      #shut = false;
      #persistedIds = /* @__PURE__ */ new Set();
      #noDisk = false;
      /**
       * Bumped by [purge]. Every staging write snapshots it before `store.put()` and
       * re-checks it after: a purge that landed while the write was in flight swept a
       * store that did not contain that row yet, so adopting the row would leave a
       * purged, distinct-id-bearing batch on disk for recover() to SHIP at next launch
       * — a privacy violation, not a lost event (ADR-0018). Mirrors the Kotlin
       * `purgeEpoch` + `adoptOrPurged()` guard in swip-core Pipeline.kt.
       */
      #purgeEpoch = 0;
      /**
       * Serialized tail of every disk-staging operation (`#persistFormed`, the purge
       * sweep). Two overlapping `#persistFormed()` runs would both snapshot
       * `#persistedIds` before either added to it and re-`put()` the same batch (a
       * duplicate IDB write that also re-stamps `persistedAt`, silently extending
       * age-based retention). Chaining also gives `purge()` something to await: an
       * in-flight `put()` completes BEFORE the sweep lists the store, so the sweep
       * deletes it rather than racing it.
       */
      #persistTail = Promise.resolve();
      #recovering = null;
      #stats = {
        drops: { consent_denied: 0, overflow: 0, dead_letter: 0 },
        counters: { flush_failures: 0, storage_errors: 0 },
        queued: 0
      };
      constructor(options) {
        this.#opts = {
          maxQueueEvents: 512,
          flushAtEvents: 30,
          flushIntervalMs: 3e4,
          baseRetryDelayMs: 1e3,
          maxRetryDelayMs: 3e5,
          maxAttempts: 96,
          maxOfflineBytes: 2 * 1024 * 1024,
          maxOfflineAgeMs: 72 * 36e5,
          ...options
        };
        this.#armTicker();
      }
      health() {
        return { ...this.#stats, queued: this.#queue.length };
      }
      setConsent(consent) {
        this.#consent = { ...consent };
        for (const [scope, buffered] of [...this.#preConsent.entries()]) {
          const decision = this.#consent[scope];
          if (decision === "unknown") continue;
          this.#preConsent.delete(scope);
          if (decision === "denied") {
            this.#stats.drops.consent_denied += buffered.length;
            continue;
          }
          for (const event of buffered) this.#enqueueGranted(event);
        }
      }
      /** Never throws (INVARIANT 13): errors become drop counters, not exceptions. */
      /**
       * `critical` = write-through durability (ADR-0017). WHAT IS ACTUALLY GUARANTEED
       * ON RETURN, precisely — because a comment promising more than the code delivers
       * is how §8.2 happened:
       *
       *  - the event has left the volatile ring and is in a FORMED batch (immutable
       *    batch_id, INVARIANT 1), and
       *  - its disk write is QUEUED, ahead of every write initiated after it.
       *
       * NOT guaranteed on return: that the bytes are on disk. They cannot be. enqueue()
       * is sync-returning and non-throwing by contract (INVARIANT 13 forbids main-thread
       * I/O at an SDK entry point) and the web's only persistence backend — IndexedDB —
       * has no synchronous API. Kotlin's store is a blocking SQLDelight call, so Kotlin
       * really does persist before returning; TypeScript cannot.
       *
       * The awaitable path for callers that need the real guarantee (a serverless
       * handler about to freeze, a test): `whenDurable()`.
       */
      enqueue(event, scope, tier = "normal", critical = false) {
        try {
          const decision = this.#consent[scope];
          if (decision === "denied") {
            this.#stats.drops.consent_denied += 1;
            return;
          }
          if (decision === "unknown") {
            const buffer = this.#preConsent.get(scope) ?? [];
            buffer.push(event);
            if (buffer.length > this.#opts.maxQueueEvents) {
              buffer.shift();
              this.#stats.drops.overflow += 1;
            }
            this.#preConsent.set(scope, buffer);
            return;
          }
          this.#enqueueGranted(event, tier);
          if (critical) {
            this.#formBatches();
            void this.#schedulePersist();
          }
        } catch {
          this.#stats.counters.flush_failures += 1;
        }
      }
      #enqueueGranted(event, tier = "normal") {
        this.#queue.push({ event, lazy: tier === "lazy" });
        if (this.#queue.length > this.#opts.maxQueueEvents) {
          this.#queue.shift();
          this.#stats.drops.overflow += 1;
        }
        if (tier === "realtime") {
          void this.flush();
          return;
        }
        const normalCount = this.#queue.reduce((n, q2) => n + (q2.lazy ? 0 : 1), 0);
        if (normalCount >= this.#opts.flushAtEvents) {
          this.#autoFlush();
        }
      }
      /** Trigger-driven flush: honors the server's next_flush_hint_s backpressure. */
      #autoFlush() {
        const now = this.#opts.now();
        if (now >= this.#hintUntil) {
          void this.flush();
          return;
        }
        if (this.#hintTimer === null) {
          this.#hintTimer = this.#opts.setTimeout(() => {
            this.#hintTimer = null;
            void this.flush();
          }, this.#hintUntil - now);
        }
      }
      /**
       * Requeue batches that survived process death (docs/02: inflight → pending on
       * init). They keep their ORIGINAL batch_id (INVARIANT 1) and go ahead of new
       * work. Called by Swip.init() when a persistence backend is wired; idempotent,
       * and every flush() awaits it, so a flush racing a cold start cannot start
       * sending before the recovered batches are back in the queue.
       */
      recover() {
        this.#recovering ??= this.#recoverOnce();
        return this.#recovering;
      }
      async #recoverOnce() {
        if (!this.#opts.persistence) return;
        const epoch = this.#purgeEpoch;
        try {
          const persisted = await this.#opts.persistence.list();
          for (const row of persisted) {
            if (row.state === "inflight") {
              await this.#opts.persistence.setState(row.batch.batch_id, "pending", row.attempts ?? 0);
            }
          }
          if (this.#purgeEpoch !== epoch) return;
          for (const row of persisted) this.#persistedIds.add(row.batch.batch_id);
          this.#batches = [
            ...persisted.map((row) => ({ batch: row.batch, attempts: row.attempts ?? 0, notBefore: 0 })),
            ...this.#batches
          ];
        } catch {
          this.#stats.counters.storage_errors += 1;
          this.#noDisk = true;
        }
      }
      async flush() {
        try {
          this.#opts.onFlushStart?.();
        } catch {
          this.#stats.counters.flush_failures += 1;
        }
        if (this.#recovering) await this.#recovering;
        this.#formBatches();
        await this.#schedulePersist();
        const watched = this.#batches.map((p) => p.batch);
        await this.#drain();
        let sent = 0;
        let failed = 0;
        for (const batch of watched) {
          for (const event of batch.events) {
            if (this.#ackedIds.has(event.event_id)) sent += 1;
            else failed += 1;
          }
        }
        return { sent, failed };
      }
      async shutdown(flush) {
        if (flush) await this.flush();
        this.#shut = true;
        for (const t of [this.#tickerTimer, this.#retryTimer, this.#hintTimer]) {
          if (t !== null) this.#opts.clearTimeout(t);
        }
        await this.#opts.transport.shutdown(flush);
      }
      #armTicker() {
        if (this.#opts.flushIntervalMs <= 0 || this.#shut) return;
        this.#tickerTimer = this.#opts.setTimeout(() => {
          if (this.#queue.length > 0) this.#autoFlush();
          this.#armTicker();
        }, this.#opts.flushIntervalMs);
      }
      #formBatches() {
        while (this.#queue.length > 0) {
          const events = this.#queue.splice(0, this.#opts.flushAtEvents).map((q2) => q2.event);
          this.#batches.push({
            batch: {
              v: 1,
              batch_id: this.#opts.newBatchId(),
              // minted once — immutable across retries (INVARIANT 1)
              ctx: this.#opts.ctx,
              events,
              sent_at: new Date(this.#opts.now()).toISOString()
            },
            attempts: 0,
            notBefore: 0
          });
        }
      }
      /**
       * ADR-0018 ANONYMOUS = memory-only. Rides `noDisk` — the existing "degrade to
       * memory-only" state that already gates every disk op — so no-disk needs no new field
       * or gate. It no longer doubles as the purge (that is `purge()`, which owns the sweep).
       *
       * `noDisk` IS A LATCH, NOT A SELF-HEALING FLAG, and the comment that used to say
       * otherwise was simply false. Two things set it:
       *
       *  - this method (a CollectionMode transition: ANONYMOUS → true, an upgrade → false), and
       *  - a STORAGE FAILURE (`#persistFormed`, `#persistState`, `#recoverOnce`), which latches
       *    it true FOR THE PROCESS: every disk op is gated on it and nothing retries, so one
       *    transient IndexedDB error (a private-mode tab, a quota bump, a blocked upgrade)
       *    disables persistence until the page is reloaded — or until a CollectionMode UPGRADE
       *    happens to clear it, which is a coincidence, not a recovery.
       *
       * That is the deliberate trade (INVARIANT 13: never block delivery on the store, never
       * throw out of an entry point) and Kotlin's `persistenceDead` latches identically. It is
       * written down rather than fixed because a retry loop against a store that just failed is
       * how instrumentation takes the main thread down; the `storage_errors` counter on
       * `sdk_health` is how it becomes visible instead.
       */
      setNoDisk(v) {
        this.#noDisk = v;
      }
      /**
       * Destroy queued AND persisted events (ADR-0018 / INVARIANT 29: a collection-mode
       * downgrade purges synchronously, and purged events are never resurrected).
       *
       * The in-memory half — ring buffer, formed batches, pre-consent buffers, the
       * persisted-id set — is destroyed SYNCHRONOUSLY, before this method's first
       * await, so a caller that drops the promise still cannot leak pre-downgrade
       * events into a post-downgrade batch. The disk sweep CANNOT be synchronous
       * (IndexedDB has no sync API), so "synchronous purge" on the web means
       * "awaitable, and awaited by the caller" — `setCollectionMode()` awaits it.
       *
       * Storage failures are COUNTED (`storage_errors` → `sdk_health`), never
       * swallowed: a purge that silently failed to clear disk is the exact shape of a
       * privacy incident nobody notices.
       */
      purge() {
        this.#queue = [];
        this.#batches = [];
        this.#preConsent.clear();
        this.#persistedIds.clear();
        this.#purgeEpoch += 1;
        const store = this.#opts.persistence;
        if (!store) return Promise.resolve();
        const sweep2 = this.#persistTail.catch(() => {
        }).then(async () => {
          try {
            for (const row of await store.list()) await store.delete(row.batch.batch_id);
          } catch {
            this.#stats.counters.storage_errors += 1;
          }
        });
        this.#persistTail = sweep2;
        return sweep2;
      }
      /**
       * Resolves once every staging write initiated so far has settled — the awaitable
       * half of the ADR-0017 write-through contract (see `enqueue`). Never rejects.
       */
      whenDurable() {
        return this.#persistTail;
      }
      /** Serialize staging behind the tail — see `#persistTail`. */
      #schedulePersist() {
        this.#persistTail = this.#persistTail.catch(() => {
        }).then(() => this.#persistFormed());
        return this.#persistTail;
      }
      async #persistFormed() {
        const queue = this.#opts.persistence;
        if (!queue || this.#noDisk) return;
        const epoch = this.#purgeEpoch;
        let wrote = false;
        for (const pending of [...this.#batches]) {
          if (this.#persistedIds.has(pending.batch.batch_id)) continue;
          try {
            await queue.put(pending.batch, "pending", this.#opts.now());
            if (this.#purgeEpoch !== epoch) {
              await queue.delete(pending.batch.batch_id);
              return;
            }
            this.#persistedIds.add(pending.batch.batch_id);
            wrote = true;
          } catch {
            this.#stats.counters.storage_errors += 1;
            this.#noDisk = true;
            return;
          }
        }
        if (!wrote) return;
        try {
          await queue.prune({
            maxBytes: this.#opts.maxOfflineBytes,
            maxAgeMs: this.#opts.maxOfflineAgeMs,
            now: this.#opts.now()
          });
        } catch {
          this.#stats.counters.storage_errors += 1;
          this.#noDisk = true;
        }
      }
      /** Best-effort persisted-state transition; storage failure degrades, never blocks. */
      async #persistState(batchId, action, attempts) {
        const queue = this.#opts.persistence;
        if (!queue || this.#noDisk || !this.#persistedIds.has(batchId)) return;
        try {
          if (action === "delete") {
            await queue.delete(batchId);
            this.#persistedIds.delete(batchId);
          } else {
            await queue.setState(batchId, action, attempts);
          }
        } catch {
          this.#stats.counters.storage_errors += 1;
          this.#noDisk = true;
        }
      }
      #drain() {
        this.#draining ??= this.#drainLoop().finally(() => {
          this.#draining = null;
        });
        return this.#draining;
      }
      /**
       * RETIRE THE SENT BATCH BY IDENTITY. NEVER `#batches.shift()`.
       *
       * `#batches` is not owned by the drain loop: while it is suspended inside
       * `transport.send()`, `purge()` can EMPTY the array, `#formBatches()` can push onto it,
       * and `recover()` can PREPEND to it. Index 0 when the send returns is therefore not
       * necessarily the batch that was sent — after a purge it is a DIFFERENT, UNSENT batch,
       * and `shift()` deletes it unsent, unacked and uncounted (every `health()` drop counter
       * still reads zero). That is silent, permanent event loss, and its most likely trigger is
       * the consent banner's Reject button (`setCollectionMode("ANONYMOUS")` → purge + noDisk,
       * so the batch is not even on disk for `recover()` to find).
       *
       * A batch that is gone (purged) is simply not removed — it was already destroyed, on
       * purpose (ADR-0018), and re-adding it is the one thing a purge must never allow.
       *
       * This is the port of Kotlin's `removeSentLocked` (swip-core Pipeline.kt): "Remove by
       * identity, or not at all." The two SDKs share the invariant; they now share the code.
       */
      #retireSent(head) {
        const i = this.#batches.indexOf(head);
        if (i >= 0) this.#batches.splice(i, 1);
      }
      async #drainLoop() {
        while (this.#batches.length > 0) {
          const head = this.#batches[0];
          if (head.notBefore > this.#opts.now()) return;
          if (head.attempts >= this.#opts.maxAttempts) {
            this.#stats.drops.dead_letter += head.batch.events.length;
            for (const event of head.batch.events) this.#deadIds.add(event.event_id);
            this.#retireSent(head);
            await this.#persistState(head.batch.batch_id, "delete");
            continue;
          }
          head.attempts += 1;
          await this.#persistState(head.batch.batch_id, "inflight", head.attempts);
          let result;
          try {
            result = await this.#opts.transport.send(head.batch);
          } catch {
            result = { kind: "retry" };
            this.#stats.counters.flush_failures += 1;
          }
          if (result.kind === "ok") {
            if (result.nextFlushHintS !== void 0) {
              this.#hintUntil = this.#opts.now() + result.nextFlushHintS * 1e3;
            }
            for (const event of head.batch.events) this.#ackedIds.add(event.event_id);
            this.#retireSent(head);
            await this.#persistState(head.batch.batch_id, "delete");
            continue;
          }
          if (result.kind === "drop") {
            this.#stats.drops.dead_letter += head.batch.events.length;
            for (const event of head.batch.events) this.#deadIds.add(event.event_id);
            this.#retireSent(head);
            await this.#persistState(head.batch.batch_id, "delete");
            continue;
          }
          if (head.attempts >= this.#opts.maxAttempts) {
            this.#stats.drops.dead_letter += head.batch.events.length;
            for (const event of head.batch.events) this.#deadIds.add(event.event_id);
            this.#retireSent(head);
            await this.#persistState(head.batch.batch_id, "delete");
            continue;
          }
          const window = Math.min(
            this.#opts.maxRetryDelayMs,
            this.#opts.baseRetryDelayMs * 2 ** (head.attempts - 1)
          );
          const delay = result.afterMs ?? Math.max(1, Math.round(this.#opts.random() * window));
          head.notBefore = this.#opts.now() + delay;
          await this.#persistState(head.batch.batch_id, "pending", head.attempts);
          if (this.#retryTimer !== null) this.#opts.clearTimeout(this.#retryTimer);
          this.#retryTimer = this.#opts.setTimeout(() => {
            this.#retryTimer = null;
            void this.#drain();
          }, delay);
          return;
        }
      }
    };
  }
});

// node_modules/@sloopworks/swip-js/src/coordinator.ts
var FlushCoordinator;
var init_coordinator = __esm({
  "node_modules/@sloopworks/swip-js/src/coordinator.ts"() {
    FlushCoordinator = class {
      #flushables = /* @__PURE__ */ new Set();
      #windowUntil = 0;
      #opts;
      constructor(opts) {
        this.#opts = opts;
      }
      register(flushable) {
        this.#flushables.add(flushable);
      }
      /** A pillar is flushing on its own — ride the same wake for everyone else, once per window. */
      onPillarFlush(originator) {
        const now = this.#opts.now();
        if (now < this.#windowUntil) return;
        const windowMs = this.#opts.windowMs ?? 500;
        this.#windowUntil = now + windowMs;
        this.#opts.setTimeout(() => {
          for (const flushable of this.#flushables) {
            if (flushable !== originator) void flushable.flush();
          }
        }, windowMs);
      }
    };
  }
});

// node_modules/@sloopworks/swip-js/src/analytics.ts
var MemoryStorage, KEY_INSTALLATION, KEY_DISTINCT, KEY_SESSION, SESSION_BACKGROUND_ROTATE_MS, SESSION_MAX_MS, SwipAnalytics;
var init_analytics = __esm({
  "node_modules/@sloopworks/swip-js/src/analytics.ts"() {
    init_pipeline();
    MemoryStorage = class {
      map = /* @__PURE__ */ new Map();
      get(key) {
        return this.map.get(key) ?? null;
      }
      set(key, value) {
        this.map.set(key, value);
      }
      remove(key) {
        this.map.delete(key);
      }
    };
    KEY_INSTALLATION = "swip.installation_id";
    KEY_DISTINCT = "swip.distinct_id";
    KEY_SESSION = "swip.session";
    SESSION_BACKGROUND_ROTATE_MS = 30 * 60 * 1e3;
    SESSION_MAX_MS = 24 * 60 * 60 * 1e3;
    SwipAnalytics = class {
      // protected (not private) so the CollectionMode subclass at the
      // "@sloopworks/swip-js/collection" subpath can override track()/identify()
      // with the tier logic — keeping every mode byte OUT of the 8 KB core bundle.
      // A visibility keyword is erased at emit, so this costs zero bundle bytes.
      deps;
      pipeline;
      installation = null;
      distinct = null;
      session = null;
      errors = 0;
      constructor(deps) {
        this.deps = deps;
        this.pipeline = new Pipeline({ ...deps, newBatchId: deps.ulid });
        try {
          this.distinct = deps.storage.get(KEY_DISTINCT);
          const raw = deps.storage.get(KEY_SESSION);
          if (raw) this.session = JSON.parse(raw);
        } catch {
          this.errors += 1;
        }
      }
      installationId() {
        try {
          if (!this.installation) {
            this.installation = this.deps.storage.get(KEY_INSTALLATION);
            if (!this.installation) {
              this.installation = this.deps.ulid();
              this.deps.storage.set(KEY_INSTALLATION, this.installation);
            }
          }
          return this.installation;
        } catch {
          this.errors += 1;
          return this.installation ??= "00000000000000000000000000";
        }
      }
      /** `scope` is the CONSENT scope the event rides (INVARIANT 15 — the gate is
       *  pre-queue, inside enqueue()). Error events pass "errors": a user who denied
       *  errors and allowed analytics must never have them collected (D2). */
      track(event, scope = "analytics") {
        try {
          this.pipeline.enqueue(
            {
              event_id: this.deps.ulid(),
              schema: event.schema,
              ts_wall: new Date(this.deps.now()).toISOString(),
              ts_mono_ms: this.deps.monotonicNow(),
              session_id: this.currentSessionId(),
              distinct_id: this.distinct ?? this.installationId(),
              props: event.props
            },
            scope,
            "normal",
            this.deps.criticalSchemas?.has(event.schema)
          );
        } catch {
          this.errors += 1;
        }
      }
      identify(distinctId, _traits) {
        try {
          const previous = this.distinct ?? this.installationId();
          this.track({ schema: "swip:event:identity_alias:1", props: { previous_id: previous, new_id: distinctId } });
          this.distinct = distinctId;
          this.deps.storage.set(KEY_DISTINCT, distinctId);
        } catch {
          this.errors += 1;
        }
      }
      alias(previousId) {
        try {
          this.track({
            schema: "swip:event:identity_alias:1",
            props: { previous_id: previousId, new_id: this.distinct ?? this.installationId() }
          });
        } catch {
          this.errors += 1;
        }
      }
      /** Flush first (events were consented when tracked), then clear identity + session. */
      async reset() {
        try {
          await this.pipeline.flush();
        } catch {
          this.errors += 1;
        }
        this.installation = null;
        this.distinct = null;
        this.session = null;
        try {
          this.deps.storage.remove(KEY_INSTALLATION);
          this.deps.storage.remove(KEY_DISTINCT);
          this.deps.storage.remove(KEY_SESSION);
        } catch {
          this.errors += 1;
        }
      }
      flush() {
        return this.pipeline.flush();
      }
      /**
       * Resolves once every write-through (`critical`, ADR-0017) staging write initiated
       * so far is actually on disk. track() is sync-returning and IndexedDB is async-only,
       * so this — not track()'s return — is where the durability guarantee is observable.
       * Never rejects (INVARIANT 13): a storage failure counts, it does not throw.
       */
      whenDurable() {
        return this.pipeline.whenDurable();
      }
      /**
       * Re-queue batches a PREVIOUS process persisted but never acked (docs/02). Without
       * it the PersistentQueue is WRITE-ONLY — the exact bug Kotlin fixed in 59f42cc.
       * Called by Swip.init() when a persistence backend is wired; flush() awaits it.
       */
      recover() {
        return this.pipeline.recover();
      }
      setConsent(consent) {
        this.pipeline.setConsent(consent);
      }
      onBackground() {
        try {
          this.touchSession();
          this.maybeEmitSdkHealth();
          void this.pipeline.flush();
        } catch {
          this.errors += 1;
        }
      }
      /**
       * sdk_health self-monitoring (docs/02): a silently degrading SDK is the one
       * failure nobody notices. Emitted on background, at most once per hour, only
       * when a counter is non-zero; rides the telemetry consent scope.
       */
      lastHealthAt = -Infinity;
      maybeEmitSdkHealth() {
        const now = this.deps.now();
        if (now - this.lastHealthAt < 60 * 60 * 1e3) return;
        const health = this.pipeline.health();
        const internal = this.errors;
        const errorsHealth = this.deps.errorsHealth?.() ?? {
          error_storm_evicted: 0,
          error_severity_coerced: 0
        };
        const factoryMissing = errorsHealth.errors_factory_missing ?? 0;
        const total = health.drops.consent_denied + health.drops.overflow + health.drops.dead_letter + health.counters.flush_failures + internal + errorsHealth.error_storm_evicted + errorsHealth.error_severity_coerced + factoryMissing;
        if (total === 0) return;
        this.lastHealthAt = now;
        this.pipeline.enqueue(
          {
            event_id: this.deps.ulid(),
            schema: "swip:event:sdk_health:1",
            ts_wall: new Date(now).toISOString(),
            ts_mono_ms: this.deps.monotonicNow(),
            session_id: this.session?.id,
            distinct_id: this.distinct ?? this.installationId(),
            props: {
              drops_consent_denied: health.drops.consent_denied,
              drops_overflow: health.drops.overflow,
              drops_dead_letter: health.drops.dead_letter,
              flush_failures: health.counters.flush_failures,
              internal_errors: internal,
              queued: health.queued,
              error_storm_evicted: errorsHealth.error_storm_evicted,
              error_severity_coerced: errorsHealth.error_severity_coerced,
              errors_factory_missing: factoryMissing
            }
          },
          "telemetry"
        );
      }
      onForeground() {
        try {
          this.currentSessionId();
        } catch {
          this.errors += 1;
        }
      }
      async shutdown(flush = true) {
        await this.pipeline.shutdown(flush);
      }
      currentSessionId() {
        const now = this.deps.now();
        const s = this.session;
        const expired = !s || now - s.lastSeen >= SESSION_BACKGROUND_ROTATE_MS || now - s.startedAt >= SESSION_MAX_MS;
        if (expired) {
          this.session = { id: this.deps.ulid(), startedAt: now, lastSeen: now };
        } else {
          s.lastSeen = now;
        }
        this.persistSession();
        return this.session.id;
      }
      touchSession() {
        if (this.session) {
          this.session.lastSeen = this.deps.now();
          this.persistSession();
        }
      }
      persistSession() {
        try {
          this.deps.storage.set(KEY_SESSION, JSON.stringify(this.session));
        } catch {
          this.errors += 1;
        }
      }
    };
  }
});

// node_modules/@sloopworks/swip-js/src/config/murmur3.ts
function murmur3_32(input, seed = 0) {
  const data = encoder.encode(input);
  const nblocks = data.length >>> 2;
  const c1 = 3432918353;
  const c2 = 461845907;
  let h1 = seed >>> 0;
  for (let i = 0; i < nblocks; i++) {
    let k12 = (data[i * 4] | data[i * 4 + 1] << 8 | data[i * 4 + 2] << 16 | data[i * 4 + 3] << 24) >>> 0;
    k12 = Math.imul(k12, c1) >>> 0;
    k12 = (k12 << 15 | k12 >>> 17) >>> 0;
    k12 = Math.imul(k12, c2) >>> 0;
    h1 = (h1 ^ k12) >>> 0;
    h1 = (h1 << 13 | h1 >>> 19) >>> 0;
    h1 = Math.imul(h1, 5) + 3864292196 >>> 0;
  }
  let k1 = 0;
  const tail = nblocks * 4;
  const remainder = data.length & 3;
  if (remainder >= 3) k1 ^= data[tail + 2] << 16;
  if (remainder >= 2) k1 ^= data[tail + 1] << 8;
  if (remainder >= 1) {
    k1 ^= data[tail];
    k1 = Math.imul(k1 >>> 0, c1) >>> 0;
    k1 = (k1 << 15 | k1 >>> 17) >>> 0;
    k1 = Math.imul(k1, c2) >>> 0;
    h1 = (h1 ^ k1) >>> 0;
  }
  h1 = (h1 ^ data.length) >>> 0;
  h1 ^= h1 >>> 16;
  h1 = Math.imul(h1, 2246822507) >>> 0;
  h1 ^= h1 >>> 13;
  h1 = Math.imul(h1, 3266489909) >>> 0;
  h1 ^= h1 >>> 16;
  return h1 >>> 0;
}
var encoder;
var init_murmur3 = __esm({
  "node_modules/@sloopworks/swip-js/src/config/murmur3.ts"() {
    encoder = new TextEncoder();
  }
});

// node_modules/@sloopworks/swip-js/src/config/bucketing.ts
function bucketOf(salt, unitId) {
  return murmur3_32(`${salt}.${unitId}`, 0) % 1e4;
}
function isAssigned(bucket, percent) {
  return bucket < percent * 100;
}
function multivariateVariant(bucket, weights) {
  let cumulative = 0;
  let last = "";
  for (const [variant, weight] of Object.entries(weights)) {
    cumulative += weight * 100;
    last = variant;
    if (bucket < cumulative) return variant;
  }
  return last;
}
var init_bucketing = __esm({
  "node_modules/@sloopworks/swip-js/src/config/bucketing.ts"() {
    init_murmur3();
  }
});

// node_modules/@sloopworks/swip-js/src/config/semver.ts
function segments(version) {
  const core = version.split(/[+-]/, 1)[0];
  return core.split(".").map((s) => Number.parseInt(s, 10) || 0);
}
function compareSemver(a, b) {
  const sa = segments(a);
  const sb = segments(b);
  const len = Math.max(sa.length, sb.length);
  for (let i = 0; i < len; i++) {
    const diff = (sa[i] ?? 0) - (sb[i] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}
function satisfiesVersion(version, range) {
  const match = RANGE_RE.exec(range.trim());
  if (!match) return false;
  const op = match[1] ?? "=";
  const cmp = compareSemver(version, match[2]);
  switch (op) {
    case ">=":
      return cmp >= 0;
    case "<=":
      return cmp <= 0;
    case ">":
      return cmp > 0;
    case "<":
      return cmp < 0;
    default:
      return cmp === 0;
  }
}
var RANGE_RE;
var init_semver = __esm({
  "node_modules/@sloopworks/swip-js/src/config/semver.ts"() {
    RANGE_RE = /^(>=|<=|>|<|=)?\s*(.+)$/;
  }
});

// node_modules/@sloopworks/swip-js/src/config/evaluate.ts
function attrValue(context, attr) {
  if (attr === "platform") return context.platform;
  if (attr === "app_version") return context.appVersion;
  if (attr === "os_version") return context.osVersion;
  return context.custom[attr];
}
function matchCondition(context, attr, condition) {
  const actual = attrValue(context, attr);
  if (typeof condition === "object" && "is_set" in condition) {
    return actual !== void 0 === condition.is_set;
  }
  if (actual === void 0) return "error";
  if (typeof condition === "string") {
    return VERSION_ATTRS.has(attr) ? satisfiesVersion(actual, condition) : actual === condition;
  }
  if ("neq" in condition) return actual !== condition.neq;
  if ("in" in condition) return condition.in.includes(actual);
  if ("not_in" in condition) return !condition.not_in.includes(actual);
  const n = Number(actual);
  if (Number.isNaN(n)) return "error";
  if ("lt" in condition) return n < condition.lt;
  if ("lte" in condition) return n <= condition.lte;
  if ("gt" in condition) return n > condition.gt;
  return n >= condition.gte;
}
function matchConditions(conditions, context, options, inSegment = false) {
  for (const [attr, condition] of Object.entries(conditions)) {
    let ok;
    if (attr === "segment") {
      if (inSegment) return "error";
      ok = typeof condition === "string" ? matchSegment(condition, context, options) : "error";
    } else {
      ok = matchCondition(context, attr, condition);
    }
    if (ok !== true) return ok;
  }
  return true;
}
function matchSegment(name, context, options) {
  const segment = options.segments?.[name];
  if (!segment) return "error";
  if (segment.ids && idMatches(segment.ids, segment.unit, context, options)) return true;
  if (segment.if) {
    return matchConditions(segment.if, context, options, true);
  }
  return false;
}
function idMatches(ids, unit, context, options) {
  const subject = unit !== "user" ? context.targetingKey : options.distinctId;
  return subject !== void 0 && ids.includes(subject);
}
function windowAllows(rule, options) {
  if (rule.active_from === void 0 && rule.active_until === void 0) return true;
  if (!options.now) return "error";
  const now = options.now();
  if (rule.active_from !== void 0 && now < Date.parse(rule.active_from)) return false;
  if (rule.active_until !== void 0 && now >= Date.parse(rule.active_until)) return false;
  return true;
}
function evaluateKeyInternal(spec, context, options, trace) {
  const rules = spec.rules ?? [];
  for (let idx = 0; idx < rules.length; idx++) {
    const rule = rules[idx];
    const inWindow = windowAllows(rule, options);
    if (inWindow === "error") return { value: spec.default, reason: "ERROR" };
    if (!inWindow) continue;
    if (rule.ids) {
      if (idMatches(rule.ids, rule.unit, context, options)) {
        if (trace) {
          trace.i = idx;
          trace.r = rule;
        }
        return { value: rule.value, reason: "TARGETING_MATCH" };
      }
      continue;
    }
    if (rule.if) {
      const matched = matchConditions(rule.if, context, options);
      if (matched === "error") return { value: spec.default, reason: "ERROR" };
      if (matched) {
        if (trace) {
          trace.i = idx;
          trace.r = rule;
        }
        return { value: rule.value, reason: "TARGETING_MATCH" };
      }
      continue;
    }
    if (rule.rollout) {
      const rollout = rule.rollout;
      const bucket = bucketOf(rollout.salt, context.targetingKey);
      if (isAssigned(bucket, rollout.percent)) {
        if (trace) {
          trace.i = idx;
          trace.r = rule;
          trace.b = bucket;
        }
        return { value: rollout.value, reason: "SPLIT", salt: rollout.salt, unit: rule.unit ?? "device" };
      }
      continue;
    }
    if (rule.experiment) {
      const experiment = rule.experiment;
      const bucket = bucketOf(experiment.salt, context.targetingKey);
      const variant = multivariateVariant(bucket, experiment.weights);
      if (trace) {
        trace.i = idx;
        trace.r = rule;
        trace.b = bucket;
        trace.v = Object.keys(experiment.values);
      }
      return { value: experiment.values[variant], reason: "SPLIT", variant, salt: experiment.salt, unit: rule.unit ?? "device" };
    }
  }
  return { value: spec.default, reason: "DEFAULT" };
}
function evaluateKey(spec, context, options = {}) {
  return evaluateKeyInternal(spec, context, options, void 0);
}
var VERSION_ATTRS;
var init_evaluate = __esm({
  "node_modules/@sloopworks/swip-js/src/config/evaluate.ts"() {
    init_bucketing();
    init_semver();
    VERSION_ATTRS = /* @__PURE__ */ new Set(["app_version", "os_version"]);
  }
});

// node_modules/@sloopworks/swip-js/src/config/duration.ts
function parseDuration(value) {
  const match = DURATION_RE.exec(value.trim());
  if (!match) throw new Error(`invalid duration: ${JSON.stringify(value)}`);
  return Number(match[1]) * UNIT_MS[match[2]];
}
var DURATION_RE, UNIT_MS;
var init_duration = __esm({
  "node_modules/@sloopworks/swip-js/src/config/duration.ts"() {
    DURATION_RE = /^(\d+(?:\.\d+)?)(ms|s|m|h|d)$/;
    UNIT_MS = { ms: 1, s: 1e3, m: 6e4, h: 36e5, d: 864e5 };
  }
});

// node_modules/@sloopworks/swip-js/src/config/key.ts
function extractTyped(type, r) {
  const { value } = r;
  switch (type) {
    case "boolean":
      return typeof value === "boolean" && value;
    case "string":
      return typeof value === "string" ? value : "";
    case "duration":
      try {
        if (typeof value === "string") return parseDuration(value);
        if (typeof value === "number") return value;
      } catch {
      }
      return 0;
    case "variant":
      return r.variant ?? String(value ?? "");
  }
  return value;
}
var init_key = __esm({
  "node_modules/@sloopworks/swip-js/src/config/key.ts"() {
    init_duration();
  }
});

// node_modules/@sloopworks/swip-js/src/config/facade.ts
var KEY_EXPOSURES, SwipConfig;
var init_facade = __esm({
  "node_modules/@sloopworks/swip-js/src/config/facade.ts"() {
    init_evaluate();
    init_key();
    KEY_EXPOSURES = "swip.exposures";
    SwipConfig = class {
      // True private (#) fields/methods below — never touched outside this class,
      // so esbuild's minifier safely mangles them to 1-2 char names (unlike TS
      // `private`, which is compile-time-only and NOT renamed in the output).
      #deps;
      #snapshot;
      #context;
      #exposures;
      // `${unit}|${key}|${salt}` -> variant
      #overrides = {};
      // config-debug seam (in-memory this task)
      constructor(deps) {
        this.#deps = deps;
        this.#snapshot = { revision: "none", keys: deps.defaults };
        this.#context = { ...deps.context, custom: { ...deps.context.custom } };
        this.#exposures = {};
        try {
          const raw = deps.storage.get(KEY_EXPOSURES);
          if (raw) this.#exposures = JSON.parse(raw);
        } catch {
        }
      }
      revision() {
        return this.#snapshot.revision;
      }
      /** Atomic: one object swap — no torn reads across keys. */
      setSnapshot(snapshot) {
        this.#snapshot = snapshot;
      }
      /** Merge; targeting rules re-evaluate on next read. */
      setContext(partial) {
        this.#context = {
          ...this.#context,
          ...partial,
          custom: { ...this.#context.custom, ...partial.custom ?? {} }
        };
      }
      boolean(key) {
        return extractTyped("boolean", this.#exposed(key));
      }
      string(key) {
        return extractTyped("string", this.#exposed(key));
      }
      durationMs(key) {
        return extractTyped("duration", this.#exposed(key));
      }
      json(key) {
        return this.#resolve(key).value;
      }
      /** Registry spec for a key in the active snapshot (adapters need the declared type). */
      keySpec(key) {
        return this.#snapshot.keys[key];
      }
      /** Full OpenFeature-aligned resolution details for a key (ADR-0010 adapters). Pure — no exposure. */
      resolveDetails(key) {
        return this.#resolve(key);
      }
      /** Same resolution as the getters, without recording an exposure — for adapters/debug UIs. */
      peek(key) {
        return this.#resolve(key);
      }
      /** Full resolution via the exposing funnel — for adapters (e.g. OpenFeature) whose
       *  non-variant reads must still emit exposure on SPLIT (INVARIANT 13-safe: #exposed never throws). */
      exposedDetails(key) {
        return this.#exposed(key);
      }
      /** The read IS the exposure (docs/03): dedupe per (unit, key, salt), re-emit on change. */
      variant(key) {
        return extractTyped("variant", this.#exposed(key));
      }
      /** @internal */
      setOverride(key, value) {
        this.#overrides[key] = value;
      }
      /** @internal Clears the override AND every dedupe entry for `key` (any salt) so
       *  the next real read re-fires exposure rather than staying suppressed by a stale entry. */
      clearOverride(key) {
        delete this.#overrides[key];
        for (const k in this.#exposures) if (k.split("|")[1] === key) delete this.#exposures[k];
        this.#persistExposures();
      }
      #resolve(key) {
        if (key in this.#overrides) return { value: this.#overrides[key], reason: "OVERRIDE" };
        const spec = this.#snapshot.keys[key];
        if (!spec) return { value: void 0, reason: "ERROR" };
        try {
          return evaluateKey(spec, this.#context, {
            segments: this.#snapshot.segments,
            distinctId: this.#deps.distinctId(),
            now: this.#deps.now
          });
        } catch {
          return { value: spec.default, reason: "ERROR" };
        }
      }
      /** ALL typed getters funnel here: SPLIT + canExpose-gated, salt-deduped exposure. */
      #exposed(key) {
        const r = this.#resolve(key);
        try {
          if (r.reason === "SPLIT" && this.#deps.canExpose()) {
            const variant = extractTyped("variant", r);
            const dedupeKey = `${this.#context.targetingKey}|${key}|${r.salt ?? ""}`;
            if (this.#exposures[dedupeKey] !== variant) {
              this.#exposures[dedupeKey] = variant;
              this.#persistExposures();
              this.#deps.trackExposure({
                schema: "swip:event:experiment_exposure:1",
                props: {
                  key,
                  variant,
                  salt: r.salt ?? null,
                  revision: this.#snapshot.revision,
                  reason: r.reason,
                  unit_id: this.#context.targetingKey,
                  bucket_by: r.unit ?? "device",
                  distinct_id: this.#deps.distinctId()
                }
              });
            }
          }
        } catch {
        }
        return r;
      }
      #persistExposures() {
        try {
          this.#deps.storage.set(KEY_EXPOSURES, JSON.stringify(this.#exposures));
        } catch {
        }
      }
    };
  }
});

// node_modules/@sloopworks/swip-js/src/errors/crash-reporter.ts
var NoOpCrashReporter;
var init_crash_reporter = __esm({
  "node_modules/@sloopworks/swip-js/src/errors/crash-reporter.ts"() {
    NoOpCrashReporter = {
      capture() {
      },
      setContext() {
      },
      onForeignEvent() {
      }
    };
  }
});

// node_modules/@sloopworks/swip-js/src/errors/facade.ts
var NoOpErrors;
var init_facade2 = __esm({
  "node_modules/@sloopworks/swip-js/src/errors/facade.ts"() {
    init_crash_reporter();
    NoOpErrors = {
      record() {
      },
      wtf() {
      },
      breadcrumb() {
      },
      drainStorms() {
      },
      async flush() {
      },
      health: () => ({ error_storm_evicted: 0, error_severity_coerced: 0 })
    };
  }
});

// node_modules/@sloopworks/swip-js/src/telemetry/traceparent.ts
function makeTraceparent(traceId, spanId, sampled) {
  return `00-${traceId}-${spanId}-${sampled ? "01" : "00"}`;
}
function parseTraceparent(header) {
  const match = TRACEPARENT_RE.exec(header.trim());
  if (!match) return null;
  const traceId = match[1];
  const spanId = match[2];
  if (/^0+$/.test(traceId) || /^0+$/.test(spanId)) return null;
  return { traceId, spanId, sampled: (Number.parseInt(match[3], 16) & 1) === 1 };
}
function isSampledByRatio(traceId, ratio) {
  if (ratio >= 1) return true;
  if (ratio <= 0) return false;
  const value = BigInt(`0x${traceId.slice(16)}`);
  const threshold = BigInt(Math.floor(ratio * 2 ** 52)) << 12n;
  return value < threshold;
}
var TRACEPARENT_RE;
var init_traceparent = __esm({
  "node_modules/@sloopworks/swip-js/src/telemetry/traceparent.ts"() {
    TRACEPARENT_RE = /^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/;
  }
});

// node_modules/@sloopworks/swip-js/src/telemetry/facade.ts
var InMemoryExporter, SwipTelemetry;
var init_facade3 = __esm({
  "node_modules/@sloopworks/swip-js/src/telemetry/facade.ts"() {
    init_traceparent();
    InMemoryExporter = class {
      spans = [];
      metrics = [];
      exportSpan(span) {
        this.spans.push(span);
      }
      async exportMetrics(metrics) {
        this.metrics.push(...metrics);
      }
    };
    SwipTelemetry = class {
      // True private (#) below — never touched outside this class, so esbuild's
      // minifier safely mangles them to 1-2 char names (unlike TS `private`,
      // which is compile-time-only and NOT renamed in the output).
      #active = null;
      #incoming = null;
      #counters = /* @__PURE__ */ new Map();
      #gauges = /* @__PURE__ */ new Map();
      #histograms = /* @__PURE__ */ new Map();
      #deps;
      constructor(deps) {
        this.#deps = deps;
      }
      /** Parent the next root span to an incoming W3C traceparent (server facades). */
      extract(headers) {
        try {
          const parsed = parseTraceparent(headers["traceparent"] ?? "");
          this.#incoming = parsed ? { traceId: parsed.traceId, spanId: parsed.spanId, sampled: parsed.sampled } : null;
        } catch {
          this.#incoming = null;
        }
      }
      span(name, attrs, block) {
        const parent = this.#active ?? this.#incoming;
        const traceId = parent?.traceId ?? this.#deps.randomHex(32);
        const spanId = this.#deps.randomHex(16);
        const sampled = parent ? parent.sampled : isSampledByRatio(traceId, this.#deps.sampleRatio);
        const record = {
          name,
          traceId,
          spanId,
          parentSpanId: parent?.spanId,
          startMonoMs: this.#deps.monotonicNow(),
          endMonoMs: 0,
          startWall: new Date(this.#deps.now()).toISOString(),
          attrs: { ...attrs },
          events: [],
          status: "unset"
        };
        const scope = {
          traceId,
          spanId,
          sampled,
          inject: (headers) => {
            headers["traceparent"] = makeTraceparent(traceId, spanId, sampled);
          },
          setAttr: (key, value) => {
            record.attrs[key] = value;
          },
          addEvent: (eventName, eventAttrs = {}) => {
            record.events.push({ name: eventName, monoMs: this.#deps.monotonicNow(), attrs: eventAttrs });
          },
          setStatus: (status) => {
            record.status = status;
          }
        };
        const previous = this.#active;
        this.#active = { traceId, spanId, sampled };
        try {
          const result = block(scope);
          return result;
        } catch (err) {
          record.status = "error";
          throw err;
        } finally {
          this.#active = previous;
          record.endMonoMs = this.#deps.monotonicNow();
          if (sampled) {
            try {
              this.#deps.exporter.exportSpan(record);
            } catch {
            }
          }
        }
      }
      counter(name, value = 1, attrs = {}) {
        try {
          const entry = this.#counters.get(name) ?? { value: 0, attrs };
          entry.value += value;
          this.#counters.set(name, entry);
        } catch {
        }
      }
      gauge(name, value, attrs = {}) {
        try {
          this.#gauges.set(name, { value, attrs });
        } catch {
        }
      }
      histogram(name, value, attrs = {}) {
        try {
          const entry = this.#histograms.get(name) ?? { values: [], attrs };
          entry.values.push(value);
          this.#histograms.set(name, entry);
        } catch {
        }
      }
      async flush() {
        try {
          const metrics = [
            ...[...this.#counters.entries()].map(
              ([name, m]) => ({ kind: "counter", name, value: m.value, attrs: m.attrs })
            ),
            ...[...this.#gauges.entries()].map(
              ([name, m]) => ({ kind: "gauge", name, value: m.value, attrs: m.attrs })
            ),
            ...[...this.#histograms.entries()].map(
              ([name, m]) => ({ kind: "histogram", name, values: m.values, attrs: m.attrs })
            )
          ];
          if (metrics.length === 0) return;
          this.#counters.clear();
          this.#gauges.clear();
          this.#histograms.clear();
          await this.#deps.exporter.exportMetrics(metrics);
        } catch {
        }
      }
    };
  }
});

// node_modules/@sloopworks/swip-js/src/transports.ts
var NoOpTransport, InMemoryTransport, MultiTransport;
var init_transports = __esm({
  "node_modules/@sloopworks/swip-js/src/transports.ts"() {
    NoOpTransport = class {
      async send(_batch) {
        return { kind: "ok" };
      }
      async shutdown(_flush) {
      }
    };
    InMemoryTransport = class {
      batches = [];
      async send(batch) {
        this.batches.push(batch);
        return { kind: "ok" };
      }
      async shutdown(_flush) {
      }
      events() {
        return this.batches.flatMap((b) => b.events);
      }
    };
    MultiTransport = class {
      #transports;
      constructor(transports) {
        this.#transports = transports;
      }
      async send(batch) {
        const results = await Promise.all(this.#transports.map((t) => t.send(batch)));
        const retry = results.find((r) => r.kind === "retry");
        if (retry) return retry;
        if (results.length > 0 && results.every((r) => r.kind === "drop")) {
          return results[0];
        }
        return { kind: "ok" };
      }
      async shutdown(flush) {
        await Promise.all(this.#transports.map((t) => t.shutdown(flush)));
      }
    };
  }
});

// node_modules/@sloopworks/swip-js/src/init.ts
var SDK_VERSION, FLUSH_TIMEOUT_MS, NoOpAnalytics, NoOpExporter, Swip;
var init_init = __esm({
  "node_modules/@sloopworks/swip-js/src/init.ts"() {
    init_analytics();
    init_coordinator();
    init_facade();
    init_key();
    init_facade2();
    init_facade3();
    init_transports();
    init_ulid();
    SDK_VERSION = "swip-js/0.1.0";
    FLUSH_TIMEOUT_MS = 2e3;
    NoOpAnalytics = class {
      track() {
      }
      identify() {
      }
      alias() {
      }
      async reset() {
      }
      async flush() {
        return { sent: 0, failed: 0 };
      }
      async whenDurable() {
      }
      setConsent() {
      }
      onBackground() {
      }
      onForeground() {
      }
    };
    NoOpExporter = class {
      exportSpan() {
      }
      async exportMetrics() {
      }
    };
    Swip = {
      init(config, deps = {}) {
        const now = deps.now ?? Date.now;
        const monotonicNow = deps.monotonicNow ?? (typeof performance !== "undefined" ? () => performance.now() : now);
        const setTimeoutFn = deps.setTimeout ?? ((fn, ms) => setTimeout(fn, ms));
        const clearTimeoutFn = deps.clearTimeout ?? ((id3) => clearTimeout(id3));
        const random = deps.random ?? Math.random;
        const ulid = deps.ulid ?? makeUlidFactory({ now });
        const storage = deps.storage ?? new MemoryStorage();
        const modules = new Set(config.modules);
        const channel = config.channel;
        const ctx = {
          app_version: deps.appVersion ?? "0.0.0",
          os: deps.os ?? "unknown",
          sdk: SDK_VERSION,
          channel,
          internal: channel !== "prod"
          // non-prod traffic never counts as real
        };
        const coordinator = new FlushCoordinator({
          setTimeout: setTimeoutFn,
          clearTimeout: clearTimeoutFn,
          now
        });
        let errors = NoOpErrors;
        const errorsFactoryMissing = modules.has("errors") && !deps.errorsFactory ? 1 : 0;
        const analytics = modules.has("analytics") ? new SwipAnalytics({
          // ci/test → NoOp: test traffic never leaves the machine (ADR-0021)
          transport: deps.forceNoOpTransport ? new NoOpTransport() : deps.transport ?? new NoOpTransport(),
          ctx,
          now,
          monotonicNow,
          setTimeout: setTimeoutFn,
          clearTimeout: clearTimeoutFn,
          random,
          ulid,
          storage,
          persistence: deps.persistence,
          criticalSchemas: deps.criticalSchemas,
          errorsHealth: () => ({ ...errors.health(), errors_factory_missing: errorsFactoryMissing }),
          onFlushStart: () => coordinator.onPillarFlush(analytics)
        }) : new NoOpAnalytics();
        if (deps.persistence && analytics instanceof SwipAnalytics) void analytics.recover();
        const installationId = () => {
          if (analytics instanceof SwipAnalytics) return analytics.installationId();
          let id3 = storage.get("swip.installation_id");
          if (!id3) {
            id3 = ulid();
            storage.set("swip.installation_id", id3);
          }
          return id3;
        };
        const telemetry = new SwipTelemetry({
          exporter: modules.has("telemetry") ? deps.exporter ?? new NoOpExporter() : new NoOpExporter(),
          sampleRatio: modules.has("telemetry") ? deps.sampleRatio ?? 1 : 0,
          monotonicNow,
          now,
          randomHex: (chars) => {
            let out = "";
            for (let i = 0; i < chars; i++) out += Math.floor(random() * 16).toString(16);
            return out;
          }
        });
        coordinator.register(telemetry);
        const swipConfig = new SwipConfig({
          defaults: deps.configDefaults ?? {},
          context: {
            targetingKey: installationId(),
            platform: deps.os,
            appVersion: deps.appVersion,
            custom: {}
          },
          storage,
          trackExposure: modules.has("config") ? (e) => analytics.track(e) : () => {
          },
          distinctId: () => void 0,
          // init() only ever builds the base (always-FULL) SwipAnalytics — the opt-in
          // CollectionMode surface lives at /collection and is deliberately excluded
          // from this core bundle (see analytics.ts), so there's no mode to gate on.
          canExpose: () => true,
          now
        });
        const inRange = (min, max) => (v) => typeof v === "number" && Number.isFinite(v) && v >= min && v <= max;
        const readLimit = (key, type, fallback, ok) => {
          try {
            if (swipConfig.keySpec(key) === void 0) return fallback;
            const value = extractTyped(type, swipConfig.peek(key));
            return ok(value) ? value : fallback;
          } catch {
            return fallback;
          }
        };
        const isBoolean = (v) => typeof v === "boolean";
        const errorLimits = () => ({
          // read as "json" (= the raw resolved value): extractTyped("boolean", …) coerces
          // a malformed value to `false`, which would kill the pillar. Fail OPEN instead.
          enabled: readLimit("swip.errors.enabled", "json", true, isBoolean),
          stormLimit: readLimit("swip.errors.storm_limit", "json", 5, inRange(1, Infinity)),
          stormWindowMs: readLimit("swip.errors.storm_window_ms", "duration", 6e4, inRange(1, Infinity)),
          warnSampleRate: readLimit("swip.errors.warn_sample_rate", "json", 1, inRange(0, 1))
        });
        errors = modules.has("errors") && deps.errorsFactory ? deps.errorsFactory({
          // Error events ride the ERRORS consent scope (D2) — a user who denied
          // errors and allowed analytics must not have them collected. The gate
          // is pre-queue, inside Pipeline.enqueue (INVARIANT 15).
          track: (e) => analytics.track(e, "errors"),
          now,
          monotonicNow,
          limits: errorLimits,
          random
        }) : NoOpErrors;
        const analyticsFacade = {
          track: (event, scope) => analytics.track(event, scope),
          identify: (distinctId, traits) => analytics.identify(distinctId, traits),
          alias: (previousId) => analytics.alias(previousId),
          reset: () => analytics.reset(),
          flush: () => analytics.flush(),
          whenDurable: () => analytics.whenDurable(),
          setConsent: (consent) => analytics.setConsent(consent),
          onBackground: () => {
            errors.drainStorms();
            analytics.onBackground();
          },
          onForeground: () => analytics.onForeground()
        };
        return {
          analytics: analyticsFacade,
          config: swipConfig,
          errors,
          telemetry,
          /** Drain pending suppressed-storm counts (D6). Called for you on background
           *  and at shutdown; exposed for products with a different lifecycle hook. */
          drainStorms: () => errors.drainStorms(),
          /**
           * See `SwipInstance.flush`. Order matters: drain the rollups FIRST so they ride
           * the analytics flush that follows, rather than the next one — which, on a
           * serverless invocation, never comes. The vendor's queue is independent of ours,
           * so it drains in parallel. Each leg is guarded: one broken sink must not abort
           * the others, and nothing here may reject into a request handler's `finally`.
           *
           * RACED AGAINST A HARD DEADLINE, because `settle()` guards REJECTIONS and a hang is
           * not a rejection. No leg below is self-bounding: `analytics.flush()` →
           * `pipeline.flush()` → `transport.send()` is a bare `fetch` with no AbortSignal, and
           * `errors.flush(budget)` merely PASSES the budget to a vendor that may or may not
           * honour it. A single never-settling leg would leave `Promise.all` pending forever,
           * the handler's `finally` never returning, and the function running to the platform's
           * own limit — a 504 caused by instrumentation. The race is at THIS level (not inside a
           * leg) precisely so it bounds every leg, including a hostile vendor's.
           *
           * The losing legs are NOT cancelled — they cannot be — they are simply no longer
           * awaited. On a frozen serverless process that means the event is lost, which is the
           * outcome this whole method exists to trade AGAINST a hung request.
           */
          async flush(timeoutMs) {
            errors.drainStorms();
            const budget = timeoutMs ?? FLUSH_TIMEOUT_MS;
            const settle = async (work) => {
              try {
                await work();
              } catch {
              }
            };
            let timer;
            const deadline = new Promise((resolve) => {
              timer = setTimeoutFn(() => resolve(), budget);
            });
            try {
              await Promise.race([
                Promise.all([
                  settle(() => telemetry.flush()),
                  settle(() => analytics.flush()),
                  settle(() => errors.flush(budget))
                ]).then(() => void 0),
                deadline
              ]);
            } finally {
              if (timer !== void 0) clearTimeoutFn(timer);
            }
          },
          async shutdown() {
            errors.drainStorms();
            await telemetry.flush();
            if (analytics instanceof SwipAnalytics) await analytics.shutdown(true);
            else await analytics.flush();
            await errors.flush();
          }
        };
      }
    };
  }
});

// node_modules/@sloopworks/swip-js/src/index.ts
var src_exports = {};
__export(src_exports, {
  FlushCoordinator: () => FlushCoordinator,
  InMemoryExporter: () => InMemoryExporter,
  InMemoryTransport: () => InMemoryTransport,
  MemoryStorage: () => MemoryStorage,
  MultiTransport: () => MultiTransport,
  NoOpErrors: () => NoOpErrors,
  NoOpTransport: () => NoOpTransport,
  PESSIMISTIC_CONSENT: () => PESSIMISTIC_CONSENT,
  Pipeline: () => Pipeline,
  Swip: () => Swip,
  SwipAnalytics: () => SwipAnalytics,
  SwipConfig: () => SwipConfig,
  SwipTelemetry: () => SwipTelemetry,
  bucketOf: () => bucketOf,
  compareSemver: () => compareSemver,
  evaluateKey: () => evaluateKey,
  isAssigned: () => isAssigned,
  isSampledByRatio: () => isSampledByRatio,
  makeTraceparent: () => makeTraceparent,
  makeUlidFactory: () => makeUlidFactory,
  multivariateVariant: () => multivariateVariant,
  murmur3_32: () => murmur3_32,
  parseDuration: () => parseDuration,
  parseTraceparent: () => parseTraceparent,
  satisfiesVersion: () => satisfiesVersion
});
var init_src = __esm({
  "node_modules/@sloopworks/swip-js/src/index.ts"() {
    init_types();
    init_ulid();
    init_pipeline();
    init_coordinator();
    init_init();
    init_transports();
    init_analytics();
    init_murmur3();
    init_bucketing();
    init_semver();
    init_duration();
    init_evaluate();
    init_facade();
    init_facade2();
    init_traceparent();
    init_facade3();
  }
});

// node_modules/@sloopworks/swip-js/src/posthog.ts
function verifyPostHogHost(region, host) {
  const rule = REGION_HOST[region];
  if (rule === void 0) {
    throw new Error(
      `[swip] UNKNOWN POSTHOG REGION ${JSON.stringify(region)} \u2014 refusing to construct the transport. Known regions: ${Object.keys(REGION_HOST).join(", ")}.`
    );
  }
  const matched = ORIGIN_RE.exec(host)?.[1];
  if (matched === void 0) {
    throw new Error(
      `[swip] MALFORMED POSTHOG HOST \u2014 refusing to construct the transport. This product declares data residency "${region}"; a host that is not a bare https origin has no verifiable residency. Expected https://<host> (e.g. https://eu.i.posthog.com) \u2014 no port, no path, no query, no fragment, no whitespace.`
    );
  }
  const hostname = matched.toLowerCase();
  if (!rule.test(hostname)) {
    throw new Error(
      `[swip] POSTHOG HOST REGION MISMATCH \u2014 refusing to construct the transport. This product declares data residency "${region}", but its PostHog host is "${hostname}". The host is where analytics data PHYSICALLY LANDS \u2014 PostHog Cloud US (Virginia) and EU (Frankfurt) are separate installations and nothing at runtime moves an event between them. Expected a host matching ${String(rule)}. An UNRECOGNIZED host (a reverse proxy, a self-hosted instance) fails here too, deliberately: residency we cannot prove is residency we do not have. Fix POSTHOG_HOST (or whichever env var registry/products/<product>.yaml names); do NOT soften this check \u2014 see the comment on verifyPostHogHost.`
    );
  }
  return hostname;
}
var REGION_HOST, ORIGIN_RE, SCHEMA_NAME_RE, PostHogTransport;
var init_posthog = __esm({
  "node_modules/@sloopworks/swip-js/src/posthog.ts"() {
    REGION_HOST = {
      eu: /^eu(\.i)?\.posthog\.com$/i,
      us: /^(us(\.i)?|app)\.posthog\.com$/i
    };
    ORIGIN_RE = /^https:\/\/([a-zA-Z0-9.-]+)\/?$/;
    SCHEMA_NAME_RE = /^swip:event:([^:]+):\d+$/;
    PostHogTransport = class {
      constructor(options) {
        this.options = options;
        this.endpoint = `https://${verifyPostHogHost(options.region, options.host)}/batch/`;
        this.fetchFn = options.fetch ?? ((url, init) => fetch(url, init));
      }
      fetchFn;
      /**
       * THE INGEST URL, BUILT FROM THE VERIFIED HOSTNAME — never from `options.host`.
       *
       * `verifyPostHogHost` accepts a trailing slash on purpose (an env var actually contains
       * one half the time), and its return value used to be THROWN AWAY: `${options.host}/batch/`
       * then built `https://eu.i.posthog.com//batch/` from `POSTHOG_HOST=https://eu.i.posthog.com/`.
       * WHATWG URL does not collapse that double slash, PostHog does not 2xx it, the transport
       * returns `{kind:"drop"}` — and EVERY event is dead-lettered, silently. Exactly the blind
       * spot that hid the `$insert_id` outage in the header above: the construction tests blessed
       * the host and the fake fetch returned 200 unconditionally, so nothing ever asserted the URL
       * that was actually POSTed. Both suites now do (`posthog.test.ts`, `PostHogTransportTest.kt`).
       */
      endpoint;
      async send(batch) {
        const payload = {
          api_key: this.options.apiKey,
          batch: batch.events.map((event) => ({
            event: SCHEMA_NAME_RE.exec(event.schema)?.[1] ?? event.schema,
            distinct_id: event.distinct_id ?? "anonymous",
            timestamp: event.ts_wall,
            properties: {
              ...event.props,
              // NOT the top-level `uuid` — see the header. event_id is a ULID; PostHog's
              // `uuid` demands a real UUID and 400s the whole batch otherwise.
              $insert_id: event.event_id,
              $session_id: event.session_id,
              $app_version: batch.ctx.app_version,
              $os: batch.ctx.os,
              $lib: batch.ctx.sdk,
              swip_schema: event.schema,
              swip_channel: batch.ctx.channel,
              // Suppress PostHog's IP capture + geo-lookup. Without these, PostHog reads the
              // source IP off the CONNECTION — nothing in the payload can prevent it — and
              // derives `$geoip_*` properties from it. An IP is personal data under GDPR, and
              // SWIP declares no `ip` field on any schema: collecting one out-of-band would
              // make the registry lie about what we collect (INVARIANT: privacy_class on every
              // field). The Kotlin transport has always set these; the TS one did not, so every
              // web/Node event was silently geo-located. Parity is the point — the two SDKs must
              // not disagree about what leaves the device.
              $geoip_disable: true,
              $ip: "0.0.0.0"
            }
          }))
        };
        let response;
        try {
          response = await this.fetchFn(this.endpoint, {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify(payload)
          });
        } catch {
          return { kind: "retry" };
        }
        if (response.ok) return { kind: "ok" };
        if (response.status === 429 || response.status >= 500) {
          const retryAfter = Number(response.headers.get("Retry-After"));
          return Number.isFinite(retryAfter) && retryAfter > 0 ? { kind: "retry", afterMs: retryAfter * 1e3 } : { kind: "retry" };
        }
        return { kind: "drop", reason: `http_${response.status}` };
      }
      async shutdown() {
      }
    };
  }
});

// node_modules/@sloopworks/swip-schema-dayfold/src/generated/analytics.ts
var analytics_exports = {};
__export(analytics_exports, {
  DayfoldSwipAnalytics: () => DayfoldSwipAnalytics
});
function processEnv() {
  return globalThis.process?.env ?? {};
}
function requireEnv(env, name) {
  const value = env[name];
  if (value === void 0 || value === "") {
    throw new Error(
      `[swip] ${name} is unset \u2014 registry/products/dayfold.yaml declares it for the analytics transport. Set it in the deploy environment; do not hardcode it.`
    );
  }
  return value;
}
var DayfoldSwipAnalytics;
var init_analytics2 = __esm({
  "node_modules/@sloopworks/swip-schema-dayfold/src/generated/analytics.ts"() {
    init_posthog();
    DayfoldSwipAnalytics = {
      /** PostHog, region "eu" — declared in registry/products/dayfold.yaml, asserted against
       *  the host at construction (a wrong-continent host throws; it does not warn). */
      apiProdTransport: (runtime = {}) => {
        const env = runtime.env ?? processEnv();
        return new PostHogTransport({
          apiKey: requireEnv(env, "POSTHOG_PROJECT_KEY"),
          host: requireEnv(env, "POSTHOG_HOST"),
          region: "eu",
          ...runtime.fetch === void 0 ? {} : { fetch: runtime.fetch }
        });
      },
      /** PostHog, region "eu" — declared in registry/products/dayfold.yaml, asserted against
       *  the host at construction (a wrong-continent host throws; it does not warn). */
      webProdTransport: (options) => new PostHogTransport({
        apiKey: options.apiKey,
        host: options.host,
        region: "eu",
        ...options.fetch === void 0 ? {} : { fetch: options.fetch }
      })
    };
  }
});

// node_modules/@sloopworks/swip-logging/src/index.ts
function scrubString(value) {
  return value.replace(URL_QUERY_RE, "$1?[redacted-query]").replace(MGMT_LINK_RE, "/m/[redacted-token]").replace(EMAIL_RE, "[redacted-email]").replace(HIGH_ENTROPY_RE, "[redacted-token]");
}
function scrubField(key, value) {
  return PII_KEY_RE.test(key) ? REDACTED : scrubString(value);
}
var PII_KEY_RE, EMAIL_RE, MGMT_LINK_RE, HIGH_ENTROPY_RE, URL_QUERY_RE, REDACTED;
var init_src2 = __esm({
  "node_modules/@sloopworks/swip-logging/src/index.ts"() {
    PII_KEY_RE = /(email|token|api_key|envelope_key)/i;
    EMAIL_RE = /\S+@\S+\.\S+/g;
    MGMT_LINK_RE = /\/m\/[A-Za-z0-9_-]{22,}/g;
    HIGH_ENTROPY_RE = /[A-Za-z0-9_-]{40,}/g;
    URL_QUERY_RE = /(https?:\/\/[^\s?#]+|\/[^\s?#]*)\?[^\s#]*/g;
    REDACTED = "[redacted]";
  }
});

// node_modules/@sloopworks/swip-js/src/errors/sha1.ts
function sha1Hex(input) {
  const data = encoder2.encode(input);
  const ml = data.length;
  const withOne = ml + 1;
  const padded = new Uint8Array(Math.ceil((withOne + 8) / 64) * 64);
  padded.set(data);
  padded[ml] = 128;
  const bitLen = ml * 8;
  const dv = new DataView(padded.buffer);
  dv.setUint32(padded.length - 8, Math.floor(bitLen / 4294967296), false);
  dv.setUint32(padded.length - 4, bitLen >>> 0, false);
  let h0 = 1732584193;
  let h1 = 4023233417;
  let h2 = 2562383102;
  let h3 = 271733878;
  let h4 = 3285377520;
  const w = new Uint32Array(80);
  for (let block = 0; block < padded.length; block += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(block + i * 4, false);
    for (let i = 16; i < 80; i++) {
      const x = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
      w[i] = (x << 1 | x >>> 31) >>> 0;
    }
    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    for (let i = 0; i < 80; i++) {
      let f;
      let k;
      if (i < 20) {
        f = b & c | ~b & d;
        k = 1518500249;
      } else if (i < 40) {
        f = b ^ c ^ d;
        k = 1859775393;
      } else if (i < 60) {
        f = b & c | b & d | c & d;
        k = 2400959708;
      } else {
        f = b ^ c ^ d;
        k = 3395469782;
      }
      const temp = ((a << 5 | a >>> 27) >>> 0) + f + e + k + w[i] >>> 0;
      e = d;
      d = c;
      c = (b << 30 | b >>> 2) >>> 0;
      b = a;
      a = temp;
    }
    h0 = h0 + a >>> 0;
    h1 = h1 + b >>> 0;
    h2 = h2 + c >>> 0;
    h3 = h3 + d >>> 0;
    h4 = h4 + e >>> 0;
  }
  return [h0, h1, h2, h3, h4].map((h) => h.toString(16).padStart(8, "0")).join("");
}
var encoder2;
var init_sha1 = __esm({
  "node_modules/@sloopworks/swip-js/src/errors/sha1.ts"() {
    encoder2 = new TextEncoder();
  }
});

// node_modules/@sloopworks/swip-js/src/errors/fingerprint.ts
function normalizeType(type) {
  return asciiTrim(
    type.replace(/<[^>]*>/g, "").replace(/@0x[0-9a-fA-F]+/g, "").replace(/[-_.]?\d+$/g, "")
    // numeric suffixes — Kotlin port: use \z, NOT $
  );
}
function normalizeMessage(message) {
  const src = truncate(message);
  let out = "";
  let i = 0;
  while (i < src.length) {
    if (isAsciiSpace(src.charCodeAt(i))) {
      out += src[i];
      i += 1;
      continue;
    }
    let end = i;
    let isPath = false;
    while (end < src.length && !isAsciiSpace(src.charCodeAt(end))) {
      const c = src.charCodeAt(end);
      if (c === 47 || c === 92) isPath = true;
      end += 1;
    }
    if (isPath) {
      out += PATH_PLACEHOLDER;
      i = end;
      continue;
    }
    while (i < end) {
      if (isAlnum(src.charCodeAt(i))) {
        let j = i;
        let hasDigit = false;
        while (j < end && isAlnum(src.charCodeAt(j))) {
          if (isDigit(src.charCodeAt(j))) hasDigit = true;
          j += 1;
        }
        out += hasDigit ? "#" : src.slice(i, j);
        i = j;
      } else {
        out += src[i];
        i += 1;
      }
    }
  }
  return asciiTrim(out);
}
function fingerprintOf(type, message) {
  return sha1Hex(`${normalizeType(type)}|${normalizeMessage(message)}`).slice(0, 32);
}
function softFingerprintOf(key) {
  return sha1Hex(`soft|${key}`).slice(0, 32);
}
function truncate(s) {
  if (s.length <= MAX_MESSAGE_CHARS) return s;
  const last = s.charCodeAt(MAX_MESSAGE_CHARS - 1);
  const orphan = last >= 55296 && last <= 56319;
  return s.slice(0, orphan ? MAX_MESSAGE_CHARS - 1 : MAX_MESSAGE_CHARS);
}
function isAsciiSpace(c) {
  return c === 32 || c >= 9 && c <= 13;
}
function asciiTrim(s) {
  let start = 0;
  let end = s.length;
  while (start < end && isAsciiSpace(s.charCodeAt(start))) start += 1;
  while (end > start && isAsciiSpace(s.charCodeAt(end - 1))) end -= 1;
  return s.slice(start, end);
}
function isDigit(c) {
  return c >= 48 && c <= 57;
}
function isAlnum(c) {
  return isDigit(c) || c >= 97 && c <= 122 || c >= 65 && c <= 90;
}
var MAX_MESSAGE_CHARS, PATH_PLACEHOLDER;
var init_fingerprint = __esm({
  "node_modules/@sloopworks/swip-js/src/errors/fingerprint.ts"() {
    init_sha1();
    MAX_MESSAGE_CHARS = 256;
    PATH_PLACEHOLDER = "<path>";
  }
});

// node_modules/@sloopworks/swip-js/src/errors/memory-reporter.ts
var init_memory_reporter = __esm({
  "node_modules/@sloopworks/swip-js/src/errors/memory-reporter.ts"() {
  }
});

// node_modules/@sloopworks/swip-js/src/errors/index.ts
var SEVERITIES, BREADCRUMB_RING, BREADCRUMB_ATTACHED, BREADCRUMB_MSG_CHARS, BREADCRUMB_CATEGORY_CHARS, ATTR_KEYS, ATTR_VALUE_CHARS, STACK_CHARS, MESSAGE_CHARS, TYPE_CHARS, KEY_CHARS, MECHANISM_CHARS, STORM_TABLE_MAX, MIRROR_DEDUPE_MAX, MIRROR_EVENT_ID_CHARS, SwipErrors;
var init_errors = __esm({
  "node_modules/@sloopworks/swip-js/src/errors/index.ts"() {
    init_src2();
    init_crash_reporter();
    init_fingerprint();
    init_facade2();
    init_memory_reporter();
    init_fingerprint();
    init_sha1();
    SEVERITIES = /* @__PURE__ */ new Set(["warn", "error", "fatal"]);
    BREADCRUMB_RING = 32;
    BREADCRUMB_ATTACHED = 16;
    BREADCRUMB_MSG_CHARS = 128;
    BREADCRUMB_CATEGORY_CHARS = 64;
    ATTR_KEYS = 16;
    ATTR_VALUE_CHARS = 256;
    STACK_CHARS = 4096;
    MESSAGE_CHARS = 1024;
    TYPE_CHARS = 256;
    KEY_CHARS = 128;
    MECHANISM_CHARS = 64;
    STORM_TABLE_MAX = 256;
    MIRROR_DEDUPE_MAX = 128;
    MIRROR_EVENT_ID_CHARS = 128;
    SwipErrors = class {
      constructor(deps) {
        this.deps = deps;
        this.reporter = deps.crashReporter ?? NoOpCrashReporter;
        try {
          this.reporter.onForeignEvent((e) => this.mirror(e));
        } catch {
        }
      }
      breadcrumbs = [];
      storms = /* @__PURE__ */ new Map();
      stormEvicted = 0;
      severityCoerced = 0;
      reporter;
      mirrored = /* @__PURE__ */ new Set();
      /** sdk_health counters. error_storm_evicted is the fingerprint-cardinality canary. */
      health() {
        return {
          error_storm_evicted: this.stormEvicted,
          error_severity_coerced: this.severityCoerced
        };
      }
      breadcrumb(category, message) {
        try {
          const [cappedCategory, categoryTruncated] = this.cap(category, BREADCRUMB_CATEGORY_CHARS);
          const [cappedMessage, messageTruncated] = this.cap(scrubString(message), BREADCRUMB_MSG_CHARS);
          this.breadcrumbs.push({
            category: cappedCategory,
            message: cappedMessage,
            ts_wall: new Date(this.deps.now()).toISOString(),
            ts_mono_ms: this.deps.monotonicNow(),
            truncated: categoryTruncated || messageTruncated
          });
          if (this.breadcrumbs.length > BREADCRUMB_RING) this.breadcrumbs.shift();
        } catch {
        }
      }
      record(error, attrs = {}, mechanism = "generic", severity = "error") {
        try {
          const sev = this.coerce(severity);
          const err = error instanceof Error ? error : new Error(String(error));
          const type = err.name || "Error";
          const message = scrubString(err.message);
          const fingerprint = fingerprintOf(type, message);
          if (!this.allows(fingerprint, sev)) return;
          const [cappedType, typeTruncated] = this.cap(type, TYPE_CHARS);
          const [cappedMessage, messageTruncated] = this.cap(message, MESSAGE_CHARS);
          const [cappedMechanism, mechanismTruncated] = this.cap(mechanism, MECHANISM_CHARS);
          let truncated = typeTruncated || messageTruncated || mechanismTruncated;
          const props = {
            "error.fingerprint": fingerprint,
            severity: sev,
            "exception.type": cappedType,
            "exception.message": cappedMessage,
            mechanism: cappedMechanism,
            handled: true
          };
          if (sev !== "warn" && err.stack) {
            const [cappedStack, stackTruncated] = this.cap(scrubString(err.stack), STACK_CHARS);
            props["exception.stacktrace"] = cappedStack;
            if (stackTruncated) truncated = true;
          }
          this.emit(props, attrs, sev, truncated, { error: err, fingerprint });
        } catch {
        }
      }
      /** Deliberate report of a condition that should never happen. No Throwable required. */
      wtf(key, message, attrs = {}, severity = "error") {
        try {
          const sev = this.coerce(severity);
          const fingerprint = softFingerprintOf(key);
          const [cappedKey, keyTruncated] = this.cap(key, KEY_CHARS);
          if (!this.allows(fingerprint, sev, cappedKey)) return;
          const [cappedMessage, messageTruncated] = this.cap(scrubString(message), MESSAGE_CHARS);
          this.emit(
            {
              "error.fingerprint": fingerprint,
              key: cappedKey,
              severity: sev,
              "exception.message": cappedMessage,
              mechanism: "wtf",
              handled: true
            },
            attrs,
            sev,
            keyTruncated || messageTruncated,
            // wtf() has no Throwable — the vendor gets the SCRUBBED message and the
            // declared key, which it SHOULD group on (there is no stack to group on).
            { message: cappedMessage, fingerprint, key: cappedKey }
          );
        } catch {
        }
      }
      /**
       * Emit the pending suppressed counts (D6). Without this, a storm that simply
       * stops — the common case, the user navigates away — reports nothing, because
       * the old code only surfaced a rollup when the same fingerprint recurred after
       * its window. Called on flush/background and at shutdown.
       */
      drainStorms() {
        let windowMs;
        try {
          windowMs = this.deps.limits().stormWindowMs;
        } catch {
          return;
        }
        for (const [fingerprint, bucket] of this.storms) {
          if (bucket.suppressed <= 0) continue;
          const count = bucket.suppressed;
          try {
            this.deps.track({
              schema: "swip:event:error_storm:1",
              props: {
                "error.fingerprint": fingerprint,
                ...bucket.key === void 0 ? {} : { key: bucket.key },
                count,
                window_ms: windowMs
              }
            });
            bucket.suppressed = 0;
          } catch {
          }
        }
      }
      /**
       * Await the VENDOR's queue — the other half of a serverless flush.
       *
       * `tee()` is fire-and-forget on purpose (see below): `captureException` does its own
       * capture, serialization and disk write, and awaiting it on a request path would put
       * the vendor's I/O in the user's latency. That is correct on a long-lived process,
       * where the vendor's queue drains by itself. On a SERVERLESS one (Dayfold's API is
       * Hono-on-Node on Vercel functions) the process is frozen or killed the moment the
       * response is returned, and anything still in that queue is GONE — handled errors that
       * "sometimes just don't appear".
       *
       * So the handler awaits ONCE, at the end, in a `finally`, via the init handle's
       * `flush()` (init.ts), which awaits SWIP's own pipeline and this together.
       *
       * NEVER REJECTS (INVARIANT 13). A vendor whose flush hangs, throws synchronously or
       * rejects must not throw out of the caller's `finally` — the request is already
       * served, and a lost crash report must never become a lost response. `flush` is
       * OPTIONAL on the seam (`NoOpCrashReporter`/`MemoryCrashReporter` have nothing in
       * flight), so an absent one resolves immediately.
       */
      async flush(timeoutMs) {
        try {
          await this.reporter.flush?.(timeoutMs);
        } catch {
        }
      }
      /** Slice to `max` chars; returns whether the PRE-slice value was over the cap.
       *  Must be checked before slicing — after slicing, "exactly at the cap" and
       *  "was longer, got cut" are indistinguishable (both have length === max). */
      cap(value, max) {
        return value.length > max ? [value.slice(0, max), true] : [value, false];
      }
      /**
       * A crash or vendor-caught error becomes an OWNED event (spec §14). Without this,
       * fatal crashes exist only in Sentry and the owned-data story ADR-0003 rests on is
       * half-built — the archive and the joins never see the most diagnostic event type
       * there is.
       *
       * Never tees: the vendor already has this event. Never storm-gated: a crash is not
       * a hot loop. Deduped on the vendor's event id, because the same crash can reach us
       * from more than one vendor path (a cached envelope replayed at next launch, plus a
       * marker file we wrote ourselves).
       *
       * `foreign.severity` is the ONE value in this class that originates OUTSIDE our type
       * system — an adapter maps the vendor's own severity vocabulary onto ours, and a
       * mis-mapping adapter (or a vendor that adds a level) must not silently mint a
       * `swip:event:error:1` that violates error.v1's enum. Coerced like every other
       * severity, which also feeds `error_severity_coerced` — the canary for exactly that.
       *
       * No breadcrumbs attached: on the JVM path a mirrored crash is delivered at the
       * NEXT launch, replayed from a marker file the vendor wrote before the process
       * died. The breadcrumb ring here belongs to the CURRENT process, not the one that
       * crashed — attaching it would stamp the crash with breadcrumbs that never led to
       * it. Omission is deliberate, not an oversight.
       *
       * PRIVATE: this is an ungated ingest path (no storm bucket, no kill switch, no
       * sampling) that stamps `handled: false`. Its only legitimate caller is the
       * closure the constructor hands to `onForeignEvent` — product code calling it
       * directly could mint `handled: false` events no vendor ever saw (corrupting the
       * handled/crash join) and drive an unbounded emit loop past the storm gate. Only
       * the adapter mirrors; the type system enforces it.
       */
      mirror(foreign) {
        try {
          const raw = typeof foreign.eventId === "string" ? foreign.eventId : "";
          const [eventId] = this.cap(raw, MIRROR_EVENT_ID_CHARS);
          if (eventId !== "") {
            if (this.mirrored.has(eventId)) return;
            this.mirrored.add(eventId);
            if (this.mirrored.size > MIRROR_DEDUPE_MAX) {
              const oldest = this.mirrored.values().next().value;
              if (oldest !== void 0) this.mirrored.delete(oldest);
            }
          }
          const type = foreign.type ?? "Error";
          const message = scrubString(foreign.message ?? "");
          const [cappedType, typeTruncated] = this.cap(type, TYPE_CHARS);
          const [cappedMessage, messageTruncated] = this.cap(message, MESSAGE_CHARS);
          const [cappedMechanism, mechanismTruncated] = this.cap(
            foreign.mechanism ?? "vendor",
            MECHANISM_CHARS
          );
          let truncated = typeTruncated || messageTruncated || mechanismTruncated;
          const props = {
            "error.fingerprint": fingerprintOf(cappedType, cappedMessage),
            severity: this.coerce(foreign.severity),
            "exception.type": cappedType,
            "exception.message": cappedMessage,
            mechanism: cappedMechanism,
            handled: false
          };
          if (foreign.stacktrace) {
            const [cappedStack, stackTruncated] = this.cap(scrubString(foreign.stacktrace), STACK_CHARS);
            props["exception.stacktrace"] = cappedStack;
            if (stackTruncated) truncated = true;
          }
          if (truncated) props["truncated"] = true;
          this.deps.track({ schema: "swip:event:error:1", props });
        } catch {
        }
      }
      /** Fire-and-forget. captureException does its own capture, serialization and disk
       *  write; it must never be awaited on a latency-sensitive path. Never throws. */
      tee(request) {
        try {
          this.reporter.capture(request);
        } catch {
        }
      }
      emit(props, rawAttrs, severity, truncated = false, capture) {
        const attrs = {};
        const keys = Object.keys(rawAttrs).sort();
        if (keys.length > ATTR_KEYS) truncated = true;
        for (const k of keys.slice(0, ATTR_KEYS)) {
          const value = scrubField(k, String(rawAttrs[k]));
          if (value.length > ATTR_VALUE_CHARS) truncated = true;
          attrs[k] = value.slice(0, ATTR_VALUE_CHARS);
        }
        if (keys.length > 0) props["attrs"] = attrs;
        if (severity !== "warn" && this.breadcrumbs.length > 0) {
          const crumbs = this.breadcrumbs.slice(-BREADCRUMB_ATTACHED);
          if (crumbs.some((c) => c.truncated)) truncated = true;
          props["breadcrumbs"] = crumbs.map((c) => ({
            category: c.category,
            message: c.message,
            ts_wall: c.ts_wall,
            ts_mono_ms: c.ts_mono_ms
          }));
        }
        if (truncated) props["truncated"] = true;
        this.deps.track({ schema: "swip:event:error:1", props });
        if (capture !== void 0 && severity !== "warn") {
          this.tee({ ...capture, severity, tags: attrs });
        }
      }
      coerce(severity) {
        if (SEVERITIES.has(severity)) return severity;
        this.severityCoerced += 1;
        return "error";
      }
      /** FATAL is never gated, sampled, or bucketed. */
      allows(fingerprint, severity, key) {
        if (severity === "fatal") return true;
        const limits = this.deps.limits();
        if (!limits.enabled) return false;
        if (severity === "warn") {
          const rand = this.deps.random ?? Math.random;
          if (rand() >= limits.warnSampleRate) return false;
        }
        const now = this.deps.monotonicNow();
        const bucket = this.storms.get(fingerprint);
        if (!bucket || now - bucket.windowStart >= limits.stormWindowMs) {
          if (bucket && bucket.suppressed > 0) {
            const count = bucket.suppressed;
            try {
              this.deps.track({
                schema: "swip:event:error_storm:1",
                props: {
                  "error.fingerprint": fingerprint,
                  ...key === void 0 ? {} : { key },
                  count,
                  window_ms: limits.stormWindowMs
                }
              });
            } catch {
            }
          }
          this.evictIfFull();
          this.storms.set(fingerprint, { windowStart: now, emitted: 1, suppressed: 0, key });
          return true;
        }
        if (bucket.emitted < limits.stormLimit) {
          bucket.emitted += 1;
          return true;
        }
        bucket.suppressed += 1;
        return false;
      }
      /**
       * The table was unbounded (D7): one entry per distinct fingerprint, pruned only
       * on recurrence. Any fingerprint churn leaked. Evictions are the canary — if
       * message normalization rots, this counter spikes.
       */
      evictIfFull() {
        if (this.storms.size < STORM_TABLE_MAX) return;
        let oldestKey;
        let oldestStart = Infinity;
        for (const [k, b] of this.storms) {
          if (b.windowStart < oldestStart) {
            oldestStart = b.windowStart;
            oldestKey = k;
          }
        }
        if (oldestKey !== void 0) {
          this.storms.delete(oldestKey);
          this.stormEvicted += 1;
        }
      }
    };
  }
});

// node_modules/@sloopworks/swip-sentry/src/vendor-event.ts
function isReservedTag(key) {
  return key.startsWith(RESERVED_TAG_PREFIX) && !SWIP_CONTEXT_TAGS.includes(key);
}
function isVendorSeverity(value) {
  return typeof value === "string" && SEVERITIES2.includes(value);
}
function toTagRecord(value) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return void 0;
  const tags = {};
  for (const [key, raw] of Object.entries(value)) {
    if (raw === void 0 || raw === null) continue;
    if (typeof raw === "string") {
      tags[key] = raw;
      continue;
    }
    try {
      tags[key] = String(raw);
    } catch {
    }
  }
  return tags;
}
function isStringArray(value) {
  return Array.isArray(value) && value.every((v) => typeof v === "string");
}
function toCaptureContext(hint) {
  const context = {};
  const level = hint["level"];
  const tags = toTagRecord(hint["tags"]);
  const fingerprint = hint["fingerprint"];
  if (isVendorSeverity(level)) context.level = level;
  if (tags !== void 0) context.tags = tags;
  if (isStringArray(fingerprint)) context.fingerprint = fingerprint;
  return context;
}
function copyFrame(f) {
  return {
    ...f.filename === void 0 ? {} : { filename: f.filename },
    ...f.abs_path === void 0 ? {} : { abs_path: f.abs_path },
    ...f.function === void 0 ? {} : { function: f.function },
    ...f.lineno === void 0 ? {} : { lineno: f.lineno },
    ...f.colno === void 0 ? {} : { colno: f.colno },
    ...f.in_app === void 0 ? {} : { in_app: f.in_app },
    ...f.context_line === void 0 ? {} : { context_line: f.context_line },
    ...f.pre_context === void 0 ? {} : { pre_context: [...f.pre_context] },
    ...f.post_context === void 0 ? {} : { post_context: [...f.post_context] }
  };
}
function toSwipEvent(event) {
  const tags = {};
  for (const [key, value] of Object.entries(event.tags ?? {})) {
    if (value !== void 0 && value !== null) tags[key] = String(value);
  }
  const values = event.exception?.values;
  const crumbs = event.breadcrumbs;
  const user = event.user;
  return {
    event_id: event.event_id ?? "",
    ...event.level !== void 0 ? { level: event.level } : {},
    tags,
    ...values ? {
      exception: {
        values: values.map((v) => ({
          type: v.type,
          value: v.value,
          ...v.mechanism ? { mechanism: { ...v.mechanism } } : {},
          // COPIED, not aliased: the view is what the scrub layer and any product
          // `scrub` hook mutate, and they must not be handed the vendor's own frame
          // objects to edit in place — the write-back is the ONE sanctioned way onto
          // the vendor event, and it fails closed.
          //
          // EXPLICIT ALLOWLIST, not `{ ...f }`. A blind spread is exactly how the
          // `request` leak happened elsewhere on this file (an allowlist that forgets
          // a field), and it is worse here: `f` is a REAL Sentry `StackFrame` at
          // runtime, which carries `vars` when `localVariables` is enabled. A spread
          // would hand `vars` to every scrub hook — ours and any product's — to
          // (mis)handle; naming the fields we carry means an unmodelled future field
          // defaults to DROPPED, not leaked. `vars` itself is named ONLY to leave it
          // out, on purpose — see `VendorStackFrame`.
          ...v.stacktrace ? {
            stacktrace: v.stacktrace.frames ? { frames: v.stacktrace.frames.map((f) => copyFrame(f)) } : {}
          } : {}
        }))
      }
    } : {},
    // The ROUTE. Carried into the view so the scrub layer can reach it — it is NOT
    // reliably the parameterized route (see `VendorEvent.transaction`), and it was the one
    // free-text field on the event this pipeline deliberately exempted.
    ...event.transaction === void 0 ? {} : { transaction: event.transaction },
    // The wtf() path. Carried into the view so the scrub layer can reach it — a wtf()
    // event has NO exception.values, so without this there is nothing for it to scrub.
    ...event.message === void 0 ? {} : { message: event.message },
    ...event.logentry?.message === void 0 ? {} : { logentry: { message: event.logentry.message } },
    ...crumbs ? {
      breadcrumbs: crumbs.map((c) => ({
        category: c.category,
        message: c.message,
        ...c.data ? { data: { ...c.data } } : {}
      }))
    } : {},
    // `id` is stringified for the same reason tags are (see the LOSSY note above); it is
    // never written back, so the vendor keeps whatever type it had.
    ...user ? {
      user: {
        ...user.id === void 0 ? {} : { id: String(user.id) },
        ...user.email === void 0 ? {} : { email: user.email },
        ...user.ip_address === void 0 || user.ip_address === null ? {} : { ip_address: user.ip_address },
        ...user.username === void 0 ? {} : { username: user.username },
        ...user.geo === void 0 ? {} : { geo: { ...user.geo } }
      }
    } : {}
    // `request`, `extra` and `contexts` are DELIBERATELY NOT CARRIED. They are dropped or
    // allowlisted on the write-back, never scrubbed (see `VendorRequest`, `VendorEvent`),
    // and a field the view never holds is a field no scrub hook — product-supplied or
    // otherwise — can put back on the wire.
  };
}
function originOf(event) {
  const value = event.tags?.[ORIGIN_TAG];
  return typeof value === "string" ? value : void 0;
}
function withVendorOrigin(view, origin) {
  const tags = { ...view.tags };
  if (origin === void 0) delete tags[ORIGIN_TAG];
  else tags[ORIGIN_TAG] = origin;
  return { ...view, tags };
}
function stripFrames(frames) {
  if (!frames) return;
  for (const frame of frames) {
    for (const key of FRAME_TEXT) delete frame[key];
    for (const key of FRAME_TEXT_ARRAYS) delete frame[key];
    delete frame.vars;
  }
}
function applyFrames(targetFrames, viewFrames) {
  if (!targetFrames) return;
  const aligned = viewFrames !== void 0 && viewFrames.length === targetFrames.length;
  if (!aligned) {
    stripFrames(targetFrames);
    return;
  }
  targetFrames.forEach((frame, i) => {
    const scrubbed = viewFrames[i];
    delete frame.vars;
    for (const key of FRAME_TEXT_ARRAYS) {
      const value = scrubbed[key];
      if (value === void 0) delete frame[key];
      else frame[key] = [...value];
    }
    for (const key of FRAME_TEXT) {
      const value = scrubbed[key];
      if (value === void 0) delete frame[key];
      else frame[key] = value;
    }
  });
}
function applySwipEvent(target, view) {
  if (view.event_id) target.event_id = view.event_id;
  if (isVendorSeverity(view.level)) target.level = view.level;
  const origin = originOf(target);
  const tags = { ...view.tags };
  if (origin === void 0) delete tags[ORIGIN_TAG];
  else tags[ORIGIN_TAG] = origin;
  target.tags = tags;
  const targetValues = target.exception?.values;
  if (targetValues) {
    const viewValues = view.exception?.values;
    const aligned = viewValues !== void 0 && targetValues.length === viewValues.length;
    targetValues.forEach((value, i) => {
      const scrubbed = aligned ? viewValues[i] : void 0;
      if (scrubbed === void 0) {
        delete value.value;
        stripFrames(value.stacktrace?.frames);
        return;
      }
      value.type = scrubbed.type;
      value.value = scrubbed.value;
      applyFrames(value.stacktrace?.frames, scrubbed.stacktrace?.frames);
    });
  }
  if (view.message === void 0) delete target.message;
  else target.message = view.message;
  if (target.logentry) {
    const message = view.logentry?.message;
    if (message === void 0) delete target.logentry;
    else target.logentry = { message };
  }
  if (view.transaction === void 0) delete target.transaction;
  else target.transaction = scrubString(view.transaction);
  delete target.extra;
  const contexts = target.contexts;
  if (contexts) {
    for (const key of Object.keys(contexts)) {
      if (!SDK_CONTEXT_KEYS.includes(key)) delete contexts[key];
    }
  }
  delete target.request;
  const targetCrumbs = target.breadcrumbs;
  if (targetCrumbs) {
    const viewCrumbs = view.breadcrumbs;
    const aligned = viewCrumbs !== void 0 && viewCrumbs.length === targetCrumbs.length;
    targetCrumbs.forEach((crumb, i) => {
      const scrubbed = aligned ? viewCrumbs[i] : void 0;
      if (scrubbed === void 0) {
        delete crumb.message;
        delete crumb.data;
        return;
      }
      if (scrubbed.message === void 0) delete crumb.message;
      else crumb.message = scrubbed.message;
      if (scrubbed.data === void 0) delete crumb.data;
      else crumb.data = scrubbed.data;
    });
  }
  const targetUser = target.user;
  if (targetUser) {
    const viewUser = view.user;
    if (viewUser?.email === void 0) delete targetUser.email;
    else targetUser.email = viewUser.email;
    if (viewUser?.ip_address === void 0) delete targetUser.ip_address;
    else targetUser.ip_address = viewUser.ip_address;
    if (viewUser?.username === void 0) delete targetUser.username;
    else targetUser.username = viewUser.username;
    if (viewUser?.geo === void 0) delete targetUser.geo;
    else targetUser.geo = viewUser.geo;
  }
  return target;
}
function runBeforeSend(event, consented, scrub2, chain) {
  let allowed;
  try {
    allowed = consented();
  } catch {
    allowed = false;
  }
  if (!allowed) return null;
  const origin = originOf(event);
  let view;
  try {
    view = toSwipEvent(event);
  } catch {
    return null;
  }
  const scrubbed = scrub2(view);
  let next = scrubbed === null || scrubbed === void 0 ? null : withVendorOrigin(scrubbed, origin);
  for (const fn of chain) {
    if (next === null) return null;
    const out = fn(next);
    next = out === null || out === void 0 ? null : withVendorOrigin(out, origin);
  }
  if (next === null) return null;
  return applySwipEvent(event, next);
}
var SEVERITIES2, ORIGIN_TAG, ORIGIN_TEE, FINGERPRINT_TAG, WTF_KEY_TAG, RESERVED_TAG_PREFIX, SWIP_CONTEXT_TAGS, SDK_CONTEXT_KEYS, FRAME_TEXT, FRAME_TEXT_ARRAYS;
var init_vendor_event = __esm({
  "node_modules/@sloopworks/swip-sentry/src/vendor-event.ts"() {
    init_src2();
    SEVERITIES2 = ["fatal", "error", "warning", "log", "info", "debug"];
    ORIGIN_TAG = "swip.origin";
    ORIGIN_TEE = "tee";
    FINGERPRINT_TAG = "swip.fingerprint";
    WTF_KEY_TAG = "swip.wtf_key";
    RESERVED_TAG_PREFIX = "swip.";
    SWIP_CONTEXT_TAGS = [
      "swip.source_id",
      "swip.tenant_id",
      "swip.product_id",
      "swip.environment_id",
      "swip.session_id",
      "swip.distinct_id"
    ];
    SDK_CONTEXT_KEYS = [
      "os",
      "device",
      "culture",
      "app",
      "runtime",
      "browser",
      "trace",
      "cloud_resource"
    ];
    FRAME_TEXT = ["filename", "abs_path", "function", "context_line"];
    FRAME_TEXT_ARRAYS = ["pre_context", "post_context"];
  }
});

// node_modules/@sloopworks/swip-sentry/src/privacy.ts
function scrubSentryEvent(event, opts) {
  try {
    return scrub(event, opts);
  } catch {
    return failClosed(event);
  }
}
function scrub(event, opts) {
  const values = event.exception?.values;
  const crumbs = event.breadcrumbs;
  const user = event.user;
  return {
    event_id: event.event_id,
    ...event.level === void 0 ? {} : { level: event.level },
    // Tags pass through UNTOUCHED. They are SWIP's own (`swip.origin`, `swip.fingerprint`,
    // `swip.wtf_key`) or came from `setContext`, and running scrubString over them would
    // eat the fingerprint outright — it is a hash, i.e. exactly what the high-entropy rule
    // is built to redact. Redacting the join key would be a strange way to protect a user.
    ...event.tags === void 0 ? {} : { tags: { ...event.tags } },
    ...values ? {
      exception: {
        // Same length, same order. `type` is `privacy_class: none` (a class name, not
        // user data) and `mechanism` is what the mirror reads to build ForeignError —
        // both survive.
        values: values.map((v) => ({
          type: v.type,
          ...v.value === void 0 ? {} : { value: scrubMessage(v.value, opts) },
          ...v.mechanism ? { mechanism: { ...v.mechanism } } : {},
          ...v.stacktrace ? { stacktrace: scrubStacktrace(v.stacktrace, opts) } : {}
        }))
      }
    } : {},
    // THE ROUTE. Scrubbed like every other free-text field, and NOT blanked under
    // `stripMessage`: a route is what makes an issue legible and a real parameterized one
    // (`GET /users/:id`) contains no user data at all — the risk is the FALLBACK, where
    // Sentry puts the raw request path (`/m/{token}`, `/reset?token=…`) because no route
    // matched, and `scrubString`'s `/m/` + high-entropy + query rules are precisely what
    // that needs. The write-back re-scrubs it as a backstop against a product `scrub` hook
    // (`applySwipEvent`), so an unscrubbed transaction cannot reach the wire by any path.
    ...event.transaction === void 0 ? {} : { transaction: scrubString(event.transaction) },
    // THE wtf() PATH — and the reason `stripMessage` was previously a half-measure. A
    // wtf() goes out through `captureMessage`, so Sentry's copy carries the string as a
    // TOP-LEVEL `message` (and, after the server normalizes it, `logentry.message`) with
    // NO `exception.values` AT ALL. Scrubbing only `exception` therefore blanked nothing
    // on a whole class of event: `stripMessage: true` stripped our owned copy while the
    // full message rode out on the vendor's. Same rules, same strip, both fields.
    ...event.message === void 0 ? {} : { message: scrubMessage(event.message, opts) },
    ...event.logentry?.message === void 0 ? {} : { logentry: { message: scrubMessage(event.logentry.message, opts) } },
    ...crumbs ? {
      breadcrumbs: crumbs.map((c) => ({
        category: c.category,
        // `data` DROPPED WHOLESALE — not scrubbed. It is where the URLs, query strings
        // and request/response bodies live (Sentry auto-instruments fetch/XHR), it is
        // free-form per breadcrumb type, and a scrubber over free-form vendor data is
        // a promise we cannot keep.
        ...c.message === void 0 ? {} : { message: scrubString(c.message) }
      }))
    } : {},
    ...user ? {
      // The anonymous id stays (it is what joins the two datasets); the two fields
      // that identify a human do not. `sendDefaultPii: false` is what stops Sentry
      // inferring `ip_address` in the first place — this is the belt to that brace.
      user: { ...user.id === void 0 ? {} : { id: user.id } }
    } : {}
  };
}
function scrubMessage(value, opts) {
  return opts.stripMessage ? "" : scrubString(value);
}
function scrubStacktrace(stack, opts) {
  const frames = stack.frames;
  if (!frames) return {};
  return {
    frames: frames.map((f) => ({
      ...f.filename === void 0 ? {} : { filename: scrubFrameText(f.filename, opts) },
      ...f.abs_path === void 0 ? {} : { abs_path: scrubFrameText(f.abs_path, opts) },
      ...f.function === void 0 ? {} : { function: scrubFrameText(f.function, opts) },
      ...f.lineno === void 0 ? {} : { lineno: f.lineno },
      ...f.colno === void 0 ? {} : { colno: f.colno },
      ...f.in_app === void 0 ? {} : { in_app: f.in_app },
      ...f.context_line === void 0 ? {} : { context_line: scrubFrameText(f.context_line, opts) },
      ...f.pre_context === void 0 ? {} : { pre_context: f.pre_context.map((line) => scrubFrameText(line, opts)) },
      ...f.post_context === void 0 ? {} : { post_context: f.post_context.map((line) => scrubFrameText(line, opts)) }
      // `vars` never reaches this function — see the doc comment above.
    }))
  };
}
function scrubFrameText(value, opts) {
  return opts.stripMessage ? "" : scrubString(value);
}
function failClosed(event) {
  try {
    const values = event.exception?.values;
    return {
      event_id: event.event_id,
      ...event.level === void 0 ? {} : { level: event.level },
      ...event.tags === void 0 ? {} : { tags: { ...event.tags } },
      ...values ? { exception: { values: values.map((v) => ({ type: v?.type, value: "" })) } } : {}
    };
  } catch {
    return { event_id: "", exception: { values: [] } };
  }
}
var init_privacy = __esm({
  "node_modules/@sloopworks/swip-sentry/src/privacy.ts"() {
    init_src2();
  }
});

// node_modules/@sloopworks/swip-sentry/src/index.ts
function toForeign(event) {
  const values = event.exception?.values;
  const thrown = values === void 0 || values.length === 0 ? void 0 : values[values.length - 1];
  const stacktrace = renderStack(thrown);
  return {
    eventId: event.event_id,
    type: thrown?.type,
    message: thrown?.value,
    severity: toSeverity(event.level),
    mechanism: thrown?.mechanism?.type,
    // Omitted, not `undefined`-valued: `ForeignError.stacktrace` is optional and
    // `mirror()` tests it for truthiness, so an absent stack and an empty one behave
    // alike — but the object stays clean for anyone comparing two ForeignErrors.
    ...stacktrace === void 0 ? {} : { stacktrace }
  };
}
function renderStack(first) {
  try {
    const frames = first?.stacktrace?.frames;
    if (first === void 0 || frames === void 0 || frames.length === 0) return void 0;
    const head = headerOf(first);
    const lines = head === "" ? [] : [head];
    let length = head.length;
    for (let i = frames.length - 1; i >= 0 && length < STACK_CHARS2; i--) {
      const line = `    at ${frameOf(frames[i])}`;
      lines.push(line);
      length += line.length + 1;
    }
    if (lines.length === 0) return void 0;
    const stack = scrubString(lines.join("\n"));
    return stack.length > STACK_CHARS2 ? stack.slice(0, STACK_CHARS2) : stack;
  } catch {
    return void 0;
  }
}
function headerOf(first) {
  const type = first.type ?? "";
  const value = first.value ?? "";
  if (type !== "" && value !== "") return `${type}: ${value}`;
  return type !== "" ? type : value;
}
function frameOf(frame) {
  let location = frame.filename ?? frame.abs_path ?? "<anonymous>";
  if (typeof frame.lineno === "number") {
    location += `:${frame.lineno}`;
    if (typeof frame.colno === "number") location += `:${frame.colno}`;
  }
  return frame.function ? `${frame.function} (${location})` : location;
}
function toSeverity(level) {
  switch (level) {
    case "fatal":
      return "fatal";
    case "warning":
      return "warn";
    case "error":
      return "error";
    case "log":
    case "info":
    case "debug":
      return "error";
    default:
      return "error";
  }
}
var LEVEL, STACK_CHARS2, FLUSH_TIMEOUT_MS2, SentryCrashReporter;
var init_src3 = __esm({
  "node_modules/@sloopworks/swip-sentry/src/index.ts"() {
    init_src2();
    init_vendor_event();
    init_privacy();
    init_options();
    init_vendor_event();
    LEVEL = {
      warn: "warning",
      error: "error",
      fatal: "fatal"
    };
    STACK_CHARS2 = 4096;
    FLUSH_TIMEOUT_MS2 = 2e3;
    SentryCrashReporter = class {
      constructor(sentry) {
        this.sentry = sentry;
        this.sentry.registerBeforeSend((event) => {
          try {
            if (event.tags?.[ORIGIN_TAG] === ORIGIN_TEE) return event;
            this.handler?.(toForeign(event));
          } catch {
          }
          return event;
        });
      }
      handler;
      capture(request) {
        const tags = {
          ...request.tags,
          [ORIGIN_TAG]: ORIGIN_TEE,
          [FINGERPRINT_TAG]: request.fingerprint
        };
        const level = LEVEL[request.severity];
        if (request.key !== void 0) {
          tags[WTF_KEY_TAG] = request.key;
          this.sentry.captureMessage(request.message ?? "", {
            level,
            tags,
            fingerprint: ["swip", request.key]
          });
          return;
        }
        this.sentry.captureException(request.error, { level, tags });
      }
      /**
       * Persistent tags on the vendor's GLOBAL scope — i.e. on every event it sends, including
       * the ones it captures itself. Two things had to change here, and both were silent:
       *
       * 1. RESERVED KEYS ARE REJECTED. `setContext({ "swip.origin": "tee" })` would have made
       *    EVERY real crash read as one of ours: `runBeforeSend` reads the origin off the
       *    vendor event, the tag would be on all of them, the mirror would never fire, no crash
       *    would ever join analytics — and nothing would fail. `swip.` is a reserved namespace
       *    in code now (`isReservedTag`), not a comment: the adapter's own tags (`swip.origin`,
       *    `swip.fingerprint`, `swip.wtf_key`, anything it adds later) are the adapter's to
       *    stamp. SWIP's identity/join keys (`swip.source_id`, `swip.tenant_id`,
       *    `swip.session_id`, …) are allowlisted — a product is EXPECTED to set those.
       *
       * 2. THE VALUES ARE SCRUBBED, with the SAME key-aware scrubber the owned event's attrs
       *    go through (`scrubField`). Without it the two datasets disagreed about PII by
       *    construction: `record(err, { token: "abc" })` is redacted in the owned event, while
       *    the same value stamped through `setContext` rode on EVERY Sentry event verbatim —
       *    the exact asymmetry that pushing the query rule down into `scrubString` was meant to
       *    end. The allowlisted `swip.*` ids are NOT scrubbed: they are SWIP-generated join
       *    keys, and the high-entropy rule would eat them exactly as it would eat the
       *    fingerprint (privacy.ts leaves the event's tags alone for the same reason).
       *
       * Non-string values coming in through the seam's `Record<string, string>` at runtime are
       * coerced per key, never all-or-nothing — one bad value must not take the bag with it.
       */
      setContext(tags) {
        const safe = {};
        for (const [key, value] of Object.entries(tags ?? {})) {
          if (isReservedTag(key)) continue;
          if (value === void 0 || value === null) continue;
          try {
            const text = typeof value === "string" ? value : String(value);
            safe[key] = SWIP_CONTEXT_TAGS.includes(key) ? text : scrubField(key, text);
          } catch {
          }
        }
        this.sentry.setTags(safe);
      }
      onForeignEvent(handler2) {
        this.handler = handler2;
      }
      /**
       * `Sentry.flush(timeout)` — wait for the teed events to actually leave the box.
       *
       * THE SERVERLESS HALF OF THE TEE. `SwipErrors.tee()` calls `capture()` and does not
       * await it, deliberately: `captureException` does its own capture, serialization and
       * disk write, and that belongs nowhere near a request's latency. On a long-lived
       * process the SDK's queue then drains on its own. On Vercel functions — which is what
       * Dayfold's API is — the process is frozen or killed when the invocation ends, and a
       * queued event is simply lost. A handler awaits `Swip.flush()` (init.ts) in its
       * `finally`; that reaches here through `SloopErrors.flush()`.
       *
       * NEVER REJECTS, NEVER THROWS SYNCHRONOUSLY, AND ALWAYS RESOLVES WITHIN `timeoutMs`
       * (INVARIANT 13) — it is awaited on the product's own request path. `Sentry.flush`
       * resolving `false` (the queue did not drain within the timeout) is a LOST EVENT, not an
       * error to raise: the request has already been served, and there is nothing a handler
       * could usefully do about it.
       *
       * THE DEADLINE IS OURS, NOT THE VENDOR'S. `this.sentry` is a STRUCTURAL seam: real
       * `@sentry/node` honours the timeout it is handed, but nothing in the type system says a
       * `SentryLike` must, and `await`ing a flush that never settles hangs the handler's
       * `finally` until the platform kills the function (a 504 — instrumentation taking the
       * product down). So the budget is imposed here, by racing the vendor, rather than being
       * delegated to it. `Swip.flush()` (init.ts) races the whole set again above this — belt
       * and braces, because this method is also reachable on its own.
       */
      async flush(timeoutMs = FLUSH_TIMEOUT_MS2) {
        let timer;
        try {
          await Promise.race([
            Promise.resolve(this.sentry.flush?.(timeoutMs)).then(() => void 0),
            new Promise((resolve) => {
              timer = setTimeout(resolve, timeoutMs);
            })
          ]);
        } catch {
        } finally {
          if (timer !== void 0) clearTimeout(timer);
        }
      }
    };
  }
});

// node_modules/@sloopworks/swip-sentry/src/options.ts
function verifyDsn(options) {
  const { region, orgId, projectId } = options;
  let host;
  let path;
  try {
    const url = new URL(options.dsn);
    if (url.protocol !== "https:" && url.protocol !== "http:") {
      throw new Error("unsupported protocol");
    }
    if (url.hostname === "" || url.username === "") throw new Error("missing host or public key");
    host = url.hostname;
    path = url.pathname.replace(/^\/+|\/+$/g, "");
  } catch {
    throw new Error(
      `[swip-sentry] MALFORMED SENTRY DSN \u2014 refusing to initialize. A DSN whose host cannot be parsed has no verifiable data-residency region, and this product declares region "${region}". Expected https://<key>@<host>/<project>.`
    );
  }
  if (!REGION_HOST2[region].test(host)) {
    throw new Error(
      `[swip-sentry] DSN REGION MISMATCH \u2014 refusing to initialize. This product declares data residency "${region}", but its DSN points at "${host}". The DSN's host is where the crash data PHYSICALLY LANDS \u2014 Sentry's regions are separate installations and no scrubbing, hook or config can move an event between them. Expected a host matching ${String(REGION_HOST2[region])}. Fix the DSN (create the project in the correct Sentry organization/region); do NOT soften this check \u2014 see the comment on verifyDsn.`
    );
  }
  if (!ORG_ID.test(orgId) || !PROJECT_ID.test(projectId)) {
    throw new Error(
      `[swip-sentry] MALFORMED SENTRY IDENTITY \u2014 refusing to initialize. orgId must be the DSN host's leading label (o<digits>) and projectId its path (<digits>); got orgId=${JSON.stringify(orgId)} projectId=${JSON.stringify(projectId)}. Both are public ids read straight off the DSN \u2014 they are declared so a wrong-project DSN cannot pass.`
    );
  }
  const dsnOrg = host.split(".")[0] ?? "";
  if (dsnOrg.toLowerCase() !== orgId.toLowerCase() || path !== projectId) {
    throw new Error(
      `[swip-sentry] DSN IDENTITY MISMATCH \u2014 refusing to initialize. This source declares Sentry org "${orgId}" project "${projectId}", but its DSN points at org "${dsnOrg}" project "${path}" (host "${host}"). The region gate cannot catch this: a product's Sentry projects normally share ONE org and therefore ONE host suffix, so a DSN for the WRONG PROJECT is in the right region and lands in the wrong issue stream \u2014 wrong alerts, wrong release health, wrong symbols, no failure anywhere. Point the env var at the project registry/products/<product>.yaml declares for this source.`
    );
  }
  return { verified: VERIFIED, host, orgId, projectId };
}
function buildSentryOptions(options, chain) {
  verifyDsn(options);
  return {
    dsn: options.dsn,
    release: options.release,
    ...options.dist === void 0 ? {} : { dist: options.dist },
    environment: options.environment,
    // WE do the sampling, in the storm gate. If Sentry also drops, the two datasets
    // disagree and nobody can tell which is lying.
    sampleRate: 1,
    // Dedupe (browser-only) would drop teed events SWIP kept; the SESSION integrations
    // (both SDKs, both defaults) send envelopes `beforeSend` NEVER SEES — i.e. straight
    // past the consent gate. See DEDUPE / SESSION above.
    integrations: (defaults) => defaults.filter((i) => !DEDUPE.test(i.name) && !SESSION.test(i.name)),
    // Sentry captures its own copy of everything, and none of it passes through
    // scrubString. Nothing we do to OUR event protects THEIR event.
    sendDefaultPii: false,
    // The OTHER envelope `beforeSend` never runs on. Client reports are outcome counts,
    // but they are still an unconsented request to the vendor from the user's IP, and they
    // are emitted whether or not a single event was ever allowed out. Off.
    sendClientReports: false,
    beforeSend: (event) => runBeforeSend(event, options.consented, scrubber(options), chain)
  };
}
function scrubber(options) {
  const configured = options.stripMessage;
  const stripMessage = typeof configured === "function" ? configured : () => configured === true;
  const extra = options.scrub;
  return (event) => {
    let strip;
    try {
      strip = stripMessage();
    } catch {
      strip = true;
    }
    const scrubbed = scrubSentryEvent(event, { stripMessage: strip });
    if (!extra) return scrubbed;
    try {
      return extra(scrubbed);
    } catch {
      return scrubbed;
    }
  };
}
function createSentryLike(api, chain, verified) {
  if (verified?.verified !== VERIFIED) {
    throw new Error(
      `[swip-sentry] createSentryLike requires a VerifiedDsn \u2014 call verifyDsn(options) (or, better, use initSentryNode/initSentryBrowser, which the generated init module already wires from the registry). Binding a Sentry SDK to the seam without verifying its DSN skips the data-residency and project-identity gates (ADR-0015, INVARIANT 32).`
    );
  }
  return {
    captureException: (error, hint) => api.captureException(error, toCaptureContext(hint)),
    // The level rides inside the capture context (toCaptureContext lifts hint.level) —
    // Sentry's own captureMessage takes one context argument, not a level and a hint.
    captureMessage: (message, hint) => api.captureMessage(message, toCaptureContext(hint)),
    setTags: (tags) => api.setTags(tags),
    registerBeforeSend: (fn) => chain.push(fn),
    // The serverless flush (SentryCrashReporter.flush -> here -> Sentry's own queue).
    flush: (timeoutMs) => api.flush(timeoutMs)
  };
}
function initSentry(sdk, options) {
  const chain = [];
  const verified = verifyDsn(options);
  sdk.init(buildSentryOptions(options, chain));
  return new SentryCrashReporter(createSentryLike(sdk, chain, verified));
}
var DEDUPE, SESSION, REGION_HOST2, VERIFIED, ORG_ID, PROJECT_ID;
var init_options = __esm({
  "node_modules/@sloopworks/swip-sentry/src/options.ts"() {
    init_src3();
    init_privacy();
    init_vendor_event();
    DEDUPE = /dedupe/i;
    SESSION = /session/i;
    REGION_HOST2 = {
      // A leading label is REQUIRED (`o4507….ingest.de.sentry.io`) — a bare `ingest.de.sentry.io`
      // is not a real DSN host, and a rule that accepts it is a rule that accepts a typo.
      eu: /^[a-z0-9-]+\.ingest\.de\.sentry\.io$/i,
      us: /^[a-z0-9-]+\.ingest\.(us\.)?sentry\.io$/i
    };
    VERIFIED = "swip-sentry.verified-dsn/1";
    ORG_ID = /^o[0-9]+$/i;
    PROJECT_ID = /^[0-9]+$/;
  }
});

// node_modules/@sloopworks/swip-sentry/src/node.ts
import * as Sentry from "@sentry/node";
function initSentryNode(options) {
  return initSentry(Sentry, options);
}
var init_node = __esm({
  "node_modules/@sloopworks/swip-sentry/src/node.ts"() {
    init_options();
  }
});

// node_modules/@sloopworks/swip-js/src/config-keys.ts
var configKeyImpl, ConfigKey;
var init_config_keys = __esm({
  "node_modules/@sloopworks/swip-js/src/config-keys.ts"() {
    init_key();
    configKeyImpl = {};
    for (const type of ["boolean", "string", "duration", "variant", "json"]) {
      configKeyImpl[type] = (name, variants) => ({ name, type, variants });
    }
    ConfigKey = configKeyImpl;
  }
});

// node_modules/@sloopworks/swip-schema-dayfold/src/generated/config.ts
var DayfoldConfig, DayfoldConfigDefaults;
var init_config = __esm({
  "node_modules/@sloopworks/swip-schema-dayfold/src/generated/config.ts"() {
    init_config_keys();
    DayfoldConfig = {
      analyticsEventsEnabled: ConfigKey.boolean("analytics.events.enabled"),
      swipErrorsEnabled: ConfigKey.boolean("swip.errors.enabled"),
      swipErrorsStormLimit: ConfigKey.json("swip.errors.storm_limit"),
      swipErrorsStormWindowMs: ConfigKey.duration("swip.errors.storm_window_ms"),
      swipErrorsWarnSampleRate: ConfigKey.json("swip.errors.warn_sample_rate"),
      syncRetryBackoff: ConfigKey.duration("sync.retry_backoff")
    };
    DayfoldConfigDefaults = {
      "analytics.events.enabled": { key: "analytics.events.enabled", type: "boolean", default: true },
      "swip.errors.enabled": { key: "swip.errors.enabled", type: "boolean", default: true },
      "swip.errors.storm_limit": { key: "swip.errors.storm_limit", type: "json", default: 5 },
      "swip.errors.storm_window_ms": { key: "swip.errors.storm_window_ms", type: "duration", default: "60s" },
      "swip.errors.warn_sample_rate": { key: "swip.errors.warn_sample_rate", type: "json", default: 1 },
      "sync.retry_backoff": { key: "sync.retry_backoff", type: "duration", default: "30s" }
    };
  }
});

// node_modules/@sloopworks/swip-schema-dayfold/src/generated/init.ts
var DayfoldSwip;
var init_init2 = __esm({
  "node_modules/@sloopworks/swip-schema-dayfold/src/generated/init.ts"() {
    init_errors();
    init_config();
    DayfoldSwip = {
      androidProd: () => ({
        product: "dayfold",
        tenant: "sloopworks",
        sourceKey: "swip_pub_dayfold_android_prod",
        endpoint: { stable: "https://t.dayfold.app", discovery: true },
        modules: ["analytics", "config", "errors", "telemetry", "swip-rk"],
        crashVendor: "sentry",
        channel: "prod"
      }),
      androidStaging: () => ({
        product: "dayfold",
        tenant: "sloopworks",
        sourceKey: "swip_pub_dayfold_android_stg",
        endpoint: { stable: "https://t.dayfold.app", discovery: true },
        modules: ["analytics", "config", "errors", "telemetry", "swip-rk"],
        crashVendor: "sentry",
        channel: "beta"
      }),
      apiProd: () => ({
        product: "dayfold",
        tenant: "sloopworks",
        sourceKey: "swip_pub_dayfold_api_prod",
        endpoint: { stable: "https://t.dayfold.app", discovery: true },
        modules: ["analytics", "config", "errors", "telemetry", "swip-rk"],
        crashVendor: "sentry",
        channel: "prod"
      }),
      webProd: () => ({
        product: "dayfold",
        tenant: "sloopworks",
        sourceKey: "swip_pub_dayfold_web_prod",
        endpoint: { stable: "https://t.dayfold.app", discovery: true },
        modules: ["analytics", "config", "errors", "telemetry", "swip-rk"],
        crashVendor: "sentry",
        channel: "prod"
      }),
      deps: (overrides) => ({
        ...overrides,
        configDefaults: DayfoldConfigDefaults,
        errorsFactory: (errorsDeps) => new SwipErrors(errorsDeps)
      })
    };
  }
});

// node_modules/@sloopworks/swip-schema-dayfold/src/generated/init-node.ts
var init_node_exports = {};
__export(init_node_exports, {
  DayfoldSwipNode: () => DayfoldSwipNode
});
function processEnv2() {
  return globalThis.process?.env ?? {};
}
function requireEnv2(env, name) {
  const value = env[name];
  if (value === void 0 || value === "") {
    throw new Error(
      `[swip] ${name} is unset \u2014 registry/products/dayfold.yaml declares it for the node crash reporter (crash_vendor: sentry). Set it in the deploy environment; do not hardcode it.`
    );
  }
  return value;
}
var DayfoldSwipNode;
var init_init_node = __esm({
  "node_modules/@sloopworks/swip-schema-dayfold/src/generated/init-node.ts"() {
    init_errors();
    init_node();
    init_config();
    init_init2();
    DayfoldSwipNode = {
      apiProd: () => DayfoldSwip.apiProd(),
      apiProdDeps: (overrides, crash) => {
        const env = crash.env ?? processEnv2();
        return {
          ...overrides,
          configDefaults: DayfoldConfigDefaults,
          errorsFactory: (errorsDeps) => new SwipErrors({
            ...errorsDeps,
            crashReporter: initSentryNode({
              dsn: requireEnv2(env, "SENTRY_NODE_EU_DSN"),
              region: "eu",
              orgId: "o4511720596570112",
              projectId: "4511734782820432",
              release: requireEnv2(env, "SENTRY_RELEASE"),
              environment: requireEnv2(env, "VERCEL_ENV"),
              consented: crash.consented,
              ...crash.stripMessage === void 0 ? {} : { stripMessage: crash.stripMessage },
              ...crash.scrub === void 0 ? {} : { scrub: crash.scrub }
            })
          })
        };
      }
    };
  }
});

// src/swip.ts
import { HTTPException } from "hono/http-exception";
function swip() {
  return handle;
}
async function initSwip(opts) {
  if (handle) return handle;
  if (!opts.required && !process.env.SENTRY_NODE_EU_DSN) {
    console.log("[swip] SENTRY_NODE_EU_DSN unset \u2014 error reporting is OFF (local dev).");
    return null;
  }
  const [{ Swip: Swip2 }, { DayfoldSwipAnalytics: DayfoldSwipAnalytics2 }, { DayfoldSwipNode: DayfoldSwipNode2 }] = await Promise.all([
    Promise.resolve().then(() => (init_src(), src_exports)),
    Promise.resolve().then(() => (init_analytics2(), analytics_exports)),
    Promise.resolve().then(() => (init_init_node(), init_node_exports))
  ]);
  const transport = DayfoldSwipAnalytics2.apiProdTransport();
  handle = Swip2.init(
    DayfoldSwipNode2.apiProd(),
    DayfoldSwipNode2.apiProdDeps(
      { transport },
      {
        // CONSENT, SERVER-SIDE (ADR 0059). An error raised inside this API is a defect in
        // OUR infrastructure, reported about OURSELVES: the API never calls
        // `analytics.identify()`, so the owned event's `distinct_id` is a per-container
        // anonymous id, and swip-sentry sends no `request` object (no url, query, cookies,
        // headers, body) and no `extra`. Nothing in an event names a family or a member.
        // There is therefore no user whose consent could be asked for, and no user-scoped
        // data to withhold — so the gate is open. It is a gate, not a formality: the day an
        // event can carry a family/member identifier, this must become a real decision
        // again (see ADR 0059 "When this must be revisited").
        consented: () => true,
        // Keep `exception.message` — it is the triage payload, and this API is
        // content-blind by construction (ADR 0015): it stores opaque blobs and never
        // parses family content, so a server-side exception message carries ids and
        // driver text, not household content. SWIP's scrubber still runs over it.
        stripMessage: () => false
      }
    )
  );
  handle.analytics.setConsent({ analytics: "denied", telemetry: "denied", errors: "granted" });
  return handle;
}
function swipErrors(get = swip) {
  return async (c, next) => {
    const s = get();
    if (!s) return next();
    let reported = false;
    const report = (err) => {
      if (reported) return;
      reported = true;
      if (err instanceof HTTPException && err.status < 500) return;
      s.errors.record(
        err,
        // The ROUTE PATTERN, never the URL: `/families/:fid/cards`, not the family id.
        { method: c.req.method, route: String(c.req.routePath ?? "unmatched") },
        "hono"
      );
    };
    try {
      await next();
      if (c.error) report(c.error);
    } catch (err) {
      report(err);
      throw err;
    } finally {
      await s.flush(SWIP_FLUSH_BUDGET_MS);
    }
  };
}
var SWIP_FLUSH_BUDGET_MS, handle;
var init_swip = __esm({
  "src/swip.ts"() {
    "use strict";
    SWIP_FLUSH_BUDGET_MS = 2e3;
    handle = null;
  }
});

// src/db.ts
var db_exports = {};
__export(db_exports, {
  pool: () => pool,
  q: () => q
});
import pg from "pg";
function q(text, params) {
  return pool.query(text, params);
}
var Pool, types, pool;
var init_db = __esm({
  "src/db.ts"() {
    "use strict";
    ({ Pool, types } = pg);
    types.setTypeParser(1184, (s) => s);
    types.setTypeParser(1114, (s) => s);
    pool = new Pool({
      connectionString: process.env.DATABASE_URL,
      max: process.env.VERCEL ? 1 : 10,
      // fail fast instead of hanging if the DB is unreachable (serverless).
      connectionTimeoutMillis: 1e4
    });
  }
});

// src/security.ts
import { createHash, timingSafeEqual } from "node:crypto";
function constantTimeEqual(presented, secret) {
  const a = createHash("sha256").update(presented, "utf8").digest();
  const b = createHash("sha256").update(secret, "utf8").digest();
  return timingSafeEqual(a, b);
}
function stripServerManaged(body) {
  const out = { ...body };
  for (const k of SERVER_MANAGED_CONTENT_FIELDS) delete out[k];
  return out;
}
function stampProvenance(body, credentialId) {
  const raw = body.provenance;
  const isPlain = raw != null && typeof raw === "object" && !Array.isArray(raw);
  const src = isPlain ? raw : {};
  const provenance = { credential_id: credentialId };
  if (typeof src.source === "string") provenance.source = src.source;
  if (typeof src.at === "string") provenance.at = src.at;
  return { ...body, provenance };
}
var SERVER_MANAGED_CONTENT_FIELDS;
var init_security = __esm({
  "src/security.ts"() {
    "use strict";
    SERVER_MANAGED_CONTENT_FIELDS = [
      "family_id",
      "version",
      "created_at",
      "updated_at",
      "deleted_at",
      "body_ref",
      // M1 object-storage spill key — never client-set at M0
      "provenance"
      // defense-in-depth: rebuilt server-side by stampProvenance
    ];
  }
});

// src/generated/content.ts
import { z } from "zod";
var ProvenanceSchema, TriggerSchema, ActionSchema, LinkPayloadSchema, ChecklistPayloadSchema, DocumentPayloadSchema, MilestonePayloadSchema, ContactPayloadSchema, LocationPayloadSchema, BudgetPayloadSchema, BlockSchema, SectionSchema, TimelineSchema, HubSchema, BriefingCardSchema, PlaceSchema, SyncResponseSchema;
var init_content = __esm({
  "src/generated/content.ts"() {
    "use strict";
    ProvenanceSchema = z.object({ "source": z.string().describe("claude | email | user | <url>"), "at": z.any(), "credential_id": z.string().describe("which credential pushed this (audit)").optional() }).strict();
    TriggerSchema = z.any().superRefine((x, ctx) => {
      const schemas = [z.object({ "geo": z.object({ "place_ref": z.string().optional(), "lat": z.number().optional(), "lng": z.number().optional(), "radius_m": z.number().int().default(150), "label": z.string().optional() }) }).strict(), z.object({ "when": z.object({ "at": z.any().optional(), "window": z.record(z.string(), z.any()).optional(), "relative": z.string().optional(), "recurring": z.string().optional(), "alert_offset": z.string().optional() }) }).strict(), z.object({ "activity": z.object({ "kind": z.enum(["walking", "running", "biking", "driving"]).optional() }) }).strict().describe("schema slot; matching DEFERRED")];
      const { errors, failed } = schemas.reduce(
        ({ errors: errors2, failed: failed2 }, schema) => ((result) => result.error ? {
          errors: [...errors2, ...result.error.issues],
          failed: failed2 + 1
        } : { errors: errors2, failed: failed2 })(
          schema.safeParse(x)
        ),
        { errors: [], failed: 0 }
      );
      const passed = schemas.length - failed;
      if (passed !== 1) {
        ctx.addIssue(errors.length ? {
          path: [],
          code: "invalid_union",
          errors: [errors],
          message: "Invalid input: Should pass single schema. Passed " + passed
        } : {
          path: [],
          code: "custom",
          errors: [errors],
          message: "Invalid input: Should pass single schema. Passed " + passed
        });
      }
    }).describe("ADR 0014 \u2014 matched ON-DEVICE; live position never leaves.");
    ActionSchema = z.object({ "label": z.string(), "action_id": z.string(), "params": z.record(z.string(), z.any()).optional() }).strict().describe("ADR 0016 RESERVED (bounded-now: buttons + structured asks; not built at MVP).");
    LinkPayloadSchema = z.object({ "url": z.string().url(), "label": z.string().optional(), "source": z.string().optional(), "thumbnailUrl": z.string().url().max(2048).describe("link preview image; https + allowlisted host (ADR 0036)").optional(), "thumbnailAlt": z.string().max(256).describe("a11y alt for thumbnailUrl").optional() }).strict();
    ChecklistPayloadSchema = z.object({ "items": z.array(z.object({ "id": z.any(), "text": z.string().describe("loop-authoritative at M0"), "done": z.boolean().describe("member-mutable (the done-triple)").default(false), "doneBy": z.string().describe("NEW (ADR 0038) \u2014 user id who toggled (display byline)").optional(), "doneAt": z.any().optional(), "ord": z.number().int().describe("NEW (ADR 0038) \u2014 order; loop-authoritative at M0 (\xA75.3); doneBy/doneAt/id are the new ADR-0038 fields").default(0), "due": z.any().optional(), "assignee": z.string().describe("loop-authoritative at M0").optional() }).strict()) }).strict();
    DocumentPayloadSchema = z.object({ "ref": z.string().describe("url | fileRef (links+small refs at MVP)"), "label": z.string().optional(), "kind": z.string().optional(), "thumbnailUrl": z.string().url().max(2048).describe("document preview image; https + allowlisted host (ADR 0036)").optional(), "thumbnailAlt": z.string().max(256).describe("a11y alt for thumbnailUrl").optional() }).strict();
    MilestonePayloadSchema = z.object({ "date": z.any(), "label": z.string() }).strict();
    ContactPayloadSchema = z.object({ "name": z.string(), "role": z.string().optional(), "phone": z.string().optional(), "email": z.string().optional(), "avatarUrl": z.string().url().max(2048).describe("contact avatar photo; https + allowlisted host (ADR 0036); falls back to initials").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed (ADR 0036)").optional() }).strict();
    LocationPayloadSchema = z.object({ "label": z.string(), "address": z.string().optional(), "mapUrl": z.string().optional() }).strict();
    BudgetPayloadSchema = z.object({ "items": z.array(z.object({ "label": z.string(), "amount": z.number(), "paid": z.boolean().default(false) }).strict()) }).strict();
    BlockSchema = z.object({ "id": z.any(), "type": z.enum(["text", "markdown", "link", "checklist", "document", "milestone", "contact", "location", "budget"]), "ord": z.number().int().default(0), "version": z.any().optional(), "body_md": z.string().max(1048576).describe("long-form markdown (text/markdown blocks); inline \u22641MB at M0, else spill to body_ref (06, M1)").optional(), "body_ref": z.string().describe("object-storage KEY when spilled (M1); never a URL; XOR with body_md").optional(), "payload": z.any().describe("structured fields for non-markdown block types; variant by `type` (see $comment)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "provenance": z.any() }).strict().and(z.any());
    SectionSchema = z.object({ "id": z.any(), "title": z.string().describe("[CONTENT/E2E-hole]").optional(), "ord": z.number().int().default(0), "version": z.any().optional(), "blocks": z.array(z.any()).optional() }).strict();
    TimelineSchema = z.object({ "title": z.string().describe("[CONTENT/E2E-hole]").optional(), "tz": z.string().describe("IANA timezone, author-stamped; anchors the day-boundary + NOW line"), "stops": z.array(z.any()).min(1) }).strict();
    HubSchema = z.object({ "id": z.any(), "type": z.string().describe("bounded template-catalog key (ADR 0004/0006): vacation|starting-college|move|party-event|new-baby|medical|school-year \u2014 app-validated"), "title": z.string().describe("[CONTENT/E2E-hole]"), "status": z.enum(["planning", "active", "archived"]).default("active"), "start_at": z.any().optional(), "end_at": z.any().optional(), "countdown_to": z.any().optional(), "version": z.any().optional(), "sections": z.array(z.any()).optional(), "timeline": z.any().optional(), "media": z.object({ "heroUrl": z.string().url().max(2048).describe("hero image (Hub detail header + list-row fallback). https + allowlisted host.").optional(), "thumbnailUrl": z.string().url().max(2048).describe("list-row 1:1 thumbnail; absent \u2192 falls back to heroUrl client-side.").optional(), "heroFit": z.enum(["cover", "contain"]).describe("cover=photo edge-to-edge crop; contain=logo letterboxed on accent tint.").optional(), "imageAlt": z.string().max(256).describe("a11y alt \u2192 contentDescription (else derived from title).").optional(), "icon": z.string().max(40).describe("curated icon NAME, server-validated vs the bundled set (ADR 0036); unknown \u2192 fallback tile.").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed (edge/tile/chip/scrim); never body text (WCAG 1.4.1). Lowercased on write.").optional() }).strict().describe("visual enrichment (ADR 0036; all optional, absent = unenriched/today's look). URLs are https + allowlisted-host (ADR 0036 shared validator); icon \u2208 curated set; accentColor is decorative-only.").optional() }).strict();
    BriefingCardSchema = z.object({ "id": z.any(), "kind": z.enum(["action", "info", "weather", "countdown"]).default("info"), "title": z.string().max(4096), "body_md": z.string().max(1048576).describe("limited inline markdown only (1MB cap, F8)").optional(), "target": z.object({ "hubId": z.string().optional(), "sectionId": z.string().optional(), "blockId": z.string().optional() }).strict().describe("deep-link into a hub (resolved client-side vs local cache, nearest-ancestor)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "not_before": z.any().optional(), "expires_at": z.any().optional(), "importance": z.number().gte(0).lte(1).describe("ADR 0043 \xA72b \u2014 bounded author weight/hint fed to the on-device Priority & Ordering Engine. The device decides final position (no author-controlled ordinal); the engine CLAMPS it so an author cannot pin spam to the top (the constitution's calm guarantee constrains the scoring function).").optional(), "version": z.any().optional(), "provenance": z.any(), "type": z.enum(["file", "link", "invite", "contact", "geo", "email"]).describe("content type (ADR 0022 D1) \u2014 drives the Now-card / detail layout. OPTIONAL for back-compat with kind-only M0 cards.").optional(), "media": z.object({ "icon": z.string().max(40).describe("curated icon NAME (server-validated); unknown \u2192 fallback.").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed; never body text. Lowercased on write.").optional(), "thumbnailUrl": z.string().url().max(2048).describe("optional leading thumbnail; https + allowlisted host.").optional(), "imageAlt": z.string().max(256).describe("a11y alt for thumbnailUrl.").optional(), "imageFit": z.enum(["cover", "contain"]).optional() }).strict().describe("card visual enrichment (ADR 0036; all optional). icon+accent on the kind chip + optional leading thumbnail. Same shared URL/host/icon/hex validation as Hub.media.").optional(), "hubRef": z.string().describe("parent Hub id \u2014 the adaptive supporting pane's 'PART OF THIS HUB' (ADR 0022; CL-10). Optional.").optional(), "relatedKicker": z.string().describe("section header for the RELATED rows (e.g. 'FROM THE SAME EMAIL'). CL-8.").optional(), "related": z.array(z.object({ "relation": z.string().describe("same-email | same-thread | same-hub | same-trip | attachment | contact-of"), "targetId": z.string(), "targetType": z.enum(["file", "link", "invite", "contact", "geo", "email"]), "title": z.string().optional(), "sub": z.string().optional() }).strict()).describe("cross-links to other cards in THIS family (CL-8). targetId resolves client-side vs the local cache; title/sub are author-denormalized so a row renders without resolving. Same-tenant only (rides authorizeTenant).").optional(), "privacy": z.object({ "storage": z.enum(["on_device", "in_browser", "location_local", "matched_on_device"]).optional() }).strict().describe("honesty chip (ADR 0014/0015) \u2014 a claim allowed ONLY where a real schema/API/client boundary enforces it.").optional(), "payload": z.any().superRefine((x, ctx) => {
      const schemas = [z.object({ "file": z.object({ "filename": z.string().optional(), "mime": z.string().optional(), "size": z.number().int().optional(), "pages": z.number().int().optional(), "source": z.string().optional(), "owner": z.string().optional(), "modified": z.string().datetime({ offset: true }).optional(), "sharedWith": z.array(z.string()).optional(), "docRef": z.string().describe("url | opaque storage ref").optional() }).strict() }).strict(), z.object({ "link": z.object({ "url": z.string().url().optional(), "domain": z.string().optional(), "title": z.string().optional(), "ogDesc": z.string().describe("author-stamped OG; server never fetches the URL (no SSRF)").optional(), "favicon": z.string().optional(), "kind": z.enum(["page", "form"]).optional(), "fieldCount": z.number().int().optional(), "closesAt": z.string().datetime({ offset: true }).optional(), "savedAt": z.string().datetime({ offset: true }).optional() }).strict() }).strict(), z.object({ "invite": z.object({ "eventName": z.string().optional(), "host": z.string().optional(), "startAt": z.string().datetime({ offset: true }).optional(), "place": z.string().optional(), "rsvpBy": z.string().datetime({ offset: true }).optional(), "rsvpState": z.enum(["yes", "no", "none"]).describe("display-of-state at M0 (no write path; ADR 0020/0016)").optional(), "guestCount": z.number().int().optional(), "confirmedCount": z.number().int().optional(), "notes": z.string().optional() }).strict() }).strict(), z.object({ "contact": z.object({ "name": z.string().optional(), "company": z.string().optional(), "role": z.string().optional(), "phone": z.string().optional(), "email": z.string().optional(), "address": z.string().optional(), "hours": z.string().optional(), "linkedEventId": z.string().optional(), "deliveryWindow": z.string().optional() }).strict() }).strict(), z.object({ "geo": z.object({ "label": z.string().optional(), "address": z.string().optional(), "lat": z.number().optional(), "lng": z.number().optional(), "etaMin": z.number().int().optional(), "distance": z.string().optional(), "travelMode": z.string().optional(), "parking": z.string().optional(), "leaveBy": z.string().datetime({ offset: true }).optional(), "linkedEventId": z.string().optional() }).strict() }).strict(), z.object({ "email": z.object({ "from": z.string().optional(), "fromAddr": z.string().optional(), "subject": z.string().optional(), "date": z.string().datetime({ offset: true }).optional(), "threadLen": z.number().int().optional(), "bodyExcerpt": z.string().describe("[E2E-ciphertext] authored over the operator's OWN mail (CLI/Claude) \u2014 never a server-side Gmail restricted-scope read (Guardrail 3)").optional(), "attachments": z.array(z.object({ "name": z.string().optional(), "mime": z.string().optional(), "size": z.number().int().optional() }).strict()).optional(), "labels": z.array(z.string()).optional() }).strict() }).strict()];
      const { errors, failed } = schemas.reduce(
        ({ errors: errors2, failed: failed2 }, schema) => ((result) => result.error ? {
          errors: [...errors2, ...result.error.issues],
          failed: failed2 + 1
        } : { errors: errors2, failed: failed2 })(
          schema.safeParse(x)
        ),
        { errors: [], failed: 0 }
      );
      const passed = schemas.length - failed;
      if (passed !== 1) {
        ctx.addIssue(errors.length ? {
          path: [],
          code: "invalid_union",
          errors: [errors],
          message: "Invalid input: Should pass single schema. Passed " + passed
        } : {
          path: [],
          code: "custom",
          errors: [errors],
          message: "Invalid input: Should pass single schema. Passed " + passed
        });
      }
    }).describe("[E2E-ciphertext at M1] typed content payload, variant selected by `type` (ADR 0022 D1). Inline oneOf (no internal $ref) so codegen emits TYPED variants, never z.any.").optional() }).strict().describe("the 'Now' surface");
    PlaceSchema = z.object({ "id": z.any(), "label": z.string(), "kind": z.enum(["home", "school", "store", "other"]).describe("category (drives the place icon in the UI; design alignment)").default("other"), "lat": z.number(), "lng": z.number(), "radius_m": z.number().int().default(150), "version": z.any().optional() }).strict().describe("ADR 0014 reusable named place; family content (encrypted at rest, never live position)");
    SyncResponseSchema = z.object({ "changes": z.object({ "hubs": z.array(z.any()).optional(), "sections": z.array(z.any()).optional(), "blocks": z.array(z.any()).optional(), "cards": z.array(z.any()).optional(), "places": z.array(z.any()).optional() }), "tombstones": z.array(z.object({ "type": z.enum(["hub", "section", "block", "card", "place"]), "id": z.string() }).strict()), "next_cursor": z.string().optional(), "has_more": z.boolean() }).strict().describe("GET /families/{fid}/sync (03 \xA7sync)");
  }
});

// src/content-validation.ts
function crossValidateCard(card) {
  const hasType = card.type != null;
  const hasPayload = card.payload != null;
  if (!hasType && !hasPayload) return [];
  if (hasType !== hasPayload) {
    return [{
      path: [hasType ? "payload" : "type"],
      message: hasType ? "a typed card (`type` set) must carry a matching `payload`" : "`payload` requires a `type` discriminator"
    }];
  }
  const keys = Object.keys(card.payload);
  if (keys.length !== 1 || keys[0] !== card.type) {
    return [{
      path: ["payload"],
      message: `payload variant "${keys[0] ?? "(none)"}" does not match type "${String(card.type)}"`
    }];
  }
  return [];
}
function isEncryptedEnvelope(p) {
  if (typeof p !== "object" || p === null || Array.isArray(p)) return false;
  const o = p;
  return typeof o.ct === "string" && typeof o.nonce === "string" && typeof o.alg === "string";
}
function blockPayloadIssues(block) {
  const { type, payload, body_md } = block;
  if (payload == null) return [];
  if (isEncryptedEnvelope(payload)) return [];
  if (typeof payload !== "object" || Array.isArray(payload)) {
    return [{ path: ["payload"], message: "payload must be an object" }];
  }
  if (type === "text" || type === "markdown") return [];
  const p = payload;
  const has = (...keys) => keys.some((k) => p[k] != null);
  const arr = (k) => Array.isArray(p[k]) && p[k].length > 0;
  const hasBody = typeof body_md === "string" && body_md.trim().length > 0;
  const ok = type === "checklist" ? arr("items") : type === "budget" ? arr("items") || has("total", "spent") : type === "document" ? has("ref", "docRef") : type === "link" ? has("url") : type === "contact" ? has("name") : type === "location" ? has("label") : type === "milestone" ? has("date", "label") || hasBody : true;
  return ok ? [] : [{ path: ["payload"], message: `block ${String(type)}: payload present but missing its core field` }];
}
function hubTimelineIssues(hub) {
  const t = hub.timeline;
  if (t == null) return [];
  if (typeof t !== "object" || Array.isArray(t)) return [{ path: ["timeline"], message: "timeline must be an object" }];
  const tl = t;
  const issues = [];
  if (typeof tl.tz !== "string" || tl.tz.trim() === "") issues.push({ path: ["timeline", "tz"], message: "timeline.tz (IANA) is required" });
  if (!Array.isArray(tl.stops) || tl.stops.length === 0) {
    issues.push({ path: ["timeline", "stops"], message: "timeline.stops must be a non-empty array" });
    return issues;
  }
  tl.stops.forEach((s, i) => {
    if (typeof s !== "object" || s === null || Array.isArray(s)) {
      issues.push({ path: ["timeline", "stops", i], message: "stop must be an object" });
      return;
    }
    const stop = s;
    if (typeof stop.at !== "string" || stop.at.trim() === "") issues.push({ path: ["timeline", "stops", i, "at"], message: "stop.at is required" });
    if (typeof stop.title !== "string" || stop.title.trim() === "") issues.push({ path: ["timeline", "stops", i, "title"], message: "stop.title is required" });
    if (stop.attachments != null) {
      if (!Array.isArray(stop.attachments)) issues.push({ path: ["timeline", "stops", i, "attachments"], message: "attachments must be an array" });
      else stop.attachments.forEach((a, j) => {
        if (typeof a !== "object" || a === null || Array.isArray(a)) {
          issues.push({ path: ["timeline", "stops", i, "attachments", j], message: "attachment must be an object" });
          return;
        }
        const k = a.kind;
        if (typeof k !== "string" || !ATTACH_KINDS.has(k)) issues.push({ path: ["timeline", "stops", i, "attachments", j, "kind"], message: `attachment.kind must be one of ${[...ATTACH_KINDS].join("|")}` });
      });
    }
  });
  return issues;
}
var ATTACH_KINDS;
var init_content_validation = __esm({
  "src/content-validation.ts"() {
    "use strict";
    ATTACH_KINDS = /* @__PURE__ */ new Set(["call", "nav", "link", "open"]);
  }
});

// src/media-validation.ts
function imageUrlError(url) {
  if (typeof url !== "string") return "must be a string";
  if (url.length === 0 || url.length > MAX_URL_LEN) return "url length out of range";
  if (/[\x00-\x20\x7f\\]/.test(url)) return "url contains illegal characters";
  let u;
  try {
    u = new URL(url);
  } catch {
    return "url does not parse";
  }
  if (u.protocol !== "https:") return "scheme must be https";
  if (u.username !== "" || u.password !== "") return "userinfo not allowed";
  if (u.port !== "") return "explicit port not allowed";
  const host = u.hostname.replace(/\.$/, "");
  if (!ALLOWED_IMAGE_HOSTS.has(host)) return `host "${host}" is not on the image allowlist`;
  if (u.pathname.toLowerCase().endsWith(".svg")) return "SVG images are not allowed";
  return null;
}
function iconError(icon) {
  if (typeof icon !== "string") return "must be a string";
  return CURATED_ICONS.has(icon) ? null : `icon "${icon}" is not in the curated set`;
}
function accentHexError(hex) {
  if (typeof hex !== "string") return "must be a string";
  return ACCENT_RE.test(hex) ? null : "accentColor must match #RRGGBB";
}
function urlField(media, key, base, out) {
  if (media[key] == null) return;
  const e = imageUrlError(media[key]);
  if (e) out.push({ path: [...base, key], message: e });
}
function validateHubMedia(media) {
  if (media == null) return [];
  if (typeof media !== "object") return [{ path: ["media"], message: "must be an object" }];
  const m = media, out = [];
  urlField(m, "heroUrl", ["media"], out);
  urlField(m, "thumbnailUrl", ["media"], out);
  if (m.icon != null) {
    const e = iconError(m.icon);
    if (e) out.push({ path: ["media", "icon"], message: e });
  }
  if (m.accentColor != null) {
    const e = accentHexError(m.accentColor);
    if (e) out.push({ path: ["media", "accentColor"], message: e });
  }
  return out;
}
function validateCardMedia(media) {
  if (media == null) return [];
  if (typeof media !== "object") return [{ path: ["media"], message: "must be an object" }];
  const m = media, out = [];
  urlField(m, "thumbnailUrl", ["media"], out);
  if (m.icon != null) {
    const e = iconError(m.icon);
    if (e) out.push({ path: ["media", "icon"], message: e });
  }
  if (m.accentColor != null) {
    const e = accentHexError(m.accentColor);
    if (e) out.push({ path: ["media", "accentColor"], message: e });
  }
  return out;
}
function validateBlockPayloadMedia(type, payload) {
  if (payload == null || typeof payload !== "object") return [];
  const p = payload, out = [];
  if (type === "link" || type === "document") urlField(p, "thumbnailUrl", ["payload"], out);
  if (type === "contact") {
    urlField(p, "avatarUrl", ["payload"], out);
    if (p.accentColor != null) {
      const e = accentHexError(p.accentColor);
      if (e) out.push({ path: ["payload", "accentColor"], message: e });
    }
  }
  return out;
}
function normalizedAccent(hex) {
  return typeof hex === "string" ? hex.toLowerCase() : null;
}
var ALLOWED_IMAGE_HOSTS, CURATED_ICONS, MAX_URL_LEN, ACCENT_RE;
var init_media_validation = __esm({
  "src/media-validation.ts"() {
    "use strict";
    ALLOWED_IMAGE_HOSTS = /* @__PURE__ */ new Set(["upload.wikimedia.org"]);
    CURATED_ICONS = /* @__PURE__ */ new Set([
      "school",
      "luggage",
      "medical",
      "move",
      "party",
      "baby",
      "calendar",
      "location",
      "link",
      "document",
      "contact",
      "budget",
      "travel",
      "car",
      "food",
      "pet",
      "sport",
      "list"
    ]);
    MAX_URL_LEN = 2048;
    ACCENT_RE = /^#[0-9a-fA-F]{6}$/;
  }
});

// src/content/visibility.ts
function cardVisible(row, caller) {
  if (caller.legacy) return true;
  if (!row.visibility || row.visibility === "family") return true;
  return !!caller.userId && Array.isArray(row.audience) && row.audience.includes(caller.userId);
}
function cardVisibilityClause(caller, nextParamIndex) {
  if (caller.legacy) return { sql: "", params: [] };
  return {
    sql: ` AND (visibility = 'family' OR ($${nextParamIndex} = ANY(audience)))`,
    params: [caller.userId ?? "\0"]
    // non-null sentinel; a real user id never equals it
  };
}
var init_visibility = __esm({
  "src/content/visibility.ts"() {
    "use strict";
  }
});

// src/repo.ts
async function upsertCard(familyId, id3, b) {
  const visibility = b.visibility === "restricted" ? "restricted" : "family";
  const audience = visibility === "restricted" && Array.isArray(b.audience) ? b.audience : null;
  const r = await q(
    `INSERT INTO briefing_cards
       (id, family_id, kind, title, body_md, target_hub_id, target_section_id,
        target_block_id, provenance, triggers, actions, not_before, expires_at,
        type, payload, privacy, hub_ref, related, related_kicker, visibility, audience, media, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       kind=EXCLUDED.kind, title=EXCLUDED.title, body_md=EXCLUDED.body_md,
       target_hub_id=EXCLUDED.target_hub_id, target_section_id=EXCLUDED.target_section_id,
       target_block_id=EXCLUDED.target_block_id, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions,
       not_before=EXCLUDED.not_before, expires_at=EXCLUDED.expires_at,
       type=EXCLUDED.type, payload=EXCLUDED.payload, privacy=EXCLUDED.privacy,
       hub_ref=EXCLUDED.hub_ref, related=EXCLUDED.related, related_kicker=EXCLUDED.related_kicker,
       visibility=EXCLUDED.visibility, audience=EXCLUDED.audience, media=EXCLUDED.media,
       version=briefing_cards.version + 1, deleted_at=NULL
     RETURNING *`,
    [
      id3,
      familyId,
      b.kind ?? "info",
      b.title,
      b.body_md ?? null,
      b.target?.hubId ?? null,
      b.target?.sectionId ?? null,
      b.target?.blockId ?? null,
      J(b.provenance),
      J(b.triggers),
      J(b.actions),
      b.not_before ?? null,
      b.expires_at ?? null,
      b.type ?? null,
      J(b.payload),
      J(b.privacy),
      b.hubRef ?? null,
      J(b.related),
      b.relatedKicker ?? null,
      visibility,
      audience,
      J(b.media)
    ]
  );
  return r.rows[0];
}
async function listCards(familyId, caller) {
  const vis = cardVisibilityClause(caller, 2);
  const r = await q(
    `SELECT * FROM briefing_cards WHERE family_id=$1 AND deleted_at IS NULL${vis.sql}
     ORDER BY not_before NULLS LAST, id`,
    [familyId, ...vis.params]
  );
  return r.rows;
}
async function softDeleteCard(familyId, id3) {
  const r = await q(
    `UPDATE briefing_cards SET deleted_at=now()
     WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`,
    [familyId, id3]
  );
  return (r.rowCount ?? 0) > 0;
}
async function syncContent(familyId, su, st, si, limit = SYNC_LIMIT) {
  const r = await q(
    `SELECT updated_at, type, id, family_id, deleted_at, payload,
            hub_id, hub_visibility, hub_created_by FROM (
       SELECT updated_at, 'card' AS type, id, family_id, deleted_at,
              to_jsonb(briefing_cards.*) AS payload,
              NULL::text AS hub_id, NULL::text AS hub_visibility, NULL::text AS hub_created_by
         FROM briefing_cards WHERE family_id=$1
       UNION ALL
       SELECT updated_at, 'hub' AS type, id, family_id, deleted_at,
              to_jsonb(hubs.*) AS payload,
              NULL::text AS hub_id, NULL::text AS hub_visibility, NULL::text AS hub_created_by
         FROM hubs WHERE family_id=$1
       UNION ALL
       SELECT s.updated_at, 'section' AS type, s.id, s.family_id, s.deleted_at,
              to_jsonb(s.*) AS payload,
              h.id AS hub_id, h.visibility AS hub_visibility, h.created_by AS hub_created_by
         FROM sections s JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
        WHERE s.family_id=$1
       UNION ALL
       SELECT b.updated_at, 'block' AS type, b.id, b.family_id, b.deleted_at,
              to_jsonb(b.*) AS payload,
              h.id AS hub_id, h.visibility AS hub_visibility, h.created_by AS hub_created_by
         FROM blocks b
         JOIN sections s ON s.family_id=b.family_id AND s.id=b.section_id
         JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
        WHERE b.family_id=$1
     ) merged
     WHERE (updated_at, type, id) > ($2::timestamptz, $3, $4)
     ORDER BY updated_at, type, id LIMIT $5`,
    [familyId, su === "" ? "-infinity" : su, st, si, limit]
  );
  return r.rows;
}
var J, SYNC_LIMIT;
var init_repo = __esm({
  "src/repo.ts"() {
    "use strict";
    init_db();
    init_visibility();
    J = (v) => v == null ? null : JSON.stringify(v);
    SYNC_LIMIT = 200;
  }
});

// src/auth/tokens.ts
var tokens_exports = {};
__export(tokens_exports, {
  jwks: () => jwks,
  mintAccess: () => mintAccess,
  verifyAccess: () => verifyAccess
});
import { SignJWT, jwtVerify, importJWK } from "jose";
import { randomUUID } from "node:crypto";
async function mintAccess({ sub, cid }) {
  return new SignJWT({ cid }).setProtectedHeader({ alg: "EdDSA", kid: KID }).setSubject(sub).setIssuer(ISS).setAudience(AUD).setIssuedAt().setJti(randomUUID()).setExpirationTime(ACCESS_TTL).sign(await privKeyP);
}
async function verifyAccess(token) {
  const key = await pubKeyP;
  const { payload, protectedHeader } = await jwtVerify(token, key, {
    algorithms: ["EdDSA"],
    issuer: ISS,
    audience: AUD,
    clockTolerance: LEEWAY
  });
  if (!protectedHeader.kid || !ALLOWLIST.has(protectedHeader.kid)) throw new Error("bad kid");
  return { sub: String(payload.sub), cid: String(payload.cid), jti: String(payload.jti) };
}
async function jwks() {
  return { keys: [pubJwk] };
}
var AUTH_SIGNING_KEY, AUTH_ISS, AUTH_AUD, ISS, AUD, ACCESS_TTL, LEEWAY, privJwk, KID, ALLOWLIST, privKeyP, pubJwk, pubKeyP;
var init_tokens = __esm({
  "src/auth/tokens.ts"() {
    "use strict";
    AUTH_SIGNING_KEY = process.env.AUTH_SIGNING_KEY;
    AUTH_ISS = process.env.AUTH_ISS;
    AUTH_AUD = process.env.AUTH_AUD;
    if (!AUTH_SIGNING_KEY) throw new Error("Missing required env var: AUTH_SIGNING_KEY");
    if (!AUTH_ISS) throw new Error("Missing required env var: AUTH_ISS");
    if (!AUTH_AUD) throw new Error("Missing required env var: AUTH_AUD");
    ISS = AUTH_ISS;
    AUD = AUTH_AUD;
    ACCESS_TTL = "5m";
    LEEWAY = 30;
    privJwk = JSON.parse(AUTH_SIGNING_KEY);
    KID = privJwk.kid;
    ALLOWLIST = /* @__PURE__ */ new Set([KID]);
    privKeyP = importJWK({ ...privJwk, alg: "EdDSA" }, "EdDSA");
    pubJwk = (() => {
      const { d, ...pub } = privJwk;
      return { ...pub, alg: "EdDSA", use: "sig" };
    })();
    pubKeyP = importJWK(pubJwk, "EdDSA");
  }
});

// src/auth/middleware.ts
function bearer(c) {
  const h = c.req.header("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7) : void 0;
}
async function authorizeTenant(c, fid) {
  const token = bearer(c);
  if (!token) return { status: 401 };
  const legacySecret = process.env.HOUSEHOLD_SECRET || "";
  if (legacySecret && constantTimeEqual(token, legacySecret)) {
    try {
      const r = await q(
        `SELECT * FROM credentials WHERE id=$1 AND revoked_at IS NULL`,
        [process.env.HOUSEHOLD_CREDENTIAL_ID || ""]
      );
      const cred = r.rows[0];
      if (!cred) return { status: 401 };
      if (cred.family_scope !== fid) return { status: 404 };
      return { cred, userId: null, role: null, scopes: cred.scopes ?? [], legacy: true };
    } catch {
      return { status: 401 };
    }
  }
  let claims;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    claims = await verifyAccess2(token);
  } catch {
    return { status: 401 };
  }
  try {
    const r = await q(`SELECT * FROM credentials WHERE id=$1`, [claims.cid]);
    const cred = r.rows[0];
    if (!cred || cred.revoked_at) return { status: 401 };
    const m = await q(`SELECT role, status FROM memberships WHERE user_id=$1 AND family_id=$2`, [claims.sub, fid]);
    if (m.rowCount === 0) return { status: 404 };
    if (m.rows[0].status !== "active") return { status: 403 };
    if (cred.family_scope && cred.family_scope !== fid) return { status: 404 };
    return { cred, userId: claims.sub, role: m.rows[0].role, scopes: cred.scopes ?? [], legacy: false };
  } catch {
    return { status: 401 };
  }
}
var init_middleware = __esm({
  "src/auth/middleware.ts"() {
    "use strict";
    init_db();
    init_security();
  }
});

// src/auth/scope.ts
var scope_exports = {};
__export(scope_exports, {
  grantScopes: () => grantScopes,
  grantedHubIds: () => grantedHubIds,
  hubGrantsFor: () => hubGrantsFor,
  requireScope: () => requireScope,
  resolveGrants: () => resolveGrants,
  scopeAllows: () => scopeAllows
});
async function resolveGrants(credId2) {
  const r = await q(`SELECT scope FROM credential_grants WHERE credential_id=$1`, [credId2]);
  return r.rows.map((x) => x.scope);
}
function scopeAllows(grants, resource, action) {
  if (grants.includes(`content:${action}`)) return true;
  if (resource !== "content" && grants.includes(`${resource}:${action}`)) return true;
  return false;
}
async function requireScope(credId2, resource, action) {
  return scopeAllows(await resolveGrants(credId2), resource, action);
}
function grantedHubIds(grants, action) {
  if (grants.includes(`content:${action}`)) return null;
  const prefix = "hub:", suffix = `:${action}`;
  return grants.filter((g) => g.startsWith(prefix) && g.endsWith(suffix) && g.length > prefix.length + suffix.length).map((g) => g.slice(prefix.length, g.length - suffix.length));
}
function hubGrantsFor(hubIds) {
  return hubIds.flatMap((id3) => [`hub:${id3}:read`, `hub:${id3}:write`]);
}
async function grantScopes(credId2, scopes, client) {
  const exec = client ? (t, p) => client.query(t, p) : (t, p) => q(t, p);
  for (const s of scopes) {
    await exec(`INSERT INTO credential_grants(credential_id, scope) VALUES ($1,$2) ON CONFLICT DO NOTHING`, [credId2, s]);
  }
}
var init_scope = __esm({
  "src/auth/scope.ts"() {
    "use strict";
    init_db();
  }
});

// src/content/hubs.ts
function hubVisible(row, caller, allowListHas = () => false) {
  if (caller.legacy) return true;
  if (!row.visibility || row.visibility === "family") return true;
  if (caller.userId && row.created_by && caller.userId === row.created_by) return true;
  return !!caller.userId && !!row.id && allowListHas(row.id);
}
function hubVisibilityClause(caller, p) {
  if (caller.legacy) return { sql: "", params: [] };
  return {
    sql: ` AND (visibility='family' OR created_by=$${p}
              OR EXISTS (SELECT 1 FROM resource_visibility rv
                          WHERE rv.family_id=hubs.family_id AND rv.hub_id=hubs.id AND rv.user_id=$${p}))`,
    params: [caller.userId ?? "\0"]
  };
}
async function listHubs(familyId, caller, grantedHubIds2) {
  const vis = hubVisibilityClause(caller, 2);
  const params = [familyId, ...vis.params];
  let grantSql = "";
  if (grantedHubIds2 !== null) {
    grantSql = ` AND id = ANY($${params.length + 1})`;
    params.push(grantedHubIds2);
  }
  const r = await q(
    `SELECT * FROM hubs WHERE family_id=$1 AND deleted_at IS NULL${vis.sql}${grantSql}
      ORDER BY coalesce(start_at, created_at), id`,
    params
  );
  return r.rows;
}
async function getHub(familyId, id3) {
  const r = await q(`SELECT * FROM hubs WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, id3]);
  return r.rows[0] ?? null;
}
async function allowListFor(familyId, hubId) {
  const r = await q(`SELECT user_id FROM resource_visibility WHERE family_id=$1 AND hub_id=$2`, [familyId, hubId]);
  return new Set(r.rows.map((x) => x.user_id));
}
async function roleFor(familyId, hubId, userId) {
  const r = await q(
    `SELECT role FROM resource_visibility WHERE family_id=$1 AND hub_id=$2 AND user_id=$3`,
    [familyId, hubId, userId]
  );
  return r.rows[0]?.role ?? null;
}
function hubWritableByMember(hub, caller, role) {
  if (caller.legacy) return true;
  if (caller.userId && hub.created_by && caller.userId === hub.created_by) return true;
  return role === "contributor" || role === "co_owner";
}
async function upsertHub(familyId, id3, b, caller, visibility, audience) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const r = await client.query(
      `INSERT INTO hubs (id, family_id, type, title, status, start_at, end_at, countdown_to, visibility, created_by, media, timeline, version)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1)
       ON CONFLICT (family_id, id) DO UPDATE SET
         type=EXCLUDED.type, title=EXCLUDED.title, status=EXCLUDED.status,
         start_at=EXCLUDED.start_at, end_at=EXCLUDED.end_at, countdown_to=EXCLUDED.countdown_to,
         visibility=EXCLUDED.visibility, created_by=COALESCE(hubs.created_by, EXCLUDED.created_by),
         media=EXCLUDED.media, timeline=EXCLUDED.timeline,
         version=hubs.version + 1, deleted_at=NULL
       RETURNING *`,
      [
        id3,
        familyId,
        b.type,
        b.title,
        b.status ?? "active",
        b.start_at ?? null,
        b.end_at ?? null,
        b.countdown_to ?? null,
        visibility,
        caller.userId,
        J2(b.media),
        J2(b.timeline)
      ]
    );
    const targetAudience = visibility === "restricted" ? new Set(audience ?? []) : /* @__PURE__ */ new Set();
    const cur = await client.query(`SELECT user_id FROM resource_visibility WHERE family_id=$1 AND hub_id=$2`, [familyId, id3]);
    const toRemove = cur.rows.map((x) => x.user_id).filter((uid) => !targetAudience.has(uid));
    if (toRemove.length)
      await client.query(
        `DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id=$2 AND user_id = ANY($3)`,
        [familyId, id3, toRemove]
      );
    for (const uid of targetAudience)
      await client.query(`INSERT INTO resource_visibility(family_id,hub_id,user_id) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, [familyId, id3, uid]);
    await client.query("COMMIT");
    return r.rows[0];
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}
async function archiveHub(familyId, id3) {
  const r = await q(`UPDATE hubs SET status='archived' WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id3]);
  return (r.rowCount ?? 0) > 0;
}
async function softDeleteHub(familyId, id3) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const h = await client.query(`UPDATE hubs SET deleted_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id3]);
    if ((h.rowCount ?? 0) === 0) {
      await client.query("ROLLBACK");
      return false;
    }
    await client.query(`UPDATE sections SET deleted_at=now() WHERE family_id=$1 AND hub_id=$2 AND deleted_at IS NULL`, [familyId, id3]);
    await client.query(
      `UPDATE blocks SET deleted_at=now()
        WHERE family_id=$1 AND deleted_at IS NULL
          AND section_id IN (SELECT id FROM sections WHERE family_id=$1 AND hub_id=$2)`,
      [familyId, id3]
    );
    await client.query("COMMIT");
    return true;
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}
async function softDeleteBlock(familyId, id3) {
  const r = await q(`UPDATE blocks SET deleted_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id3]);
  return (r.rowCount ?? 0) > 0;
}
async function blockForDelete(familyId, id3) {
  const r = await q(`SELECT section_id, created_by, deleted_at FROM blocks WHERE family_id=$1 AND id=$2`, [familyId, id3]);
  if (r.rowCount === 0) return null;
  const row = r.rows[0];
  return { section_id: row.section_id, created_by: row.created_by ?? null, deleted: row.deleted_at != null };
}
async function getHubTree(familyId, hubId) {
  const hub = await getHub(familyId, hubId);
  if (!hub) return null;
  const sections = (await q(`SELECT * FROM sections WHERE family_id=$1 AND hub_id=$2 AND deleted_at IS NULL ORDER BY ord, id`, [familyId, hubId])).rows;
  const blocks = (await q(
    `SELECT * FROM blocks WHERE family_id=$1 AND deleted_at IS NULL
       AND section_id IN (SELECT id FROM sections WHERE family_id=$1 AND hub_id=$2) ORDER BY ord, id`,
    [familyId, hubId]
  )).rows;
  return { hub, sections, blocks };
}
async function hubAudience(familyId, hubId) {
  const r = await q(
    `SELECT m.user_id AS uid, u.display_name, u.avatar_color, u.avatar_ref, m.role,
            CASE WHEN m.user_id = h.created_by THEN 'co_owner' ELSE rv2.role END AS participation_role,
            (m.user_id = h.created_by) AS is_author,
            (h.visibility = 'family'
             OR m.user_id = h.created_by
             OR EXISTS (SELECT 1 FROM resource_visibility rv
                         WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=m.user_id)) AS permitted
       FROM memberships m
       JOIN users u ON u.id = m.user_id
       JOIN hubs h ON h.family_id=$1 AND h.id=$2
       LEFT JOIN resource_visibility rv2 ON rv2.family_id=$1 AND rv2.hub_id=$2 AND rv2.user_id=m.user_id
      WHERE m.family_id=$1 AND m.status='active'
      ORDER BY (m.role='owner') DESC, u.display_name, m.user_id`,
    [familyId, hubId]
  );
  return r.rows;
}
async function canManageHub(familyId, hubId, caller) {
  if (caller.legacy) return true;
  if (!caller.userId) return false;
  const r = await q(
    `SELECT 1 FROM hubs h
      WHERE h.family_id=$1 AND h.id=$2 AND h.deleted_at IS NULL
        AND (h.created_by=$3
             OR EXISTS (SELECT 1 FROM resource_visibility rv
                         WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=$3 AND rv.role='co_owner'))`,
    [familyId, hubId, caller.userId]
  );
  return (r.rowCount ?? 0) > 0;
}
async function setParticipant(familyId, hubId, uid, role) {
  const r = await q(
    `INSERT INTO resource_visibility (family_id, hub_id, user_id, role)
     VALUES ($1,$2,$3,$4)
     ON CONFLICT (family_id, hub_id, user_id) DO UPDATE SET role=EXCLUDED.role
     RETURNING *`,
    [familyId, hubId, uid, role]
  );
  return r.rows[0];
}
async function removeParticipant(familyId, hubId, uid) {
  const r = await q(
    `DELETE FROM resource_visibility rv
      WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=$3
        AND rv.user_id <> (SELECT created_by FROM hubs WHERE family_id=$1 AND id=$2)
      RETURNING 1`,
    [familyId, hubId, uid]
  );
  return (r.rowCount ?? 0) > 0;
}
async function setHubVisibility(familyId, hubId, visibility) {
  const r = await q(
    `UPDATE hubs SET visibility=$3, updated_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING *`,
    [familyId, hubId, visibility]
  );
  return r.rows[0] ?? null;
}
async function liveHubOfSection(familyId, sectionId) {
  const r = await q(
    `SELECT s.hub_id FROM sections s JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
      WHERE s.family_id=$1 AND s.id=$2 AND s.deleted_at IS NULL AND h.deleted_at IS NULL`,
    [familyId, sectionId]
  );
  return r.rows[0]?.hub_id ?? null;
}
async function upsertSection(familyId, id3, hubId, b) {
  const live = await q(`SELECT 1 FROM hubs WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, hubId]);
  if (live.rowCount === 0) return null;
  const r = await q(
    `INSERT INTO sections (id, family_id, hub_id, title, ord, version)
     VALUES ($1,$2,$3,$4,$5,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       hub_id=EXCLUDED.hub_id, title=EXCLUDED.title, ord=EXCLUDED.ord,
       version=sections.version + 1, deleted_at=NULL
     RETURNING *`,
    [id3, familyId, hubId, b.title ?? null, b.ord ?? 0]
  );
  return r.rows[0];
}
async function getBlock(familyId, id3) {
  const r = await q(`SELECT * FROM blocks WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, id3]);
  return r.rows[0] ?? null;
}
async function upsertBlock(familyId, id3, sectionId, b, opts = {}) {
  const allowResurrect = opts.allowResurrect !== false;
  const live = await q(`SELECT 1 FROM sections WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, sectionId]);
  if (live.rowCount === 0) return null;
  const r = await q(
    `INSERT INTO blocks (id, family_id, section_id, type, payload, body_md, body_ref, provenance, triggers, actions, ord, created_by, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       section_id=EXCLUDED.section_id, type=EXCLUDED.type, payload=EXCLUDED.payload,
       body_md=EXCLUDED.body_md, body_ref=EXCLUDED.body_ref, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions, ord=EXCLUDED.ord,
       version=blocks.version + 1, deleted_at=NULL
       ${allowResurrect ? "" : "WHERE blocks.deleted_at IS NULL"}
     RETURNING *`,
    [
      id3,
      familyId,
      sectionId,
      b.type,
      J2(b.payload),
      b.body_md ?? null,
      b.body_ref ?? null,
      J2(b.provenance),
      J2(b.triggers),
      J2(b.actions),
      b.ord ?? 0,
      opts.createdBy ?? null
    ]
  );
  return r.rows[0] ?? null;
}
var J2;
var init_hubs = __esm({
  "src/content/hubs.ts"() {
    "use strict";
    init_db();
    J2 = (v) => v == null ? null : JSON.stringify(v);
  }
});

// src/content/write-guard.ts
function isMemberWrite(a) {
  return !a.legacy && a.cred?.kind === "app";
}
function memberDeleteForbidden(a, createdBy) {
  if (!isMemberWrite(a)) return false;
  return a.userId == null || createdBy !== a.userId;
}
function ifMatchFails(header, liveVersion) {
  if (header == null || header.trim() === "") return false;
  const want = header.replace(/^W\//, "").replace(/^"|"$/g, "").trim();
  return String(liveVersion ?? "") !== want;
}
async function blockState(familyId, id3) {
  const r = await q(`SELECT version, deleted_at FROM blocks WHERE family_id=$1 AND id=$2`, [familyId, id3]);
  if (r.rowCount === 0) return { exists: false, deleted: false, version: null };
  const row = r.rows[0];
  return { exists: true, deleted: row.deleted_at != null, version: Number(row.version) };
}
async function hubWriteGate(familyId, hubId, caller) {
  const hub = await getHub(familyId, hubId);
  if (!hub) return "absent";
  const allow = await allowListFor(familyId, hubId);
  const visible = hubVisible(
    hub,
    { userId: caller.userId, legacy: caller.legacy },
    () => !!caller.userId && allow.has(caller.userId)
  );
  if (!visible) return "invisible";
  if (!await requireScope(caller.cred.id, `hub:${hubId}`, "write")) return "denied";
  const isAuthor = !!caller.userId && !!hub.created_by && caller.userId === hub.created_by;
  const role = !caller.legacy && !isAuthor && caller.userId ? await roleFor(familyId, hubId, caller.userId) : null;
  if (!hubWritableByMember(hub, { userId: caller.userId, legacy: caller.legacy }, role)) return "denied";
  return "ok";
}
var init_write_guard = __esm({
  "src/content/write-guard.ts"() {
    "use strict";
    init_db();
    init_hubs();
    init_scope();
  }
});

// src/content/oplog.ts
async function findOp(familyId, opId) {
  const r = await q(
    `SELECT result_kind, result_ref, result_version FROM op_log WHERE family_id=$1 AND op_id=$2`,
    [familyId, opId]
  );
  if (r.rowCount === 0) return null;
  const row = r.rows[0];
  return {
    result_kind: row.result_kind ?? null,
    result_ref: row.result_ref ?? null,
    result_version: row.result_version != null ? Number(row.result_version) : null
  };
}
async function recordOp(familyId, opId, kind, ref, version) {
  await q(
    `INSERT INTO op_log (family_id, op_id, result_kind, result_ref, result_version)
     VALUES ($1,$2,$3,$4,$5) ON CONFLICT (family_id, op_id) DO NOTHING`,
    [familyId, opId, kind, ref, version]
  );
}
var init_oplog = __esm({
  "src/content/oplog.ts"() {
    "use strict";
    init_db();
  }
});

// src/auth/sweep.ts
var sweep_exports = {};
__export(sweep_exports, {
  CONTENT_TOMBSTONE_RETENTION_DAYS: () => CONTENT_TOMBSTONE_RETENTION_DAYS,
  sweep: () => sweep
});
async function sweep(graceMs = 24 * 3600 * 1e3) {
  const grace = new Date(Date.now() - graceMs).toISOString();
  const rate = (await q(
    `DELETE FROM rate_limits WHERE window_start < $1 AND (locked_until IS NULL OR locked_until < now())`,
    [grace]
  )).rowCount ?? 0;
  const devices = (await q(
    `DELETE FROM device_authorizations
       WHERE created_at < $1 AND (expires_at < now() OR status IN ('denied','expired','consumed'))`,
    [grace]
  )).rowCount ?? 0;
  const invites = (await q(
    `DELETE FROM invites i
       WHERE i.used_count = 0 AND i.status <> 'active' AND i.expires_at < $1
         AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.invite_id = i.id)`,
    [grace]
  )).rowCount ?? 0;
  const refresh = (await q(
    `DELETE FROM refresh_tokens WHERE expires_at < $1`,
    [grace]
  )).rowCount ?? 0;
  const opLogGrace = new Date(Date.now() - OP_LOG_TTL_MS).toISOString();
  const opLog = (await q(
    `DELETE FROM op_log WHERE created_at < $1`,
    [opLogGrace]
  )).rowCount ?? 0;
  const tombGrace = new Date(Date.now() - CONTENT_TOMBSTONE_RETENTION_MS).toISOString();
  let contentTombstones = 0;
  for (const table of ["blocks", "sections", "briefing_cards", "hubs"]) {
    contentTombstones += (await q(
      `DELETE FROM ${table} WHERE deleted_at IS NOT NULL AND deleted_at < $1`,
      [tombGrace]
    )).rowCount ?? 0;
  }
  return { rate_limits: rate, device_authorizations: devices, invites, refresh_tokens: refresh, op_log: opLog, content_tombstones: contentTombstones };
}
var OP_LOG_TTL_MS, CONTENT_TOMBSTONE_RETENTION_DAYS, CONTENT_TOMBSTONE_RETENTION_MS;
var init_sweep = __esm({
  "src/auth/sweep.ts"() {
    "use strict";
    init_db();
    OP_LOG_TTL_MS = 7 * 24 * 3600 * 1e3;
    CONTENT_TOMBSTONE_RETENTION_DAYS = Number(process.env.CONTENT_TOMBSTONE_RETENTION_DAYS) || 90;
    CONTENT_TOMBSTONE_RETENTION_MS = CONTENT_TOMBSTONE_RETENTION_DAYS * 24 * 3600 * 1e3;
  }
});

// src/auth/identity.ts
var identity_exports = {};
__export(identity_exports, {
  FirebaseVerifier: () => FirebaseVerifier,
  StubVerifier: () => StubVerifier,
  createFamily: () => createFamily,
  findOrCreateUser: () => findOrCreateUser,
  mintCredentialFor: () => mintCredentialFor
});
import { randomBytes } from "node:crypto";
import { jwtVerify as jwtVerify2, decodeJwt, createRemoteJWKSet } from "jose";
async function findOrCreateUser(idn) {
  const existing = await q(
    `SELECT user_id FROM user_identities WHERE provider=$1 AND provider_uid=$2`,
    [idn.provider, idn.provider_uid]
  );
  if (existing.rowCount === 1) return { userId: existing.rows[0].user_id };
  const userId = id("usr");
  const r = await q(
    // Orphan-users invariant: the CTE's INSERT INTO users always commits its row,
    // even when the outer INSERT INTO user_identities hits ON CONFLICT DO NOTHING
    // (i.e. a concurrent caller won the race). That orphan users row has no FK
    // referencing it and is harmless today. Any future schema work that adds
    // references to users.id (e.g. a soft-delete flag, audit log FK, etc.) must
    // either tolerate these orphans or add a cleanup sweep.
    `WITH u AS (INSERT INTO users(id) VALUES ($1) RETURNING id)
     INSERT INTO user_identities(id,user_id,provider,provider_uid,email_verified)
     VALUES ($2, $1, $3, $4, $5)
     ON CONFLICT (provider, provider_uid) DO NOTHING
     RETURNING user_id`,
    [userId, id("uid"), idn.provider, idn.provider_uid, !!idn.email_verified]
  );
  if (r.rowCount === 1) return { userId: r.rows[0].user_id };
  const w = await q(
    `SELECT user_id FROM user_identities WHERE provider=$1 AND provider_uid=$2`,
    [idn.provider, idn.provider_uid]
  );
  return { userId: w.rows[0].user_id };
}
async function createFamily(userId, name) {
  const familyId = id("fam");
  const c = await pool.connect();
  try {
    await c.query("BEGIN");
    await c.query(`INSERT INTO families(id,name,created_by) VALUES ($1,$2,$3)`, [familyId, name, userId]);
    await c.query(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ($1,$2,'owner','active')`, [userId, familyId]);
    await c.query("COMMIT");
  } catch (e) {
    await c.query("ROLLBACK");
    throw e;
  } finally {
    c.release();
  }
  return { familyId };
}
async function mintCredentialFor(userId) {
  const credentialId = id("cred");
  await q(
    `INSERT INTO credentials(id,user_id,kind,scopes) VALUES ($1,$2,'app','{content:read,content:write,content:delete}')`,
    [credentialId, userId]
  );
  const { grantScopes: grantScopes2 } = await Promise.resolve().then(() => (init_scope(), scope_exports));
  await grantScopes2(credentialId, ["content:read", "content:write", "content:delete"]);
  return { credentialId };
}
var StubVerifier, FIREBASE_JWKS_URL, FirebaseVerifier, id;
var init_identity = __esm({
  "src/auth/identity.ts"() {
    "use strict";
    init_db();
    StubVerifier = class {
      async verify(a) {
        const o = a;
        if (!o?.provider || !o?.provider_uid) throw new Error("bad stub identity");
        return { provider: o.provider, provider_uid: o.provider_uid, email_verified: !!o.email_verified };
      }
    };
    FIREBASE_JWKS_URL = process.env.FIREBASE_JWKS_URI || "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    FirebaseVerifier = class {
      jwks;
      opts;
      // NB: no constructor parameter properties — Node's strip-only TS loader
      // (`node src/server.ts`) rejects them (ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX),
      // even though esbuild (vitest + the Vercel bundle) accepts them.
      constructor(opts) {
        this.opts = opts;
        this.jwks = opts.jwks ?? createRemoteJWKSet(new URL(FIREBASE_JWKS_URL));
      }
      async verify(assertion) {
        const idToken = typeof assertion === "string" ? assertion : assertion?.idToken;
        if (!idToken) throw new Error("missing idToken");
        const iss = `https://securetoken.google.com/${this.opts.projectId}`;
        let payload;
        if (this.opts.emulator) {
          payload = decodeJwt(idToken);
          if (payload.iss !== iss) throw new Error("bad iss");
          if (payload.aud !== this.opts.projectId) throw new Error("bad aud");
          const exp = Number(payload.exp);
          if (!exp || exp * 1e3 < Date.now()) throw new Error("expired");
          if (!payload.sub) throw new Error("missing sub");
        } else {
          ({ payload } = await jwtVerify2(idToken, this.jwks, {
            algorithms: ["RS256"],
            issuer: iss,
            audience: this.opts.projectId
          }));
        }
        const fb = payload.firebase ?? {};
        const provider = fb.sign_in_provider;
        if (!provider || provider === "anonymous") throw new Error("unsupported provider");
        const ids = fb.identities?.[provider];
        const providerUid = Array.isArray(ids) && ids[0] ? String(ids[0]) : String(payload.sub);
        return { provider, provider_uid: providerUid, email_verified: !!payload.email_verified };
      }
    };
    id = (p) => p + "_" + randomBytes(9).toString("hex");
  }
});

// src/auth/audit.ts
var audit_exports = {};
__export(audit_exports, {
  audit: () => audit
});
async function audit(event, opts = {}) {
  await q(
    `INSERT INTO audit_log(event, actor_user_id, family_id, detail) VALUES ($1,$2,$3,$4)`,
    [event, opts.actorUserId ?? null, opts.familyId ?? null, JSON.stringify(opts.detail ?? {})]
  );
}
var init_audit = __esm({
  "src/auth/audit.ts"() {
    "use strict";
    init_db();
  }
});

// src/auth/refresh.ts
var refresh_exports = {};
__export(refresh_exports, {
  hashToken: () => hashToken,
  issueRefresh: () => issueRefresh,
  rotate: () => rotate
});
import { randomBytes as randomBytes2, createHash as createHash2 } from "node:crypto";
async function issueRefresh(credentialId, client) {
  const opaque = randomBytes2(32).toString("base64url");
  const run = client ? client.query.bind(client) : q;
  await run(
    `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at)
     VALUES ($1,$2, now() + ($3 || ' days')::interval)`,
    [hashToken(opaque), credentialId, String(ABS_TTL_DAYS)]
  );
  return opaque;
}
async function rotate(opaque) {
  const h = hashToken(opaque);
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const cas = await client.query(
      `UPDATE refresh_tokens SET consumed_at=now()
       WHERE token_hash=$1 AND consumed_at IS NULL AND expires_at > now()
       RETURNING credential_id`,
      [h]
    );
    if (cas.rowCount === 1) {
      const credentialId = cas.rows[0].credential_id;
      const nextOpaque = randomBytes2(32).toString("base64url");
      const nextHash = hashToken(nextOpaque);
      await client.query(
        `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at)
         VALUES ($1,$2, now() + ($3 || ' days')::interval)`,
        [nextHash, credentialId, String(ABS_TTL_DAYS)]
      );
      await client.query(
        `UPDATE refresh_tokens SET superseded_by=$1 WHERE token_hash=$2`,
        [nextHash, h]
      );
      await client.query("COMMIT");
      return { refresh: nextOpaque };
    }
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
  const row = await q(
    `SELECT credential_id, consumed_at, superseded_by FROM refresh_tokens WHERE token_hash=$1`,
    [h]
  );
  if (row.rowCount === 0) return null;
  const { credential_id: cid, consumed_at, superseded_by } = row.rows[0];
  if (!consumed_at) return null;
  const gc = await pool.connect();
  let graceResult = null;
  let graceCollision = false;
  let genuineReuse = false;
  try {
    await gc.query("BEGIN");
    await gc.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [cid]);
    const grace = await gc.query(
      `SELECT 1 FROM refresh_tokens prior
         JOIN refresh_tokens succ ON succ.token_hash = prior.superseded_by
        WHERE prior.token_hash=$1 AND prior.consumed_at > now() - interval '20 seconds'
          AND succ.consumed_at IS NULL`,
      [h]
    );
    if (grace.rowCount === 1 && superseded_by) {
      const cas2 = await gc.query(
        `UPDATE refresh_tokens SET consumed_at=now()
           WHERE token_hash=$1 AND consumed_at IS NULL AND expires_at > now() RETURNING credential_id`,
        [superseded_by]
      );
      if (cas2.rowCount === 1) {
        const nextOpaque = randomBytes2(32).toString("base64url");
        const nextHash = hashToken(nextOpaque);
        await gc.query(
          `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at, graced_from)
           VALUES ($1,$2, now() + ($3 || ' days')::interval, $4)`,
          [nextHash, cid, String(ABS_TTL_DAYS), h]
        );
        await gc.query(
          `UPDATE refresh_tokens SET superseded_by=$1 WHERE token_hash=$2`,
          [nextHash, superseded_by]
        );
        graceResult = { refresh: nextOpaque, graced: true };
      } else {
        genuineReuse = true;
        await gc.query(
          `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
          [cid]
        );
      }
    } else if (!superseded_by) {
      genuineReuse = true;
      await gc.query(
        `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
        [cid]
      );
    } else {
      const collision = await gc.query(
        `SELECT 1 FROM refresh_tokens next_token
           JOIN refresh_tokens issued ON issued.token_hash = next_token.superseded_by
          WHERE next_token.token_hash=$1
            AND issued.graced_from=$2
            AND next_token.consumed_at > now() - interval '20 seconds'`,
        [superseded_by, h]
      );
      if (collision.rowCount === 1) {
        graceCollision = true;
      } else {
        genuineReuse = true;
        await gc.query(
          `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
          [cid]
        );
      }
    }
    await gc.query("COMMIT");
  } catch (e) {
    await gc.query("ROLLBACK");
    throw e;
  } finally {
    gc.release();
  }
  if (graceResult) return graceResult;
  if (graceCollision) return { reuse: true };
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  await audit2("refresh.reuse_revoked", { detail: { credential_id: cid } });
  return { reuse: true };
}
var ABS_TTL_DAYS, hashToken;
var init_refresh = __esm({
  "src/auth/refresh.ts"() {
    "use strict";
    init_db();
    ABS_TTL_DAYS = 45;
    hashToken = (s) => createHash2("sha256").update(s, "utf8").digest("hex");
  }
});

// src/auth/ratelimit.ts
var ratelimit_exports = {};
__export(ratelimit_exports, {
  clientIp: () => clientIp,
  hit: () => hit,
  isLocked: () => isLocked,
  recordFailure: () => recordFailure,
  resetFailures: () => resetFailures
});
function clientIp(c) {
  return c.req.header("x-vercel-forwarded-for") || c.req.header("x-forwarded-for")?.split(",").pop()?.trim() || "unknown";
}
async function hit(key, windowSecs, cap) {
  const r = await q(
    `INSERT INTO rate_limits(key, window_start, count) VALUES ($1, ${winSql}, 1)
     ON CONFLICT (key, window_start) DO UPDATE SET count = rate_limits.count + 1
     RETURNING count`,
    [key, windowSecs]
  );
  const count = r.rows[0].count;
  return { ok: count <= cap, count };
}
async function isLocked(key) {
  const r = await q(`SELECT 1 FROM rate_limits WHERE key=$1 AND locked_until > now() LIMIT 1`, [key]);
  return (r.rowCount ?? 0) > 0;
}
async function recordFailure(key, windowSecs, threshold, lockSecs) {
  await q(
    `INSERT INTO rate_limits(key, window_start, count) VALUES ($1, ${winSql}, 1)
     ON CONFLICT (key, window_start) DO UPDATE SET
       count = rate_limits.count + 1,
       locked_until = CASE
         WHEN rate_limits.count + 1 >= $3
         THEN now() + ($4 || ' seconds')::interval
         ELSE rate_limits.locked_until
       END`,
    [key, windowSecs, threshold, String(lockSecs)]
  );
}
async function resetFailures(key) {
  await q(`DELETE FROM rate_limits WHERE key=$1`, [key]);
}
var winSql;
var init_ratelimit = __esm({
  "src/auth/ratelimit.ts"() {
    "use strict";
    init_db();
    winSql = `to_timestamp(floor(extract(epoch from now())/$2)*$2)`;
  }
});

// src/auth/device.ts
var device_exports = {};
__export(device_exports, {
  createAuthorization: () => createAuthorization,
  genDeviceCode: () => genDeviceCode,
  genUserCode: () => genUserCode,
  redeem: () => redeem
});
import { randomBytes as randomBytes3 } from "node:crypto";
function genUserCode() {
  const pick = () => ALPHABET[randomBytes3(1)[0] % ALPHABET.length];
  const block = () => Array.from({ length: 4 }, pick).join("");
  return `${block()}-${block()}`;
}
async function createAuthorization(client, ip, ua) {
  const device_code = genDeviceCode();
  for (let attempt = 0; ; attempt++) {
    const user_code = genUserCode();
    try {
      await q(
        `INSERT INTO device_authorizations(device_code,user_code,client,origin_ip,origin_ua,interval_s,expires_at)
         VALUES ($1,$2,$3,$4,$5,$6, now() + ($7||' seconds')::interval)`,
        [device_code, user_code, client, ip, ua, INTERVAL_S, String(EXPIRES_S)]
      );
      return { device_code, user_code };
    } catch (e) {
      if (e?.code === "23505" && attempt < 3) continue;
      throw e;
    }
  }
}
async function redeem(device_code, mintAccess2, issueRefresh2) {
  const row = (await q(`SELECT * FROM device_authorizations WHERE device_code=$1`, [device_code])).rows[0];
  if (!row) return { error: "expired_token" };
  const expired = new Date(row.expires_at).getTime() < Date.now();
  if (expired) {
    if (row.status === "pending") await q(`UPDATE device_authorizations SET status='expired' WHERE device_code=$1 AND status='pending'`, [device_code]);
    return { error: "expired_token" };
  }
  if (row.status === "denied") return { error: "access_denied" };
  if (row.status === "consumed") return { error: "expired_token" };
  if (row.status === "pending") {
    const upd = await q(
      `UPDATE device_authorizations SET last_polled_at=now()
       WHERE device_code=$1 AND status='pending'
         AND (last_polled_at IS NULL OR last_polled_at < now() - make_interval(secs => interval_s)) RETURNING 1`,
      [device_code]
    );
    return { error: (upd.rowCount ?? 0) === 1 ? "authorization_pending" : "slow_down" };
  }
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const cas = await client.query(
      `UPDATE device_authorizations SET status='consumed' WHERE device_code=$1 AND status='approved'
       RETURNING user_id, family_id, origin_ua, granted_scopes`,
      [device_code]
    );
    if (cas.rowCount !== 1) {
      await client.query("COMMIT");
      return { error: "expired_token" };
    }
    const { user_id, family_id, origin_ua, granted_scopes } = cas.rows[0];
    const scopes = granted_scopes ?? ["content:read", "content:write", "content:delete"];
    const cid = credId();
    await client.query(
      `INSERT INTO credentials(id,user_id,family_scope,kind,scopes,label)
       VALUES ($1,$2,$3,'cli',$4, 'dayfold-cli '||left(coalesce($5,''),64))`,
      [cid, user_id, family_id, scopes, origin_ua]
    );
    const { grantScopes: grantScopes2 } = await Promise.resolve().then(() => (init_scope(), scope_exports));
    await grantScopes2(cid, scopes, client);
    const refresh = await issueRefresh2(cid, client);
    await client.query(`UPDATE device_authorizations SET credential_id=$1 WHERE device_code=$2`, [cid, device_code]);
    await client.query("COMMIT");
    const access = await mintAccess2({ sub: user_id, cid });
    return { tokens: { access_token: access, refresh_token: refresh, token_type: "Bearer", expires_in: 300 } };
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}
var ALPHABET, genDeviceCode, credId, EXPIRES_S, INTERVAL_S;
var init_device = __esm({
  "src/auth/device.ts"() {
    "use strict";
    init_db();
    ALPHABET = "23456789CFGHJMPQRVWX";
    genDeviceCode = () => randomBytes3(32).toString("base64url");
    credId = () => "cred_" + randomBytes3(9).toString("hex");
    EXPIRES_S = 600;
    INTERVAL_S = 5;
  }
});

// src/auth/origin.ts
var origin_exports = {};
__export(origin_exports, {
  classifyOrigin: () => classifyOrigin
});
function ipv4ToInt(ip) {
  const m = ip.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return null;
  const o = m.slice(1, 5).map(Number);
  if (o.some((n) => n > 255)) return null;
  return (o[0] << 24 | o[1] << 16 | o[2] << 8 | o[3]) >>> 0;
}
function isPrivateOrReserved(n) {
  const inR = (cidr) => {
    const [addr, b] = cidr.split("/");
    const bits = Number(b);
    const mask = bits === 0 ? 0 : 4294967295 << 32 - bits >>> 0;
    return (n & mask) >>> 0 === (ipv4ToInt(addr) & mask) >>> 0;
  };
  return [
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "100.64.0.0/10",
    "0.0.0.0/8",
    "192.0.2.0/24",
    "198.51.100.0/24",
    "203.0.113.0/24",
    "240.0.0.0/4"
  ].some(inR);
}
function classifyOrigin(ip) {
  if (!ip || ip === "unknown") return "unknown";
  if (ip.includes(":")) return "unknown";
  const n = ipv4ToInt(ip);
  if (n === null) return "unknown";
  if (isPrivateOrReserved(n)) return "unknown";
  for (const [base, mask] of RANGES) if ((n & mask) >>> 0 === base) return "datacenter";
  return "residential";
}
var DATACENTER_CIDRS, RANGES;
var init_origin = __esm({
  "src/auth/origin.ts"() {
    "use strict";
    DATACENTER_CIDRS = [
      // AWS
      "3.0.0.0/9",
      "13.32.0.0/12",
      "15.177.0.0/16",
      "18.32.0.0/11",
      "35.152.0.0/13",
      "52.0.0.0/11",
      "54.144.0.0/12",
      "99.78.0.0/18",
      // Google Cloud
      "34.0.0.0/9",
      "35.184.0.0/13",
      "104.196.0.0/14",
      "130.211.0.0/16",
      "146.148.0.0/17",
      // Microsoft Azure
      "20.0.0.0/8",
      "40.64.0.0/10",
      "13.64.0.0/11",
      "104.40.0.0/13",
      "52.224.0.0/11",
      // DigitalOcean
      "159.65.0.0/16",
      "165.227.0.0/16",
      "167.71.0.0/16",
      "134.209.0.0/16",
      "138.197.0.0/16",
      "157.230.0.0/16",
      "64.225.0.0/16",
      "146.190.0.0/16",
      // Linode / Akamai
      "45.33.0.0/16",
      "45.56.0.0/16",
      "139.162.0.0/16",
      "172.104.0.0/15",
      "173.255.192.0/18",
      // Hetzner
      "5.9.0.0/16",
      "88.99.0.0/16",
      "116.202.0.0/16",
      "95.216.0.0/15",
      "78.46.0.0/15",
      // OVH
      "51.38.0.0/16",
      "51.68.0.0/16",
      "137.74.0.0/16",
      "145.239.0.0/16",
      "51.83.0.0/16",
      // Oracle Cloud
      "129.146.0.0/16",
      "132.145.0.0/16",
      "140.238.0.0/16",
      // Cloudflare
      "104.16.0.0/13",
      "172.64.0.0/13",
      "162.158.0.0/15",
      // Vultr
      "45.32.0.0/16",
      "45.63.0.0/16",
      "108.61.0.0/16",
      "149.28.0.0/16"
    ];
    RANGES = DATACENTER_CIDRS.map((cidr) => {
      const [addr, bitsStr] = cidr.split("/");
      const bits = Number(bitsStr);
      const mask = bits === 0 ? 0 : 4294967295 << 32 - bits >>> 0;
      return [(ipv4ToInt(addr) & mask) >>> 0, mask];
    });
  }
});

// src/auth/invites.ts
var invites_exports = {};
__export(invites_exports, {
  createInvite: () => createInvite,
  genInviteToken: () => genInviteToken,
  hashInvite: () => hashInvite,
  redeem: () => redeem2
});
import { randomBytes as randomBytes4, createHash as createHash3 } from "node:crypto";
async function createInvite(familyId, createdBy, mode, role, maxUses) {
  const token = genInviteToken();
  const inviteId = id2();
  const ttl = mode === "qr" ? "15 minutes" : "72 hours";
  await q(
    `INSERT INTO invites(id, family_id, role, token_hash, mode, max_uses, created_by, expires_at)
     VALUES ($1,$2,$3,$4,$5,$6,$7, now() + $8::interval)`,
    [inviteId, familyId, role, hashInvite(token), mode, maxUses, createdBy, ttl]
  );
  return { inviteId, token };
}
async function redeem2(token, sub) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const inv = await client.query(
      `SELECT id, family_id, role, used_count, max_uses FROM invites
       WHERE token_hash=$1 AND status='active' AND used_count<max_uses AND expires_at>now() FOR UPDATE`,
      [hashInvite(token)]
    );
    if (inv.rowCount !== 1) {
      await client.query("ROLLBACK");
      return { notfound: true };
    }
    const { id: invId, family_id, role } = inv.rows[0];
    await client.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [family_id]);
    const pend = await client.query(
      `SELECT count(*)::int n FROM memberships WHERE family_id=$1 AND status='pending'`,
      [family_id]
    );
    if (pend.rows[0].n >= PENDING_CAP) {
      await client.query("ROLLBACK");
      return { capfull: true };
    }
    const ins = await client.query(
      `INSERT INTO memberships(user_id, family_id, role, status, invite_id)
       VALUES ($1,$2,$3,'pending',$4) ON CONFLICT (user_id, family_id) DO NOTHING RETURNING 1`,
      [sub, family_id, role, invId]
    );
    if (ins.rowCount === 1) {
      await client.query(
        `UPDATE invites SET used_count=used_count+1,
           status = CASE WHEN used_count+1 >= max_uses THEN 'exhausted' ELSE 'active' END
         WHERE id=$1 AND status='active'`,
        [invId]
      );
      await client.query("COMMIT");
      return { ok: true, family_id, role };
    }
    const cur = await client.query(
      `SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`,
      [sub, family_id]
    );
    await client.query("COMMIT");
    return { conflict: cur.rows[0].status };
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}
var hashInvite, genInviteToken, id2, PENDING_CAP;
var init_invites = __esm({
  "src/auth/invites.ts"() {
    "use strict";
    init_db();
    hashInvite = (t) => createHash3("sha256").update(t, "utf8").digest("hex");
    genInviteToken = () => randomBytes4(32).toString("base64url");
    id2 = () => "inv_" + randomBytes4(9).toString("hex");
    PENDING_CAP = 20;
  }
});

// src/app.ts
var app_exports = {};
__export(app_exports, {
  app: () => app
});
import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";
function problem(c, status, type, detail) {
  return c.body(
    JSON.stringify({ type, title: type, status, ...detail ? { detail } : {} }),
    status,
    { "content-type": "application/problem+json" }
  );
}
function callerFrom(a) {
  return { userId: a.userId, legacy: a.legacy, cred: a.cred };
}
function parseVisibilityAudience(raw) {
  if (raw.visibility !== void 0 && raw.visibility !== "family" && raw.visibility !== "restricted")
    return { error: { type: "validation", issues: [{ path: ["visibility"], message: "family|restricted" }] } };
  const visibilityProvided = raw.visibility !== void 0;
  const visibility = raw.visibility === "restricted" ? "restricted" : "family";
  const audienceProvided = raw.audience !== void 0;
  let audience;
  if (visibility === "restricted") {
    if (raw.audience !== void 0 && (!Array.isArray(raw.audience) || raw.audience.some((x) => typeof x !== "string")))
      return { error: { type: "validation", issues: [{ path: ["audience"], message: "string[] of user ids" }] } };
    audience = Array.isArray(raw.audience) ? raw.audience : [];
  }
  const { visibility: _v, audience: _a, ...rest } = raw;
  return { visibility, audience, rest, visibilityProvided, audienceProvided };
}
function devAuthAllowed(_c) {
  if (process.env.ENABLE_DEV_AUTH !== "1") return false;
  const env = process.env.VERCEL_ENV;
  if (env === "production" || env === "preview") return false;
  return true;
}
async function ownerGate(c, fid) {
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return { status: a.status };
  if (a.role !== "owner") return { status: 403 };
  if (a.cred.kind !== "app") return { status: 403 };
  return { sub: a.userId, caller: callerFrom(a) };
}
var app, ANDROID_DEBUG_SHA256, RESOURCE_ID, idError, PARTICIPANT_ROLES, HUB_VISIBILITIES;
var init_app = __esm({
  "src/app.ts"() {
    "use strict";
    init_db();
    init_security();
    init_content();
    init_content_validation();
    init_media_validation();
    init_repo();
    init_middleware();
    init_scope();
    init_visibility();
    init_write_guard();
    init_oplog();
    init_sweep();
    init_hubs();
    init_content();
    init_swip();
    app = new Hono();
    app.use("*", swipErrors());
    app.get("/health", (c) => c.json({ ok: true, surface: "m0" }));
    if (process.env.ENABLE_DEV_ERRORS === "1" && process.env.VERCEL_ENV !== "production" && process.env.VERCEL_ENV !== "preview") {
      app.get("/debug/boom", () => {
        throw new Error("dayfold api smoke: deliberate unhandled route error", {
          cause: new Error("connection terminated unexpectedly")
        });
      });
      app.get("/debug/wtf", (c) => {
        swip()?.errors.wtf("dayfold.api.smoke", "deliberate non-crash report", { surface: "api" }, "error");
        return c.json({ ok: true });
      });
    }
    app.get("/cron/sweep", async (c) => {
      const secret = process.env.CRON_SECRET || "";
      if (!secret) return c.body(null, 404);
      if (!constantTimeEqual(bearer(c) ?? "", secret)) return c.body(null, 401);
      const { sweep: sweep2 } = await Promise.resolve().then(() => (init_sweep(), sweep_exports));
      return c.json(await sweep2());
    });
    app.use("*", bodyLimit({ maxSize: 1024 * 1024, onError: (c) => problem(c, 413, "payload-too-large") }));
    app.post("/auth/dev-token", async (c) => {
      if (!devAuthAllowed(c)) return c.body(null, 404);
      if (bearer(c) !== (process.env.DEV_AUTH_SECRET || "\0")) return c.body(null, 401);
      const body = await c.req.json().catch(() => null);
      const { StubVerifier: StubVerifier2, findOrCreateUser: findOrCreateUser2 } = await Promise.resolve().then(() => (init_identity(), identity_exports));
      const idn = await new StubVerifier2().verify(body).catch(() => null);
      if (!idn) return c.json({ type: "bad-identity" }, 400);
      const { userId } = await findOrCreateUser2(idn);
      console.warn(`[dev-auth] minted token for ${idn.provider}:${idn.provider_uid} user=${userId}`);
      const credentialId = "cred_" + Math.random().toString(16).slice(2);
      await q(
        `INSERT INTO credentials(id,user_id,kind,scopes) VALUES ($1,$2,'app','{content:read,content:write,content:delete}')`,
        [credentialId, userId]
      );
      await grantScopes(credentialId, ["content:read", "content:write", "content:delete"]);
      const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
      const { issueRefresh: issueRefresh2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
      const access = await mintAccess2({ sub: userId, cid: credentialId });
      const refresh = await issueRefresh2(credentialId);
      return c.json({ access, refresh });
    });
    app.post("/auth/firebase", async (c) => {
      const body = await c.req.json().catch(() => null);
      const idToken = body?.idToken;
      if (!idToken || typeof idToken !== "string") return c.json({ type: "missing-id-token" }, 400);
      const projectId = process.env.FIREBASE_PROJECT_ID;
      if (!projectId) return c.json({ type: "auth-unconfigured" }, 503);
      const { FirebaseVerifier: FirebaseVerifier2, findOrCreateUser: findOrCreateUser2, mintCredentialFor: mintCredentialFor2 } = await Promise.resolve().then(() => (init_identity(), identity_exports));
      const env = process.env.VERCEL_ENV;
      const emulator = !!process.env.FIREBASE_AUTH_EMULATOR_HOST && env !== "production" && env !== "preview";
      const verifier = new FirebaseVerifier2({ projectId, emulator });
      const idn = await verifier.verify(idToken).catch((e) => {
        console.warn(`[auth/firebase] verify failed: ${e?.message}`);
        return null;
      });
      if (!idn) return c.json({ type: "bad-identity" }, 401);
      const { userId } = await findOrCreateUser2(idn);
      const { credentialId } = await mintCredentialFor2(userId);
      const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
      const { issueRefresh: issueRefresh2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
      const access = await mintAccess2({ sub: userId, cid: credentialId });
      const refresh = await issueRefresh2(credentialId);
      return c.json({ access, refresh });
    });
    app.post("/auth/refresh", async (c) => {
      const body = await c.req.json().catch(() => null);
      const { rotate: rotate2, hashToken: hashToken2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
      const out = await rotate2(body?.refresh || "");
      if (!out) return c.body(null, 401);
      if ("refresh" in out) {
        if (out.graced) {
          const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
          await audit2("refresh.grace_reissued", {});
        }
      } else {
        return c.body(null, 401);
      }
      const h = hashToken2(out.refresh);
      const row = await q(
        `SELECT rt.credential_id, c.user_id FROM refresh_tokens rt JOIN credentials c ON c.id=rt.credential_id WHERE rt.token_hash=$1 AND c.revoked_at IS NULL`,
        [h]
      ).catch(() => null);
      if (!row || row.rowCount === 0) return c.body(null, 401);
      const { credential_id: cid, user_id: sub } = row.rows[0];
      const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
      const access = await mintAccess2({ sub, cid });
      return c.json({ access, refresh: out.refresh });
    });
    app.post("/auth/signout", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        cid = (await verifyAccess2(t)).cid;
      } catch {
        return c.body(null, 401);
      }
      await q(`UPDATE credentials SET revoked_at=now() WHERE id=$1`, [cid]);
      await q(`UPDATE refresh_tokens SET consumed_at=now() WHERE credential_id=$1 AND consumed_at IS NULL`, [cid]);
      return c.body(null, 204);
    });
    app.get("/auth/whoami", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub, cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        ({ sub, cid } = await verifyAccess2(t));
      } catch {
        return c.body(null, 401);
      }
      const credRow = await q(
        `SELECT family_scope FROM credentials WHERE id=$1 AND revoked_at IS NULL`,
        [cid]
      );
      if (!credRow || credRow.rowCount === 0) return c.body(null, 401);
      const family_id = credRow.rows[0].family_scope ?? null;
      const r = await q(
        `SELECT m.family_id, f.name, m.role, m.status FROM memberships m JOIN families f ON f.id=m.family_id
     WHERE m.user_id=$1 AND m.status IN ('active','pending') ORDER BY m.created_at`,
        [sub]
      );
      const grants = await resolveGrants(cid);
      return c.json({ family_id, families: r.rows, grants });
    });
    app.get("/auth/me", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub, cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        ({ sub, cid } = await verifyAccess2(t));
      } catch {
        return c.body(null, 401);
      }
      const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
      if (!cred || cred.rowCount === 0) return c.body(null, 401);
      const u = (await q(`SELECT id, display_name, avatar_color, avatar_ref FROM users WHERE id=$1 AND deleted_at IS NULL`, [sub])).rows[0];
      if (!u) return c.body(null, 401);
      return c.json({ user_id: u.id, display_name: u.display_name, avatar_color: u.avatar_color, avatar_ref: u.avatar_ref });
    });
    app.patch("/auth/me", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub, cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        ({ sub, cid } = await verifyAccess2(t));
      } catch {
        return c.body(null, 401);
      }
      const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
      if (!cred || cred.rowCount === 0) return c.body(null, 401);
      const parsed = await c.req.json().catch(() => null);
      const body = parsed !== null && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
      const hasName = typeof body?.display_name === "string";
      const name = hasName ? body.display_name.trim() : null;
      if (hasName && (!name || name.length < 1 || name.length > 80)) return c.json({ type: "bad-display-name" }, 400);
      const AVATAR_RE = /^avatar:[a-z0-9-]{1,40}$/;
      const hasRef = "avatar_ref" in body;
      const ref = body?.avatar_ref ?? null;
      if (hasRef && ref !== null && !(typeof ref === "string" && AVATAR_RE.test(ref)))
        return c.json({ type: "bad-avatar" }, 400);
      const hasColor = "avatar_color" in body;
      const color = body?.avatar_color ?? null;
      if (hasColor && color !== null && !(typeof color === "string" && color.length <= 32))
        return c.json({ type: "bad-avatar" }, 400);
      const sets = ["updated_at=now()"];
      const vals = [];
      let i = 1;
      if (hasName) {
        sets.push(`display_name=$${i++}`);
        vals.push(name);
      }
      if (hasRef) {
        sets.push(`avatar_ref=$${i++}`);
        vals.push(ref);
      }
      if (hasColor) {
        sets.push(`avatar_color=$${i++}`);
        vals.push(color);
      }
      vals.push(sub);
      const r = await q(
        `UPDATE users SET ${sets.join(", ")} WHERE id=$${i} AND deleted_at IS NULL
     RETURNING display_name, avatar_color, avatar_ref`,
        vals
      );
      if (r.rowCount === 0) return c.body(null, 401);
      return c.json(r.rows[0]);
    });
    app.get("/auth/me/export", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub, cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        ({ sub, cid } = await verifyAccess2(t));
      } catch {
        return c.body(null, 401);
      }
      const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
      if (!cred || cred.rowCount === 0) return c.body(null, 401);
      const user = (await q(`SELECT id, display_name, created_at FROM users WHERE id=$1 AND deleted_at IS NULL`, [sub])).rows[0];
      if (!user) return c.body(null, 401);
      const identities = (await q(`SELECT provider, email_verified, created_at FROM user_identities WHERE user_id=$1 ORDER BY created_at`, [sub])).rows;
      const memberships = (await q(
        `SELECT m.family_id, f.name AS family_name, m.role, m.status, m.joined_at
       FROM memberships m JOIN families f ON f.id=m.family_id WHERE m.user_id=$1 ORDER BY m.created_at`,
        [sub]
      )).rows;
      const credentials = (await q(
        `SELECT kind, scopes, label, last_used_at, created_at FROM credentials WHERE user_id=$1 AND revoked_at IS NULL ORDER BY created_at`,
        [sub]
      )).rows;
      return c.json({ exported_at: (/* @__PURE__ */ new Date()).toISOString(), user, identities, memberships, credentials });
    });
    app.get("/auth/me/credentials", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub, cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        ({ sub, cid } = await verifyAccess2(t));
      } catch {
        return c.body(null, 401);
      }
      const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
      if (!self || self.rowCount === 0) return c.body(null, 401);
      const rows = (await q(
        `SELECT id, kind, label, scopes, family_scope, last_used_at, last_used_ip, created_at
       FROM credentials WHERE user_id=$1 AND revoked_at IS NULL ORDER BY last_used_at DESC NULLS LAST, created_at DESC`,
        [sub]
      )).rows;
      return c.json({ credentials: rows.map((r) => ({ ...r, current: r.id === cid })) });
    });
    app.delete("/auth/me/credentials/:id", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub, cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        ({ sub, cid } = await verifyAccess2(t));
      } catch {
        return c.body(null, 401);
      }
      const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
      if (!self || self.rowCount === 0) return c.body(null, 401);
      const target = c.req.param("id");
      const r = await q(`UPDATE credentials SET revoked_at=now() WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL RETURNING 1`, [target, sub]);
      if (r.rowCount === 0) return c.body(null, 404);
      (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("credential.revoke", { actorUserId: sub, detail: { credential_id: target } });
      return c.body(null, 204);
    });
    app.delete("/auth/me", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub, cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        ({ sub, cid } = await verifyAccess2(t));
      } catch {
        return c.body(null, 401);
      }
      const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
      if (!self || self.rowCount === 0) return c.body(null, 401);
      const blocked = await q(
        `SELECT m.family_id, f.name FROM memberships m JOIN families f ON f.id=m.family_id
      WHERE m.user_id=$1 AND m.role='owner' AND m.status='active'
        AND (SELECT count(*) FROM memberships o WHERE o.family_id=m.family_id AND o.role='owner' AND o.status='active' AND o.user_id<>$1)=0
        AND (SELECT count(*) FROM memberships x WHERE x.family_id=m.family_id AND x.status='active' AND x.user_id<>$1)>0`,
        [sub]
      );
      if (blocked.rowCount && blocked.rowCount > 0)
        return c.json({ type: "transfer-required", families: blocked.rows }, 409);
      await q(`UPDATE users SET deleted_at=now() WHERE id=$1 AND deleted_at IS NULL`, [sub]);
      await q(`UPDATE memberships SET status='removed', updated_at=now() WHERE user_id=$1 AND status<>'removed'`, [sub]);
      await q(`UPDATE credentials SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, [sub]);
      (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("account.soft_delete", { actorUserId: sub });
      return c.body(null, 204);
    });
    app.post("/families", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        sub = (await verifyAccess2(t)).sub;
      } catch {
        return c.body(null, 401);
      }
      const body = await c.req.json().catch(() => null);
      if (!body?.name || typeof body.name !== "string") return c.json({ type: "bad-name" }, 400);
      const { createFamily: createFamily2 } = await Promise.resolve().then(() => (init_identity(), identity_exports));
      const { familyId } = await createFamily2(sub, body.name);
      return c.json({ familyId });
    });
    app.get("/.well-known/jwks.json", async (c) => {
      c.header("cache-control", "public, max-age=300");
      const { jwks: jwks2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
      return c.json(await jwks2());
    });
    ANDROID_DEBUG_SHA256 = "15:A0:66:E7:BF:25:07:CB:0E:4D:5C:24:DE:FC:C9:75:06:EE:FF:19:B1:06:CB:7F:76:DF:C0:E9:23:00:87:6B";
    app.get("/.well-known/assetlinks.json", (c) => {
      const fps = (process.env.ANDROID_CERT_SHA256 || ANDROID_DEBUG_SHA256).split(",").map((s) => s.trim()).filter(Boolean);
      c.header("cache-control", "public, max-age=300");
      return c.json([{
        relation: ["delegate_permission/common.handle_all_urls"],
        target: { namespace: "android_app", package_name: "com.sloopworks.dayfold", sha256_cert_fingerprints: fps }
      }]);
    });
    app.get("/.well-known/apple-app-site-association", (c) => {
      const appID = process.env.APPLE_APP_ID || "TEAMID.com.sloopworks.dayfold";
      c.header("content-type", "application/json");
      c.header("cache-control", "public, max-age=300");
      return c.json({ applinks: { apps: [], details: [{ appID, paths: ["/device", "/device?*", "/invite/*"] }] } });
    });
    app.get("/device", (c) => {
      const code = (c.req.query("user_code") || "").replace(/[^A-Za-z0-9-]/g, "").slice(0, 9);
      c.header("content-type", "text/html; charset=utf-8");
      return c.html(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Approve a device \xB7 Dayfold</title>
<div style="font-family:system-ui,sans-serif;max-width:30rem;margin:14vh auto;padding:0 1.5rem;color:#271814">
  <div style="font-weight:700;letter-spacing:.04em;color:#8C726B;font-size:.8rem">DAYFOLD</div>
  <h1 style="font-size:1.6rem;margin:.4rem 0 .8rem">Approve this device</h1>
  <p style="line-height:1.5;color:#5A423C">Open the Dayfold app on your phone to review and approve this sign-in.
  If it doesn't open automatically, go to <b>Connect a device</b> and enter this code:</p>
  ${code ? `<div style="font-family:ui-monospace,monospace;font-size:1.8rem;font-weight:700;letter-spacing:.12em;background:#FCEBE6;border-radius:.8rem;padding:1rem;text-align:center;margin:1rem 0">${code}</div>` : ""}
  <p style="color:#8C726B;font-size:.9rem">Only approve a device you started signing in on yourself.</p>
</div>`);
    });
    app.get("/invite/:token", (c) => {
      const token = (c.req.param("token") || "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 64);
      c.header("content-type", "text/html; charset=utf-8");
      c.header("cache-control", "no-store, no-transform");
      c.header("referrer-policy", "no-referrer");
      return c.html(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Join a family \xB7 Dayfold</title>
<div style="font-family:system-ui,sans-serif;max-width:30rem;margin:14vh auto;padding:0 1.5rem;color:#271814">
  <div style="font-weight:700;letter-spacing:.04em;color:#8C726B;font-size:.8rem">DAYFOLD</div>
  <h1 style="font-size:1.6rem;margin:.4rem 0 .8rem">You're invited to a family</h1>
  <p style="line-height:1.5;color:#5A423C">Open the Dayfold app, tap <b>Join a family</b>, and paste this code.
  Then sign in \u2014 every join waits for the family owner's approval.</p>
  ${token ? `<div style="font-family:ui-monospace,monospace;font-size:1.1rem;font-weight:700;word-break:break-all;background:#FCEBE6;border-radius:.8rem;padding:1rem;margin:1rem 0">${token}</div>` : ""}
  <p style="color:#8C726B;font-size:.9rem">This invite is single-use and expires soon.</p>
</div>`);
    });
    app.put("/families/:fid/cards/:id", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      {
        const e = idError(id3);
        if (e) return c.json(e, 422);
      }
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, "content", "write")) return c.json({ type: "forbidden" }, 403);
      {
        const cur = await q(`SELECT visibility, audience FROM briefing_cards WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [fid, id3]);
        if (cur.rowCount && !cardVisible(cur.rows[0], callerFrom(a))) return c.body(null, 404);
      }
      const raw = await c.req.json().catch(() => null);
      if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
      const va = parseVisibilityAudience(raw);
      if ("error" in va) return c.json(va.error, 422);
      const { visibility, audience, rest } = va;
      let body = stripServerManaged(rest);
      body = stampProvenance(body, a.cred.id);
      const parsed = BriefingCardSchema.safeParse({ ...body, id: id3 });
      if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
      const cross = crossValidateCard(parsed.data);
      if (cross.length) return c.json({ type: "validation", issues: cross }, 422);
      const media = parsed.data.media;
      const mediaIssues = validateCardMedia(media);
      if (mediaIssues.length) return c.json({ type: "validation", issues: mediaIssues }, 422);
      if (media?.accentColor) media.accentColor = normalizedAccent(media.accentColor);
      return c.json(await upsertCard(fid, id3, { ...parsed.data, visibility, audience }), 200);
    });
    app.get("/families/:fid/cards", async (c) => {
      const fid = c.req.param("fid");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, "content", "read")) return c.json({ type: "forbidden" }, 403);
      return c.json(await listCards(fid, callerFrom(a)));
    });
    app.get("/families/:fid/members", async (c) => {
      const fid = c.req.param("fid");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      const rows = await q(
        `SELECT m.user_id AS uid, u.display_name, u.avatar_color, u.avatar_ref, m.role, m.status, m.joined_at
       FROM memberships m JOIN users u ON u.id = m.user_id
      WHERE m.family_id = $1 AND m.status = 'active'
      ORDER BY (m.role = 'owner') DESC, m.joined_at`,
        [fid]
      );
      return c.json({ members: rows.rows });
    });
    app.delete("/families/:fid/cards/:id", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      {
        const e = idError(id3);
        if (e) return c.json(e, 422);
      }
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, "content", "write")) return c.json({ type: "forbidden" }, 403);
      return c.body(null, await softDeleteCard(fid, id3) ? 204 : 404);
    });
    RESOURCE_ID = /^[A-Za-z0-9_-]{1,128}$/;
    idError = (id3) => RESOURCE_ID.test(id3) ? null : { type: "validation", issues: [{ path: ["id"], message: "id must match [A-Za-z0-9_-]{1,128}" }] };
    app.get("/families/:fid/hubs", async (c) => {
      const fid = c.req.param("fid");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      const grants = await resolveGrants(a.cred.id);
      const hubGrantIds = grantedHubIds(grants, "read");
      if (hubGrantIds !== null && hubGrantIds.length === 0) return c.json({ type: "forbidden" }, 403);
      return c.json(await listHubs(fid, callerFrom(a), hubGrantIds));
    });
    app.get("/families/:fid/hubs/:id", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "read")) return c.body(null, 404);
      const hub = await getHub(fid, id3);
      const caller = callerFrom(a);
      if (!hub) return c.body(null, 404);
      const allow = await allowListFor(fid, id3);
      if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
      return c.json(hub);
    });
    app.get("/families/:fid/hubs/:id/tree", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "read")) return c.body(null, 404);
      const hub = await getHub(fid, id3);
      const caller = callerFrom(a);
      if (!hub) return c.body(null, 404);
      const allow = await allowListFor(fid, id3);
      if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
      return c.json(await getHubTree(fid, id3));
    });
    app.get("/families/:fid/hubs/:id/audience", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "read")) return c.body(null, 404);
      const hub = await getHub(fid, id3);
      const caller = callerFrom(a);
      if (!hub) return c.body(null, 404);
      const allow = await allowListFor(fid, id3);
      if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
      const canManage = await canManageHub(fid, id3, caller);
      return c.json({ visibility: hub.visibility, members: await hubAudience(fid, id3), can_manage: canManage });
    });
    PARTICIPANT_ROLES = /* @__PURE__ */ new Set(["viewer", "contributor", "co_owner"]);
    HUB_VISIBILITIES = /* @__PURE__ */ new Set(["family", "restricted"]);
    app.put("/families/:fid/hubs/:id/participants/:uid", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id"), uid = c.req.param("uid");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      const hub = await getHub(fid, id3);
      const caller = callerFrom(a);
      if (!hub) return c.body(null, 404);
      const allow = await allowListFor(fid, id3);
      if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
      if (!await canManageHub(fid, id3, caller)) return c.json({ type: "forbidden" }, 403);
      if (uid === hub.created_by) return c.json({ type: "author-immutable" }, 400);
      const raw = await c.req.json().catch(() => null);
      const role = raw?.role;
      if (typeof role !== "string" || !PARTICIPANT_ROLES.has(role))
        return c.json({ type: "validation", issues: [{ path: ["role"], message: "role must be one of viewer|contributor|co_owner" }] }, 400);
      return c.json(await setParticipant(fid, id3, uid, role), 200);
    });
    app.delete("/families/:fid/hubs/:id/participants/:uid", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id"), uid = c.req.param("uid");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      const hub = await getHub(fid, id3);
      const caller = callerFrom(a);
      if (!hub) return c.body(null, 404);
      const allow = await allowListFor(fid, id3);
      if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
      if (!await canManageHub(fid, id3, caller)) return c.json({ type: "forbidden" }, 403);
      if (uid === hub.created_by) return c.json({ type: "author-immutable" }, 400);
      return c.body(null, await removeParticipant(fid, id3, uid) ? 204 : 404);
    });
    app.put("/families/:fid/hubs/:id/visibility", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      const hub = await getHub(fid, id3);
      const caller = callerFrom(a);
      if (!hub) return c.body(null, 404);
      const allow = await allowListFor(fid, id3);
      if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
      if (!await canManageHub(fid, id3, caller)) return c.json({ type: "forbidden" }, 403);
      const raw = await c.req.json().catch(() => null);
      const visibility = raw?.visibility;
      if (typeof visibility !== "string" || !HUB_VISIBILITIES.has(visibility))
        return c.json({ type: "validation", issues: [{ path: ["visibility"], message: "visibility must be family|restricted" }] }, 400);
      return c.json(await setHubVisibility(fid, id3, visibility), 200);
    });
    app.put("/families/:fid/hubs/:id", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      {
        const e = idError(id3);
        if (e) return c.json(e, 422);
      }
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
      const raw = await c.req.json().catch(() => null);
      if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
      const va = parseVisibilityAudience(raw);
      if ("error" in va) return c.json(va.error, 422);
      let { visibility, audience } = va;
      const { rest, visibilityProvided, audienceProvided } = va;
      const parsed = HubSchema.safeParse({ ...rest, id: id3 });
      if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
      const hubMediaIssues = validateHubMedia(parsed.data.media);
      if (hubMediaIssues.length) return c.json({ type: "validation", issues: hubMediaIssues }, 422);
      {
        const m = parsed.data.media;
        if (m?.accentColor) m.accentColor = normalizedAccent(m.accentColor);
      }
      const timelineIssues = hubTimelineIssues(parsed.data);
      if (timelineIssues.length) return c.json({ type: "validation", issues: timelineIssues }, 422);
      const caller = callerFrom(a);
      const existing = await getHub(fid, id3);
      if (existing) {
        const allow = await allowListFor(fid, id3);
        const permitted = () => !!caller.userId && allow.has(caller.userId);
        if (!visibilityProvided) visibility = existing.visibility === "restricted" ? "restricted" : "family";
        if (visibility === "restricted" && !audienceProvided) audience = [...allow];
        if (!hubVisible(existing, caller, permitted)) return c.body(null, 404);
        if (!caller.legacy && existing.created_by && existing.created_by !== caller.userId && !permitted())
          return c.json({ type: "forbidden" }, 403);
        const newAudience = visibility === "restricted" ? new Set(audience ?? []) : /* @__PURE__ */ new Set();
        const audienceChanged = newAudience.size !== allow.size || [...newAudience].some((u) => !allow.has(u));
        if ((visibility !== existing.visibility || audienceChanged) && !await canManageHub(fid, id3, caller))
          return c.json({ type: "forbidden" }, 403);
      }
      return c.json(await upsertHub(fid, id3, parsed.data, caller, visibility, audience), 200);
    });
    app.post("/families/:fid/hubs/:id/archive", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
      return c.body(null, await archiveHub(fid, id3) ? 204 : 404);
    });
    app.delete("/families/:fid/hubs/:id", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      {
        const e = idError(id3);
        if (e) return c.json(e, 422);
      }
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
      return c.body(null, await softDeleteHub(fid, id3) ? 204 : 404);
    });
    app.put("/families/:fid/sections/:id", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      {
        const e = idError(id3);
        if (e) return c.json(e, 422);
      }
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      const raw = await c.req.json().catch(() => null);
      if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
      const hubId = typeof raw.hubId === "string" ? raw.hubId : null;
      if (!hubId) return c.json({ type: "validation", issues: [{ path: ["hubId"], message: "required" }] }, 422);
      const gate = await hubWriteGate(fid, hubId, callerFrom(a));
      if (gate === "invisible") return c.body(null, 404);
      if (gate === "denied") return c.json({ type: "forbidden" }, 403);
      if (gate === "absent") return c.json({ type: "conflict", detail: "parent hub missing or deleted" }, 409);
      const { hubId: _h, ...rest } = raw;
      const parsed = SectionSchema.safeParse({ ...rest, id: id3 });
      if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
      const ifMatch = c.req.header("if-match");
      if (ifMatch) {
        const cur = await q(`SELECT version FROM sections WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [fid, id3]);
        if (ifMatchFails(ifMatch, cur.rowCount ? Number(cur.rows[0].version) : null)) return c.body(null, 412);
      }
      const row = await upsertSection(fid, id3, hubId, parsed.data);
      return row ? c.json(row, 200) : c.json({ type: "conflict", detail: "parent hub missing or deleted" }, 409);
    });
    app.put("/families/:fid/blocks/:id", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      {
        const e = idError(id3);
        if (e) return c.json(e, 422);
      }
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      const raw = await c.req.json().catch(() => null);
      if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
      const sectionId = typeof raw.sectionId === "string" ? raw.sectionId : null;
      if (!sectionId) return c.json({ type: "validation", issues: [{ path: ["sectionId"], message: "required" }] }, 422);
      const caller = callerFrom(a);
      const hubId = await liveHubOfSection(fid, sectionId);
      if (!hubId) return c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
      const gate = await hubWriteGate(fid, hubId, caller);
      if (gate === "invisible") return c.body(null, 404);
      if (gate === "denied") return c.json({ type: "forbidden" }, 403);
      if (gate === "absent") return c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
      const { sectionId: _s, ...rest } = raw;
      const body = stampProvenance(rest, a.cred.id);
      const parsed = BlockSchema.safeParse({ ...body, id: id3 });
      if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
      const payloadIssues = blockPayloadIssues(parsed.data);
      if (payloadIssues.length) return c.json({ type: "validation", issues: payloadIssues }, 422);
      const blockMediaIssues = validateBlockPayloadMedia(parsed.data.type, parsed.data.payload);
      if (blockMediaIssues.length) return c.json({ type: "validation", issues: blockMediaIssues }, 422);
      {
        const p = parsed.data.payload;
        if (p?.accentColor) p.accentColor = normalizedAccent(p.accentColor);
      }
      const opId = c.req.header("idempotency-key");
      if (opId) {
        const prior = await findOp(fid, opId);
        if (prior) {
          const existing = prior.result_ref ? await getBlock(fid, prior.result_ref) : null;
          return existing ? c.json(existing, 200) : c.body(null, 410);
        }
      }
      const member = isMemberWrite(a);
      const st = await blockState(fid, id3);
      if (member && st.deleted) return c.body(null, 410);
      if (ifMatchFails(c.req.header("if-match"), st.deleted ? null : st.version)) return c.body(null, 412);
      const row = await upsertBlock(fid, id3, sectionId, parsed.data, { allowResurrect: !member, createdBy: a.userId });
      if (!row) {
        return member && st.exists ? c.body(null, 410) : c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
      }
      if (opId) await recordOp(fid, opId, "block", id3, Number(row.version));
      return c.json(row, 200);
    });
    app.delete("/families/:fid/blocks/:id", async (c) => {
      const fid = c.req.param("fid"), id3 = c.req.param("id");
      {
        const e = idError(id3);
        if (e) return c.json(e, 422);
      }
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      const caller = callerFrom(a);
      const opId = c.req.header("idempotency-key");
      if (opId && await findOp(fid, opId)) return c.body(null, 204);
      const blk = await blockForDelete(fid, id3);
      if (!blk) return c.body(null, 404);
      const hubId = await liveHubOfSection(fid, blk.section_id);
      if (!hubId) return c.body(null, 404);
      const hub = await getHub(fid, hubId);
      if (!hub) return c.body(null, 404);
      const allow = await allowListFor(fid, hubId);
      if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
      if (!await requireScope(a.cred.id, "content", "delete")) return c.json({ type: "forbidden" }, 403);
      if (memberDeleteForbidden(a, blk.created_by)) return c.json({ type: "forbidden" }, 403);
      if (blk.deleted) {
        if (opId) await recordOp(fid, opId, "block", id3, null);
        return c.body(null, 204);
      }
      const ok = await softDeleteBlock(fid, id3);
      if (opId) await recordOp(fid, opId, "block", id3, null);
      return c.body(null, ok ? 204 : 404);
    });
    app.post("/device/authorize", async (c) => {
      const { clientIp: clientIp2, hit: hit2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
      const ip = clientIp2(c);
      const rl = await hit2(`ip:authorize:${ip}`, 600, 10);
      if (!rl.ok) return c.body(null, 429);
      const body = await c.req.json().catch(() => ({}));
      const { createAuthorization: createAuthorization2 } = await Promise.resolve().then(() => (init_device(), device_exports));
      const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
      const { device_code, user_code } = await createAuthorization2(body?.client ?? "dayfold-cli", ip, c.req.header("user-agent") ?? null);
      await audit2("device.authorize", { detail: { ip } });
      const base = `${new URL(c.req.url).origin}/device`;
      return c.json({ device_code, user_code, verification_uri: base, verification_uri_complete: `${base}?user_code=${user_code}`, expires_in: 600, interval: 5 });
    });
    app.post("/device/token", async (c) => {
      const { clientIp: clientIp2, hit: hit2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
      const ip = clientIp2(c);
      const rl = await hit2(`ip:token:${ip}`, 600, 600);
      if (!rl.ok) return c.body(null, 429);
      const body = await c.req.json().catch(() => null);
      if (!body?.device_code) return c.json({ error: "invalid_request" }, 400);
      const { redeem: redeem3 } = await Promise.resolve().then(() => (init_device(), device_exports));
      const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
      const { issueRefresh: issueRefresh2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
      const out = await redeem3(body.device_code, mintAccess2, issueRefresh2);
      if ("tokens" in out) {
        const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
        await audit2("device.token.redeemed", { detail: { device_code: body.device_code } });
        return c.json(out.tokens, 200);
      }
      return c.json({ error: out.error }, 400);
    });
    app.get("/device/pending", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub, cid;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        ({ sub, cid } = await verifyAccess2(t));
      } catch {
        return c.body(null, 401);
      }
      const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
      if (!self || self.rowCount === 0) return c.body(null, 401);
      const { isLocked: isLocked2, recordFailure: recordFailure2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
      const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
      const lockKey = `account:approve:${sub}`;
      if (await isLocked2(lockKey)) {
        await audit2("device.lockout", { actorUserId: sub });
        return c.body(null, 429);
      }
      const userCode = c.req.query("user_code");
      if (!userCode) return c.json({ type: "bad-request" }, 400);
      const r = await q(
        `SELECT user_code, client, origin_ip, origin_ua, created_at, expires_at
       FROM device_authorizations WHERE user_code=$1 AND status='pending' AND expires_at > now()`,
        [userCode]
      );
      if (r.rowCount !== 1) {
        await recordFailure2(lockKey, 900, 5, 900);
        return c.json({ type: "not-found" }, 404);
      }
      const row = r.rows[0];
      const { classifyOrigin: classifyOrigin2 } = await Promise.resolve().then(() => (init_origin(), origin_exports));
      await audit2("device.lookup", { actorUserId: sub, detail: { user_code: userCode } });
      return c.json({
        user_code: row.user_code,
        client: row.client,
        origin_ip: row.origin_ip,
        origin_ua: row.origin_ua,
        origin_kind: classifyOrigin2(row.origin_ip),
        created_at: row.created_at,
        expires_at: row.expires_at
      });
    });
    app.post("/families/:fid/device/approve", async (c) => {
      const fid = c.req.param("fid");
      const g = await ownerGate(c, fid);
      if ("status" in g) return c.body(null, g.status);
      const { isLocked: isLocked2, recordFailure: recordFailure2, resetFailures: resetFailures2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
      const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
      const lockKey = `account:approve:${g.sub}`;
      if (await isLocked2(lockKey)) {
        await audit2("device.lockout", { actorUserId: g.sub });
        return c.body(null, 429);
      }
      const body = await c.req.json().catch(() => null);
      if (typeof body !== "object" || body === null || !body.user_code) return c.json({ type: "bad-request" }, 400);
      const mode = body.scope;
      let grantedScopes = null;
      if (mode === "hubs") {
        const hubIds = body.hubs;
        if (!Array.isArray(hubIds) || hubIds.length === 0) return c.json({ type: "bad-scope" }, 400);
        for (const h of hubIds) {
          if (typeof h !== "string") return c.json({ type: "bad-scope" }, 400);
          const e = idError(h);
          if (e) return c.json(e, 422);
          const hub = await getHub(fid, h);
          if (!hub) return c.json({ type: "bad-scope" }, 400);
          const allow = await allowListFor(fid, h);
          if (!hubVisible(hub, g.caller, () => !!g.caller.userId && allow.has(g.caller.userId)))
            return c.json({ type: "bad-scope" }, 400);
        }
        grantedScopes = hubGrantsFor(hubIds);
      } else if (mode !== void 0 && mode !== "full") {
        return c.json({ type: "bad-scope" }, 400);
      }
      const r = await q(
        `UPDATE device_authorizations SET status='approved', user_id=$1, family_id=$2, approved_at=now(), granted_scopes=$4
     WHERE user_code=$3 AND status='pending' AND expires_at > now() RETURNING device_code`,
        [g.sub, fid, body.user_code, grantedScopes]
      );
      if (r.rowCount !== 1) {
        await recordFailure2(lockKey, 900, 5, 900);
        return c.body(null, 404);
      }
      await resetFailures2(lockKey);
      const { clientIp: clientIp2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
      await audit2("device.approve", { actorUserId: g.sub, familyId: fid, detail: { ip: clientIp2(c), scope: mode ?? "full" } });
      return c.body(null, 204);
    });
    app.post("/families/:fid/device/deny", async (c) => {
      const fid = c.req.param("fid");
      const g = await ownerGate(c, fid);
      if ("status" in g) return c.body(null, g.status);
      const body = await c.req.json().catch(() => null);
      if (!body?.user_code) return c.json({ type: "bad-request" }, 400);
      const r = await q(`UPDATE device_authorizations SET status='denied' WHERE user_code=$1 AND status='pending' AND expires_at > now() RETURNING device_code`, [body.user_code]);
      const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
      if (r.rowCount === 1) await audit2("device.deny", { actorUserId: g.sub, familyId: fid });
      return c.body(null, r.rowCount === 1 ? 204 : 404);
    });
    app.post("/families/:fid/invites", async (c) => {
      const fid = c.req.param("fid");
      const g = await ownerGate(c, fid);
      if ("status" in g) return c.body(null, g.status);
      const body = await c.req.json().catch(() => null);
      const mode = body?.mode;
      if (mode !== "qr" && mode !== "link") return c.json({ type: "bad-mode" }, 400);
      const role = body?.role ?? "adult";
      if (role !== "adult") return c.json({ type: "bad-role" }, 400);
      const maxUses = mode === "qr" ? 1 : Math.trunc(body?.max_uses ?? 1);
      if (maxUses < 1 || maxUses > 10) return c.json({ type: "bad-max-uses" }, 400);
      const { clientIp: clientIp2, hit: hit2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
      if (!(await hit2(`owner:mint:${g.sub}`, 600, 20)).ok) return c.body(null, 429);
      const caps = await q(
        `SELECT (SELECT count(*) FROM invites WHERE family_id=$1 AND status='active' AND expires_at>now()) AS inv,
            (SELECT count(*) FROM memberships WHERE family_id=$1 AND status='pending') AS pend`,
        [fid]
      );
      if (Number(caps.rows[0].inv) >= 10 || Number(caps.rows[0].pend) >= 20) return c.body(null, 429);
      const { createInvite: createInvite2 } = await Promise.resolve().then(() => (init_invites(), invites_exports));
      const { inviteId, token } = await createInvite2(fid, g.sub, mode, role, maxUses);
      const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
      await audit2("invite.mint", { actorUserId: g.sub, familyId: fid, detail: { mode, role, max_uses: maxUses } });
      const expires = await q(`SELECT expires_at FROM invites WHERE id=$1`, [inviteId]);
      c.header("cache-control", "no-store, no-transform");
      return c.json({ invite_id: inviteId, token, url: `${new URL(c.req.url).origin}/invite/${token}`, role, mode, expires_at: expires.rows[0].expires_at }, 201);
    });
    app.post("/invites:redeem", async (c) => {
      const t = bearer(c);
      if (!t) return c.body(null, 401);
      let sub;
      try {
        const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
        sub = (await verifyAccess2(t)).sub;
      } catch {
        return c.body(null, 401);
      }
      const { isLocked: isLocked2, recordFailure: recordFailure2, resetFailures: resetFailures2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
      const key = `account:redeem:${sub}`;
      if (await isLocked2(key)) return c.body(null, 429);
      const body = await c.req.json().catch(() => null);
      if (!body?.token) return c.json({ type: "bad-request" }, 400);
      const { redeem: redeem3 } = await Promise.resolve().then(() => (init_invites(), invites_exports));
      const out = await redeem3(body.token, sub);
      const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
      if ("notfound" in out) {
        await recordFailure2(key, 900, 5, 900);
        return c.body(null, 404);
      }
      if ("capfull" in out) return c.body(null, 429);
      await resetFailures2(key);
      if ("conflict" in out) {
        if (out.conflict === "pending") return c.json({ status: "pending" }, 200);
        return c.json({ type: out.conflict === "active" ? "already-member" : "removed" }, 409);
      }
      const fam = await q(`SELECT name FROM families WHERE id=$1`, [out.family_id]);
      await audit2("invite.redeem", { actorUserId: sub, familyId: out.family_id });
      return c.json({ family_id: out.family_id, family_name: fam.rows[0]?.name, role: out.role, status: "pending" }, 200);
    });
    app.post("/families/:fid/members/*", async (c) => {
      const fid = c.req.param("fid");
      const pathname = new URL(c.req.url).pathname;
      const membersPrefix = `/families/${fid}/members/`;
      const seg = pathname.startsWith(membersPrefix) ? pathname.slice(membersPrefix.length) : "";
      const colonIdx = seg.lastIndexOf(":");
      if (colonIdx === -1) return c.body(null, 404);
      const uid = seg.slice(0, colonIdx);
      const action = seg.slice(colonIdx + 1);
      if (action !== "approve" && action !== "decline" && action !== "promote") return c.body(null, 404);
      const g = await ownerGate(c, fid);
      if ("status" in g) return c.body(null, g.status);
      if (action === "promote") {
        const r = await q(`UPDATE memberships SET role='owner', updated_at=now() WHERE user_id=$1 AND family_id=$2 AND status='active' AND role<>'owner' RETURNING 1`, [uid, fid]);
        if (r.rowCount === 1) {
          (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("member.promote", { actorUserId: g.sub, familyId: fid, detail: { uid } });
          return c.body(null, 204);
        }
        const cur = await q(`SELECT role, status FROM memberships WHERE user_id=$1 AND family_id=$2`, [uid, fid]);
        if (cur.rowCount === 0 || cur.rows[0].status !== "active") return c.body(null, 404);
        return c.body(null, 200);
      } else if (action === "approve") {
        const r = await q(`UPDATE memberships SET status='active', joined_at=now() WHERE user_id=$1 AND family_id=$2 AND status='pending' RETURNING 1`, [uid, fid]);
        if (r.rowCount === 1) {
          (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("invite.approve", { actorUserId: g.sub, familyId: fid, detail: { uid } });
          return c.body(null, 204);
        }
        const cur = await q(`SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`, [uid, fid]);
        if (cur.rowCount === 0) return c.body(null, 404);
        return c.body(null, cur.rows[0].status === "active" ? 200 : 409);
      } else {
        const r = await q(`UPDATE memberships SET status='removed' WHERE user_id=$1 AND family_id=$2 AND status='pending' RETURNING 1`, [uid, fid]);
        if (r.rowCount === 1) (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("invite.decline", { actorUserId: g.sub, familyId: fid, detail: { uid } });
        return c.body(null, r.rowCount === 1 ? 204 : 404);
      }
    });
    app.delete("/families/:fid/members/:uid", async (c) => {
      const fid = c.req.param("fid"), uid = c.req.param("uid");
      const g = await ownerGate(c, fid);
      if ("status" in g) return c.body(null, g.status);
      const client = await pool.connect();
      try {
        await client.query("BEGIN");
        const owners = await client.query(
          `SELECT user_id FROM memberships WHERE family_id=$1 AND role='owner' AND status='active' FOR UPDATE`,
          [fid]
        );
        const targetIsOwner = owners.rows.some((r2) => r2.user_id === uid);
        if (targetIsOwner && (owners.rowCount ?? 0) < 2) {
          await client.query("ROLLBACK");
          return c.body(null, 409);
        }
        const r = await client.query(
          `UPDATE memberships SET status='removed' WHERE user_id=$1 AND family_id=$2 AND status IN ('active','pending') RETURNING 1`,
          [uid, fid]
        );
        await client.query("COMMIT");
        if ((r.rowCount ?? 0) !== 1) return c.body(null, 404);
      } catch (e) {
        await client.query("ROLLBACK");
        throw e;
      } finally {
        client.release();
      }
      (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("member.remove", { actorUserId: g.sub, familyId: fid, detail: { uid } });
      return c.body(null, 204);
    });
    app.delete("/families/:fid/invites/:id", async (c) => {
      const fid = c.req.param("fid"), iid = c.req.param("id");
      const g = await ownerGate(c, fid);
      if ("status" in g) return c.body(null, g.status);
      const r = await q(`UPDATE invites SET status='revoked' WHERE id=$1 AND family_id=$2 AND status='active' RETURNING 1`, [iid, fid]);
      if (r.rowCount === 1) (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("invite.revoke", { actorUserId: g.sub, familyId: fid, detail: { invite_id: iid } });
      return c.body(null, 204);
    });
    app.get("/families/:fid/invites", async (c) => {
      const fid = c.req.param("fid");
      const g = await ownerGate(c, fid);
      if ("status" in g) return c.body(null, g.status);
      const invites = await q(`SELECT id, role, mode, max_uses, used_count, expires_at, created_at FROM invites WHERE family_id=$1 AND status='active' AND expires_at>now() ORDER BY created_at DESC`, [fid]);
      const pending = await q(
        `SELECT m.user_id AS uid, u.display_name, u.avatar_color, u.avatar_ref, ui.provider, ui.provider_uid, ui.email_verified,
            m.role, m.invite_id, m.created_at AS requested_at
       FROM memberships m JOIN users u ON u.id=m.user_id
       LEFT JOIN LATERAL (
         SELECT provider, provider_uid, email_verified
           FROM user_identities WHERE user_id=m.user_id ORDER BY created_at, id LIMIT 1
       ) ui ON true
      WHERE m.family_id=$1 AND m.status='pending' ORDER BY m.created_at`,
        [fid]
      );
      return c.json({ invites: invites.rows, pending: pending.rows });
    });
    app.get("/families/:fid/sync", async (c) => {
      const fid = c.req.param("fid");
      const a = await authorizeTenant(c, fid);
      if ("status" in a) return c.body(null, a.status);
      if (!await requireScope(a.cred.id, "content", "read")) return c.json({ type: "forbidden" }, 403);
      const caller = callerFrom(a);
      const raw = c.req.query("since") ?? "";
      let su = "", st = "", si = "";
      if (raw) {
        const parts = Buffer.from(raw, "base64").toString().split("|");
        if (parts.length === 2) {
          const validTs = parts[0] === "-infinity" || !Number.isNaN(Date.parse(parts[0]));
          if (!validTs) return problem(c, 400, "bad-cursor");
        } else if (parts.length === 3) {
          const validTs = !Number.isNaN(Date.parse(parts[0]));
          if (!validTs) return problem(c, 400, "bad-cursor");
          if (["card", "hub", "section", "block"].includes(parts[1])) {
            [su, st, si] = parts;
          }
        } else {
          return problem(c, 400, "bad-cursor");
        }
      }
      let fullResync = false;
      if (su) {
        const cursorAgeMs = Date.now() - Date.parse(su);
        if (cursorAgeMs > CONTENT_TOMBSTONE_RETENTION_DAYS * 24 * 3600 * 1e3) {
          fullResync = true;
          su = "";
          st = "";
          si = "";
        }
      }
      const rows = await syncContent(fid, su, st, si);
      const restrictedHubIds = Array.from(new Set([
        ...rows.filter((r) => r.type === "hub" && r.payload?.visibility === "restricted" && !r.deleted_at).map((r) => r.id),
        ...rows.filter((r) => (r.type === "section" || r.type === "block") && r.hub_visibility === "restricted").map((r) => r.hub_id)
      ].filter(Boolean)));
      const allowSets = /* @__PURE__ */ new Map();
      if (restrictedHubIds.length > 0) {
        const { q: dbq } = await Promise.resolve().then(() => (init_db(), db_exports));
        const rv = await dbq(
          `SELECT hub_id, user_id FROM resource_visibility WHERE family_id=$1 AND hub_id = ANY($2)`,
          [fid, restrictedHubIds]
        );
        for (const row of rv.rows) {
          if (!allowSets.has(row.hub_id)) allowSets.set(row.hub_id, /* @__PURE__ */ new Set());
          allowSets.get(row.hub_id).add(row.user_id);
        }
      }
      const changes = { cards: [], hubs: [], sections: [], blocks: [] };
      const tombstones = [];
      for (const r of rows) {
        let visible;
        if (r.deleted_at) {
          visible = false;
        } else if (r.type === "card") {
          visible = cardVisible(r.payload, caller);
        } else if (r.type === "hub") {
          visible = hubVisible(r.payload, caller, (hid) => !!(caller.userId && allowSets.get(hid)?.has(caller.userId)));
        } else {
          const parentHub = { id: r.hub_id, visibility: r.hub_visibility, created_by: r.hub_created_by };
          visible = hubVisible(parentHub, caller, (hid) => !!(caller.userId && allowSets.get(hid)?.has(caller.userId)));
        }
        if (visible) {
          if (r.type === "card") changes.cards.push(r.payload);
          else if (r.type === "hub") changes.hubs.push(r.payload);
          else if (r.type === "section") changes.sections.push(r.payload);
          else changes.blocks.push(r.payload);
        } else {
          tombstones.push({ type: r.type, id: r.id });
        }
      }
      const last = rows[rows.length - 1];
      const next_cursor = last ? Buffer.from(`${last.updated_at}|${last.type}|${last.id}`).toString("base64") : raw;
      return c.json({ changes, tombstones, next_cursor, has_more: rows.length >= SYNC_LIMIT, full_resync: fullResync });
    });
  }
});

// src/vercel-entry.ts
init_swip();
await initSwip({ required: true });
var { app: app2 } = await Promise.resolve().then(() => (init_app(), app_exports));
async function handler(req, res) {
  const method = req.method ?? "GET";
  let body;
  if (method !== "GET" && method !== "HEAD") {
    if (req.body !== void 0 && req.body !== null) {
      body = Buffer.from(typeof req.body === "string" ? req.body : JSON.stringify(req.body));
    } else {
      const chunks = [];
      for await (const c of req) chunks.push(c);
      body = chunks.length ? Buffer.concat(chunks) : void 0;
    }
  }
  const headers = new Headers();
  for (const [k, v] of Object.entries(req.headers)) {
    if (Array.isArray(v)) v.forEach((x) => headers.append(k, x));
    else if (v != null) headers.set(k, v);
  }
  const url = `https://${req.headers.host}${req.url ?? "/"}`;
  const response = await app2.fetch(new Request(url, { method, headers, body }));
  res.statusCode = response.status;
  response.headers.forEach((v, k) => res.setHeader(k, v));
  res.end(Buffer.from(await response.arrayBuffer()));
}
export {
  handler as default
};
