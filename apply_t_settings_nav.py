#!/usr/bin/env python3
"""Applies T-SETTINGS-NAV to the Dunder repo. Run from the repo root:  python3 apply_t_settings_nav.py
Every edit asserts its anchor exists exactly once, so a stale tree fails loudly instead of corrupting files."""
import sys
from pathlib import Path

ROOT = Path.cwd()
NAV = ROOT / "app/src/main/java/com/agoria/app/ui/AgoriaNav.kt"
SET = ROOT / "app/src/main/java/com/agoria/app/ui/settings/SettingsScreen.kt"
MD = ROOT / "claudefrontend.md"

def sub(text, old, new, name):
    n = text.count(old)
    if n != 1:
        sys.exit(f"ABORT: anchor for '{name}' found {n} times (need 1). Tree differs from the one this was written against.")
    return text.replace(old, new)

def edit(path, fn):
    if not path.exists():
        sys.exit(f"ABORT: {path} missing. Run from the repo root.")
    path.write_text(fn(path.read_text(encoding="utf-8")), encoding="utf-8")

# 1. AgoriaNav: pass the callback
def nav(t):
    return sub(t,
        '''                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack("office", false) },''',
        '''                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenConnectors = { navController.navigate("connectors") },
                        onBack = { navController.popBackStack("office", false) },''',
        "AgoriaNav settings composable")

# 2. SettingsScreen: parameter, Configure button, empty-state button + stale copy
def settings(t):
    t = sub(t,
        '''    viewModel: AgoriaViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current''',
        '''    viewModel: AgoriaViewModel,
    onOpenConnectors: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current''', "SettingsScreen signature")
    t = sub(t,
        '''                            PixelButton("Configure", {
                                // Navigate to connectors screen would need navigation (see T-SETTINGS-NAV)
                            }, small = true, fill = Pal.tan)''',
        '''                            PixelButton("Configure", onOpenConnectors, small = true, fill = Pal.tan)''',
        "Configure button")
    t = sub(t,
        '''                    Text(
                        text = "No payment connector configured. Go to the Connectors tab to add PayPal or Polar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Dim,
                    )''',
        '''                    Text(
                        text = "No payment connector configured. Open Connectors to add PayPal or Polar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Dim,
                    )
                    Spacer(Modifier.height(6.dp))
                    PixelButton("Open Connectors", onOpenConnectors, small = true, fill = Pal.tan)''',
        "empty-state copy")
    return t

# 3. claudefrontend.md: status, new task, checklist, log
def md(t):
    lines = t.split("\n")
    out, done = [], set()
    for ln in lines:
        if ln.startswith("Last updated by:"):
            out.append("Last updated by: Claude (Anthropic chat), Monday 23 Nov 2026 (T-SETTINGS-NAV CODE WRITTEN, NOT COMPILED: needs a branch, PR and green build; T-CONNECTORS-FOCUS added).")
            done.add("head")
        elif ln.startswith("| T-SETTINGS-NAV |"):
            out.append("| T-CONNECTORS-FOCUS | Make Settings > Payment Connectors > Configure land on the tapped connector | After T-SETTINGS-NAV is verified. Route `connectors?focus={id}` (optional nav argument, `NavType.StringType`, nullable). `ConnectorsScreen` reads it, scrolls its list to that connector (`LazyListState.animateScrollToItem`) and draws the card with an amber 2 dp border for ~2 s. Settings then calls `onOpenConnectors(connector.id)`. No DB change. |")
            done.add("row")
        elif ln.startswith("| T-SETTINGS-PIXEL | Restyle the BODY"):
            out.append(ln)
            out.append("| T-SETTINGS-NAV | \"Configure\" on a payment connector in Settings opens the Connectors page (new `onOpenConnectors` parameter on `SettingsScreen`, wired in `AgoriaNav`; empty state got an \"Open Connectors\" button and the stale \"Connectors tab\" copy was fixed) | CODE WRITTEN, NOT COMPILED. Needs branch + PR + green CI, then phone check. |")
            done.add("done")
        elif ln.startswith("- [ ] T-CARDCOLOR: Home"):
            out.append(ln)
            out.append("- [ ] T-SETTINGS-NAV: Settings > Revenue > Payment Connectors: with no payment connector, \"Open Connectors\" opens the Connectors page; with one, \"Configure\" does the same; BACK TO OFFICE returns to the office.")
            done.add("check")
        elif ln.startswith("Format: `YYYY-MM-DD HH:MM"):
            out.append(ln)
            out.append("")
            out.append("- 2026-11-23 (time not recorded)  Claude (Anthropic chat)  T-SETTINGS-NAV  CODE WRITTEN, NOT COMPILED, NOT PUSHED (no Gradle in the chat sandbox, no network). Three edits, applied by `apply_t_settings_nav.py` with exact-anchor asserts: `AgoriaNav.kt` passes `onOpenConnectors = { navController.navigate(\"connectors\") }` to `SettingsScreen`; `SettingsScreen.kt` gets a new `onOpenConnectors: () -> Unit` parameter (before `onBack`), the payment \"Configure\" button calls it (the no-op comment is gone), the empty state shows an \"Open Connectors\" `PixelButton` and its copy no longer mentions a \"Connectors tab\" (there are no tabs). Back from Connectors still goes to the office (`popBackStack(\"office\", false)`), same as every page. Compile suspects: named-arg order at the `SettingsScreen(...)` call site (callers other than AgoriaNav? grep `SettingsScreen(` first), `PixelButton(text, onClick, small=, fill=)` positional use with a function reference `onOpenConnectors` (it is `() -> Unit`, matches). Added task T-CONNECTORS-FOCUS. [untested: not compiled, not on a phone]")
            done.add("log")
        else:
            out.append(ln)
    missing = {"head", "row", "done", "check", "log"} - done
    if missing:
        sys.exit(f"ABORT: claudefrontend.md anchors not found: {sorted(missing)}")
    return "\n".join(out)

edit(NAV, nav); edit(SET, settings); edit(MD, md)
print("OK: T-SETTINGS-NAV applied to AgoriaNav.kt, SettingsScreen.kt, claudefrontend.md. Next: branch, build, PR.")
