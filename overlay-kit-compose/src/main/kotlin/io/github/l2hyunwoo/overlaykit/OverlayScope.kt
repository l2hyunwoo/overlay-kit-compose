package io.github.l2hyunwoo.overlaykit

import androidx.compose.runtime.Stable

/**
 * Receiver scope handed to the content of an overlay opened with [OverlayController.open].
 *
 * The overlay content can observe its own [phase] and request to close (animated removal) or
 * unmount (immediate removal) itself without holding a reference to the controller.
 */
@Stable
public interface OverlayScope {
    /** Current lifecycle phase of this overlay (snapshot-backed; reads are observable). */
    public val phase: OverlayPhase

    /** Request an animated close: drives the overlay through [OverlayPhase.Exiting] then removal. */
    public fun close()

    /** Remove this overlay immediately, skipping the exit animation. Idempotent. */
    public fun unmount()
}

/**
 * Receiver scope handed to the content of an overlay opened with [OverlayController.openForResult].
 *
 * Calling [close] resolves the suspended `openForResult` call exactly once with
 * [OverlayResult.Resolved] carrying [result]. The consume-once gate inside [OverlayHostState]
 * guarantees a single resume even if [close] is invoked multiple times or races with
 * [OverlayController.closeAll].
 */
@Stable
public interface ResultOverlayScope<T> : OverlayScope {
    /** Resolve the awaiting `openForResult` with [result] and drive the overlay to removal. */
    public fun close(result: T)
}
