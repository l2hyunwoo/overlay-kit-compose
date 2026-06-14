package io.github.l2hyunwoo.overlaykit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (real emulator) M2 verification. Unlike the JVM/Robolectric [OverlayHostComposeTest],
 * this suite runs on a real Android runtime: [androidx.compose.animation.AnimatedVisibility]
 * transitions advance on a real frame clock, [androidx.activity.compose.BackHandler] is dispatched
 * through a real `OnBackPressedDispatcher` (via a system back key injected with UI Automator), and a
 * [androidx.compose.ui.window.Dialog] overlay is hosted in a genuine platform window — so the
 * Dialog dismiss path is exercised through the real window-focus back routing instead of a
 * Robolectric `ShadowDialog`.
 *
 * The host activity is the empty ComponentActivity supplied by `ui-test-manifest`; it provides the
 * `OnBackPressedDispatcher` that the top-most overlay's BackHandler registers against.
 *
 * Animation synchronization: `createAndroidComposeRule` installs a controllable test clock even on
 * device. We rely on `waitForIdle()` to auto-advance enter/exit transitions to completion, and on
 * `mainClock.autoAdvance = false` + `advanceTimeByFrame()` to freeze an overlay mid-exit for the
 * revival case. Result observation uses `waitUntil` rather than a bare assertion so the suspend
 * resume (marshalled to the main dispatcher) is given a chance to land.
 */
@RunWith(AndroidJUnit4::class)
class OverlayHostInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val uiDevice: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * Dispatch a real system back key and wait for the resulting overlay teardown to settle. Uses
     * UI Automator rather than `Espresso.pressBack()`: Espresso requires the targeted root view to
     * already hold window focus, which the empty `ui-test-manifest` host activity does not reliably
     * acquire on an emulator (RootViewWithoutFocusException). `UiDevice.pressBack()` injects the key
     * at the system level to the currently-focused window — the Dialog window when one is showing,
     * otherwise the activity — which is exactly the real-system-back path under test.
     */
    private fun pressSystemBack() {
        rule.waitForIdle()
        uiDevice.waitForIdle()
        uiDevice.pressBack()
    }

    /** Captured from inside the host content, where [LocalOverlayHostState] is provided. */
    private class Handles(
        val controller: OverlayController,
        val state: OverlayHostState,
        val scope: CoroutineScope,
    )

    /**
     * Sets an [OverlayHost] whose content captures a controller + scope for the test body. The
     * underlying app content is a single tagged Box so the overlay nodes are unambiguous.
     */
    private fun setHost(
        placement: OverlayPlacement = OverlayPlacement.InComposition,
    ): Handles {
        lateinit var handles: Handles
        rule.setContent {
            val state = rememberOverlayHostState()
            OverlayHost(state = state) {
                val controller = rememberOverlayController(placement = placement)
                val scope = rememberCoroutineScope()
                handles = Handles(controller, state, scope)
                Box(Modifier.testTag("app-content"))
            }
        }
        rule.waitForIdle()
        return handles
    }

    // (a) open → overlay composable is present and displayed in the tree.
    @Test
    fun open_showsOverlayContent() {
        val h = setHost()

        rule.runOnIdle { h.controller.open { Text("banner") } }
        rule.waitForIdle()

        rule.onNodeWithText("banner").assertIsDisplayed()
        assertThat(h.state.entries).hasSize(1)
        assertThat(h.state.entries.single().phase).isEqualTo(OverlayPhase.Visible)
    }

    // (b) close → after the exit transition settles the node is gone and the entry is removed.
    @Test
    fun close_removesOverlayAfterExit() {
        val h = setHost()
        val id = rule.runOnIdle { h.controller.open { Text("banner") } }
        rule.waitForIdle()
        rule.onNodeWithText("banner").assertIsDisplayed()

        rule.runOnIdle { h.controller.close(id) }
        // waitForIdle drives the real-clock exit transition to idle; the LaunchedEffect snapshotFlow
        // then fires onExitFinished, which removes the entry. waitUntil guards against the removal
        // landing a frame after waitForIdle returns on a real device.
        rule.waitUntil(timeoutMillis = 5_000) { h.state.entries.isEmpty() }

        rule.onNodeWithText("banner").assertDoesNotExist()
        assertThat(h.state.entries).isEmpty()
    }

    // (c) openForResult → Resolved(value) when content calls close(result).
    @Test
    fun openForResult_resolvedOnTypedClose() {
        val h = setHost()
        var result: OverlayResult<String>? = null

        rule.runOnIdle {
            h.scope.launch {
                result = h.controller.openForResult<String> {
                    Button(onClick = { close("ok") }) { Text("resolve") }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("resolve").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { result != null }

        assertThat(result).isEqualTo(OverlayResult.Resolved("ok"))
        rule.waitUntil(timeoutMillis = 5_000) { h.state.entries.isEmpty() }
        assertThat(h.state.entries).isEmpty()
    }

    // (c) openForResult → Dismissed when content calls plain close() (no value).
    @Test
    fun openForResult_dismissedOnPlainClose() {
        val h = setHost()
        var result: OverlayResult<String>? = null

        rule.runOnIdle {
            h.scope.launch {
                result = h.controller.openForResult<String> {
                    // ResultOverlayScope also exposes the plain close() from OverlayScope.
                    Button(onClick = { close() }) { Text("dismiss") }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("dismiss").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { result != null }

        assertThat(result).isEqualTo(OverlayResult.Dismissed)
        rule.waitUntil(timeoutMillis = 5_000) { h.state.entries.isEmpty() }
        assertThat(h.state.entries).isEmpty()
    }

    // (d) BackHandler closes only the top-most in-composition overlay.
    @Test
    fun back_closesOnlyTopMostInComposition() {
        val h = setHost()
        val lowerId = rule.runOnIdle { h.controller.open { Text("lower") } }
        rule.waitForIdle()
        val upperId = rule.runOnIdle { h.controller.open { Text("upper") } }
        rule.waitForIdle()
        rule.onNodeWithText("lower").assertIsDisplayed()
        rule.onNodeWithText("upper").assertIsDisplayed()

        // System back dispatched through the activity's real OnBackPressedDispatcher, which the
        // top-most overlay's BackHandler intercepts. Driven on the UI thread directly (rather than an
        // injected key) so it is independent of which window currently holds focus on the emulator —
        // the bare ui-test-manifest host activity does not reliably hold focus. This still exercises
        // the real BackHandler → OnBackPressedDispatcher path on-device.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(timeoutMillis = 5_000) { h.state.find(upperId) == null }

        // Only the upper overlay is gone; the lower one stays.
        rule.onNodeWithText("upper").assertDoesNotExist()
        rule.onNodeWithText("lower").assertIsDisplayed()
        assertThat(h.state.find(upperId)).isNull()
        assertThat(h.state.find(lowerId)?.phase).isEqualTo(OverlayPhase.Visible)
    }

    // (e) Dialog placement: a real system back inside the dialog window routes through the Dialog's
    // own OnBackPressedDispatcher → onDismissRequest → state.close(id).
    @Test
    fun dialog_onDismissRequestClosesOverlay() {
        val h = setHost(placement = OverlayPlacement.Dialog)
        val id = rule.runOnIdle { h.controller.open { Text("dialog-body") } }
        rule.waitForIdle()
        rule.onNodeWithText("dialog-body").assertIsDisplayed()

        // When the Compose Dialog is showing it owns window focus, so a system back reaches the
        // dialog window's own back-pressed callback — the one AndroidDialog.android.kt registers to
        // invoke onDismissRequest (dismissOnBackPress = true by default) — which is wired here to
        // state.close(id). This is the real-device equivalent of the Robolectric ShadowDialog back
        // path.
        pressSystemBack()
        rule.waitUntil(timeoutMillis = 5_000) { h.state.find(id) == null }

        rule.onNodeWithText("dialog-body").assertDoesNotExist()
        assertThat(h.state.find(id)).isNull()
    }

    // (e2) Regression — Dialog enters at full size, not animating its size 0 -> full.
    //
    // A Dialog is a separate WRAP_CONTENT platform window. The default AnimatedVisibility transition
    // (fadeIn() + expandIn()) animates the content's measured size from 0x0 to full (EnterExitTransition
    // expandIn default initialSize = IntSize(0, 0)), which forces the dialog window to physically
    // resize every frame — the content visibly grows. The fix uses fadeIn()/fadeOut() so the content
    // is measured at full size on the very first composed frame and only its alpha animates.
    //
    // This test freezes the clock (no auto-advance, so the entering transition cannot settle to full)
    // and advances exactly ONE frame after open. With the fix the tagged content already occupies its
    // full 260x200 bounds; with the buggy expandIn it would be clipped to ~0 on this first frame, so
    // the assertions fail. We assert on getBoundsInRoot (CLIPPED bounds): expandIn lays the full-size
    // content out inside a tiny growing, clipping container, so the clipped width/height is near 0
    // mid-grow, whereas a pure fade leaves the content at full bounds throughout.
    @Test
    fun dialog_entersAtFullSizeWithoutSizeAnimation() {
        val h = setHost(placement = OverlayPlacement.Dialog)

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            h.controller.open {
                Box(Modifier.size(width = 260.dp, height = 200.dp).testTag("dialog-sized"))
            }
        }
        // One frame: enough to compose the Dialog window and place its content. With a size
        // animation the content would still be near 0x0 here; with fade-only it is already full.
        rule.mainClock.advanceTimeByFrame()

        // getBoundsInRoot returns the CLIPPED bounds. Mid-expandIn the full-size content is laid out
        // inside a tiny growing clipping container, so the clipped width/height is near 0; a pure fade
        // leaves it at full bounds. assertWidthIsEqualTo/HeightIsEqualTo use the framework's own dp
        // tolerance, so the buggy near-0 clip fails while the fade's full 260x200 passes.
        val bounds = rule.onNodeWithTag("dialog-sized").getBoundsInRoot()
        assertThat(bounds.right.value - bounds.left.value).isWithin(2f).of(260f)
        assertThat(bounds.bottom.value - bounds.top.value).isWithin(2f).of(200f)
        rule.onNodeWithTag("dialog-sized").assertWidthIsEqualTo(260.dp)
        rule.onNodeWithTag("dialog-sized").assertHeightIsEqualTo(200.dp)
    }

    // (e3) Regression — a real system back drives the Dialog's exit as a pure fade that PRESERVES the
    // content size, never the buggy size-shrinking exit.
    //
    // Back on a focused Dialog window routes dismissOnBackPress -> onDismissRequest -> state.close(id)
    // -> Exiting -> the AnimatedVisibility exit runs. The buggy default exit (shrinkOut() + fadeOut())
    // shrinks the content's laid-out size from full to 0 — inside a WRAP_CONTENT window this physically
    // resizes the window every frame (the visible shrink-and-linger on back). The fix's fadeOut()-only
    // exit keeps the content at full size and only animates alpha.
    //
    // We freeze the clock so the exit cannot be force-settled, fire a real back, then advance a few
    // frames INTO the exit and assert the content's clipped bounds are STILL full 260x200. With the
    // buggy shrinkOut the clipped size has already dropped well below full by these frames, so the
    // assertion fails; the fade keeps it at full size, so it passes. This pins the exit's visual nature
    // deterministically instead of relying on frame-count timing.
    @Test
    fun dialog_backExitsByFadeKeepingFullSize() {
        val h = setHost(placement = OverlayPlacement.Dialog)
        val id = rule.runOnIdle {
            h.controller.open {
                Box(Modifier.size(width = 260.dp, height = 200.dp).testTag("dialog-sized"))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("dialog-sized").assertIsDisplayed()
        assertThat(h.state.find(id)?.phase).isEqualTo(OverlayPhase.Visible)

        // Freeze the clock: from here only our explicit advanceTimeByFrame() drives the exit, so a
        // size animation cannot be silently skipped past by auto-advance.
        rule.mainClock.autoAdvance = false
        // Real system back to the focused Dialog window. UI Automator routes the key at the system
        // level to the dialog window (the bare host activity does not reliably hold focus on the
        // emulator).
        uiDevice.waitForIdle()
        uiDevice.pressBack()

        // Advance a few frames into the exit. A shrinkOut spring has visibly shrunk the laid-out size
        // by now; a fade has not touched it. Loop until the entry actually enters Exiting so the back
        // dispatch has landed, then sample the bounds a couple frames in.
        var enteredExiting = false
        repeat(20) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            if (h.state.find(id)?.phase == OverlayPhase.Exiting) {
                enteredExiting = true
                return@repeat
            }
        }
        assertThat(enteredExiting).isTrue()
        // Two more frames into the exit transition — far enough that a size spring would be clearly
        // mid-shrink, while a fade leaves the layout untouched.
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()

        val bounds = rule.onNodeWithTag("dialog-sized").getBoundsInRoot()
        assertThat(bounds.right.value - bounds.left.value).isWithin(2f).of(260f)
        assertThat(bounds.bottom.value - bounds.top.value).isWithin(2f).of(200f)

        // And the fade still resolves to a clean removal once allowed to settle.
        rule.mainClock.autoAdvance = true
        rule.waitUntil(timeoutMillis = 5_000) { h.state.find(id) == null }
        assertThat(h.state.find(id)).isNull()
        rule.onNodeWithTag("dialog-sized").assertDoesNotExist()
    }

    // (f) Revival: opening the same id while it is exiting reverses the exit; the entry is kept.
    @Test
    fun revival_reopeningExitingIdKeepsEntry() {
        val h = setHost()
        // Open and let the enter transition fully settle to Visible (auto-advance on).
        rule.runOnIdle { h.controller.open(id = "banner") { content("first") } }
        rule.waitForIdle()
        assertThat(h.state.find("banner")?.phase).isEqualTo(OverlayPhase.Visible)

        // Take manual control of the clock so the exit transition does not run to completion — we
        // need to catch the overlay while it is still Exiting (one frame is enough to flip phase).
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { h.controller.close("banner") }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertThat(h.state.find("banner")?.phase).isEqualTo(OverlayPhase.Exiting)

        // Re-open the same id mid-exit: revive() flips targetState back to true, which breaks the
        // exit-completion predicate so the queued removal self-cancels.
        rule.runOnUiThread { h.controller.open(id = "banner") { content("revived") } }
        // Let everything settle (revive drives it back to Visible).
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        // The entry survived the revival — not removed, and back to Visible. revive() keeps the
        // original entry/content, so the body is still "first" (the second open's content is ignored).
        assertThat(h.state.find("banner")?.phase).isEqualTo(OverlayPhase.Visible)
        assertThat(h.state.entries).hasSize(1)
        rule.onNodeWithText("first").assertIsDisplayed()
    }

    // (g) bringToFront re-stacks an entry to the top z-order while preserving the SAME entry
    // identity, so the store reports the new order and the content stays in the tree.
    @Test
    fun bringToFront_reordersZIndexKeepingEntries() {
        val h = setHost()
        rule.runOnIdle { h.controller.open(id = "a") { content("a") } }
        rule.waitForIdle()
        rule.runOnIdle { h.controller.open(id = "b") { content("b") } }
        rule.waitForIdle()
        rule.runOnIdle { h.controller.open(id = "c") { content("c") } }
        rule.waitForIdle()
        // Insertion order is z-order: a (bottom) .. c (top).
        assertThat(h.state.entries.map { it.id }).containsExactly("a", "b", "c").inOrder()

        rule.runOnIdle { h.controller.bringToFront("a") }
        rule.waitForIdle()

        // 'a' is now the last (top-most) entry; b and c keep their relative order. All three
        // overlays are still present (none disposed) and displayed.
        assertThat(h.state.entries.map { it.id }).containsExactly("b", "c", "a").inOrder()
        assertThat(h.state.entries).hasSize(3)
        rule.onNodeWithText("a").assertIsDisplayed()
        rule.onNodeWithText("b").assertIsDisplayed()
        rule.onNodeWithText("c").assertIsDisplayed()
    }

    // (h) The movableContentOf payoff: a counter held in the overlay content's OWN `remember`
    // survives a bringToFront reorder. If the host disposed and re-created the slot at the new index
    // (plain key() reorder without a movable slot, or a remove+re-add), the counter would reset to
    // its initial value. Surviving proves the slot MOVED, carrying the content's internal state.
    @Test
    fun bringToFront_preservesContentInternalRememberState() {
        val h = setHost()
        // Lower overlay carries a counter; an upper overlay sits on top of it.
        rule.runOnIdle { h.controller.open(id = "counter") { Counter(tag = "counter") } }
        rule.waitForIdle()
        rule.runOnIdle { h.controller.open(id = "top") { content("top") } }
        rule.waitForIdle()
        assertThat(h.state.entries.map { it.id }).containsExactly("counter", "top").inOrder()

        // Drive the lower overlay's internal remember state to 3.
        rule.onNodeWithTag("counter-inc").performClick()
        rule.onNodeWithTag("counter-inc").performClick()
        rule.onNodeWithTag("counter-inc").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("counter-value").assertTextEquals("3")

        // Re-stack the counter overlay to the top. With movableContentOf the slot — and its
        // internal `remember { mutableIntStateOf(0) }` — moves with the entry.
        rule.runOnIdle { h.controller.bringToFront("counter") }
        rule.waitForIdle()
        assertThat(h.state.entries.map { it.id }).containsExactly("top", "counter").inOrder()

        // The counter is still 3, not reset to 0 — the content's own remember survived the reorder.
        rule.onNodeWithTag("counter-value").assertTextEquals("3")
        // And it still increments from the preserved value (the slot is live, not a stale snapshot).
        rule.onNodeWithTag("counter-inc").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("counter-value").assertTextEquals("4")
    }

    @Composable
    private fun OverlayScope.content(label: String) {
        Text(label, Modifier.testTag(label))
    }

    /** Overlay content with its OWN `remember`-backed state, used to prove movable-slot survival. */
    @Composable
    private fun OverlayScope.Counter(tag: String) {
        var count by remember { mutableIntStateOf(0) }
        Box {
            Text(count.toString(), Modifier.testTag("$tag-value"))
            // testTag on the clickable Button itself so performClick targets the click handler.
            Button(onClick = { count++ }, modifier = Modifier.testTag("$tag-inc")) { Text("inc") }
        }
    }
}
