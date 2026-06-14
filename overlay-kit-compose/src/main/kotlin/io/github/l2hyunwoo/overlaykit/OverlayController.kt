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
     * Open an overlay and suspend until it resolves itself via [AsyncOverlayScope.close], returning
     * the supplied result. Cancelling the calling coroutine tears the overlay down. A double `close`
     * or a `close` racing `closeAll` still resumes exactly once.
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
 * @param mainDispatcher dispatcher the `openAsync` cancellation teardown is marshalled to.
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

    private fun <T> resumeOnce(continuation: CancellableContinuation<T>, signal: ResolveSignal) {
        // The consume-once CAS in OverlayHostState guarantees this runs at most once, so no
        // `isActive` guard (a TOCTOU race) is needed. Resuming a cancelled continuation is a no-op.
        when (signal) {
            is ResolveSignal.Value -> {
                @Suppress("UNCHECKED_CAST")
                continuation.resume(signal.value as T)
            }
            ResolveSignal.Removed -> continuation.cancel()
        }
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
