package io.github.l2hyunwoo.overlaykit

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicReference

/**
 * One overlay tracked by [OverlayHostState].
 *
 * Design 2.0 notes:
 * - The old `visible: MutableState<Boolean>` is replaced by [transitionState] so the host can use
 *   the `AnimatedVisibility(visibleState = ...)` overload and observe exit completion via
 *   `transitionState.isIdle && !transitionState.currentState`.
 * - [phase] is snapshot-backed so reads in composition recompose on transition, but the state
 *   machine is owned by [OverlayHostState] — entries never mutate their own phase.
 * - The coroutine continuation is replaced by a [resolver] guarded by a consume-once CAS so an
 *   `openAsync` is resumed exactly once (double-resume on a [kotlin.coroutines.Continuation]
 *   throws `IllegalStateException`; see CancellableContinuationImpl.kt:521).
 *
 * Instances are stable: identity is the [id], and the only observable mutable surface is the
 * snapshot-backed [phase] (and the compose-observable [transitionState]).
 */
@Stable
public class OverlayEntry internal constructor(
    public val id: String,
    public val placement: OverlayPlacement,
    /**
     * The overlay body. Modeled as a composable taking the scope as a **parameter** (not an
     * extension receiver) so that `openAsync`'s typed `AsyncOverlayScope<T>` content can be wrapped
     * without a raw `as` cast — casting a `@Composable` lambda (compiled to `ComposableLambdaImpl`
     * / `Function3`) to a differently-shaped function type throws `ClassCastException`.
     */
    internal val content: @Composable (OverlayScope) -> Unit,
) {
    /** Drives `AnimatedVisibility`; target=true shows, target=false animates out. */
    internal val transitionState: MutableTransitionState<Boolean> = MutableTransitionState(false)

    /** Snapshot-backed lifecycle phase. Mutated only by [OverlayHostState]. */
    public var phase: OverlayPhase by mutableStateOf(OverlayPhase.Entering)
        internal set

    /**
     * Consume-once resolver for `openAsync`. Set when the overlay was opened via
     * [OverlayController.openAsync]; `null` for fire-and-forget [OverlayController.open].
     *
     * The boxed [AtomicReference] is the single-resume gate: the first caller to win the CAS
     * (clearing the reference) is the only one allowed to resume the continuation. This is the
     * SSOT gate Design 2.0 mandates instead of a `continuation.isActive` check (which is a TOCTOU
     * race) — see [OverlayHostState.consumeResolver].
     */
    internal val resolver: AtomicReference<((ResolveSignal) -> Unit)?> = AtomicReference(null)
}
