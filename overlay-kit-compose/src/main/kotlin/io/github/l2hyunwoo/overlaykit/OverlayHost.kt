package io.github.l2hyunwoo.overlaykit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.filter

/**
 * Hosts overlays opened against [state] above [content].
 *
 * Rendering contract (Design 2.0 §2, §6, §7):
 * - Provides [state] through [LocalOverlayHostState] so descendants can `rememberOverlayController`.
 * - Each entry is rendered under `key(entry.id)` so identity survives reordering and the entry's
 *   transition/scope are not torn down on unrelated recompositions.
 * - Visibility uses the `AnimatedVisibility(visibleState = MutableTransitionState<Boolean>)`
 *   overload (NOT the `visible: Boolean` one) so exit completion is observable.
 * - Exit completion is detected via `snapshotFlow { phase == Exiting && isIdle && !currentState }`
 *   and reported to [OverlayHostState.onExitFinished]. A revival flips `targetState` back to true,
 *   which breaks the predicate and auto-cancels the pending removal.
 * - All writes to [state] happen inside event handlers / effects, never in the render body of the
 *   `forEach` (no backwards writes → no infinite recomposition).
 *
 * @param state the host state, typically from [rememberOverlayHostState].
 * @param content the underlying app content; overlays stack above it.
 */
@Composable
public fun OverlayHost(
    state: OverlayHostState = rememberOverlayHostState(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOverlayHostState provides state) {
        content()

        val entries = state.entries
        // In-composition overlays are siblings stacked above content; z-order = list order.
        // Dialog overlays render in their own windows.
        val inCompositionEntries = entries.filter { it.placement == OverlayPlacement.InComposition }
        val topMostInComposition = inCompositionEntries.lastOrNull { it.phase != OverlayPhase.Exiting }

        entries.forEach { entry ->
            key(entry.id) {
                when (entry.placement) {
                    OverlayPlacement.InComposition ->
                        InCompositionOverlay(
                            state = state,
                            entry = entry,
                            isTopMost = entry === topMostInComposition,
                        )

                    OverlayPlacement.Dialog ->
                        DialogOverlay(state = state, entry = entry)
                }
            }
        }
    }
}

@Composable
private fun InCompositionOverlay(
    state: OverlayHostState,
    entry: OverlayEntry,
    isTopMost: Boolean,
) {
    // BackHandler only on the topmost visible in-composition overlay (Design 2.0 §7).
    if (isTopMost && entry.phase != OverlayPhase.Exiting) {
        BackHandler(enabled = true) { state.close(entry.id) }
    }

    AnimatedVisibility(visibleState = entry.transitionState) {
        // scope is remembered per entry; its callbacks are stable method references so the
        // content's recomposition skipping is not broken by a freshly-allocated lambda.
        val scope = rememberOverlayScope(state, entry)
        entry.content(scope)
    }

    ExitCompletionEffect(state, entry)
    EnterCompletionEffect(state, entry)
}

@Composable
private fun DialogOverlay(
    state: OverlayHostState,
    entry: OverlayEntry,
) {
    // A Dialog hosts its own window; render only while it should be on screen. The exit
    // transition still plays inside via AnimatedVisibility before removal.
    Dialog(onDismissRequest = { state.close(entry.id) }) {
        AnimatedVisibility(visibleState = entry.transitionState) {
            val scope = rememberOverlayScope(state, entry)
            entry.content(scope)
        }
    }
    ExitCompletionEffect(state, entry)
    EnterCompletionEffect(state, entry)
}

/**
 * Observe exit completion for [entry] and report it once. The predicate is exactly Design 2.0 §2:
 * `phase == Exiting && transitionState.isIdle && !transitionState.currentState`. A revival flips
 * `targetState` back to true, breaking `isIdle` (now `currentState != targetState`), so the flow
 * stops emitting `true` and the removal is auto-cancelled.
 */
@Composable
private fun ExitCompletionEffect(state: OverlayHostState, entry: OverlayEntry) {
    LaunchedEffect(entry.id) {
        snapshotFlow {
            entry.phase == OverlayPhase.Exiting &&
                entry.transitionState.isIdle &&
                !entry.transitionState.currentState
        }
            .filter { it }
            .collect { state.onExitFinished(entry.id) }
    }
}

/** Settle [entry] to [OverlayPhase.Visible] once its enter transition is idle. */
@Composable
private fun EnterCompletionEffect(state: OverlayHostState, entry: OverlayEntry) {
    LaunchedEffect(entry.id) {
        snapshotFlow {
            entry.phase == OverlayPhase.Entering &&
                entry.transitionState.isIdle &&
                entry.transitionState.currentState
        }
            .filter { it }
            .collect { state.onEnterFinished(entry.id) }
    }
}

/**
 * Remembered, stable [OverlayScope]/[AsyncOverlayScope] for [entry]. Callbacks are method
 * references on [state] (stable) rather than freshly-allocated lambdas, so passing this scope into
 * content does not defeat recomposition skipping (Design 2.0 §6).
 */
@Composable
private fun rememberOverlayScope(
    state: OverlayHostState,
    entry: OverlayEntry,
): AsyncOverlayScope<Any?> = remember(entry.id) { EntryOverlayScope(state, entry) }

/**
 * Stable scope implementation. Implements [AsyncOverlayScope] (and therefore [OverlayScope]); the
 * controller casts the user's typed `AsyncOverlayScope<T>` content to the erased form, which is
 * sound because the only typed surface is the `close(result)` argument.
 */
@androidx.compose.runtime.Stable
private class EntryOverlayScope(
    private val state: OverlayHostState,
    private val entry: OverlayEntry,
) : AsyncOverlayScope<Any?> {
    override val phase: OverlayPhase get() = entry.phase
    override fun close() = state.close(entry.id)
    override fun close(result: Any?) = state.resolveAndClose(entry.id, result)
    override fun unmount() = state.unmount(entry.id)
}
