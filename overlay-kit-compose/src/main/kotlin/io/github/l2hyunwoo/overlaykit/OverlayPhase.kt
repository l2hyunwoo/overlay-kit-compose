package io.github.l2hyunwoo.overlaykit

/**
 * Single source of truth for an overlay's lifecycle.
 *
 * Every transition (open, close, revival via [OverlayController.open] with an existing id,
 * unmount, and exit-completion removal) is funneled through [OverlayHostState] so that the
 * phase is the only authority on whether an entry is visible, animating out, or gone.
 *
 * - [Entering]  : just added; enter animation requested ([androidx.compose.animation.core.MutableTransitionState.targetState] = true).
 * - [Visible]   : enter animation settled; the overlay is on screen.
 * - [Exiting]   : close requested; exit animation running ([androidx.compose.animation.core.MutableTransitionState.targetState] = false).
 *                 Removal happens only after the transition is idle and both states are false.
 * - [Removed]   : terminal; the entry is detached from the host and disposed.
 */
public enum class OverlayPhase {
    Entering,
    Visible,
    Exiting,
    Removed,
}
