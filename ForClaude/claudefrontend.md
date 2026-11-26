# Claude Frontend Plan - Dunder / Agoria

Living task tracker for the **frontend/UI** of this Android app. It is written so that **any Claude account or session** can open it, see what is done and what is not, and continue the work.

- Reviewed commit: `1904bb8` (branch `kilo/pearly-relay-7or`)
- Review date: 2026-11-26
- Scope: everything UI-related: `app/src/main/java/com/agoria/app/ui/`, `app/src/main/java/com/agoria/app/viewer/`, `app/src/main/java/com/agoria/app/browser/`, res/ layouts, theme, navigation, Compose screens.

---

## 0. How to use this file (instructions for Claude sessions)

1. Read this whole file before touching frontend code. Then read the actual code for the task you pick; line-level evidence can drift.
2. Pick the highest-priority `TODO` task. Set it to `IN-PROGRESS` (add the date) in the same commit that starts the work.
3. Never mark a task `DONE` unless its **Done when** criteria are met and verified (build, UI test, or a reproduction). Put the commit or PR link next to it.
4. Keep task IDs stable. Add new tasks at the end of their section with the next free number. Never renumber.
5. If a claim in this file is wrong, fix the claim and note it in the Changelog.
6. Never put secrets, tokens, keystores or user data in this repo, in issues, or in this file.
7. Statuses: `TODO`, `IN-PROGRESS`, `DONE`, `BLOCKED`, `NEEDS-DECISION`, `WONTFIX`.
8. Append one line to the Changelog (bottom) on every edit of this file, and one entry to the Work log (section 11) when you implement a task.
9. Effort: S = under 1 hour, M = about half a day, L = a day or more.

---

## 1. Architecture snapshot (what exists today)

| Layer | Files | Notes |
| --- | --- | --- |
| Root | `ui/AgoriaRoot.kt` | Compose entry point. Shows loading spinner while `settings == null`, `OnboardingScreen` until `setupComplete`, then `AgoriaNav`. Calls `viewModel.syncAutonomy()` on init. |
| Navigation | `ui/AgoriaNav.kt` | `NavHost` with routes: office (start), home, project, browsers, diary, approvals, connectors, settings, agent/{agentId}. All non-office screens pop back to "office" on Back. |
| ViewModel | `ui/AgoriaViewModel.kt` | `AndroidViewModel` exposing StateFlows for agents, diary, tasks, approvals, connectors, providers, builds, messages, memories, settings, revenue, runtime state. Polls pending approvals every 2s. Has `ProviderTest` data class for provider testing UI. |
| Theme | `ui/theme/AgoriaTheme.kt` | Material3 theme with `Pal` color palette (amber, etc.). |
| Components | `ui/components/` | `Common.kt`, `PixelScaffold.kt`, `PixelCard.kt`, `PixelPill.kt`, `AgentAvatar.kt`, `AgentCard.kt`, `OfficeThumbnail.kt`, `SectionHeader.kt`. |
| Home | `ui/home/HomeScreen.kt` | Agent cards, office thumbnail, diary preview, settings/approvals/diary navigation. |
| Office | `ui/office/OfficeScreen.kt` | Main office view with agent grid, play/pause autonomy, boot failure banner (RUN-6), build/project navigation. |
| Office World | `ui/office/world/` | Pixel-art office simulation: `OfficeWorld`, `OfficeLayout`, `OfficeMapData`, `OfficeSim`, `WorldAssets`, `PixelUi`, `Signals`, `OfficeSound`, `DirectoryPanel`, `DeskPanel`, `ChatterPanel`, `Chatter`. |
| Agent | `ui/agent/AgentScreen.kt` | Per-agent view: messages, diary, tasks, builds, memories. |
| Diary | `ui/diary/DiaryScreen.kt` | Diary entries list. |
| Approvals | `ui/approvals/ApprovalsScreen.kt` | Pending approvals list, navigate to connectors. |
| Connectors | `ui/connectors/ConnectorsScreen.kt` | Connector configuration list. |
| Browsers | `ui/browser/BrowsersScreen.kt` | WebView-based browser connector UI ("Office Browser"). |
| Project | `ui/project/ProjectScreen.kt` | Shared project folder view. |
| Settings | `ui/settings/SettingsScreen.kt` | Settings UI including refusal reasons (SEC-15), passphrase dialog (SEC-16), provider tests, auto-approve settings. |
| Onboarding | `ui/OnboardingScreen.kt` | First-run setup. |
| Browser Activity | `browser/AgentBrowserActivity.kt` | Activity hosting WebView for browser connector. |
| Browser Hub | `browser/BrowserHub.kt` | Manages WebView instances per agent, `databaseEnabled = true`. |
| Build Viewer | `viewer/BuildViewerActivity.kt` | Activity for viewing built APKs/zips. |

### What is already solid (keep it)
- Navigation graph with all screens reachable from the office.
- `AgoriaViewModel` combines multiple Flows into per-screen state classes to reduce recomposition.
- Pending approvals polling (2s interval) replaces unreliable Room Flow observation.
- Office world pixel-art rendering with `OfficeMapData` (never hand-edit per plan rule).
- Boot failure banner in `OfficeScreen` (RUN-6).
- Settings refusal reasons (SEC-15) and passphrase dialog (SEC-16).

---

## 2. Frontend task tracker

### UI-1 - Settings screen: refusal reasons + passphrase dialog
- Status: DONE (2026-11-26) | Effort: M
- Evidence: `ui/settings/SettingsScreen.kt` shows refusal reasons and passphrase dialog for encrypted export/import (SEC-15, SEC-16).
- Done when: Settings UI reflects current refusal reasons; passphrase dialog opens for encrypted export/import and validates input.
- Implemented (2026-11-26): SEC-15 refusal reasons updated in Settings UI; SEC-16 passphrase dialog added.

### UI-2 - Office screen: boot failure banner
- Status: DONE (2026-11-26) | Effort: S
- Evidence: `ui/office/OfficeScreen.kt` displays boot failure banner from `BootStatus` (RUN-6).
- Done when: Banner shows when `BootStatus.lastFailure` is set; clears on autonomy restart.
- Implemented (2026-11-26): RUN-6 boot failure banner in OfficeScreen.

### UI-3 - ViewModel: provider test state
- Status: DONE (2026-11-26) | Effort: S
- Evidence: `ui/AgoriaViewModel.kt` has `ProviderTest` data class and `providerTests` map in `SettingsScreenState`.
- Done when: Settings screen can trigger provider tests and display results.
- Implemented (2026-11-26): Provider test infrastructure in ViewModel + SettingsScreenState.

### UI-4 - Navigation: all screens pop back to office
- Status: DONE (2026-11-26) | Effort: S
- Evidence: `ui/AgoriaNav.kt` — every non-office route uses `onBack = { navController.popBackStack("office", false) }`.
- Done when: Back from any screen returns to the single office instance, no duplicate stacks.
- Implemented (2026-11-26): Navigation back-to-office pattern applied to all routes.

### UI-5 - AgoriaRoot: loading + onboarding + main flow
- Status: DONE (2026-11-26) | Effort: S
- Evidence: `ui/AgoriaRoot.kt` handles `settings == null` (spinner), `!setupComplete` (onboarding), else (nav + browser overlay). Calls `syncAutonomy()`.
- Done when: App shows spinner while loading, onboarding when not set up, office when ready.
- Implemented (2026-11-26): AgoriaRoot three-state flow with syncAutonomy call.

### UI-6 - Crash fix: AgoriaApplication async DB init revert
- Status: DONE (2026-11-26) | Effort: S
- Evidence: `AgoriaApplication.kt` reverted from `GlobalScope.launch(Dispatchers.IO)` async DB init back to synchronous. The async change caused `UninitializedPropertyAccessException` crashes on app open because `lateinit` properties (`database`, `repository`, `runtime`, `llm`, `appScope`, `updateManager`) were accessed before the coroutine completed.
- Done when: App opens without crash; `database`/`repository`/`runtime` are set before any UI reads them.
- Implemented (2026-11-26): Reverted to synchronous `AppDatabase.create()` in `onCreate()`. Bumped version to 1.0.243. Release v1.0.243 deployed.

---

## 3. TODO - Frontend tasks still open

### UI-7 - Office world: pixel map data (OfficeMapData.kt) — never hand-edit
- Status: TODO | Effort: L
- Evidence: `ui/office/world/OfficeMapData.kt` is a generated file. Plan rule: never hand-edit `OfficeMapData.kt` or `office_pixel_*.png`. Regenerate via `tools/office_gen` if map changes are needed.
- Done when: Map changes are made through the generator, not by editing the file directly.

### UI-8 - Agent screen: message/input/chat UI polish
- Status: TODO | Effort: M
- Evidence: `ui/agent/AgentScreen.kt` exists but chat input, message bubbles, and scroll behavior need review.
- Done when: Agent chat UI is functional, messages display correctly, input sends to the backend.

### UI-9 - Settings screen: auto-approve allowlist + daily cap UI
- Status: TODO | Effort: M
- Evidence: `ui/settings/SettingsScreen.kt` needs UI for `autoApproveAllowlist` (JSON map) and `autoApproveDailyCap` settings (SEC-5).
- Done when: Settings screen lets user configure auto-approve allowlist and daily cap per agent.

### UI-10 - Connectors screen: per-connector configuration UI
- Status: TODO | Effort: M
- Evidence: `ui/connectors/ConnectorsScreen.kt` lists connectors but per-connector secret/token input needs review against SEC-8 (input validation + JSON escaping).
- Done when: Connector configuration UI validates input and escapes JSON properly.

### UI-11 - Home screen: agent cards + office thumbnail
- Status: TODO | Effort: S
- Evidence: `ui/home/HomeScreen.kt` shows agent cards but may need refresh after backend changes (DATA-5 agentName fields).
- Done when: Home screen reflects agentName and other new fields correctly.

### UI-12 - Build viewer activity
- Status: TODO | Effort: S
- Evidence: `viewer/BuildViewerActivity.kt` exists for viewing built APKs/zips. Verify it works with new build statuses ("fallback", "failed") from BUG-9.
- Done when: Build viewer displays build output correctly for all statuses.

### UI-13 - Browser overlay: WebView integration
- Status: TODO | Effort: M
- Evidence: `browser/BrowserHub.kt` and `ui/browser/BrowsersScreen.kt` manage WebView instances. Verify cookie handling and navigation work with connector actions.
- Done when: Browser overlay loads pages, handles cookies, and returns results to connectors.

### UI-14 - Onboarding screen: first-run setup flow
- Status: TODO | Effort: M
- Evidence: `ui/OnboardingScreen.kt` handles first-run setup. Verify it sets `setupComplete = true` and seeds data correctly.
- Done when: Onboarding completes and transitions to office screen.

---

## 4. Cross-references to backend plan

| Frontend task | Depends on | Backend task |
| --- | --- | --- |
| UI-3 provider test UI | LLM-1 / SEC-10 | Provider test infrastructure |
| UI-9 auto-approve UI | SEC-5 | `autoApproveAllowlist`, `autoApproveDailyCap` settings |
| UI-10 connector config UI | SEC-8 | Input validation + JSON escaping |
| UI-12 build viewer | BUG-9 | Build statuses ("fallback", "failed") |
| UI-6 crash fix | DATA-4 | Synchronous DB init in AgoriaApplication |

---

## 5. Suggested order of work

1. **Fix the crash (UI-6)** — DONE: reverted async DB init, released v1.0.243.
2. **UI-8** Agent screen chat polish — core user flow.
3. **UI-9** Settings auto-approve UI — ties to SEC-5 backend.
4. **UI-10** Connectors config UI — ties to SEC-8 backend.
5. **UI-7** Office map via generator only — never hand-edit `OfficeMapData.kt`.
6. **UI-11** Home screen refresh — ties to DATA-5 agentName.
7. **UI-12** Build viewer verification — ties to BUG-9.
8. **UI-13** Browser overlay — ties to connector actions.
9. **UI-14** Onboarding flow — first-run experience.

---

## 6. Changelog

| Date | Who | Change |
| --- | --- | --- |
| 2026-11-26 | Claude | Initial creation of `ForClaude/claudefrontend.md`. Migrated UI-relevant completed work from backend plan context: UI-1 through UI-6. UI-6 crash fix and v1.0.243 release logged. Added UI-7 through UI-14 as open TODO tasks. Added cross-reference table to backend plan. |

---

## 7. Work log

### 2026-11-26 - Create claudefrontend.md + crash fix + v1.0.243 release - Claude
- Created `ForClaude/claudefrontend.md` as the frontend task tracker equivalent to `ForClaude/CLAUDE_BACKEND_PLAN.md`.
- Logged completed frontend tasks UI-1 through UI-6 (settings refusal reasons/passphrase dialog, boot failure banner, provider test state, navigation back-to-office, AgoriaRoot flow, crash fix).
- Added open TODO tasks UI-7 through UI-14.
- Added cross-reference table linking frontend tasks to backend dependencies.
- Crash fix (UI-6): reverted `AgoriaApplication.onCreate()` from async `GlobalScope.launch(Dispatchers.IO)` DB init to synchronous `AppDatabase.create()`. The async change caused `UninitializedPropertyAccessException` crashes on app open.
- Bumped version to 1.0.243 (versionCode 243). Tag `v1.0.243` created at commit `43be63e` and pushed.
- CI green: Build APK run 45810711716, Lint run 45810696645, Backup config guard run 45810696639. Pages deploy green.
- Release: https://github.com/Ejthetired14/Dunder/releases/tag/v1.0.243 with `app-release-signed.apk` (3.9 MB).
- Next for whoever continues: UI-8 agent screen chat polish, UI-9 settings auto-approve UI, UI-10 connectors config UI.
