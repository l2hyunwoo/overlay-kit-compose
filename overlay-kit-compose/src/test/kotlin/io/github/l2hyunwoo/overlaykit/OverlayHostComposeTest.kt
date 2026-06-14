package io.github.l2hyunwoo.overlaykit

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowDialog

/**
 * Compose-level M2 verification: instantiates a real [OverlayHost] under Robolectric (JVM, no
 * emulator) and exercises the open/close/result/back/dialog/revival paths that the pure-store tests
 * never touch. createAndroidComposeRule drives the same TestCoroutineScheduler-backed MainTestClock
 * that advances [androidx.compose.animation.AnimatedVisibility] transitions and the
 * `snapshotFlow`-based enter/exit completion effects to idle, so removal-after-exit is observable.
 *
 * The host activity is the empty ComponentActivity from `ui-test-manifest`; it supplies the
 * OnBackPressedDispatcher that [androidx.activity.compose.BackHandler] registers against.
 */
@RunWith(AndroidJUnit4::class)
class OverlayHostComposeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

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
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                handles = Handles(controller, state, scope)
                Box(Modifier.testTag("app-content"))
            }
        }
        rule.waitForIdle()
        return handles
    }

    // (a) open → overlay composable is present in the tree.
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
        rule.waitForIdle()

        // waitForIdle auto-advances the clock through the exit transition; the LaunchedEffect
        // snapshotFlow then fires onExitFinished, which removes the entry.
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
        rule.waitForIdle()

        assertThat(result).isEqualTo(OverlayResult.Resolved("ok"))
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
        rule.waitForIdle()

        assertThat(result).isEqualTo(OverlayResult.Dismissed)
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

        // System back: dispatched through the activity's OnBackPressedDispatcher, which the
        // top-most overlay's BackHandler intercepts.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        // Only the upper overlay is gone; the lower one stays.
        rule.onNodeWithText("upper").assertDoesNotExist()
        rule.onNodeWithText("lower").assertIsDisplayed()
        assertThat(h.state.find(upperId)).isNull()
        assertThat(h.state.find(lowerId)?.phase).isEqualTo(OverlayPhase.Visible)
    }

    // (e) Dialog placement: the Dialog window's onDismissRequest routes to close().
    @Test
    fun dialog_onDismissRequestClosesOverlay() {
        val h = setHost(placement = OverlayPlacement.Dialog)
        val id = rule.runOnIdle { h.controller.open { Text("dialog-body") } }
        rule.waitForIdle()
        rule.onNodeWithText("dialog-body").assertIsDisplayed()

        // The Compose Dialog is a ComponentDialog with its OWN OnBackPressedDispatcher (it registers
        // a back callback that calls onDismissRequest — AndroidDialog.android.kt:576). The activity
        // dispatcher does not reach it, so drive the shown dialog's own back, which is exactly what a
        // system back inside the dialog window does → onDismissRequest → state.close(id).
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        rule.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

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
