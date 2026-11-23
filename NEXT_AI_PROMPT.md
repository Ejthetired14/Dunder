# Copy-paste prompt for the next AI (Kilo / Claude Code / any agent with repo write access)

You are continuing work on Ejthetired14/Dunder (Android, Kotlin, Jetpack Compose pixel-office app).

1. Read `claudefrontend.md` at the repo root: "Rules" first, then the task board and the log.
2. Unzip `dunder-t-settings-nav.zip` into the repo root. Create branch `kilo/t-settings-nav` from current `main`.
3. Run `python3 apply_t_settings_nav.py` from the repo root. It edits `AgoriaNav.kt`, `SettingsScreen.kt` and `claudefrontend.md`. If it aborts on an anchor, the tree changed: apply the same three edits by hand (they are described in the top log line of claudefrontend.md) and do not force it.
4. `grep -rn "SettingsScreen(" app/src` and make sure every caller passes `onOpenConnectors`.
5. Run `./gradlew assembleDebug testDebugUnitTest`. Fix any compile error with the smallest change. Do not hand-edit generated files (rule 2) and keep the pixel rules (rules 1 and 8).
6. Commit: `T-SETTINGS-NAV: Configure opens Connectors`. Push with the owner's credentials, open a PR, wait for "Build Debug & Release APK" and "Backup config guard" to go green, then merge to main (squash).
7. Update `claudefrontend.md`: change T-SETTINGS-NAV to `VERIFIED (CI, PR #N, commit <sha>)` with the run IDs, set "Last updated by", and add a NEW top log line (newest first, format `YYYY-MM-DD HH:MM  who  task  what happened  [verified / phone / untested]`). Be honest about what was compiled and what was not tested on the phone. Push that md edit to main.
8. Take the next task: T-CONNECTORS-FOCUS (spec in claudefrontend.md). Keep it to ONE task per session. When done, add one new task to the Open list, add a log line, rebuild `dunder-<task-id>.zip` with the changed files plus claudefrontend.md, and regenerate this prompt for the following AI.

Never auto-approve for connector_action, send_email, external_action, publish, delete, spend_api. No DB migration without a plan. Never say "verified" unless CI was green.
