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
 * Resume-gate tests for `openAsync` (Design 2.0 §4/§5/§9).
 *
 * The consume-once resolver in [OverlayHostState] must guarantee a single resume even when
 * `close` is called twice, or `closeAll` races an individual `close`. A second resume on the
 * underlying continuation would throw `IllegalStateException` (CancellableContinuationImpl.kt:521),
 * so "no crash + exactly one result" is the property under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenAsyncResumeGateTest {

    /**
     * Build a controller wired to a store, sharing the test scheduler so the cancellation teardown
     * (marshalled to [mainDispatcher]) is deterministic.
     */
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

    /**
     * Report exit completion as the host would once the (now-idle) exit transition settles. In a
     * pure-store test the transition never animates, so reporting directly exercises the phase
     * guard inside [OverlayHostState.onExitFinished].
     */
    private fun OverlayHostState.completeExit(id: String) {
        onExitFinished(id)
    }

    @Test
    fun resolvesWithValueOnClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        val deferred = async {
            controller.openAsync<String> { /* content */ }
        }
        advanceUntilIdle()

        // The async overlay is registered with a resolver.
        val id = state.entries.single().id
        // Resolve with a value through the scope's typed close().
        state.resolveAndClose(id, "ok")
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo("ok")
    }

    @Test
    fun doubleResolveResumesExactlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        val deferred = async {
            controller.openAsync<Int> { }
        }
        advanceUntilIdle()
        val id = state.entries.single().id

        // First resolve wins; the second finds a consumed (null) resolver and does nothing.
        // A second resume would throw IllegalStateException — the absence of a crash is the assert.
        state.resolveAndClose(id, 1)
        state.resolveAndClose(id, 2)
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(1)
    }

    @Test
    fun closeAllThenIndividualCloseResumesExactlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        var result: String? = null
        val job = launch {
            result = try {
                controller.openAsync<String> { }
            } catch (e: CancellationException) {
                "cancelled"
            }
        }
        advanceUntilIdle()
        val id = state.entries.single().id

        // closeAll starts the animated close; resolve provides the value first (winner),
        // then completing exit / a second close must not resume again.
        state.resolveAndClose(id, "value")
        controller.closeAll()
        state.completeExit(id)
        advanceUntilIdle()

        assertThat(result).isEqualTo("value")
        assertThat(job.isCompleted).isTrue()
    }

    @Test
    fun closeWithoutValueCancelsTheAwaiter() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        var cancelled = false
        val job = launch {
            try {
                controller.openAsync<String> { }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        advanceUntilIdle()
        val id = state.entries.single().id

        // Plain close (no value): overlay exits and is removed; the resolver fires with Removed,
        // which cancels the awaiting coroutine.
        controller.close(id)
        state.completeExit(id)
        advanceUntilIdle()

        assertThat(cancelled).isTrue()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun cancellationTeardownClosesOverlayOnMainDispatcher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = OverlayHostState()
        val controller = controller(state, dispatcher, backgroundScope)

        val job = launch {
            controller.openAsync<String> { }
        }
        advanceUntilIdle()
        val id = state.entries.single().id
        assertThat(state.find(id)!!.phase).isEqualTo(OverlayPhase.Entering)

        // Cancel the awaiting coroutine. invokeOnCancellation only unregisters; the close is
        // marshalled to the injected (test) main dispatcher.
        job.cancel()
        advanceUntilIdle()

        // The overlay was driven to Exiting on the main dispatcher (not removed yet — exit anim).
        assertThat(state.find(id)!!.phase).isEqualTo(OverlayPhase.Exiting)
    }
}
