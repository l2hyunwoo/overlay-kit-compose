package io.github.l2hyunwoo.overlaykit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Exhaustive phase-transition table for the pure [OverlayHostState] store (no composables).
 *
 * Mirrors Design 2.0 §1/§9 transition rules:
 * - open → Entering
 * - close → Exiting
 * - revival (open existing) → Exiting → Visible
 * - exit-completion removal happens only when `Exiting && idle && !target`
 * - unmount is idempotent across phases
 * - closeAll's deferred removal does not delete a revived entry
 */
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
}
