package io.github.l2hyunwoo.overlaykit

/**
 * An overlay's lifecycle phase. Funneled through [OverlayHostState] so the phase is the only
 * authority on whether an entry is visible, animating out, or gone.
 */
public enum class OverlayPhase {
    /** Just added; enter animation requested. */
    Entering,

    /** Enter animation settled; on screen. */
    Visible,

    /** Close requested; exit animation running. Removed only once the transition is idle. */
    Exiting,

    /** Terminal; detached from the host and disposed. */
    Removed,
}
