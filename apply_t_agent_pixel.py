#!/usr/bin/env python3
"""Apply T-AGENT-PIXEL + T-ABOUT-VERSION small edits. Run from the repo root. Idempotent; asserts every anchor.
AgentScreen.kt is shipped whole (copy it over). This script patches SettingsScreen.kt, PixelUi.kt, claudefrontend.md."""
import re, sys, pathlib

ROOT = pathlib.Path('.')
SETTINGS = ROOT / 'app/src/main/java/com/agoria/app/ui/settings/SettingsScreen.kt'
PIXELUI = ROOT / 'app/src/main/java/com/agoria/app/ui/office/world/PixelUi.kt'
MD = ROOT / 'claudefrontend.md'

def sub_once(text, old, new, label):
    if new in text:
        print('already applied:', label); return text
    assert text.count(old) == 1, f'anchor not unique/found: {label} ({text.count(old)})'
    print('patched:', label)
    return text.replace(old, new)

# 1. T-ABOUT-VERSION
t = SETTINGS.read_text()
t = sub_once(t,
    'Text("Dunder 1.0.0", style = MaterialTheme.typography.titleMedium)',
    'Text("Dunder $currentVersionName ($currentVersionCode)", style = MaterialTheme.typography.titleMedium)',
    'About version line')
SETTINGS.write_text(t)

# 2. PixelTextField gets optional onDone (Enter/Done sends the chat message)
t = PIXELUI.read_text()
t = sub_once(t, 'import androidx.compose.foundation.text.KeyboardOptions\n',
    'import androidx.compose.foundation.text.KeyboardActions\nimport androidx.compose.foundation.text.KeyboardOptions\n', 'import KeyboardActions')
t = sub_once(t, 'import androidx.compose.ui.text.input.KeyboardType\n',
    'import androidx.compose.ui.text.input.ImeAction\nimport androidx.compose.ui.text.input.KeyboardType\n', 'import ImeAction')
t = sub_once(t, '    hintColor: Color = Pal.tan,\n) {',
    '    hintColor: Color = Pal.tan,\n    onDone: (() -> Unit)? = null,\n) {', 'onDone parameter')
t = sub_once(t,
    '            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,\n',
    '            keyboardOptions = when {\n'
    '                password -> KeyboardOptions(keyboardType = KeyboardType.Password)\n'
    '                onDone != null -> KeyboardOptions(imeAction = ImeAction.Done)\n'
    '                else -> KeyboardOptions.Default\n'
    '            },\n'
    '            keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,\n',
    'keyboard options/actions')
PIXELUI.write_text(t)

# 3. claudefrontend.md
m = MD.read_text()
def line_sub(text, pattern, repl, label, flags=re.M):
    if repl.strip() and repl.strip() in text:
        print('already applied:', label); return text
    new, n = re.subn(pattern, lambda _: repl, text, count=1, flags=flags)
    assert n == 1, f'md anchor not found: {label}'
    print('patched md:', label)
    return new

m = line_sub(m, r'^Last updated by:.*\n',
    'Last updated by: Claude (Anthropic chat), Tuesday 24 Nov 2026 (T-AGENT-PIXEL + T-ABOUT-VERSION CODE WRITTEN, NOT COMPILED; needs a branch + PR + green CI; new open task T-AGENT-TOPBAR).\n',
    'last updated')

# drop stale/verified or moved rows from Open
assert '### Done recently' in m, 'md anchor not found: Done recently heading'
head, tail = m.split('### Done recently', 1)   # only touch the Open table, which comes first
for tid in ('T-CARDCOLOR-AUDIT', 'T-ABOUT-VERSION'):
    head, n = re.subn(r'^\| %s \|.*\n' % tid, '', head, count=1, flags=re.M)
    print('removed Open row' if n else 'no Open row (already moved)', tid)
m = head + '### Done recently' + tail

new_open = ('| T-AGENT-TOPBAR | Agent page top-bar pause/resume is still a Material `IconButton` (round ripple) inside `PixelScaffold` `actions` | '
            'Found 24 Nov while restyling `AgentScreen.kt`. Swap for `PixelIconButton` (44 dp, check it fits the ink bar; add a compact variant if not). '
            'Then check the other scaffolds\' `actions` (Approvals auto-approve settings, Home) for Material `IconButton`. |\n')
m = line_sub(m, r'^\| T-AGENT-PIXEL \|.*\n', new_open, 'Open: T-AGENT-PIXEL -> T-AGENT-TOPBAR')

done_rows = (
 '| T-AGENT-PIXEL | Agent page body restyled: square `PixelTabs` (amber selected / teal), `PixelSwitch`, `PixelButton` Edit/Delete (dropdown removed), square chat bubbles in `PixelPanel`, '
 '`PixelTextField` composer (Enter sends via new `onDone`) + `PixelIconButton` send, text "LOADING..." instead of spinner, Edit/Add-memory/Delete dialogs -> `PixelDialog`, `PixelSlider` for importance, diary/task/memory cards -> `PixelCard` | '
 'CODE WRITTEN, NOT COMPILED (24 Nov). Needs branch + PR + green CI, then phone check (section 7). Compile suspects: `Modifier.weight(1f, fill = false)` inside the agent bubble Row, `OfficeSound.Fx.CLICK` import, `PixelTextField(onDone=)` patch in PixelUi.kt, `PixelPanel` without `modifier` in the thinking bubble. |\n'
 '| T-ABOUT-VERSION | Settings > About shows `Dunder $currentVersionName ($currentVersionCode)` instead of the hard-coded "Dunder 1.0.0" | CODE WRITTEN, NOT COMPILED (24 Nov): one-line change in `SettingsScreen.kt`. |\n'
 '| T-CARDCOLOR-AUDIT | Replaced Material theme text colours on cream/tan with `Pal` across 10 files | VERIFIED (CI, PR #22, commit c187269). Build APK 45177467724 green, Backup guard 45178419208 green. |\n'
)
if '| T-AGENT-PIXEL | Agent page body restyled' in m:
    print('already applied: Done rows')
else:
    m, n = re.subn(r'^(\| T-PIXEL-WIDGETS \| Shared pixel widgets)', lambda mo: done_rows + mo.group(1), m, count=1, flags=re.M)
    assert n == 1, 'md anchor not found: Done recently T-PIXEL-WIDGETS row'
    print('patched md: Done rows')

checklist = (
 '- [ ] T-AGENT-PIXEL (install the newest CI APK first): open any agent. Header is a navy square card with status pill, goal (tap to expand), square switch, EDIT and DELETE buttons; tabs CHAT / DIARY / TASKS / MEMORY are square (amber = selected, teal = others). '
 'Chat: your messages are amber square bubbles, agent replies navy square bubbles with the copy icon, tool lines teal; the message box is square and the keyboard Done key sends; the send button works; "Load older messages" is a square button (shows LOADING... while fetching). '
 'EDIT opens a square dialog (fields readable, Save / Cancel); DELETE opens a square confirm dialog (Cancel closes it; do not confirm unless you mean it). Memory > ADD opens a square dialog with a square importance slider (1-5). Diary, Tasks, Memory cards are square. No round Material control left on the page except the top-bar pause/resume icon (T-AGENT-TOPBAR).\n'
 '- [ ] T-ABOUT-VERSION: Settings > About shows "Dunder <versionName> (<versionCode>)" matching App Updates > Current version.\n'
)
if '- [ ] T-AGENT-PIXEL (install the newest CI APK first)' in m:
    print('already applied: checklist')
else:
    m, n = re.subn(r'^(- \[ \] T-CARDCOLOR: Home)', lambda mo: checklist + '\n' + mo.group(1), m, count=1, flags=re.M)
    assert n == 1, 'md anchor not found: checklist T-CARDCOLOR'
    print('patched md: checklist')

log = (
 '- 2026-11-24 (time not recorded)  Claude (Anthropic chat)  T-AGENT-PIXEL + T-ABOUT-VERSION  CODE WRITTEN, NOT COMPILED, NOT PUSHED (no Gradle and no network in the chat sandbox). '
 'Read at commit e17ab29. Delivered as `dunder-t-agent-pixel.zip`: full `ui/agent/AgentScreen.kt` plus `apply_t_agent_pixel.py` (exact-anchor asserts, idempotent) that patches `SettingsScreen.kt`, `PixelUi.kt` and this file. '
 'T-AGENT-PIXEL: Material TabRow -> private square `PixelTabs`; header Surface -> `PixelCard`, Switch -> `PixelSwitch`, TextButton + dropdown menu -> `PixelButton` EDIT / DELETE; chat bubbles -> `PixelPanel` (user amber, agent navy, tool opaque teal, thinking navy); OutlinedTextField + IconButton + Surface composer -> `PixelTextField` + `PixelIconButton`; CircularProgressIndicator -> "LOADING..." text; TextButton "Load older" -> `PixelButton`; EditAgent / AddMemory / Delete AlertDialogs -> `PixelDialog` (EditAgentDialog lost its unused `onDelete` parameter; persona and goal are now multi-line boxes); Material Slider -> `PixelSlider` (1f..5f, steps 3); Diary/Task/Memory `Card` -> `PixelCard`. '
 '`PixelUi.kt`: `PixelTextField` gains optional `onDone: (() -> Unit)? = null` (Done key action); existing calls are unchanged. Grep of AgentScreen.kt for `RoundedCornerShape|CircleShape|Card(|OutlinedTextField|Switch(|TextButton|AlertDialog|CircularProgress|LinearProgress|Surface(|DropdownMenu|Slider(` is clean apart from the Pixel* names; only the top-bar `IconButton` is still Material (new task T-AGENT-TOPBAR). Brace and paren balance checked by script only. '
 'T-ABOUT-VERSION: About card now prints `Dunder $currentVersionName ($currentVersionCode)`. '
 'Compile suspects: `Modifier.weight(1f, fill = false)` in the agent bubble Row, `OfficeSound.Fx.CLICK` (same package as PixelUi, imported here), `PixelPanel(fill = ...)` called with no modifier, `PixelTabs` `weight` inside `forEachIndexed` in a Row scope, `PixelSwitch(checked =, onChange =)` named call. Also: the old Edit icon button and the three-dot menu are gone (Edit and Delete are now direct buttons). '
 '[untested: not compiled, not on a phone]\n'
)
if 'T-AGENT-PIXEL + T-ABOUT-VERSION  CODE WRITTEN' in m:
    print('already applied: log')
else:
    m, n = re.subn(r'^(- 2026-11-24 02:10  Kilo Code  T-PIXEL-WIDGETS)', lambda mo: log + mo.group(1), m, count=1, flags=re.M)
    assert n == 1, 'md anchor not found: first log line'
    print('patched md: log')

MD.write_text(m)
print('done')
