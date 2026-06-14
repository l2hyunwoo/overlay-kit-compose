package io.github.l2hyunwoo.overlaykit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Pure phase-gate store for a set of overlays. **No composable here** — this is testable in plain
 * JVM unit tests.
 *
 * Design 2.0 §1: this is the single gate. Every transition (open / close / revival / unmount /
 * exit-completion removal) is a method on this class, and [OverlayPhase] is the only authority on
 * an entry's lifecycle. The host composable ([OverlayHost]) merely renders [entries] and reports
 * exit completion back via [onExitFinished]; it never decides removal on its own.
 *
 * Threading: mutations must happen on the main thread (these touch snapshot state read during
 * composition). `openAsync`'s cancellation path marshals back here via a main dispatcher; see
 * [OverlayController].
 */
@Stable
public class OverlayHostState internal constructor() {

    /**
     * Live overlays in z-order (insertion order). Snapshot-backed so the host recomposes on
     * structural change. Exposed read-only to the host; mutated only by this store.
     */
    internal val entries: SnapshotStateList<OverlayEntry> = mutableStateListOf()

    private fun indexOf(id: String): Int = entries.indexOfFirst { it.id == id }

    internal fun find(id: String): OverlayEntry? = entries.firstOrNull { it.id == id }

    // ---------------------------------------------------------------------------------------------
    // Open / revival
    // ---------------------------------------------------------------------------------------------

    /**
     * Open (or revive) an overlay.
     *
     * - New id → add an entry in [OverlayPhase.Entering] and request the enter animation
     *   (`targetState = true`). The phase settles to [OverlayPhase.Visible] when the host reports
     *   the enter transition idle, but for store purposes we mark it Entering immediately.
     * - Existing id that is [OverlayPhase.Exiting] (mid close) → **revival**: flip phase back to
     *   [OverlayPhase.Visible] and set `targetState = true`. This breaks the exit-completion
     *   removal condition (`!currentState` no longer holds once target flips back), so the
     *   in-flight removal in the host self-cancels.
     * - Existing id already [OverlayPhase.Entering]/[OverlayPhase.Visible] → no-op (already shown).
     *
     * Enter/revival are **event-handler writes**, not side effects — never deferred to a
     * `LaunchedEffect` (Design 2.0 §3).
     */
    internal fun add(entry: OverlayEntry) {
        // entry starts in Entering with transition target requested.
        entry.phase = OverlayPhase.Entering
        entry.transitionState.targetState = true
        entries.add(entry)
    }

    /**
     * Revive an existing entry that is currently exiting (or about to be removed). Returns true if
     * an entry with [id] existed and was revived, false otherwise.
     */
    internal fun revive(id: String): Boolean {
        val entry = find(id) ?: return false
        return when (entry.phase) {
            OverlayPhase.Exiting -> {
                entry.phase = OverlayPhase.Visible
                entry.transitionState.targetState = true
                true
            }
            OverlayPhase.Entering, OverlayPhase.Visible -> true // already on screen
            OverlayPhase.Removed -> false // terminal; caller should add a fresh entry
        }
    }

    /** Mark an entry as fully visible once its enter transition is idle. */
    internal fun onEnterFinished(id: String) {
        val entry = find(id) ?: return
        if (entry.phase == OverlayPhase.Entering) {
            entry.phase = OverlayPhase.Visible
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Close (animated) / unmount (immediate)
    // ---------------------------------------------------------------------------------------------

    /**
     * Request an animated close: only a [OverlayPhase.Visible] or [OverlayPhase.Entering] overlay
     * transitions to [OverlayPhase.Exiting] (`targetState = false`). Already-exiting or removed
     * overlays are left untouched (idempotent). Actual removal is deferred to [onExitFinished].
     */
    internal fun close(id: String) {
        val entry = find(id) ?: return
        when (entry.phase) {
            OverlayPhase.Entering, OverlayPhase.Visible -> {
                entry.phase = OverlayPhase.Exiting
                entry.transitionState.targetState = false
            }
            OverlayPhase.Exiting, OverlayPhase.Removed -> Unit // already closing / gone
        }
    }

    /** Close every live overlay (animated). Resolvers fire when each exit completes. */
    internal fun closeAll() {
        // Iterate over a snapshot copy of ids — close() does not remove, so the list is stable,
        // but copying keeps intent explicit and avoids surprises if that ever changes.
        entries.map { it.id }.forEach { close(it) }
    }

    /**
     * Remove an overlay immediately, skipping the exit animation. Idempotent across any phase
     * (Design 2.0 §1: unmount → Removed from any state).
     */
    internal fun unmount(id: String) {
        val entry = find(id) ?: return
        remove(entry)
    }

    /** Immediately remove all overlays. */
    internal fun unmountAll() {
        entries.map { it.id }.forEach { unmount(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // Exit completion (reported by the host)
    // ---------------------------------------------------------------------------------------------

    /**
     * The host calls this when an entry's exit transition has fully finished
     * (`phase == Exiting && transitionState.isIdle && !transitionState.currentState`).
     *
     * Guard the removal once more against the live phase: if the entry was revived between the
     * host observing exit-completion and this call, [phase] is no longer [OverlayPhase.Exiting]
     * and we **skip** removal (Design 2.0 §1: "closeAll 잔여 remove는 phase!=Exiting이라 스킵").
     *
     * If an `openAsync` overlay reaches removal without ever being resolved with a value (e.g.
     * `close()` with no result, or `closeAll`), its resolver is consumed with [ResolveSignal.Removed]
     * so the awaiting coroutine is cancelled rather than left dangling.
     */
    internal fun onExitFinished(id: String) {
        val entry = find(id) ?: return
        if (entry.phase != OverlayPhase.Exiting) return // revived in the meantime → keep it
        remove(entry)
    }

    private fun remove(entry: OverlayEntry) {
        entry.phase = OverlayPhase.Removed
        val index = indexOf(entry.id)
        if (index >= 0) entries.removeAt(index)
        // Any still-pending async caller is cancelled (no explicit value was provided).
        consumeResolver(entry)?.invoke(ResolveSignal.Removed)
    }

    // ---------------------------------------------------------------------------------------------
    // Async resolver — consume-once SSOT gate
    // ---------------------------------------------------------------------------------------------

    /**
     * Atomically take ([resolver][OverlayEntry.resolver]) for this entry, returning it to the
     * single winner and leaving `null` behind so no second caller can resume the continuation.
     *
     * This is the gate Design 2.0 §4 requires: double-resume on a cancellable continuation throws
     * `IllegalStateException` (CancellableContinuationImpl.kt:521 `alreadyResumedError`). A
     * `continuation.isActive` check is a TOCTOU race; a CAS that clears the box is not.
     */
    private fun consumeResolver(entry: OverlayEntry): ((ResolveSignal) -> Unit)? =
        entry.resolver.getAndSet(null)

    /**
     * Resolve a specific overlay with a typed [value] (used by [AsyncOverlayScope.close]) and drive
     * it through an animated close. Resolution is **consume-once**: only the first call wins; later
     * calls (a second `close`, or `closeAll`) find a null resolver and resolve nothing, so the
     * continuation is never resumed twice. The phase transition still runs so the exit animation
     * plays and the entry is eventually removed.
     */
    internal fun resolveAndClose(id: String, value: Any?) {
        val entry = find(id) ?: return
        val resolver = consumeResolver(entry)
        // Start the animated close regardless, so a no-op resolve still removes the overlay.
        close(id)
        resolver?.invoke(ResolveSignal.Value(value))
    }

    /** Register the consume-once resolver for an overlay opened via `openAsync`. */
    internal fun attachResolver(id: String, resolver: (ResolveSignal) -> Unit) {
        find(id)?.resolver?.set(resolver)
    }
}

/**
 * What a consumed resolver was handed. Either an explicit typed [Value] from
 * [AsyncOverlayScope.close], or [Removed] when the overlay was torn down without an explicit
 * result (treated as cancellation of the awaiting `openAsync`).
 */
internal sealed interface ResolveSignal {
    @JvmInline value class Value(val value: Any?) : ResolveSignal
    data object Removed : ResolveSignal
}
