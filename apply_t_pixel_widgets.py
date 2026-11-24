#!/usr/bin/env python3
"""T-PIXEL-WIDGETS patch. Run from the repo root:  python3 apply_t_pixel_widgets.py

Edits (every edit asserts its anchor, nothing is forced; the script is idempotent):
  1. app/.../ui/office/world/PixelUi.kt   + PixelDialog, PixelSlider, PixelProgressBar (public)
  2. app/.../ui/settings/SettingsScreen.kt  4 Material Slider -> PixelSlider, ResetDialog -> PixelDialog,
                                            private PixelProgressBar removed (shared one is used)
  3. app/.../ui/home/HomeScreen.kt          AddAgentDialog -> PixelDialog (labels Pal.navy, not amber on cream)
  4. claudefrontend.md                      board rows, new task T-ABOUT-VERSION, checklist item, log line

CODE WRITTEN WITHOUT A COMPILER. Needs a branch, a PR and a green build before merging.
"""
import re
import sys
from pathlib import Path

ROOT = Path.cwd()
PIXELUI = ROOT / "app/src/main/java/com/agoria/app/ui/office/world/PixelUi.kt"
SETTINGS = ROOT / "app/src/main/java/com/agoria/app/ui/settings/SettingsScreen.kt"
HOME = ROOT / "app/src/main/java/com/agoria/app/ui/home/HomeScreen.kt"
MD = ROOT / "claudefrontend.md"


def die(msg):
    print("ABORT: " + msg)
    print("The tree changed. Apply the edit by hand (see the top log line of claudefrontend.md). Nothing was forced.")
    sys.exit(1)


def read(p):
    if not p.exists():
        die(f"missing file {p}")
    return p.read_text(encoding="utf-8")


# --------------------------------------------------------------------------------------
# 1. PixelUi.kt
# --------------------------------------------------------------------------------------
NEW_IMPORTS = """import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
"""

NEW_WIDGETS = '''

/**
 * Shared square dialog (task T-PIXEL-WIDGETS): cream [PixelPanel] with a hard shadow and 2 dp ink border,
 * ink content colour (so default Text is readable on cream), 16 dp padding, 10 dp between children.
 * `title` is drawn as an uppercase [PixelLabel]. Replaces the private ResetDialog (Settings) and the
 * hand-built hire dialog (Home). Put fields and buttons in `content`.
 */
@Composable
fun PixelDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(modifier = modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalContentColor provides Pal.ink) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (title != null) {
                        Text(title.uppercase(), style = PixelLabel)
                    }
                    content()
                }
            }
        }
    }
}

/** Flat square progress bar: navy track, amber fill, 2 dp ink border. `fraction` is 0f..1f. */
@Composable
fun PixelProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    fill: Color = Pal.amber,
) {
    Box(
        modifier = modifier
            .height(16.dp)
            .background(Pal.navy)
            .border(2.dp, Pal.ink),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(fill),
        )
    }
}

/**
 * Square slider (task T-PIXEL-WIDGETS): navy track with 2 dp ink border, amber fill, 16 x 28 dp cream thumb.
 * Same contract as the Material Slider for the parameters we use: `valueRange`, and `steps` = number of
 * discrete values strictly between the ends (so there are `steps + 1` intervals). Tap or drag anywhere.
 * 32 dp tall, fills the width it is given.
 */
@Composable
fun PixelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    fill: Color = Pal.amber,
) {
    val start = valueRange.start
    val span = valueRange.endInclusive - start
    val fraction = if (span <= 0f) 0f else ((value - start) / span).coerceIn(0f, 1f)
    val thumbWidth = 16.dp
    val thumbPx = with(LocalDensity.current) { thumbWidth.roundToPx() }
    var widthPx by remember { mutableStateOf(0) }
    val latestOnChange by rememberUpdatedState(onValueChange)

    fun valueAt(x: Float): Float {
        val usable = (widthPx - thumbPx).coerceAtLeast(1)
        var f = ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
        if (steps > 0) {
            val n = steps + 1
            f = (f * n).roundToInt() / n.toFloat()
        }
        return start + f * span
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(start, span, steps, thumbPx) {
                detectTapGestures { offset -> latestOnChange(valueAt(offset.x)) }
            }
            .pointerInput(start, span, steps, thumbPx) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> latestOnChange(valueAt(offset.x)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        latestOnChange(valueAt(change.position.x))
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Pal.navy)
                .border(2.dp, Pal.ink),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(fill),
            )
        }
        Box(
            Modifier
                .offset { IntOffset(((widthPx - thumbPx) * fraction).roundToInt().coerceAtLeast(0), 0) }
                .size(thumbWidth, 28.dp)
                .background(Pal.cream)
                .border(2.dp, Pal.ink),
        )
    }
}
'''


def patch_pixelui():
    s = read(PIXELUI)
    if "fun PixelSlider(" in s:
        print("PixelUi.kt: already patched, skipped")
        return
    anchor = "import androidx.compose.ui.unit.sp\n"
    if s.count(anchor) != 1:
        die("PixelUi.kt: import anchor 'import androidx.compose.ui.unit.sp' not found exactly once")
    # only add imports that are not already there
    add = "".join(line + "\n" for line in NEW_IMPORTS.splitlines() if (line + "\n") not in s)
    s = s.replace(anchor, anchor + add)
    s = s.rstrip("\n") + "\n" + NEW_WIDGETS
    PIXELUI.write_text(s, encoding="utf-8")
    print("PixelUi.kt: PixelDialog, PixelProgressBar, PixelSlider added")


# --------------------------------------------------------------------------------------
# 2. SettingsScreen.kt
# --------------------------------------------------------------------------------------
NEW_RESET_DIALOG = '''/** Reset confirmation: the shared [PixelDialog] (square, ink border, hard shadow). */
@Composable
private fun ResetDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PixelDialog(onDismiss = onDismiss, title = "Reset Dunder?") {
        Text(
            text = "This deletes all agents, diaries, builds, approvals, and connector configuration on this device. It cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton(
                text = "Reset everything",
                onClick = onConfirm,
                small = true,
                fill = Pal.red,
            )
            PixelButton(
                text = "Cancel",
                onClick = onDismiss,
                small = true,
                fill = Pal.tan,
            )
        }
    }
}

'''


def patch_settings():
    s = read(SETTINGS)
    if "PixelSlider(" in s:
        print("SettingsScreen.kt: already patched, skipped")
        return

    # (a) sliders: drop the Material colors argument, rename the call
    s, n_colors = re.subn(
        r"^[ \t]*colors = androidx\.compose\.material3\.SliderDefaults\.colors\([^\n]*\),\n",
        "",
        s,
        flags=re.M,
    )
    if n_colors != 4:
        die(f"SettingsScreen.kt: expected 4 SliderDefaults.colors lines, found {n_colors}")
    s, n_sliders = re.subn(r"(?<![A-Za-z])Slider\(", "PixelSlider(", s)
    if n_sliders != 4:
        die(f"SettingsScreen.kt: expected 4 Slider( calls, found {n_sliders}")

    # (b) ResetDialog + private PixelProgressBar -> new ResetDialog on PixelDialog (shared bar is imported)
    pat = re.compile(
        r"/\*\* Square, bordered confirmation dialog.*?(?=@Composable\nprivate fun ProviderRow\()",
        re.S,
    )
    if len(pat.findall(s)) != 1:
        die("SettingsScreen.kt: ResetDialog / PixelProgressBar block not found exactly once")
    if "private fun PixelProgressBar(" not in pat.findall(s)[0]:
        die("SettingsScreen.kt: private PixelProgressBar is not inside the replaced block")
    s = pat.sub(lambda m: NEW_RESET_DIALOG, s, count=1)

    # (c) imports
    for old in ("import androidx.compose.material3.Slider\n", "import androidx.compose.ui.window.Dialog\n"):
        if s.count(old) != 1:
            die(f"SettingsScreen.kt: import not found exactly once: {old.strip()}")
        s = s.replace(old, "")
    anchor = "import com.agoria.app.ui.office.world.PixelLabel\n"
    if s.count(anchor) != 1:
        die("SettingsScreen.kt: PixelLabel import anchor not found exactly once")
    s = s.replace(
        anchor,
        "import com.agoria.app.ui.office.world.PixelDialog\n"
        + anchor
        + "import com.agoria.app.ui.office.world.PixelProgressBar\n"
        + "import com.agoria.app.ui.office.world.PixelSlider\n",
    )
    SETTINGS.write_text(s, encoding="utf-8")
    print("SettingsScreen.kt: 4 sliders, ResetDialog and progress bar moved to shared widgets")


# --------------------------------------------------------------------------------------
# 3. HomeScreen.kt
# --------------------------------------------------------------------------------------
NEW_ADD_AGENT = '''@Composable
private fun AddAgentDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, role: String, persona: String, goal: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Generalist") }
    var persona by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }

    // Field labels are navy: the dialog is cream and the PixelTextField default label colour is amber (rule 8).
    PixelDialog(onDismiss = onDismiss, title = "Hire an agent") {
        PixelTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name",
            labelColor = Pal.navy,
        )
        PixelTextField(
            value = role,
            onValueChange = { role = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Role",
            labelColor = Pal.navy,
        )
        PixelTextField(
            value = persona,
            onValueChange = { persona = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Persona (optional)",
            singleLine = false,
            labelColor = Pal.navy,
        )
        PixelTextField(
            value = goal,
            onValueChange = { goal = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Goal (optional)",
            singleLine = false,
            labelColor = Pal.navy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("Create", { if (name.isNotBlank()) onCreate(name, role, persona, goal) })
            PixelButton("Cancel", onDismiss, fill = Pal.cream)
        }
    }
}
'''


def patch_home():
    s = read(HOME)
    if "PixelDialog(" in s:
        print("HomeScreen.kt: already patched, skipped")
        return
    pat = re.compile(r"@Composable\nprivate fun AddAgentDialog\(.*\Z", re.S)
    found = pat.findall(s)
    if len(found) != 1:
        die("HomeScreen.kt: AddAgentDialog not found exactly once")
    if "Dialog(onDismissRequest = onDismiss)" not in found[0] or "HIRE AN AGENT" not in found[0]:
        die("HomeScreen.kt: AddAgentDialog body is not what this patch expects")
    s = pat.sub(lambda m: NEW_ADD_AGENT, s, count=1)

    old = "import androidx.compose.ui.window.Dialog\n"
    if s.count(old) != 1:
        die("HomeScreen.kt: Dialog import not found exactly once")
    s = s.replace(old, "")
    anchor = "import com.agoria.app.ui.office.world.PixelButton\n"
    if s.count(anchor) != 1:
        die("HomeScreen.kt: PixelButton import anchor not found exactly once")
    s = s.replace(anchor, anchor + "import com.agoria.app.ui.office.world.PixelDialog\n")
    HOME.write_text(s, encoding="utf-8")
    print("HomeScreen.kt: AddAgentDialog now uses PixelDialog")


# --------------------------------------------------------------------------------------
# 4. claudefrontend.md
# --------------------------------------------------------------------------------------
LAST_UPDATED = (
    "Last updated by: Claude (Anthropic chat), Tuesday 24 Nov 2026 "
    "(T-PIXEL-WIDGETS CODE WRITTEN, NOT COMPILED: needs a branch, PR and green build; T-ABOUT-VERSION added)."
)

ROW_WIDGETS = (
    "| T-PIXEL-WIDGETS | Shared pixel widgets in `PixelUi.kt`: `PixelDialog`, `PixelSlider`, `PixelProgressBar`; "
    "used by Settings (Reset dialog, 4 sliders, update progress bar) and the Home hire dialog | "
    "CODE WRITTEN, NOT COMPILED (24 Nov). Apply with `apply_t_pixel_widgets.py`, branch + PR + green CI, then the "
    "phone check in section 7. After it merges, `grep -rn \"AlertDialog\\|Slider(\" app/src` should show only "
    "`PixelSlider(`. |"
)

ROW_ABOUT = (
    "| T-ABOUT-VERSION | Settings > About hard-codes \"Dunder 1.0.0\" | "
    "`SettingsScreen` already computes `currentVersionName` / `currentVersionCode` (`rememberCurrentVersion`) for "
    "App Updates; show `Dunder $currentVersionName ($currentVersionCode)` in the About card. One line, no DB change. "
    "Found 24 Nov while reading `SettingsScreen.kt`. |"
)

ROW_SETTINGS_NAV = (
    "| T-SETTINGS-NAV | \"Configure\" on a payment connector in Settings opens the Connectors page "
    "(`onOpenConnectors` on `SettingsScreen`, wired in `AgoriaNav`; the empty state has an \"Open Connectors\" button) | "
    "VERIFIED (CI) as part of PR #25: T-CONNECTORS-FOCUS widened `onOpenConnectors` to `(String?) -> Unit` and the "
    "code is in `SettingsScreen.kt` on main (read 24 Nov, commit 9eefbad). Phone check still open (section 7). |"
)

CHECK_ITEM = (
    "- [ ] T-PIXEL-WIDGETS: Settings > Autonomy: drag both sliders (square navy track, amber fill, square cream thumb); "
    "the value text above each updates and Save timing still saves. Revenue: dragging Agent Fund sets Your Share to "
    "100 minus it, and the other way round. Data & privacy > Reset app opens a square dialog (title RESET DUNDER?, same "
    "text, Cancel closes it; do not confirm). App Updates download still shows the amber bar. Home > Hire agent: square "
    "dialog, field labels dark navy (not amber), Create adds the agent, Cancel closes."
)

LOG_LINE = (
    "- 2026-11-24 (time not recorded)  Claude (Anthropic chat)  T-PIXEL-WIDGETS  CODE WRITTEN, NOT COMPILED, NOT PUSHED "
    "(no Gradle and no network in the chat sandbox; the chat GitHub token is read-only). Delivered as "
    "`apply_t_pixel_widgets.py` (exact-anchor asserts, idempotent) in `dunder-t-pixel-widgets.zip`. The real files were "
    "read through the GitHub connector at commit 9eefbad; the script itself was only dry-run on abbreviated fixtures "
    "of those anchors. Edits: (1) `PixelUi.kt` gains public `PixelDialog(onDismiss, modifier, title, content: ColumnScope)` "
    "(Dialog + PixelPanel, ink `LocalContentColor`, 16 dp padding, 10 dp spacing, uppercase PixelLabel title), "
    "`PixelSlider(value, onValueChange, modifier, valueRange, steps, fill)` (navy track with 2 dp ink border, amber fill, "
    "16x28 dp cream thumb, tap and horizontal drag through `pointerInput`, snaps like Material's `steps`) and "
    "`PixelProgressBar(fraction, modifier, fill)` (moved out of SettingsScreen, now public). (2) `SettingsScreen.kt`: "
    "4 Material `Slider`s -> `PixelSlider`, private `ResetDialog` -> `PixelDialog`, private `PixelProgressBar` deleted "
    "(shared one imported), `Slider` and `Dialog` imports removed. (3) `HomeScreen.kt`: `AddAgentDialog` -> `PixelDialog`, "
    "and its field labels are now `Pal.navy` (they were amber on cream, which breaks rule 8). Compile suspects: "
    "`change.consume()` (older Compose needs `consumePositionChange()`), the `androidx.compose.material3.LocalContentColor` "
    "import in PixelUi.kt, `Modifier.offset { IntOffset(...) }` (`foundation.layout.offset`), the local `fun valueAt` "
    "capturing the `by` delegated `widthPx`, and the `Row` inside the `ColumnScope` content lambda in ResetDialog. "
    "Board tidy: T-SETTINGS-NAV marked shipped inside PR #25; the duplicate Open row for T-CONNECTORS-FOCUS removed "
    "(it is in Done recently). Added task T-ABOUT-VERSION. [untested: not compiled, not on a phone]"
)


def patch_md():
    s = read(MD)
    if "T-ABOUT-VERSION" in s:
        print("claudefrontend.md: already patched, skipped")
        return
    lines = s.split("\n")

    def find(prefix, what):
        hits = [i for i, l in enumerate(lines) if l.startswith(prefix)]
        if len(hits) != 1:
            die(f"claudefrontend.md: {what} (prefix {prefix!r}) found {len(hits)} times, expected 1")
        return hits[0]

    # Last updated line
    lines[find("Last updated by:", "Last updated line")] = LAST_UPDATED

    # Duplicate Open row for T-CONNECTORS-FOCUS (the verified one lives in Done recently)
    i = find("| T-CONNECTORS-FOCUS | Make Settings", "open T-CONNECTORS-FOCUS row")
    del lines[i]

    # T-PIXEL-WIDGETS row + new T-ABOUT-VERSION row right after it
    i = find("| T-PIXEL-WIDGETS |", "T-PIXEL-WIDGETS row")
    lines[i] = ROW_WIDGETS
    lines.insert(i + 1, ROW_ABOUT)

    # T-SETTINGS-NAV row in Done recently
    lines[find("| T-SETTINGS-NAV |", "T-SETTINGS-NAV row")] = ROW_SETTINGS_NAV

    # Phone checklist: new item above the T-CARDCOLOR item
    i = find("- [ ] T-CARDCOLOR:", "checklist T-CARDCOLOR item")
    lines.insert(i, CHECK_ITEM)
    lines.insert(i + 1, "")

    # Log: newest first, so insert before the first dated log line inside section 6
    h = find("## 6. Log", "log heading")
    first = next((j for j in range(h, len(lines)) if lines[j].startswith("- 20")), None)
    if first is None:
        die("claudefrontend.md: no dated log line found after '## 6. Log'")
    lines.insert(first, LOG_LINE)

    MD.write_text("\n".join(lines), encoding="utf-8")
    print("claudefrontend.md: board, checklist and log updated")


if __name__ == "__main__":
    # Read every target first so a missing file aborts before anything is written.
    for p in (PIXELUI, SETTINGS, HOME, MD):
        read(p)
    patch_pixelui()
    patch_settings()
    patch_home()
    patch_md()
    print("\nDone. Next: grep -rn \"Slider(\\|AlertDialog\" app/src ; ./gradlew assembleDebug testDebugUnitTest")
