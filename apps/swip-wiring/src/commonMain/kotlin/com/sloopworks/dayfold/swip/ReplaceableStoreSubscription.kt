package com.sloopworks.dayfold.swip

import org.reduxkotlin.Store
import org.reduxkotlin.StoreSubscription

/**
 * Owns one replaceable Redux subscription and emits only distinct selected values.
 *
 * Android binds this from `Activity.onCreate`, on the same serial UI notification thread used by
 * the store. Rebinding the same store is idempotent; binding a recreated host's store first
 * unsubscribes and releases the previous store. [dispose] is available to explicit lifecycle
 * owners and tests. The last selected value intentionally survives rebinding so a configuration
 * change does not duplicate an unchanged screen event.
 */
public class ReplaceableStoreSubscription<S, V>(
  private val select: (S) -> V,
  private val onChanged: (V) -> Unit,
) {
  private var store: Store<S>? = null
  private var unsubscribe: StoreSubscription? = null
  private var hasValue: Boolean = false
  private var lastValue: V? = null

  /** Binds [next], replacing any subscription to an older store. */
  public fun bind(next: Store<S>) {
    if (store === next) return

    unsubscribe?.invoke()
    unsubscribe = null
    store = next
    unsubscribe = next.subscribe { report(next) }
    report(next)
  }

  /** Removes the current subscription and releases its store. Safe to call repeatedly. */
  public fun dispose() {
    unsubscribe?.invoke()
    unsubscribe = null
    store = null
  }

  private fun report(source: Store<S>) {
    if (store !== source) return
    val selected = select(source.state)
    if (!hasValue || selected != lastValue) {
      hasValue = true
      lastValue = selected
      onChanged(selected)
    }
  }
}
