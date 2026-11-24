# Copy-paste prompt for the next AI (Kilo / Claude Code / any agent with repo write access)

You are continuing work on Ejthetired14/Dunder (Android, Kotlin, Jetpack Compose pixel-office app).

1. Read `claudefrontend.md` at the repo root: "Rules" first, then the task board and the log.
2. Unzip `dunder-t-pixel-widgets.zip` into the repo root. Create branch `kilo/t-pixel-widgets` from current `main`.
3. First run `grep -rn "fun PixelDialog\|fun PixelSlider\|fun PixelProgressBar" app/src`. Expect only the private `PixelProgressBar` in `SettingsScreen.kt`. If a public one already exists elsewhere, stop and merge by hand.
4. Run `python3 apply_t_pixel_widgets.py` from the repo root. It edits `PixelUi.kt` (adds `PixelDialog`, `PixelSlider`, `PixelProgressBar`), `SettingsScreen.kt`, `HomeScreen.kt` and `claudefrontend.md`. If it aborts on an anchor, the tree changed: apply the same edits by hand (described in the top log line of claudefrontend.md) and do not force it.
5. Run `grep -rn "Slider(\|AlertDialog\|Dialog(" app/src`. Any Material `Slider` or `AlertDialog` left outside the Agent page must become `PixelSlider` / `PixelDialog` too.
6. Run `./gradlew assembleDebug testDebugUnitTest`. Fix compile errors with the smallest change. Known suspects are listed in the top log line (`change.consume()` vs `consumePositionChange()`, the `LocalContentColor` import, `Modifier.offset`). Do not hand-edit generated files (rule 2). Keep the pixel rules (rules 1 and 8).
7. Commit: `T-PIXEL-WIDGETS: shared PixelDialog, PixelSlider, PixelProgressBar`. Push with the owner's credentials, open a PR, wait for "Build Debug & Release APK" and "Backup config guard" to go green, then squash-merge to main.
8. Update `claudefrontend.md`: move T-PIXEL-WIDGETS from Open to "Done recently" as `VERIFIED (CI, PR #N, commit <sha>)` with the run IDs, set "Last updated by", and add a NEW top log line (newest first, format `YYYY-MM-DD HH:MM  who  task  what happened  [verified / phone / untested]`). Be honest about what was compiled and what was not tested on the phone. In the same commit delete `apply_t_pixel_widgets.py`, `apply_t_settings_nav.py` and any `dunder-*.zip` from the repo root. Push that commit to main.
9. Take the next task: T-AGENT-PIXEL (top of the Open list; spec in claudefrontend.md). Use the new `PixelDialog`, `PixelSlider`, `PixelButton`, `PixelSwitch`, `PixelTextField`; square chat bubbles; no Material `TabRow`, `Switch`, `TextButton`, `DropdownMenu`. T-ABOUT-VERSION (one line in Settings > About) may ride along in the same PR. Keep it to ONE real task per session. Use the same branch, PR, green CI, squash-merge flow.
10. When done: add one NEW task to the Open list, add a log line, rebuild `dunder-<task-id>.zip` with the changed files plus claudefrontend.md, and regenerate this prompt (`NEXT_AI_PROMPT.md`) for the following AI.

Never auto-approve for connector_action, send_email, external_action, publish, delete, spend_api. No DB migration without a plan. Never say "verified" unless CI was green. If you run out of budget mid-task, stop at a compiling state, log exactly what is done and what is not, and push that.
