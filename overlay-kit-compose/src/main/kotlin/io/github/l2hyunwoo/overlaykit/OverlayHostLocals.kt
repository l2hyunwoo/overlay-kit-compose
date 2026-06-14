package io.github.l2hyunwoo.overlaykit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The host state for the nearest [OverlayHost]. `static` is sound because the instance is created
 * once per host and never re-created — only its internal snapshot state changes.
 */
public val LocalOverlayHostState: androidx.compose.runtime.ProvidableCompositionLocal<OverlayHostState> =
    staticCompositionLocalOf { error("No OverlayHostState provided. Wrap content in an OverlayHost.") }

/**
 * Remember a single stable [OverlayHostState] for the lifetime of the composition.
 *
 * The state lives in a plain [remember], so it is **not** retained across configuration changes: an
 * activity recreation (e.g. rotation) reinitializes it and drops every live overlay. Overlay content
 * is a lambda and cannot be serialized, so there is no saved-state restoration. If a flow must
 * survive rotation, the caller owns that — lock the orientation, or persist the decision yourself
 * and re-open on re-entry.
 */
@Composable
public fun rememberOverlayHostState(): OverlayHostState = remember { OverlayHostState() }

/**
 * Remember a stable [OverlayController] bound to the nearest [OverlayHostState].
 *
 * @param placement default placement for overlays opened through this controller.
 * @param mainDispatcher dispatcher used to marshal `openForResult` cancellation teardown to the main
 *   thread. Injectable for tests.
 */
@Composable
public fun rememberOverlayController(
    placement: OverlayPlacement = OverlayPlacement.InComposition,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
): OverlayController {
    val state = LocalOverlayHostState.current
    val scope = rememberCoroutineScope()
    return remember(state, scope, placement, mainDispatcher) {
        OverlayControllerImpl(
            state = state,
            scope = scope,
            mainDispatcher = mainDispatcher,
            placement = placement,
        )
    }
}
