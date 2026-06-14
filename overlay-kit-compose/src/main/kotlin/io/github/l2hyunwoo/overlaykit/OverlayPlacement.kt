package io.github.l2hyunwoo.overlaykit

/**
 * Where an [OverlayHost] renders its overlays.
 *
 * - [InComposition] : overlays are siblings stacked above the app tree inside the same
 *   composition (z-order follows insertion order). Suitable for in-app banners, sheets, and
 *   any surface that should share the host window.
 * - [Dialog] : each overlay is hosted in its own platform window via
 *   [androidx.compose.ui.window.Dialog]. Suitable for modal content that must sit above
 *   everything, including system insets handling owned by the dialog window.
 */
public enum class OverlayPlacement {
    InComposition,
    Dialog,
}
