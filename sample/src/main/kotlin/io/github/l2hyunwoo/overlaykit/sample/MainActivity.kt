package io.github.l2hyunwoo.overlaykit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.l2hyunwoo.overlaykit.OverlayHost
import io.github.l2hyunwoo.overlaykit.OverlayPlacement
import io.github.l2hyunwoo.overlaykit.OverlayResult
import io.github.l2hyunwoo.overlaykit.rememberOverlayController
import io.github.l2hyunwoo.overlaykit.rememberOverlayHostState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // No android:configChanges is declared, so rotation recreates this activity. The host state is a
    // plain remember, so any live overlay is dropped on rotation. That is the documented limitation
    // (see README "Known limitation: configuration changes"), shown here as the default behavior.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val hostState = rememberOverlayHostState()
                    OverlayHost(state = hostState) {
                        SampleScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleScreen() {
    val inComposition = rememberOverlayController(placement = OverlayPlacement.InComposition)
    val dialog = rememberOverlayController(placement = OverlayPlacement.Dialog)
    val scope = rememberCoroutineScope()

    var lastAsyncResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("overlay-kit-compose", style = MaterialTheme.typography.headlineSmall)

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                inComposition.open(id = "banner") {
                    Banner(text = "In-composition overlay", onClose = ::close)
                }
            },
        ) { Text("open() in-composition banner") }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // open() with the same id revives a closing overlay instead of stacking.
                inComposition.open(id = "banner") {
                    Banner(text = "Revived banner", onClose = ::close)
                }
            },
        ) { Text("open(id=\"banner\") again (revive)") }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    val result = dialog.openForResult<String> {
                        ConfirmDialogContent(
                            onConfirm = { close("confirmed") },
                            onCancel = { close("cancelled") },
                            // A plain close() (e.g. the back button or a tap outside) dismisses
                            // without a result instead of carrying one.
                            onDismiss = ::close,
                        )
                    }
                    lastAsyncResult = when (result) {
                        is OverlayResult.Resolved -> result.value
                        OverlayResult.Dismissed -> "dismissed"
                    }
                }
            },
        ) { Text("openForResult() dialog → awaits result") }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // Stack two distinct overlays, then re-stack the lower one on top. bringToFront keeps
                // each overlay's own state and transition (movableContentOf), so the lifted banner is
                // not re-created — it just changes z-order.
                inComposition.open(id = "lower") { Banner(text = "Lower", onClose = ::close) }
                inComposition.open(id = "upper") { Banner(text = "Upper", onClose = ::close) }
                inComposition.bringToFront("lower")
            },
        ) { Text("bringToFront(\"lower\") above \"upper\"") }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { inComposition.closeAll() },
        ) { Text("closeAll() in-composition") }

        lastAsyncResult?.let {
            Text("Last async result: $it", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Banner(text: String, onClose: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text, style = MaterialTheme.typography.titleMedium)
                Button(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ConfirmDialogContent(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(24.dp).size(width = 260.dp, height = 200.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Proceed?", style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.fillMaxWidth(), onClick = onConfirm) { Text("Confirm") }
                Button(modifier = Modifier.fillMaxWidth(), onClick = onCancel) { Text("Cancel") }
                Button(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
