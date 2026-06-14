package io.github.l2hyunwoo.overlaykit

/**
 * Outcome of an [OverlayController.openForResult] call. Distinguishes a value supplied by the
 * overlay from a result-less dismissal, without the nullability ambiguity of returning `T?`
 * (where `Resolved(null)` and a dismissal would be indistinguishable).
 *
 * @see OverlayController.openForResult
 */
public sealed interface OverlayResult<out T> {
    /** The overlay resolved itself via [ResultOverlayScope.close], carrying [value]. */
    public data class Resolved<T>(val value: T) : OverlayResult<T>

    /**
     * The overlay was closed without a result — a plain [OverlayScope.close] or
     * [OverlayController.closeAll]. This is a normal return, not a cancellation: handle it in a
     * `when` rather than catching a `CancellationException`.
     */
    public data object Dismissed : OverlayResult<Nothing>
}
