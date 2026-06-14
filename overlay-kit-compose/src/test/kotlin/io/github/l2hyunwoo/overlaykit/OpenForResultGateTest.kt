package io.github.l2hyunwoo.overlaykit

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `openForResult` termination contract: a value `close` returns [OverlayResult.Resolved], a
 * result-less teardown returns [OverlayResult.Dismissed] (a normal return, *not* a cancellation),
 * and a real coroutine cancellation still throws. The consume-once resolver must resume exactly
 * once even when `close` is called twice or `closeAll` races an individual `close` — a second
 * resume would throw `IllegalStateException`, so "no crash + exactly one result" is also tested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenForResultGateTest {

    private fun controller(
        state: OverlayHostState,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        scope: kotlinx.coroutines.CoroutineScope,
    ): OverlayController = OverlayControllerImpl(
        state = state,
        scope = scope,
        mainDispatcher = dispatcher,
        placement = OverlayPlacement.InComposition,
    )

    // In a pure-store test the transition never animates, so reporting exit directly exercises the
    // phase guard inside onExitFinished, as the host would once the exit settles.
    private fun OverlayHostState.completeExit(id: String) {
        onExitFinished(id)
    }

    @Test
    fun resolvesWithValueOnClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        val deferred = async {
            controller.openForResult<String> { /* content */ }
        }
        advanceUntilIdle()

        // The overlay is registered with a resolver.
        val id = state.entries.single().id
        // Resolve with a value through the scope's typed close().
        state.resolveAndClose(id, "ok")
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(OverlayResult.Resolved("ok"))
    }

    @Test
    fun dismissReturnsDistinctFromResolved() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        // A plain close (no value) must resume normally with Dismissed, not throw.
        val closeDeferred = async {
            controller.openForResult<String> { }
        }
        advanceUntilIdle()
        val closeId = state.entries.single().id
        controller.close(closeId)
        state.completeExit(closeId)
        advanceUntilIdle()
        assertThat(closeDeferred.await()).isEqualTo(OverlayResult.Dismissed)

        // closeAll() is also a result-less teardown → Dismissed.
        val closeAllDeferred = async {
            controller.openForResult<String> { }
        }
        advanceUntilIdle()
        val closeAllId = state.entries.single().id
        controller.closeAll()
        state.completeExit(closeAllId)
        advanceUntilIdle()
        assertThat(closeAllDeferred.await()).isEqualTo(OverlayResult.Dismissed)

        // Dismissed is its own type, never confused with a Resolved value.
        assertThat(OverlayResult.Dismissed).isNotEqualTo(OverlayResult.Resolved(Unit))
    }

    @Test
    fun doubleResolveResumesExactlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        val deferred = async {
            controller.openForResult<Int> { }
        }
        advanceUntilIdle()
        val id = state.entries.single().id

        // First resolve wins; the second finds a consumed (null) resolver and does nothing.
        // A second resume would throw IllegalStateException — the absence of a crash is the assert.
        state.resolveAndClose(id, 1)
        state.resolveAndClose(id, 2)
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(OverlayResult.Resolved(1))
    }

    @Test
    fun closeAllThenIndividualCloseResumesExactlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        val deferred = async {
            controller.openForResult<String> { }
        }
        advanceUntilIdle()
        val id = state.entries.single().id

        // resolve provides the value first (winner), then closeAll / a second close / completing
        // exit must not resume again (a second resume throws IllegalStateException).
        state.resolveAndClose(id, "value")
        controller.closeAll()
        state.completeExit(id)
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(OverlayResult.Resolved("value"))
    }

    @Test
    fun closeWithoutValueReturnsDismissed() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        var result: OverlayResult<String>? = null
        val job = launch {
            result = controller.openForResult<String> { }
        }
        advanceUntilIdle()
        val id = state.entries.single().id

        // Plain close (no value): overlay exits and is removed; the resolver fires with Removed,
        // which resumes the awaiting coroutine with Dismissed (a normal return, not a cancellation).
        controller.close(id)
        state.completeExit(id)
        advanceUntilIdle()

        assertThat(result).isEqualTo(OverlayResult.Dismissed)
        assertThat(job.isCancelled).isFalse()
        assertThat(job.isCompleted).isTrue()
    }

    @Test
    fun realCancellationStillThrows() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        var caught: Throwable? = null
        var result: OverlayResult<String>? = null
        val job = launch {
            try {
                result = controller.openForResult<String> { }
            } catch (e: CancellationException) {
                caught = e
                throw e
            }
        }
        advanceUntilIdle()
        val id = state.entries.single().id
        assertThat(state.find(id)!!.phase).isEqualTo(OverlayPhase.Entering)

        // Cancelling the *calling* coroutine throws CancellationException — never resumes Dismissed.
        job.cancel()
        advanceUntilIdle()

        assertThat(result).isNull()
        assertThat(caught).isInstanceOf(CancellationException::class.java)
        assertThat(job.isCancelled).isTrue()
        // invokeOnCancellation tears the overlay down: unregister resolver + drive it Exiting on the
        // injected (test) main dispatcher (not removed yet — exit animation).
        assertThat(state.find(id)!!.resolver.get()).isNull()
        assertThat(state.find(id)!!.phase).isEqualTo(OverlayPhase.Exiting)
    }
}
