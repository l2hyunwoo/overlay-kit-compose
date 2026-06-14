package io.github.l2hyunwoo.overlaykit

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicReference

/**
 * One overlay tracked by [OverlayHostState]. Stable: identity is the [id], and the only observable
 * mutable surface is the snapshot-backed [phase] and the compose-observable [transitionState].
 */
@Stable
public class OverlayEntry internal constructor(
    public val id: String,
    public val placement: OverlayPlacement,
    /**
     * The overlay body. The scope is a **parameter**, not an extension receiver, so `openForResult`'s
     * typed `ResultOverlayScope<T>` content can be wrapped without a raw cast — casting a
     * `@Composable` lambda (compiled to `Function3`) to a differently-shaped function type throws
     * `ClassCastException`.
     */
    internal val content: @Composable (OverlayScope) -> Unit,
) {
    internal val transitionState: MutableTransitionState<Boolean> = MutableTransitionState(false)

    /** Snapshot-backed lifecycle phase. Mutated only by [OverlayHostState]. */
    public var phase: OverlayPhase by mutableStateOf(OverlayPhase.Entering)
        internal set

    /**
     * Consume-once resolver for `openForResult` (null for fire-and-forget [OverlayController.open]). The
     * first caller to win the CAS that clears this reference is the only one allowed to resume the
     * continuation — the single-resume gate, in place of a TOCTOU-prone `continuation.isActive` check.
     */
    internal val resolver: AtomicReference<((ResolveSignal) -> Unit)?> = AtomicReference(null)
}
