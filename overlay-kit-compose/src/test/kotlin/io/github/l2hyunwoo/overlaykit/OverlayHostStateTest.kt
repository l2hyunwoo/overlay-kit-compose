package io.github.l2hyunwoo.overlaykit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Exhaustive phase-transition table for the pure [OverlayHostState] store (no composables). */
class OverlayHostStateTest {

    private fun state() = OverlayHostState()

    private fun OverlayHostState.openEntry(id: String): OverlayEntry {
        add(OverlayEntry(id = id, placement = OverlayPlacement.InComposition, content = {}))
        return find(id)!!
    }

    @Test
    fun open_addsEntryInEnteringWithTargetTrue() {
        val s = state()
        val entry = s.openEntry("a")

        assertThat(entry.phase).isEqualTo(OverlayPhase.Entering)
        assertThat(entry.transitionState.targetState).isTrue()
        assertThat(s.entries).containsExactly(entry)
    }

    @Test
    fun enterFinished_movesEnteringToVisible() {
        val s = state()
        val entry = s.openEntry("a")

        s.onEnterFinished("a")

        assertThat(entry.phase).isEqualTo(OverlayPhase.Visible)
    }

    @Test
    fun close_movesVisibleToExitingWithTargetFalse() {
        val s = state()
        val entry = s.openEntry("a")
        s.onEnterFinished("a")

        s.close("a")

        assertThat(entry.phase).isEqualTo(OverlayPhase.Exiting)
        assertThat(entry.transitionState.targetState).isFalse()
        // Not removed yet — removal waits for exit completion.
        assertThat(s.entries).contains(entry)
    }

    @Test
    fun revival_movesExitingBackToVisibleAndCancelsRemoval() {
        val s = state()
        val entry = s.openEntry("a")
        s.onEnterFinished("a")
        s.close("a")
        assertThat(entry.phase).isEqualTo(OverlayPhase.Exiting)

        // open() on an existing exiting id revives it.
        val revived = s.revive("a")

        assertThat(revived).isTrue()
        assertThat(entry.phase).isEqualTo(OverlayPhase.Visible)
        assertThat(entry.transitionState.targetState).isTrue()
    }

    @Test
    fun exitFinished_removesOnlyWhenExitingAndIdleAndNotTarget() {
        val s = state()
        val entry = s.openEntry("a")
        s.onEnterFinished("a")
        s.close("a")
        // After close(), targetState=false. currentState is still false (no animation ran in this
        // pure-store test), so the transition is idle and currentState is false — exactly the
        // host's exit-completion predicate.
        val canRemove = entry.phase == OverlayPhase.Exiting &&
            entry.transitionState.isIdle &&
            !entry.transitionState.currentState
        assertThat(canRemove).isTrue()

        s.onExitFinished("a")

        assertThat(entry.phase).isEqualTo(OverlayPhase.Removed)
        assertThat(s.entries).isEmpty()
    }

    @Test
    fun exitCompletionPredicate_isFalseWhileEnteringAndAfterRevival() {
        val s = state()
        val entry = s.openEntry("a")
        // While entering: targetState=true, currentState=false → not idle → predicate false.
        fun predicate() = entry.phase == OverlayPhase.Exiting &&
            entry.transitionState.isIdle &&
            !entry.transitionState.currentState
        assertThat(predicate()).isFalse()

        s.onEnterFinished("a")
        s.close("a")
        assertThat(predicate()).isTrue() // ready to remove

        // Revival flips targetState back to true → currentState(false) != targetState(true)
        // → not idle → predicate false → removal auto-cancels.
        s.revive("a")
        assertThat(predicate()).isFalse()
    }

    @Test
    fun exitFinished_skipsRemovalIfRevivedBeforeReport() {
        val s = state()
        val entry = s.openEntry("a")
        s.onEnterFinished("a")
        s.close("a")
        // Revive after the host queued exit-completion but before onExitFinished runs.
        s.revive("a")

        s.onExitFinished("a") // must be a no-op: phase != Exiting

        assertThat(entry.phase).isEqualTo(OverlayPhase.Visible)
        assertThat(s.entries).contains(entry)
    }

    @Test
    fun unmount_isIdempotentAcrossPhases() {
        val s = state()
        s.openEntry("a")

        s.unmount("a")
        assertThat(s.entries).isEmpty()

        // Second unmount on a gone id is a safe no-op.
        s.unmount("a")
        assertThat(s.entries).isEmpty()
    }

    @Test
    fun unmount_fromExitingRemovesImmediately() {
        val s = state()
        val entry = s.openEntry("a")
        s.onEnterFinished("a")
        s.close("a")
        assertThat(entry.phase).isEqualTo(OverlayPhase.Exiting)

        s.unmount("a")

        assertThat(entry.phase).isEqualTo(OverlayPhase.Removed)
        assertThat(s.entries).isEmpty()
    }

    @Test
    fun closeAll_marksEveryEntryExitingWithoutRemoving() {
        val s = state()
        val a = s.openEntry("a")
        val b = s.openEntry("b")
        s.onEnterFinished("a")
        s.onEnterFinished("b")

        s.closeAll()

        assertThat(a.phase).isEqualTo(OverlayPhase.Exiting)
        assertThat(b.phase).isEqualTo(OverlayPhase.Exiting)
        assertThat(s.entries).hasSize(2)
    }

    @Test
    fun closeAll_deferredRemovalDoesNotDeleteRevivedEntry() {
        val s = state()
        val a = s.openEntry("a")
        val b = s.openEntry("b")
        s.onEnterFinished("a")
        s.onEnterFinished("b")
        s.closeAll()

        // 'b' is revived while both are exiting.
        s.revive("b")

        s.onExitFinished("a") // a is Exiting → removed
        s.onExitFinished("b") // b is Visible again (revived) → skipped by the phase guard

        assertThat(s.find("a")).isNull()
        assertThat(b.phase).isEqualTo(OverlayPhase.Visible)
        assertThat(s.entries).containsExactly(b)
    }

    @Test
    fun unmountAll_removesEverything() {
        val s = state()
        s.openEntry("a")
        s.openEntry("b")

        s.unmountAll()

        assertThat(s.entries).isEmpty()
    }

    // --- bringToFront (z-order reorder) -------------------------------------------------------

    @Test
    fun bringToFront_movesEntryToTopPreservingIdentity() {
        val s = state()
        val a = s.openEntry("a")
        val b = s.openEntry("b")
        val c = s.openEntry("c")
        // Insertion order is z-order: a (bottom) .. c (top).
        assertThat(s.entries).containsExactly(a, b, c).inOrder()

        val moved = s.bringToFront("a")

        assertThat(moved).isTrue()
        // 'a' is now last (top z). b and c keep their relative order.
        assertThat(s.entries).containsExactly(b, c, a).inOrder()
        // The SAME OverlayEntry instance was repositioned — identity (and thus the movable slot the
        // host keys on) is preserved, not a remove+re-add of a fresh entry.
        assertThat(s.entries.last()).isSameInstanceAs(a)
        assertThat(s.find("a")).isSameInstanceAs(a)
    }

    @Test
    fun bringToFront_onAlreadyTopIsNoOp() {
        val s = state()
        val a = s.openEntry("a")
        val b = s.openEntry("b")

        val moved = s.bringToFront("b") // already the top-most entry

        assertThat(moved).isFalse()
        assertThat(s.entries).containsExactly(a, b).inOrder()
    }

    @Test
    fun bringToFront_isIdempotent() {
        val s = state()
        val a = s.openEntry("a")
        val b = s.openEntry("b")

        assertThat(s.bringToFront("a")).isTrue()       // a -> top
        assertThat(s.entries).containsExactly(b, a).inOrder()
        assertThat(s.bringToFront("a")).isFalse()      // a already top: second call is a no-op
        assertThat(s.entries).containsExactly(b, a).inOrder()
    }

    @Test
    fun bringToFront_absentIdIsNoOp() {
        val s = state()
        val a = s.openEntry("a")

        assertThat(s.bringToFront("missing")).isFalse()
        assertThat(s.entries).containsExactly(a)
    }

    @Test
    fun bringToFront_movesEnteringEntry() {
        val s = state()
        val a = s.openEntry("a") // still Entering (no onEnterFinished)
        val b = s.openEntry("b")
        assertThat(a.phase).isEqualTo(OverlayPhase.Entering)

        // An entry whose enter transition has not settled is still a live, growing overlay and may
        // be re-stacked; movableContentOf carries its in-flight enter transition across the move.
        val moved = s.bringToFront("a")

        assertThat(moved).isTrue()
        assertThat(s.entries).containsExactly(b, a).inOrder()
    }

    @Test
    fun bringToFront_refusesExitingEntry() {
        val s = state()
        val a = s.openEntry("a")
        val b = s.openEntry("b")
        s.onEnterFinished("a")
        s.close("a")
        assertThat(a.phase).isEqualTo(OverlayPhase.Exiting)

        // An exiting overlay is on its way out; bringing it forward would race the host's
        // AnimatedVisibility disposing the content Layout on exit completion. The gate refuses it.
        val moved = s.bringToFront("a")

        assertThat(moved).isFalse()
        // z-order unchanged: a stays where it was.
        assertThat(s.entries).containsExactly(a, b).inOrder()
    }

    @Test
    fun bringToFront_refusesRemovedEntry() {
        val s = state()
        val a = s.openEntry("a")
        s.openEntry("b")
        s.unmount("a") // a -> Removed and detached
        assertThat(a.phase).isEqualTo(OverlayPhase.Removed)

        // The id no longer resolves to a live entry, so the reorder is a no-op.
        assertThat(s.bringToFront("a")).isFalse()
        assertThat(s.find("a")).isNull()
    }
}
