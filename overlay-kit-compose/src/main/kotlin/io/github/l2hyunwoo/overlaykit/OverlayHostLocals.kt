package io.github.l2hyunwoo.overlaykit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The host state for the nearest [OverlayHost]. `static` because the instance is a single stable
 * object created once per host (Design 2.0 §6): readers do not need invalidation when it changes,
 * because it never changes — only its internal snapshot state does.
 */
public val LocalOverlayHostState: androidx.compose.runtime.ProvidableCompositionLocal<OverlayHostState> =
    staticCompositionLocalOf { error("No OverlayHostState provided. Wrap content in an OverlayHost.") }

/**
 * Remember a single stable [OverlayHostState] for the lifetime of the composition. The instance is
 * created exactly once (`remember {}` with no keys) — never re-created — which is what makes the
 * `staticCompositionLocalOf` above sound (Design 2.0 §6).
 */
@Composable
public fun rememberOverlayHostState(): OverlayHostState = remember { OverlayHostState() }

/**
 * Remember a stable [OverlayController] bound to the nearest [OverlayHostState].
 *
 * @param placement default placement for overlays opened through this controller.
 * @param mainDispatcher dispatcher used to marshal `openAsync` cancellation teardown to the main
 *   thread; defaults to [Dispatchers.Main]'s immediate variant. Injectable for tests.
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
