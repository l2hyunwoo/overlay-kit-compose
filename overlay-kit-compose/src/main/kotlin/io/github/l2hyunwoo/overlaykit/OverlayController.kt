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
 * Imperative handle for opening and closing overlays against an [OverlayHostState]. Obtain one with
 * [rememberOverlayController].
 */
@Stable
public interface OverlayController {
    /**
     * Open a fire-and-forget overlay. If [id] is null a fresh id is generated. If an overlay with
     * [id] already exists and is animating out, it is revived instead of re-added. Returns the
     * overlay id.
     */
    public fun open(
        id: String? = null,
        content: @Composable OverlayScope.() -> Unit,
    ): String

    /**
     * Open an overlay and suspend until it terminates, returning an [OverlayResult]:
     *
     * - [OverlayResult.Resolved] — the overlay called [ResultOverlayScope.close] with a value.
     * - [OverlayResult.Dismissed] — the overlay was closed without a result (a plain
     *   [OverlayScope.close] or [closeAll]). This is a **normal return**, not a cancellation.
     *
     * Both outcomes resume normally, so handle them with a `when`. If the *calling* coroutine is
     * cancelled (e.g. its `Job` is cancelled, or its scope leaves the composition), a
     * `CancellationException` propagates as usual and the overlay is torn down — do not catch that
     * exception to detect a dismissal (it would break structured concurrency and cannot be told
     * apart from a real cancellation: `cancel(null)` produces a fixed-message `CancellationException`
     * either way).
     *
     * A configuration change is one such cancellation. Activity recreation (e.g. rotation) disposes
     * the composition this call awaits in, so the call throws `CancellationException` rather than
     * returning a result, and the overlay is dropped. Overlay state is not retained across
     * configuration changes; if a flow must survive rotation, the caller is responsible for it.
     *
     * A double `close`, or a `close` racing `closeAll`, still resumes exactly once.
     *
     * Leak note: the [continuation][CancellableContinuation] cancellation handler is non-blocking and
     * thread-safe (it may run on any thread). The consume-once gate means only the first terminal
     * signal resumes; later ones are ignored, not errors. An [OverlayResult.Resolved] value is held
     * by a strong reference until the caller consumes it, so avoid returning an `Activity`, `View`,
     * or large `Bitmap`.
     */
    public suspend fun <T> openForResult(
        id: String? = null,
        content: @Composable ResultOverlayScope<T>.() -> Unit,
    ): OverlayResult<T>

    /**
     * Re-stack the overlay with [id] to the top of the z-order (rendered last, above its siblings),
     * keeping the same overlay so its content state, internal `remember`, and any in-flight enter
     * transition survive the move.
     *
     * No-op (and the z-order is unchanged) if [id] is absent, already top-most, or animating out —
     * an overlay that is closing is on its way out and is not brought back to the front. Only affects
     * [OverlayPlacement.InComposition] overlays; [OverlayPlacement.Dialog] overlays each own a
     * separate platform window and are not stacked within the host composition.
     */
    public fun bringToFront(id: String)

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
 * @param mainDispatcher dispatcher the `openForResult` cancellation teardown is marshalled to.
 *   Injectable so tests can supply a `TestDispatcher`.
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

    override suspend fun <T> openForResult(
        id: String?,
        content: @Composable ResultOverlayScope<T>.() -> Unit,
    ): OverlayResult<T> = suspendCancellableCoroutine { continuation ->
        val overlayId = id ?: nextId()

        // Wrap the typed content in a composable lambda (no raw cast). The host always supplies an
        // EntryOverlayScope, which implements ResultOverlayScope<Any?>; the typed content only uses
        // close(result: T), and T is erased, so the unchecked cast of the scope is sound.
        val wrapped: @Composable (OverlayScope) -> Unit = { scope ->
            @Suppress("UNCHECKED_CAST")
            (scope as ResultOverlayScope<T>).content()
        }

        if (!state.revive(overlayId)) {
            state.add(OverlayEntry(id = overlayId, placement = placement, content = wrapped))
        }

        state.attachResolver(overlayId) { signal ->
            resumeOnce(continuation, signal)
        }

        // invokeOnCancellation has no execution-context guarantee, so it only unregisters the
        // resolver here; the close (a snapshot-state write) is marshalled to the main dispatcher.
        continuation.invokeOnCancellation {
            state.find(overlayId)?.resolver?.set(null)
            scope.launch(mainDispatcher) {
                state.close(overlayId)
            }
        }
    }

    private fun <T> resumeOnce(
        continuation: CancellableContinuation<OverlayResult<T>>,
        signal: ResolveSignal,
    ) {
        // The consume-once CAS in OverlayHostState guarantees this runs at most once, so no
        // `isActive` guard (a TOCTOU race) is needed. Resuming a cancelled continuation is a no-op.
        // A result-less teardown (Removed) is a normal Dismissed return, not a cancellation, so the
        // caller can tell it apart from a real coroutine cancellation, which still throws.
        when (signal) {
            is ResolveSignal.Value -> {
                @Suppress("UNCHECKED_CAST")
                continuation.resume(OverlayResult.Resolved(signal.value as T))
            }
            ResolveSignal.Removed -> continuation.resume(OverlayResult.Dismissed)
        }
    }

    override fun bringToFront(id: String) {
        state.bringToFront(id)
    }

    override fun close(id: String) {
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
