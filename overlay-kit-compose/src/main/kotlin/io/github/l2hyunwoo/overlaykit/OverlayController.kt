package io.github.l2hyunwoo.overlaykit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Imperative handle for opening and closing overlays against an [OverlayHostState].
 *
 * Obtain one with [rememberOverlayController]. The controller is [Stable]; method references on it
 * are safe to pass as callbacks without breaking recomposition skipping (Design 2.0 §6).
 */
@Stable
public interface OverlayController {
    /**
     * Open a fire-and-forget overlay. If [id] is null a fresh id is generated. If an overlay with
     * [id] already exists and is animating out, it is **revived** instead of re-added. Returns the
     * overlay id.
     *
     * Enter/revival happen synchronously in the calling event handler — not deferred to a
     * `LaunchedEffect` (Design 2.0 §3).
     */
    public fun open(
        id: String? = null,
        content: @Composable OverlayScope.() -> Unit,
    ): String

    /**
     * Open an overlay and suspend until it resolves itself via [AsyncOverlayScope.close], returning
     * the supplied result. Cancellation of the calling coroutine tears the overlay down (the
     * teardown is marshalled to the main dispatcher; Design 2.0 §5).
     *
     * Resume is **consume-once**: a double `close`, or a `close` racing `closeAll`, resumes the
     * continuation exactly once (a second resume would throw `IllegalStateException`).
     */
    public suspend fun <T> openAsync(
        id: String? = null,
        content: @Composable AsyncOverlayScope<T>.() -> Unit,
    ): T

    /** Animated close of the overlay with [id] (no-op if absent or already closing). */
    public fun close(id: String)

    /** Immediate removal of the overlay with [id] (no exit animation). Idempotent. */
    public fun unmount(id: String)

    /** Animated close of every live overlay. */
    public fun closeAll()

    /** Immediate removal of every live overlay. */
    public fun unmountAll()
}

/**
 * Default [OverlayController] bound to an [OverlayHostState].
 *
 * @param state the phase-gate store this controller drives.
 * @param scope a coroutine scope (typically `rememberCoroutineScope()`) used to marshal the
 *   `openAsync` cancellation teardown back onto the main thread.
 * @param mainDispatcher the dispatcher the cancellation teardown is dispatched to. Injectable so
 *   tests can supply a `TestDispatcher`; defaults to the main immediate dispatcher in the
 *   composable factory.
 */
internal class OverlayControllerImpl(
    private val state: OverlayHostState,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val placement: OverlayPlacement,
) : OverlayController {

    private val idCounter = AtomicLong(0L)

    private fun nextId(): String = "overlay-${idCounter.incrementAndGet()}"

    override fun open(
        id: String?,
        content: @Composable OverlayScope.() -> Unit,
    ): String {
        val overlayId = id ?: nextId()
        // Revival path: an existing entry (possibly mid-exit) is brought back instead of re-added.
        if (state.revive(overlayId)) return overlayId
        state.add(
            OverlayEntry(
                id = overlayId,
                placement = placement,
                content = { scope -> scope.content() },
            ),
        )
        return overlayId
    }

    override suspend fun <T> openAsync(
        id: String?,
        content: @Composable AsyncOverlayScope<T>.() -> Unit,
    ): T = suspendCancellableCoroutine { continuation ->
        val overlayId = id ?: nextId()

        // Wrap the typed content in a composable lambda (no raw cast). The host always supplies an
        // EntryOverlayScope, which implements AsyncOverlayScope<Any?>; the typed content only uses
        // close(result: T), and T is erased, so the unchecked cast of the scope is sound.
        val wrapped: @Composable (OverlayScope) -> Unit = { scope ->
            @Suppress("UNCHECKED_CAST")
            (scope as AsyncOverlayScope<T>).content()
        }

        if (!state.revive(overlayId)) {
            state.add(OverlayEntry(id = overlayId, placement = placement, content = wrapped))
        }

        // The consume-once resolver lives in OverlayHostState; it is the single-resume gate.
        // Resuming here is only ever reached by the one caller that won the CAS, so a double
        // resume (which would throw IllegalStateException) is impossible.
        state.attachResolver(overlayId) { signal ->
            resumeOnce(continuation, signal)
        }

        // Design 2.0 §5: invokeOnCancellation has NO execution-context guarantee
        // (CancellableContinuation.kt:225-227). So the handler only unregisters the resolver here;
        // the actual close + removal (snapshot-state writes) is marshalled to the main dispatcher.
        continuation.invokeOnCancellation {
            // Unregister so no late resolve targets a dead continuation.
            state.find(overlayId)?.resolver?.set(null)
            scope.launch(mainDispatcher) {
                state.close(overlayId)
            }
        }
    }

    private fun <T> resumeOnce(continuation: CancellableContinuation<T>, signal: ResolveSignal) {
        // No `continuation.isActive` guard here: that is a TOCTOU race (Design 2.0 §4). The
        // consume-once CAS in OverlayHostState already guarantees this resolver runs at most once,
        // so a double resume (the only thing that would throw IllegalStateException) cannot happen.
        // Resuming an already-cancelled continuation is a safe no-op in suspendCancellableCoroutine.
        when (signal) {
            is ResolveSignal.Value -> {
                @Suppress("UNCHECKED_CAST")
                continuation.resume(signal.value as T)
            }
            // Removed without an explicit value: cancel the awaiting coroutine rather than resume.
            ResolveSignal.Removed -> continuation.cancel()
        }
    }

    override fun close(id: String) {
        // If this overlay was opened via openAsync, route through the resolver gate with no value
        // so the awaiting coroutine is cancelled on removal; otherwise a plain animated close.
        state.close(id)
    }

    override fun unmount(id: String) {
        state.unmount(id)
    }

    override fun closeAll() {
        state.closeAll()
    }

    override fun unmountAll() {
        state.unmountAll()
    }
}
