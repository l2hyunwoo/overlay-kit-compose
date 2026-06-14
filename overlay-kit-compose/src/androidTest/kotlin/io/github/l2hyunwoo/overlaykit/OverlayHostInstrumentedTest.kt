package io.github.l2hyunwoo.overlaykit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Composable
    private fun OverlayScope.content(label: String) {
        Text(label, Modifier.testTag(label))
    }
}
