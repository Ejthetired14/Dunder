#!/usr/bin/env python3
"""Updates claudefrontend.md (repo root) for the 2026-11-23 session. Run from the repo root.
Aborts without writing if any anchor is missing. Idempotent."""
import pathlib, sys

p = pathlib.Path("claudefrontend.md")
s = p.read_text(encoding="utf-8")
if "T-SETTINGS-PIXEL" in s:
    sys.exit("claudefrontend.md already updated; nothing to do.")

edits = [
    # header
    ("Last updated by: Claude (Anthropic chat), Monday 23 Nov 2026 14:45 UTC (T-HOME VERIFIED).",
     "Last updated by: Claude (Anthropic chat), Monday 23 Nov 2026 10:12 UTC (T-BACK2 Settings + Onboarding pixel: CODE WRITTEN, untested)."),
    # T-BACK2 row
    ("PARTIAL: Home, Project, Connectors, Agent, Approvals done (PR #9, #10, #11). Remaining: Settings, Onboarding.",
     "CODE WRITTEN, NOT COMPILED (zip `dunder-phase7-settings-onboarding.zip`, PR pending): Home, Project, Connectors, Agent, Approvals done earlier (PR #9, #10, #11); Settings now on PixelScaffold and the floating \"< Office\" button + `ScaffoldedRoutes` are deleted from `AgoriaNav.kt`. Onboarding is NOT a scaffolded page (first run, no office behind it): it was restyled in pixel instead (see T-SCREENS)."),
    # new open task, inserted above T-MSG
    ("| T-MSG | Real `agent_messages` table",
     "| T-SETTINGS-PIXEL | Restyle the BODY of `SettingsScreen.kt` (the top bar is already pixel) | NEW. Replace `SectionCard` (Material `Card`, `MaterialTheme.shapes.large`) with `PixelCard`/`PixelPanel`; `Button`/`TextButton` -> `PixelButton(small=true)`; `Switch` -> `PixelSwitch`; `OutlinedTextField` in `ProviderRow` -> `PixelTextField` (it has no `supportingText`, render the hint as a `PixelBody` line under it); `SummaryCard` and `ProviderRow` `Surface`/`Card` -> flat bordered; progress indicators keep Material but tint `Pal.amber`. The file is ~50 KB: do it in 2 PRs (top half: Autonomy, Revenue; bottom half: Providers, Data, Backup, Updates, About). Grep must find no `RoundedCornerShape`, `MaterialTheme.shapes.*`, `Card(`, `OutlinedTextField`, `Switch(` left. |\n| T-MSG | Real `agent_messages` table"),
    # T-SCREENS note
    ("| T-SCREENS | Restyle every page to the pixel look | Specs in section 5. One PR per 2-3 screens. |",
     "| T-SCREENS | Restyle every page to the pixel look | Specs in section 5. One PR per 2-3 screens. Onboarding DONE in code (untested). Remaining pages: Settings body (T-SETTINGS-PIXEL), then audit Home/Agent/Approvals/Connectors/Project/Diary/Browsers for leftover `RoundedCornerShape`, Material buttons, switches and text fields. |"),
    # log line, newest first
    ("- 2026-11-23 14:45  Claude  T-HOME / T-HIRE",
     "- 2026-11-23 10:12  Claude  T-BACK2 / T-SCREENS  CODE WRITTEN, NOT COMPILED, NOT PUSHED (the chat's GitHub token is read-only: `create_branch` returned 403, same as the 10:15 T-BACK entry). Delivered as `dunder-phase7-settings-onboarding.zip`: (1) `SettingsScreen.kt` -> `PixelScaffold(title = \"Settings\", onBack, snackbarHost)` via `tools/apply_t_back2_settings.py` (anchor-checked string patch, tested on a stub only, because the 50 KB file was patched rather than retyped); removed the now-unused `Scaffold`, `TopAppBar`, `IconButton` and `ArrowBack` imports, added the `PixelScaffold` import. (2) `AgoriaNav.kt`: settings `onBack` is now `popBackStack(\"office\", false)` (was plain `popBackStack()`); every route is scaffolded so the floating \"< Office\" button, `ScaffoldedRoutes`, `currentRoute` and their imports are deleted. (3) `OnboardingScreen.kt` restyled: navy ground, ink-bordered thumbnail (no `RoundedCornerShape`), `PixelPanel` feature rows, `PixelTextField`, `PixelButton`. Onboarding deliberately does not use `PixelScaffold` (no office to go back to). Compile suspects: `OfficeThumbnail` accepting a `Modifier.border`, `PixelLabel.copy(...)` in `Text`, unused-import warnings if the build treats warnings as errors. Phone checklist items for the floating button and Settings top bar are untested. [untested]\n- 2026-11-23 14:45  Claude  T-HOME / T-HIRE"),
    # phone checklist
    ("- [ ] Diary and Agent browsers pages show the ink top bar",
     "- [ ] Settings shows the ink top bar with BACK TO OFFICE and returns to the office; no \"< Office\" floating button on ANY page.\n- [ ] First launch (clear app data): onboarding is navy with square panels, typing a name works, ENTER THE OFFICE opens the office.\n- [ ] Diary and Agent browsers pages show the ink top bar"),
]
for old, _ in edits:
    if s.count(old) != 1:
        sys.exit("Anchor not found exactly once, nothing written:\n" + old[:100])
for old, new in edits:
    s = s.replace(old, new)
p.write_text(s, encoding="utf-8")
print("claudefrontend.md updated.")
