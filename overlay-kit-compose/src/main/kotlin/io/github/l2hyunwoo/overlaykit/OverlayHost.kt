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
 * Hosts overlays opened against [state], stacked above [content] in insertion order. Provides
 * [state] through [LocalOverlayHostState] so descendants can `rememberOverlayController`.
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
        val inCompositionEntries = entries.filter { it.placement == OverlayPlacement.InComposition }
        val topMostInComposition = inCompositionEntries.lastOrNull { it.phase != OverlayPhase.Exiting }

        entries.forEach { entry ->
            // key(id) so an entry's transition/scope survive reordering and unrelated recompositions.
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
    if (isTopMost && entry.phase != OverlayPhase.Exiting) {
        BackHandler(enabled = true) { state.close(entry.id) }
    }

    AnimatedVisibility(visibleState = entry.transitionState) {
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
 * Report exit completion once the exit transition is fully idle. A revival flips `targetState` back
 * to true, which breaks `isIdle`, so the flow stops emitting and the removal is auto-cancelled.
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
 * Stable per-entry scope. Remembered so its callbacks stay stable method references on [state]
 * rather than fresh lambdas, which would defeat the content's recomposition skipping.
 */
@Composable
private fun rememberOverlayScope(
    state: OverlayHostState,
    entry: OverlayEntry,
): AsyncOverlayScope<Any?> = remember(entry.id) { EntryOverlayScope(state, entry) }

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
