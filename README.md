# overlay-kit-compose

Declarative overlay management for Jetpack Compose. Open dialogs, sheets, banners, and toasts from
plain event handlers — no manual `mutableStateOf` flags, no leaked visibility booleans. A Compose
port of [toss/overlay-kit](https://github.com/toss/overlay-kit).

Android only. Kotlin 2.x, Jetpack Compose.

## Why

The usual way to show an overlay in Compose is to hoist a boolean and toggle it:

```kotlin
var showDialog by remember { mutableStateOf(false) }
if (showDialog) MyDialog(onDismiss = { showDialog = false })
```

This spreads overlay state across every screen, makes "show A, then await its result, then show B"
awkward, and leaves exit animations half-handled. overlay-kit-compose moves that state into a single
phase-gated store and gives you an imperative controller:

```kotlin
controller.open { /* ... close() ... */ }
val result = controller.openForResult<Choice> { /* ... close(choice) ... */ }
```

## Install

```kotlin
// settings.gradle.kts — mavenCentral() in dependencyResolutionManagement

// build.gradle.kts
dependencies {
    implementation("io.github.l2hyunwoo:overlay-kit-compose:<version>")
}
```

## Basic usage

Wrap the part of your tree that may show overlays in an `OverlayHost`, then open overlays through a
controller obtained with `rememberOverlayController()`.

```kotlin
@Composable
fun App() {
    val hostState = rememberOverlayHostState()
    OverlayHost(state = hostState) {
        HomeScreen()
    }
}

@Composable
fun HomeScreen() {
    val overlays = rememberOverlayController()

    Button(onClick = {
        overlays.open {                       // receiver: OverlayScope
            Banner(text = "Saved", onClose = ::close)
        }
    }) {
        Text("Show banner")
    }
}
```

`OverlayScope` exposes `phase`, `close()` (animated), and `unmount()` (immediate). `open` returns the
overlay id; calling `open(id = ...)` again with the same id revives a closing overlay instead of
stacking a new one.

### Awaiting a result with `openForResult`

`openForResult` suspends until the overlay terminates and returns an `OverlayResult<T>` so the two
ways an overlay can end are distinct values, never a `null`:

- `OverlayResult.Resolved(value)` — the overlay called `close(value)` on its `ResultOverlayScope`.
- `OverlayResult.Dismissed` — the overlay was closed without a result (a plain `close()` or
  `closeAll()`). This is a normal return, so handle it in a `when` alongside `Resolved`.

```kotlin
val result = overlays.openForResult<Choice> {     // receiver: ResultOverlayScope<Choice>
    ConfirmDialog(
        onConfirm = { close(Choice.Confirm) },
        onCancel  = { close(Choice.Cancel) },
        onDismiss = { close() },                  // result-less close → Dismissed
    )
}

when (result) {
    is OverlayResult.Resolved -> apply(result.value)
    OverlayResult.Dismissed   -> { /* user backed out */ }
}
```

Resume is consume-once: a double `close`, or a `close` racing `closeAll`, resolves the call exactly
once.

Dismissal is a return value, not an exception — don't use a `try`/`catch` to detect it. Cancelling
the *calling* coroutine (its `Job` is cancelled, or its scope leaves the composition) throws a
`CancellationException` and tears the overlay down. That cancellation is a genuinely different event
from a dismissal and must not be caught to fake one, which would break structured concurrency.

### Placement

`rememberOverlayController(placement = ...)` selects where overlays render:

- `OverlayPlacement.InComposition` (default) — overlays are siblings stacked above your content in
  the same window; z-order follows open order. The back button closes the topmost one.
- `OverlayPlacement.Dialog` — each overlay is hosted in its own platform window via
  `androidx.compose.ui.window.Dialog`.

```kotlin
val dialogs = rememberOverlayController(placement = OverlayPlacement.Dialog)
dialogs.openForResult<Boolean> { ConfirmDialog(...) }
```

Placement is full-screen or own-window only; an overlay anchored to a specific composable (e.g. a
tooltip pinned to a button) is out of scope — reach for a dedicated library such as
[Balloon](https://github.com/skydoves/Balloon) for that.

### Closing

```kotlin
overlays.close(id)     // animated close of one overlay
overlays.unmount(id)   // immediate removal, no exit animation
overlays.closeAll()    // animated close of every overlay
overlays.unmountAll()  // immediate removal of every overlay
```

## API surface (MVP)

| Symbol | Purpose |
|--------|---------|
| `OverlayHost(state, content)` | Renders overlays above `content`. |
| `rememberOverlayHostState()` | One stable `OverlayHostState` per composition. |
| `rememberOverlayController(placement, mainDispatcher)` | Imperative handle. |
| `OverlayController` | `open` / `openForResult` / `close` / `unmount` / `closeAll` / `unmountAll`. |
| `OverlayScope` | `phase`, `close()`, `unmount()`. |
| `ResultOverlayScope<T>` | adds `close(result: T)`. |
| `OverlayResult<T>` | `Resolved(value)`, `Dismissed`. |
| `OverlayPlacement` | `InComposition`, `Dialog`. |
| `OverlayPhase` | `Entering`, `Visible`, `Exiting`, `Removed`. |

## How removal works

Each overlay is one entry in a phase-gated store. Every transition — open, close, revival, unmount,
exit-completion removal — goes through `OverlayHostState`, and `OverlayPhase` is the only authority on
an entry's lifecycle. Visibility is driven by `AnimatedVisibility(visibleState = ...)` over a
`MutableTransitionState`, and removal happens only after the exit transition is idle and both states
are false. Re-opening a closing overlay flips the transition target back, which cancels the pending
removal automatically.

## Not yet included

This is the MVP. Out of scope for now: `movableContentOf` z-order reordering, declarative /
`rememberSaveable` restoration, a global (no-host) adapter, and Compose Multiplatform targets.

## License

Apache 2.0.
