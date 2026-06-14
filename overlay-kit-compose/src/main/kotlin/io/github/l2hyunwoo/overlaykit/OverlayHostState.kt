package io.github.l2hyunwoo.overlaykit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Pure, composable-free store of overlays. [OverlayPhase] is the single authority on an entry's
 * lifecycle: every transition is a method here, and [OverlayHost] only renders [entries] and reports
 * exit completion via [onExitFinished].
 *
 * Mutations must happen on the main thread — they touch snapshot state read during composition.
 */
@Stable
public class OverlayHostState internal constructor() {

    /** Live overlays in z-order (insertion order). Mutated only by this store. */
    internal val entries: SnapshotStateList<OverlayEntry> = mutableStateListOf()

    private fun indexOf(id: String): Int = entries.indexOfFirst { it.id == id }

    internal fun find(id: String): OverlayEntry? = entries.firstOrNull { it.id == id }

    /** Add a new overlay and request its enter animation. */
    internal fun add(entry: OverlayEntry) {
        entry.phase = OverlayPhase.Entering
        entry.transitionState.targetState = true
        entries.add(entry)
    }

    /**
     * Bring an exiting entry back. Flipping `targetState` back to true breaks the exit-completion
     * predicate (`!currentState`), so the host's in-flight removal self-cancels. Returns false if no
     * such id exists, or it was already [OverlayPhase.Removed].
     */
    internal fun revive(id: String): Boolean {
        val entry = find(id) ?: return false
        return when (entry.phase) {
            OverlayPhase.Exiting -> {
                entry.phase = OverlayPhase.Visible
                entry.transitionState.targetState = true
                true
            }
            OverlayPhase.Entering, OverlayPhase.Visible -> true
            OverlayPhase.Removed -> false
        }
    }

    internal fun onEnterFinished(id: String) {
        val entry = find(id) ?: return
        if (entry.phase == OverlayPhase.Entering) {
            entry.phase = OverlayPhase.Visible
        }
    }

    /**
     * Re-stack the entry [id] to the end of [entries] (top z-order) **in place**, preserving its
     * identity so the host's `movableContentOf` slot moves rather than disposes — the entry's
     * transition progress and content-internal `remember` survive the reorder.
     *
     * Phase gate: only a live, settling/settled overlay may move. An [OverlayPhase.Exiting] or
     * [OverlayPhase.Removed] entry is left untouched (returns false), because while exiting the
     * host's `AnimatedVisibility` is racing toward disposing the content `Layout`
     * (`AnimatedVisibility.kt` `shouldDisposeBlock`: dispose once exit is idle), and moving a slot
     * that is mid-dispose is exactly the [movableContentOf] hazard we serialize against. Idempotent:
     * an id that is already top, or absent, is a no-op returning false.
     */
    internal fun bringToFront(id: String): Boolean {
        val index = indexOf(id)
        if (index < 0) return false
        val entry = entries[index]
        when (entry.phase) {
            OverlayPhase.Entering, OverlayPhase.Visible -> Unit
            OverlayPhase.Exiting, OverlayPhase.Removed -> return false
        }
        // Already the top-most entry — nothing to move (avoids a no-op list mutation that would
        // still invalidate observers reading entries).
        if (index == entries.lastIndex) return false
        // Reposition the SAME OverlayEntry instance: removeAt + add re-appends the identical
        // reference (SnapshotStateList has no move()). Because the instance — and therefore its id —
        // is unchanged, the host's `remember(entry.id) { movableContentOf { … } }` resolves to the
        // same movable slot, so the slot moves with its state/transition instead of being disposed
        // and re-created at the new index.
        entries.removeAt(index)
        entries.add(entry)
        return true
    }

    /** Animated close. Idempotent for already-exiting/removed overlays; removal is deferred to [onExitFinished]. */
    internal fun close(id: String) {
        val entry = find(id) ?: return
        when (entry.phase) {
            OverlayPhase.Entering, OverlayPhase.Visible -> {
                entry.phase = OverlayPhase.Exiting
                entry.transitionState.targetState = false
            }
            OverlayPhase.Exiting, OverlayPhase.Removed -> Unit
        }
    }

    internal fun closeAll() {
        entries.map { it.id }.forEach { close(it) }
    }

    /** Remove immediately, skipping the exit animation. Idempotent across any phase. */
    internal fun unmount(id: String) {
        val entry = find(id) ?: return
        remove(entry)
    }

    internal fun unmountAll() {
        entries.map { it.id }.forEach { unmount(it) }
    }

    /**
     * Reported by the host once an exit transition fully finishes. Re-guards on the live phase: if
     * the entry was revived between the host observing completion and this call, it is no longer
     * Exiting and we keep it.
     */
    internal fun onExitFinished(id: String) {
        val entry = find(id) ?: return
        if (entry.phase != OverlayPhase.Exiting) return
        remove(entry)
    }

    private fun remove(entry: OverlayEntry) {
        entry.phase = OverlayPhase.Removed
        val index = indexOf(entry.id)
        if (index >= 0) entries.removeAt(index)
        // Removed with no explicit value dismisses any still-awaiting openForResult caller.
        consumeResolver(entry)?.invoke(ResolveSignal.Removed)
    }

    /**
     * Atomically take the resolver, leaving null so no second caller can resume the continuation.
     * Double-resume on a cancellable continuation throws `IllegalStateException`
     * (CancellableContinuationImpl.kt:521 `alreadyResumedError`); a `continuation.isActive` check
     * would be a TOCTOU race, this CAS is not.
     */
    private fun consumeResolver(entry: OverlayEntry): ((ResolveSignal) -> Unit)? =
        entry.resolver.getAndSet(null)

    /**
     * Resolve an overlay with a typed [value] and animate it closed. Consume-once: only the first
     * caller wins; a second `close` or a racing `closeAll` finds a null resolver and resolves
     * nothing, so the continuation is never resumed twice.
     */
    internal fun resolveAndClose(id: String, value: Any?) {
        val entry = find(id) ?: return
        val resolver = consumeResolver(entry)
        close(id)
        resolver?.invoke(ResolveSignal.Value(value))
    }

    internal fun attachResolver(id: String, resolver: (ResolveSignal) -> Unit) {
        find(id)?.resolver?.set(resolver)
    }
}

/**
 * What a consumed resolver was handed: an explicit [Value], or [Removed] when the overlay was torn
 * down without a result (dismisses the awaiting `openForResult` with `OverlayResult.Dismissed`).
 */
internal sealed interface ResolveSignal {
    @JvmInline value class Value(val value: Any?) : ResolveSignal
    data object Removed : ResolveSignal
}
