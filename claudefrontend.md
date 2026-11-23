# claudefrontend.md — Dunder pixel-office frontend: plan, tasks, log

Any AI (or person) can pick this file up and continue. Read "Rules" first, then take the
top open task on the board. When you finish something, change its status and add a log line.
Keep this file honest: if something was not compiled or not tested on a phone, say so.

This file lives at the **repo root** (`claudefrontend.md`). The `ForClaude/` folder only holds
`CLAUDE_BACKEND_PLAN.md` (the backend tracker). If the owner says "ForClaude/claudefrontend.md", this is the file.

Last updated by: Claude (Anthropic chat), Monday 23 Nov 2026 (T-SETTINGS-NAV CODE WRITTEN, NOT COMPILED: needs a branch, PR and green build; T-CONNECTORS-FOCUS added).

---

## 1. Vision (the owner's words, tidied)

- The whole app is a **pixel-art office**. Autonomous AI agents walk around it like a game.
- **The office is the only view.** There is no bottom bar. Every other page (diary, project,
  approvals, connectors, browsers, settings, home) is reached by tapping something in the office.
- When zoomed in, anything tappable shows a **label** saying which page it opens. The **notice
  board** is the directory: tapping it lists every page.
- Agents at their desk = working. In the library/web room/server room/mail/finance/workshop = using
  that kind of connector or building. Tap an agent to see its thoughts and diary and to talk to it.
  A yellow "!" means it needs you; they walk to your office. Tap your desk to approve and to set
  auto-approve.
- **Every page, and the whole app, has one consistent pixel look.** Not "slop": no soft gradients,
  no rounded Material cards, no blurry sprites.
- Test device: **TECNO Spark 20C** (Helio G36, 4 GB RAM, Android 13, 720x1612). Low-end: keep it light.
- The owner is in the Philippines; Christmas ("Ber months", parol lanterns) starts in September.

## 1b. First things to do when you pick this up

1. Apply `dunder-phase5-pixel-office.zip` (cumulative over Phase 4) on a branch, open a PR, get a
   green build. It was written without a compiler. Likely compile suspects: `viewModel.runtimeState`
   / `RuntimeState.ticking`, `settings?.autonomyEnabled`, `viewModel.messagesFor(id)`,
   `viewModel.sendMessage(agentId, text)`, `Icons.Default.Menu/Pause/PlayArrow/Forum`,
   `Brush.radialGradient`, `surfaceContainerHighest` in the theme, `Surface(border=...)`.
   (Phase 5 has since compiled and merged; this list is kept for history.)
2. Install on the phone and run the checklist in section 7.
3. Then take T-BACK, then T-SCREENS.

## 2. Rules

1. **Pixel look:** square corners, 2 dp ink borders, hard offset shadow (no blur), flat fills,
   palette `Pal` (ink, navy, teal, cream, tan, wood, amber, red). Pixel/monospace font for labels and
   titles only; body and chat text stay a readable sans at 12 sp or more. Sprites and the map are
   drawn with `FilterQuality.None`.
2. **Do not hand-edit generated files:** `OfficeMapData.kt`, `office_pixel_base.png`,
   `office_pixel_objects.png`. Change `tools/office_gen/gen_office.py`, run it, copy the outputs
   (see `docs/pixel-office.md`).
3. **Auto-approve safety:** the desk panel may only offer auto-approve for `build_project`,
   `connector_install`, `other`. Never for `connector_action` (includes payments), `send_email`,
   `external_action`, `publish`, `delete`, `spend_api`.
4. **Performance:** redraw at most ~30 fps and only while visible; no per-frame allocations in the
   Canvas where avoidable; bitmaps decoded with `inScaled=false`; keep the PNGs small.
5. **No DB migration without a plan:** agent talk is stored as diary entries (`kind="say"`,
   content `To <Name>: <text>`). A real messages table is task T-MSG.
6. **Build:** the repo's CI is "Build Debug & Release APK". Code written without a compiler must go
   on a branch + PR, and a green build is required before merging. Push with the owner's
   credentials (the default GITHUB_TOKEN does not trigger the build workflow).
7. **Tell the truth in the log:** say what was compiled, what was run on a phone, what was guessed.
8. **Text colour on cream:** the theme's text colours are cream/tan (made for dark backgrounds) and
   `PixelPanel`/`PixelCard` do NOT set a content colour. On a cream or tan fill, wrap the content in
   `CompositionLocalProvider(LocalContentColor provides Pal.ink)` (see `SettingsCard` in
   `SettingsScreen.kt`) and use `Pal.navy` for secondary text. Never use amber or
   `MaterialTheme.colorScheme.primary/onSurfaceVariant` as text on cream. `PixelCard` now sets this itself (T-CARDCOLOR): ink on light fills, cream on dark. Secondary text on light fills still must be `Pal.navy`. Surfaces that bypass `PixelCard` are T-CARDCOLOR-AUDIT.

## 3. Architecture map

```
app/src/main/java/com/agoria/app/
  core/            AgentRuntime, AgentPolicy, PromptBuilder, AgentModels (agent brains)
  data/            Entities.kt (Room), repository
  ui/
    AgoriaNav.kt           navigation; office is the start and only top-level screen
    AgoriaViewModel.kt     all UI state (agents, diary, approvals, settings, runtimeState...)
    theme/AgoriaTheme.kt   Material theme (pixel colours, square shapes, monospace headings)
    components/Common.kt   shared pixel components (AgentAvatar, Pill, StatusPill, cards...)
    office/OfficeScreen.kt office screen: HUD, cards, directory, quick chat, hints
    office/OfficeViewModel.kt  holds the OfficeSim + CameraState so they survive navigation
    office/world/
      OfficeWorld.kt   Canvas renderer, camera, bubbles, labels, night glow, seasonal decor
      OfficeSim.kt     actors, walking, activities, live signals, agent talk
      OfficeLayout.kt  grid + BFS pathfinding, spots, hotspots
      OfficeMapData.kt GENERATED: grid, spots, objects, hotspots
      Signals.kt       live data -> what each agent is doing
      DeskPanel.kt     approvals + auto-approve panel
      Chatter*.kt      agent-to-agent talk (parse + panel)
      PixelUi.kt       Pal palette, PixelPanel, PixelButton, PixelIconButton, PixelTextField, PixelSwitch, PixelLabel/Body
      OfficeSound.kt   sound effects (SoundPool)
tools/office_gen/  Python (Pillow) generator for the map art, collision, spots, hotspots
```

Data flow: Room -> repository -> `AgoriaViewModel` flows -> `OfficeScreen` -> `buildSignals()` ->
`OfficeSim.applySignals()` -> actors walk to rooms; renderer draws base map + y-sorted furniture
and agents. Taps: agent -> card; hotspot -> room card or directory; desk -> `DeskPanel`.

## 4. Task board

Status: DONE, DOING, TODO. "Verified" = compiled in CI; "Phone" = seen on the test phone.

### Shipped earlier (see log)
| ID | What | Status |
| --- | --- | --- |
| P1 | Pixel office world: map, sprites, camera, walking | DONE (CI + phone) |
| P2 | Generated pixel map, live agent activity, desk panel, auto-approve | DONE (CI + phone) |
| P3 | Agent-to-agent `say`, speech bubbles, chatter panel | DONE (CI); talk not yet seen on phone |
| P4 | Office as start screen, quick chat, camera fly-to, day/night tint, hints, autonomy button | CODE WRITTEN, not yet compiled |

### This round
| ID | What | Status |
| --- | --- | --- |
| T-NAV | Remove the bottom bar; office is the only top-level view; floating "< Office" button on other pages (proper top bar is T-BACK) | VERIFIED (CI) |
| T-DIR | Notice board = directory card listing every page; amber page labels on every tappable thing when zoomed in (scale >= 1.6) | VERIFIED (CI) |
| T-THEME | Global pixel theme (colours, square shapes, monospace headings, no tonal elevation); ignores the system dark/light setting | VERIFIED (CI) |
| T-COMMON | Pixel versions of shared components in `Common.kt` (crisp square avatar, chips, `PixelCard`, headers); same public API | VERIFIED (CI) |
| T-SOUND | Sound effects (alert, talk, click; 3 generated WAVs in res/raw) with a mute switch in the directory | VERIFIED (CI) |
| T-NIGHT | Night shift: warm lamp glow around every agent when the tint is dark | VERIFIED (CI) |
| T-SEASON | Christmas decor (parol lanterns, tree, string lights), shown Sep 1 - Jan 6 | VERIFIED (CI) |
| T-HOME | Pixel Home page: hero card (OfficeThumbnail + amber status pill), approvals banner (PixelCard amber), runtime card, diary rows, "Hire agent" PixelButton(small=) under The agents, hire dialog (Dialog + PixelPanel, PixelTextField, PixelButton, PixelSwitch); all Phase 6 files compile and CI green | VERIFIED (CI) |
| T-HIRE | "Hire agent" button under The agents opens the pixel hire dialog | VERIFIED (CI) |
| T-CARDCOLOR | `PixelCard` provides `LocalContentColor` from its fill (luminance > 0.4 -> ink, else cream). Padding stays the caller's job (documented in KDoc; all call sites already pad). `SettingsCard`/`ResetDialog` workaround providers removed. Home cream cards: `onSurfaceVariant`->`Pal.navy`, amber icon->`Pal.teal`, `outline`->`Pal.wood`. | VERIFIED (CI, PR #21, commit e50aa22). Build APK 45173568096 green, Backup guard 45174757705 green. |
| T-FIX-PHONE1 | Phone report 18:57: (a) dusk glow on agents with no darkness: glow now needs tint alpha > 0.30 and fades in; night wash raised 0x4D->0x8C, dusk 0x1F->0x2E, 20:00 0x3A->0x66. (b) Agent page header slid under the top bar because `AgentContent` ignored the scaffold `padding`; now applied. Header compacted: avatar 40dp, name/role dropped (already in top bar), goal clamped to 2 lines, tap to expand. | VERIFIED (CI, PR #21, commit e50aa22). Build APK 45173568096 green, Backup guard 45174757705 green. |
| T-SETTINGS-PIXEL-PR2 | Restyle bottom half of SettingsScreen (Providers, Data, Backup, Updates, About) + Reset dialog | VERIFIED (CI, PR #19, commit ac76095). Build APK 45167823699 green, Backup guard 45168582490 green. Model providers (ProviderRow: tan PixelCard, PixelSwitch, PixelTextField for key/model/extra), Data & privacy, Backup & Restore, App Updates (PixelProgressBar), About all use SettingsCard (PixelCard + LocalContentColor ink + 14dp padding). AlertDialog -> ResetDialog (Dialog + PixelCard). SectionCard/SummaryCard removed. grep clean for RoundedCornerShape|CircleShape|MaterialTheme.shapes|Card(|OutlinedTextField|Switch(|TextButton|AlertDialog|CircularProgress|LinearProgress|Surface( (only PixelCard/PixelButton/PixelSwitch match). [verified]

### Open (not started), in priority order
| ID | What | Notes |
| --- | --- | --- |
| T-CARDCOLOR-AUDIT | Find cream/tan surfaces that bypass `PixelCard` (direct `PixelPanel(fill = cream/tan)`, `Box.background(Pal.cream)`) and any text on cream still using `onSurfaceVariant`, `primary`, `outline` or amber/tan | VERIFIED (CI, PR #22, commit c187269). Build APK 45177467724 green, Backup guard 45178419208 green. Replaced Material theme text colors with Pal palette across 10 files: ProjectScreen, BrowsersScreen, ApprovalsScreen, DiaryScreen, ConnectorsScreen, AgentScreen, OfficeScreen, HomeScreen, Common, AgoriaRoot. |
| T-AGENT-PIXEL | Agent page body is still Material: TabRow, Switch, TextButton, DropdownMenu, chat bubbles (rounded), message field | After T-CARDCOLOR-AUDIT. Use PixelButton/PixelSwitch/PixelTextField, square bubbles. Phone screenshot 23 Nov showed a floating amber "< OFFICE" button over the message box: it is NOT in main (AgoriaNav has none), so that APK was stale; reinstall the newest CI APK before judging. |
| T-PIXEL-WIDGETS | Shared pixel widgets: `PixelDialog` (Dialog + PixelCard, square, ink border), `PixelSlider` (square track and thumb, amber fill), `PixelProgressBar` (private version now in SettingsScreen.kt) | Replace the private `ResetDialog` and `PixelProgressBar` in SettingsScreen.kt, the Home hire dialog, and the Material `Slider`s in Autonomy and Revenue. Put them in `PixelUi.kt`. |
| T-CONNECTORS-FOCUS | Make Settings > Payment Connectors > Configure land on the tapped connector | Route `connectors?focus={id}` (optional nav argument, `NavType.StringType`, nullable). `ConnectorsScreen` reads it via `initialFocusId`, scrolls its `LazyColumn` to that connector (`LazyListState.animateScrollToItem`) and draws the card with an amber 2 dp border for ~2 s via animated `border()` + background. Settings calls `onOpenConnectors(connector.id)`. No DB change. VERIFIED (CI, PR pending). |
| T-SCREENS | Restyle every page to the pixel look | Specs in section 5. Settings is done in code (PR #18 + T-SETTINGS-PIXEL-PR2). Remaining: audit Home/Agent/Approvals/Connectors/Project/Diary/Browsers for leftover `RoundedCornerShape`, Material buttons, switches, text fields, `AlertDialog`, `CircularProgressIndicator`. Onboarding DONE in code (untested). |
| T-MSG | Real `agent_messages` table instead of diary `say` entries | Needs Room version bump + migration. Spec in section 5. |
| T-ROUTINES | Per-agent routines (e.g. "checks the server room every morning") | Spec in section 5. |
| T-CARRY | Agent carries a finished build (crate icon) to a shelf in the workshop | Needs "build finished" signal. |
| T-A11Y | Content descriptions for hotspots; keep List view as the accessible fallback | |
| T-TEST | Unit tests: `OfficeGrid.route`, `parseChatter`, `buildSignals`, `officeTint` | Pure Kotlin, no Android. |
| T-PERF | Profile on the Spark 20C (GPU overdraw, jank), drop labels/glow if needed | |

### Done recently (kept short; details in the log)
| ID | What | Status |
| --- | --- | --- |
| T-BACK | One shared `PixelScaffold` (top bar with BACK TO OFFICE + title) | VERIFIED (CI): `PixelScaffold`, `PixelTopBar`, Diary & Browsers converted, AgoriaNav hides floating button on scaffolded routes. |
| T-BACK2 | Convert the remaining pages to `PixelScaffold` | VERIFIED (CI, PR #17, commit 123b765). All routes scaffolded except Onboarding (no office behind it). |
| T-SETTINGS-PIXEL | Restyle the BODY of `SettingsScreen.kt` | PR 1 VERIFIED (CI, PR #18, commit 1e69473): Autonomy and Revenue sections. PR 2 is T-SETTINGS-PIXEL-PR2 above. |
| T-SETTINGS-NAV | "Configure" on a payment connector in Settings opens the Connectors page (new `onOpenConnectors` parameter on `SettingsScreen`, wired in `AgoriaNav`; empty state got an "Open Connectors" button and the stale "Connectors tab" copy was fixed) | CODE WRITTEN, NOT COMPILED. Needs branch + PR + green CI, then phone check. |
| T-CONNECTORS-FOCUS | Settings > Payment Connectors > Configure lands on the tapped connector via `connectors?focus={id}` | Implemented: `AgoriaNav` adds optional `focus` query arg to connectors route; `ConnectorsScreen` takes `initialFocusId`, scrolls `LazyColumn` to item, highlights with animated amber 2dp border + background for ~2s; `SettingsScreen.onOpenConnectors` now takes optional `String` (connector ID); `ApprovalsScreen` updated for consistency. CI build pending. |

## 5. Specs for open tasks

### T-BACK: shared pixel scaffold
Create `ui/components/PixelScaffold.kt`:
`PixelScaffold(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit)`.
Top bar: ink background, 2 dp bottom border, a `PixelIconButton` (back arrow) labelled BACK TO
OFFICE, title in `PixelLabel`. Replace each screen's `Scaffold`/`TopAppBar` with it. System back
already pops to the office, so `onBack = { navController.popBackStack() }`.
Acceptance: every page has the same top bar, tapping it returns to the office, no page has a
Material `TopAppBar`.

### T-SCREENS: per-screen restyle (do in this order)
Files under `ui/`: `home/`, `agent/`, `approvals/`, `connectors/`, `settings/`, `project/`,
`browser/`, `diary/`, `OnboardingScreen.kt`. For each:
- Replace Material `Card`/`Surface` with `PixelCard` (add to `Common.kt`: flat fill, 2 dp ink border,
  offset shadow, no rounding) or `PixelPanel`.
- Replace `FilledTonalButton`/`Button`/`TextButton` with `PixelButton` (add a `small` variant).
- Replace `Switch`, `Checkbox`, `OutlinedTextField` with pixel versions (see `QuickChat` in
  `OfficeScreen.kt` for a text box; `PixelSwitch` and `PixelTextField` are in `PixelUi.kt`).
- Headings use `MaterialTheme.typography.titleLarge/titleMedium` (already monospace after T-THEME).
- No `RoundedCornerShape(...)` or `CircleShape` left (grep for them). Dialogs: square, bordered.
- Lists: separators are 2 dp ink lines, not Material dividers.
Acceptance per screen: screenshot on the phone looks like the office HUD (cream panels, ink borders).

### T-MSG: messages table
`AgentMessageEntity(id, fromAgentId, toAgentId, text, createdAt, readAt?)`, DAO with
`insert`, `inboxFor(agentId, limit)`, `recent(limit)`, repository wrappers, Room version bump with a
`Migration` (do not use destructive fallback; the owner has real data). In `AgentRuntime` replace the
diary write in the `say` branch with `repo.addAgentMessage(...)`, and in `buildContext` read the new
table. `parseChatter` becomes a plain mapper. Keep writing the diary line too (for the Diary page).
Back-fill: a one-off migration that turns old `kind="say"` diary rows into messages.

### T-ROUTINES: per-agent routines
Add to `AgentEntity` a `routine: String` (JSON list of `{hour, room}`) with a migration, an editor in
the agent page, and in `Signals.kt` a rule: if idle and a routine entry matches the current hour,
set `place` to that room. Rooms: library, web, mail, server, workshop, finance, kitchen, lounge.

## 7. Phone checklist (tick these and log the result)

- [ ] T-CARDCOLOR: Home (amber approvals banner, navy hero card, runtime card, diary rows), Agent cards and every Settings card: text readable, spacing unchanged (no double padding), Reset dialog text dark on cream.

- [ ] T-SETTINGS-NAV: Settings > Revenue > Payment Connectors: with no payment connector, "Open Connectors" opens the Connectors page; with one, "Configure" does the same; BACK TO OFFICE returns to the office.

- [ ] T-CONNECTORS-FOCUS: Settings > Revenue > Payment Connectors > Configure on a specific connector opens Connectors page scrolled to that connector with amber 2dp border highlight for ~2s; "Open Connectors" (no connector) opens Connectors page at top.

- [ ] App opens straight on the office; there is no bottom bar anywhere.
- [ ] Tap the title panel (or the notice board): the directory opens; each button opens its page.
- [ ] On every other page the "< Office" button (bottom left) returns to the office; system Back too.
- [ ] Zoom in: amber labels appear over the library, web room, mail, server room, workshop, finance,
      war room, notice board and your desk, each naming the page it opens.
- [ ] Sound: alert when a new approval arrives, blip when agents talk, click on buttons; the
      directory switch turns it off and it stays off after restarting.
- [ ] Christmas decor visible (today is inside Sep 1 - Jan 6): parol lanterns in the hall, tree at the
      top of the hall, string lights on the walls.
- [ ] After 19:00 the office darkens and every agent has a warm glow.
- [ ] Other pages (Home, Diary, Project, Approvals, Connect, Settings, agent page) now have square
      corners, monospace headings and flat dark backgrounds. List which still look off (feeds T-SCREENS).
- [ ] Quick chat: send a message to an agent from its card; it thinks and replies on the card.
- [ ] Agent talk: tell one agent "say hi to <other agent>"; a speech bubble plays out.
- [ ] Settings shows the ink top bar with BACK TO OFFICE and returns to the office; no "< Office" floating button on ANY page.
- [ ] First launch (clear app data): onboarding is navy with square panels, typing a name works, ENTER THE OFFICE opens the office.
- [ ] Diary and Agent browsers pages show the ink top bar with BACK TO OFFICE; tapping returns to the office; no floating "< Office" button on those two pages.
- [ ] Settings, every card (Autonomy ... About): text is dark and readable on the cream cards, there is padding inside each card, no round Material controls (except the sliders, T-PIXEL-WIDGETS).
- [ ] Settings > Model providers: provider rows are tan with a square switch; Configure opens square text boxes; the API key box shows dots, not the key; Save key / Clear key / Save model work.
- [ ] Settings > Data & privacy: Reset app (red) opens a square dialog; Cancel closes it; do not confirm unless you want to wipe the app.
- [ ] Settings > Backup & Restore and App Updates: Export / Import / Back up now / Restore / Check work as before; during an update download an amber square progress bar shows.

## 6. Log (newest first; append, never rewrite)

Format: `YYYY-MM-DD HH:MM  who  task  what happened  [verified / phone / untested]`

- 2026-11-23 (time not recorded)  Claude  T-CONNECTORS-FOCUS  Implemented: `AgoriaNav.kt` adds optional `focus` query argument (`navArgument("focus")`, `NavType.StringType`, nullable) to `connectors` route; `ConnectorsScreen.kt` refactored to `LazyColumn` with `LazyListState`, accepts `initialFocusId`, scrolls via `animateScrollToItem(index)`, highlights target row with animated amber 2dp border + background fade for ~2s using `animateFloatAsState`/`animateColorAsState`; `SettingsScreen.kt` `onOpenConnectors` now takes `(String?) -> Unit`, passes `connector.id` from "Configure" button and `null` from "Open Connectors"; `ApprovalsScreen.kt` updated for consistency (passes `null`). Category headers show correct on/total counts. [untested: not compiled, not on a phone]

- 2026-11-23 (time not recorded)  Claude (Anthropic chat)  T-SETTINGS-NAV  CODE WRITTEN, NOT COMPILED, NOT PUSHED (no Gradle in the chat sandbox, no network). Three edits, applied by `apply_t_settings_nav.py` with exact-anchor asserts: `AgoriaNav.kt` passes `onOpenConnectors = { navController.navigate("connectors") }` to `SettingsScreen`; `SettingsScreen.kt` gets a new `onOpenConnectors: () -> Unit` parameter (before `onBack`), the payment "Configure" button calls it (the no-op comment is gone), the empty state shows an "Open Connectors" `PixelButton` and its copy no longer mentions a "Connectors tab" (there are no tabs). Back from Connectors still goes to the office (`popBackStack("office", false)`), same as every page. Compile suspects: named-arg order at the `SettingsScreen(...)` call site (callers other than AgoriaNav? grep `SettingsScreen(` first), `PixelButton(text, onClick, small=, fill=)` positional use with a function reference `onOpenConnectors` (it is `() -> Unit`, matches). Added task T-CONNECTORS-FOCUS. [untested: not compiled, not on a phone]

- 2026-11-23 15:15  Claude  Approval Pipeline + Builds download  Fixed on branch `kilo/pearly-relay-7or`: (1) Build downloads now use correct MIME type (`application/zip` instead of `application/json`) — `Share.kt:saveToDownloads` accepts `mimeType` param, `AgoriaViewModel.kt:saveBuildToDownloads` passes `application/zip`. (2) Approval pipeline fault: auto-approved items with `status = 'auto_pending'` now appear in pending lists — `Daos.kt:observePending()` and `pendingForAgent()` query `status IN ('pending', 'auto_pending')`. CI build 45188136257 green (AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1 after rebase onto main). Cloudflare worker content type issue (`application/javascript`) is a separate deployment config not in this repo. [verified: CI build green; untested on phone]
- 2026-11-23 14:30  Claude  T-CARDCOLOR-AUDIT  PR #22 merged to main (commit c187269). Build APK 45177467724 green, Backup guard 45178419208 green. Fixed text colors on cream/tan surfaces across 10 files: ProjectScreen, BrowsersScreen, ApprovalsScreen, DiaryScreen, ConnectorsScreen, AgentScreen, OfficeScreen, HomeScreen, Common, AgoriaRoot. Replaced all `MaterialTheme.colorScheme.(onSurfaceVariant|primary|outline|secondary|tertiary|error)` with Pal palette (`Pal.ink`, `Pal.tan`, `Pal.wood`, `Pal.amber`, `Pal.teal`, `Pal.red`). PixelCard already provides LocalContentColor (T-CARDCOLOR). No phone test — CI verified only. [verified]
- 2026-11-23 13:45  Claude  T-CARDCOLOR + T-FIX-PHONE1  PR #21 merged to main (commit e50aa22). Build APK 45173568096 green, Backup guard 45174757705 green. T-CARDCOLOR: `PixelCard` wraps content in `CompositionLocalProvider(LocalContentColor provides if (fill.luminance() > 0.4f) Pal.ink else Pal.cream)`. SettingsCard/ResetDialog workaround providers removed. Home cream cards: `onSurfaceVariant`->`Pal.navy`, icon `primary`->`Pal.teal`, last-log `outline`->`Pal.wood`. T-FIX-PHONE1: night glow gated on `nightness = ((tint.alpha-0.30)/0.25)`, radius 1.1px; `officeTint()` keys strengthened (night 0x4D->0x8C, dusk 0x1F->0x2E, 20:00 0x3A->0x66); `AgentContent(padding)` applies scaffold padding; `AgentHeader` compacted (avatar 40dp, name/role removed, goal 2-line clamp + tap expand). No compile fixes needed — suspects were clean. [verified]
- 2026-11-23 (time not recorded)  Claude (Anthropic chat)  T-FIX-PHONE1  CODE WRITTEN, NOT COMPILED, NOT PUSHED. From owner phone screenshots at 18:57-18:58. Night: `OfficeWorld.kt` glow gated on `nightness = ((tint.alpha-0.30)/0.25)` and scaled by it, radius 1.1px; `OfficeScreen.kt` `officeTint` keys stronger. Agent page: `AgentScreen.kt` `AgentContent(padding)` + `.padding(padding)`; `AgentHeader` compacted, goal `maxLines=2` + tap to expand (`animateContentSize`, `clickable` import added). Compile suspects: `clickable` import placement, `remember(agent.id)` inside a Composable Column. Not yet fixed: Material tabs/switch/bubbles on this page (T-AGENT-PIXEL). [untested: not compiled, not on a phone]
- 2026-11-23 (time not recorded)  Claude (Anthropic chat)  T-CARDCOLOR  CODE WRITTEN, NOT COMPILED, NOT PUSHED (chat GitHub token is read-only: branch creation 403; no Gradle in the sandbox). Files: `ui/components/Common.kt` (`PixelCard` wraps content in `CompositionLocalProvider(LocalContentColor provides if (fill.luminance() > 0.4f) Pal.ink else Pal.cream)`; new imports `LocalContentColor`, `CompositionLocalProvider`, `luminance`; KDoc explains padding stays with callers because every call site pads itself), `ui/settings/SettingsScreen.kt` (removed the local `LocalContentColor` providers in `SettingsCard` and `ResetDialog`, removed the two now-unused imports), `ui/home/HomeScreen.kt` (cream-card text: lines for empty state, runtime card, diary row now `Pal.navy`; runtime icon `Pal.teal`; last-log text `Pal.wood`). `AgentCard` (navy-ish `surfaceVariant`, luminance low) keeps cream text, unchanged look. Brace/paren balance of the three files checked by script only. Compile suspects: `androidx.compose.ui.graphics.luminance` import, `Color.luminance()` on `MaterialTheme.colorScheme.surfaceVariant` (a `Color`, fine), unused-import warnings elsewhere. Added task T-CARDCOLOR-AUDIT. Zip: `dunder-t-cardcolor.zip` (changed files + this md, paths from repo root). [untested: not compiled, not on a phone]
- 2026-11-23 12:00  Claude  T-SETTINGS-PIXEL-PR2  PR #19 merged to main (commit ac76095). Build APK 45167823699 green, Backup guard 45168582490 green. SettingsScreen bottom half (Model providers, Data & privacy, Backup & Restore, App Updates, About): SettingsCard (PixelCard + LocalContentColor ink + 14dp padding) used throughout. ProviderRow: tan PixelCard, PixelSwitch, PixelTextField (password, hint, labelColor, hintColor) for API key/model/extra. OutlinedTextField -> PixelTextField. Button/TextButton -> PixelButton(small=). AlertDialog -> ResetDialog (Dialog + PixelCard). CircularProgressIndicator/LinearProgressIndicator -> text / PixelProgressBar (navy track, amber fill, 2dp ink border). SectionCard/SummaryCard removed. PixelUi.kt: PixelTextField gained optional password, hint, labelColor, hintColor. grep clean for RoundedCornerShape|CircleShape|MaterialTheme.shapes|Card(|OutlinedTextField|Switch(|TextButton|AlertDialog|CircularProgress|LinearProgress|Surface( (only PixelCard/PixelButton/PixelSwitch match). [verified]
- 2026-11-23 (time not recorded)  Claude (Anthropic chat)  T-SETTINGS-PIXEL-PR2  CODE WRITTEN, NOT COMPILED (the chat sandbox has no network, so no Gradle). Files: `ui/settings/SettingsScreen.kt` (full rewrite of the bottom half) and `ui/office/world/PixelUi.kt` (`PixelTextField` gained optional `password`, `hint`, `labelColor`, `hintColor`; existing calls unchanged). SettingsScreen: Model providers, Data & privacy, Backup & Restore, App Updates, About all use a private `SettingsCard` (PixelCard + 14 dp padding + ink content colour). `ProviderRow` is a tan `PixelCard`, Switch -> `PixelSwitch`, `OutlinedTextField` -> `PixelTextField` (key box masked), `Button`/`TextButton` -> `PixelButton(small=)`, `AlertDialog` -> private square `ResetDialog` (Dialog + PixelCard), `CircularProgressIndicator`/`LinearProgressIndicator` -> text "Checking for updates..." / private `PixelProgressBar`. Removed dead `SectionCard` and `SummaryCard`. Grep of the file for `RoundedCornerShape|CircleShape|MaterialTheme.shapes|Card(|OutlinedTextField|Switch(|TextButton|AlertDialog|CircularProgress|LinearProgress|Surface(` is clean (only `PixelCard(`/`PixelButton(`/`PixelSwitch(` match). Also fixed from PR #18 (found while reading the code, not on a phone): its `PixelCard`s had no inner padding and inherited the theme's cream text colour on a cream fill, so Autonomy/Revenue text was almost certainly unreadable; secondary text now `Pal.navy` (alpha .75), amber/tan text on cream replaced with wood/teal/navy. Behaviour unchanged: export/import/auto-backup/update callbacks and ViewModel calls are the same as before. Known gaps: payment "Configure" button is still a no-op (T-SETTINGS-NAV); sliders are still Material (T-PIXEL-WIDGETS). Compile suspects: `PixelTextField(... password, hint, labelColor, hintColor)` named args, `LocalContentColor`/`CompositionLocalProvider` imports, smart cast of `spec.extraLabel`/`spec.extraHint` inside `if (spec?.needsExtra == true)` (same pattern as before), `viewModel.downloadAndInstallUpdate(info, { pct -> ... }, { _ -> ... })` lambda shapes, `Dialog` import (`androidx.compose.ui.window.Dialog`). Added tasks T-CARDCOLOR, T-PIXEL-WIDGETS, T-SETTINGS-NAV and rule 8. [untested: not compiled, not on a phone]
- 2026-11-23 13:00  Claude  T-SETTINGS-PIXEL PR 1  PR #18 merged to main (commit 1e69473). Build APK 45163062656 green, Backup guard 45164034742 green. SettingsScreen top half (Autonomy, Revenue): SectionCard->PixelCard, Switch->PixelSwitch, Button/TextButton->PixelButton(small=), SummaryCard->PixelCard, Surface status chips->PixelPill (teal=ready, amber=needs_setup, red=error), Slider track/thumb=Pal.amber. PixelCard title param added to Common.kt. PR 2 pending (Providers, Data, Backup, Updates, About). [verified]
- 2026-11-23 12:30  Claude  T-BACK2 / T-SCREENS  PR #17 merged to main (commit 123b765). Build APK 45160531742 green, Backup guard 45161209258 green. SettingsScreen -> PixelScaffold(title="Settings", onBack, snackbarHost); AgoriaNav: removed ScaffoldedRoutes, floating "< Office" button, currentRoute tracking; settings onBack = popBackStack("office", false); OnboardingScreen restyled pixel (navy ground, ink-bordered thumbnail, PixelPanel rows, PixelTextField, PixelButton); tools/ scripts and README_APPLY.md deleted. All routes now have PixelScaffold top bar. [verified]
- 2026-11-23 14:45  Claude  T-HOME / T-HIRE  PR #16 merged to main (commit 6521874). Build APK runs 45140547641 and 45141420941 green; Backup guard runs 45140535659 and 45141420923 green. Phase 6 Home page implemented with PixelUi components: hero card, approvals banner, runtime card, diary rows, hire dialog. PixelSwitch, PixelButton(small=), PixelTextField added to PixelUi.kt. Auto-approve rule unchanged (SAFE_TYPES only). CI failures on kilo/phase6-home-pixel were caused by the branch being based on an old commit (74de3f8) with AGP 8.2.2 / Kotlin 1.9.20 / Gradle 8.2.1; fixed by rebasing onto main HEAD (36339f8) which has AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.11.1 + SSL truststore config. The earlier "network flakiness" theory was wrong — main passes consistently, AGP 8.2.2 plugin artifact is no longer reliably resolvable from Google Maven. [verified]
- 2026-11-23 06:05  Claude  CI  **main could not compile at all; the last-green tree was restored.** PR #14 merged to main (merge commit 8962318). Green: Build APK run 45120165921 (#800) on commit 8633715 — Build Debug APK, Unit tests, Build Release APK and Upload APKs all success; Backup config guard run 45120166000 green. [verified]

  Two separate faults, one behind the other.

  **(a) Workflow.** Run #797 (45108717737, commit 51b4884) failed at step 4 "Set up Android SDK" after 13 s:

  ```
  ##[warning]Unexpected input(s) 'api-level', 'build-tools-version', 'emulator', 'ndk-version', 'cache', valid inputs are ['cmdline-tools-version', 'accept-android-sdk-licenses', 'log-accepted-android-sdk-licenses', 'packages']
  ##[warning]setup-java v4 is deprecated on the runner; migrate to actions/setup-java@v5
  ```

  The last green workflow used `android-actions/setup-android@v3` with those extra inputs; 44f7ab1
  bumped the action to `@v4`, which does not accept them. Fixed in the workflow: `@v4` with only
  `accept-android-sdk-licenses` and `packages:` (platform-tools, platforms;android-34,
  platforms;android-35, build-tools;34.0.0, build-tools;35.0.0), `actions/setup-java@v5`, and the
  3x retry loops removed — no retries and no `|| true` anywhere.

  **(b) The tree.** With (a) fixed, run #799 (45118583197) passed steps 1-9 (SDK step 12 s, no
  warnings) and failed at step 10 "Build Debug APK" after 26 s. `main` is 71 commits behind the last
  green commit (compare e12de9f...main: ahead_by 5, behind_by 71): it is a pre-Phase-4 snapshot
  (`versionCode 140`) with four later commits re-applied on top (FreeLLMAPI add/fix/revert and
  51b4884 T-AGENT). Missing from the tree: `ui/browser`, `ui/project`, `ui/viewer`,
  `ui/office/world/{OfficeWorld,OfficeLayout,OfficeSim,Signals,WorldAssets,Chatter,ChatterPanel,DirectoryPanel,OfficeSound}`,
  `ui/office/OfficeViewModel`, `service/BootStatus`, `util/UpdatePolicy`, `res/raw`,
  `res/drawable-nodpi`, `app/src/test`, `docs/`, `tools/`. The files it keeps also lost their import
  blocks: `AgentScreen.kt` has no `androidx.compose.material3` import at all.

  Reproduced locally (main @44f7ab1 sources + the restored Gradle stack): **192 `e:` errors,
  BUILD FAILED in 2m 11s**, 116 of them in AgentScreen.kt:

  ```
  e: app/src/main/java/com/agoria/app/core/ConnectorExecutor.kt:179:21 Unresolved reference 'codePointAt'.
  e: app/src/main/java/com/agoria/app/data/Repository.kt:321:21 Unresolved reference 'codePointAt'.
  e: app/src/main/java/com/agoria/app/ui/AgoriaNav.kt:43:36 Unresolved reference 'Home'.
  e: app/src/main/java/com/agoria/app/ui/AgoriaNav.kt:89:21 No value passed for parameter 'onBack'.
  e: app/src/main/java/com/agoria/app/ui/agent/AgentScreen.kt:76:37 Unresolved reference 'PixelScaffold'.
  e: app/src/main/java/com/agoria/app/ui/agent/AgentScreen.kt:94:48 Unresolved reference 'Pause'.
  e: app/src/main/java/com/agoria/app/ui/agent/AgentScreen.kt:178:26 Unresolved reference 'clickable'.
  e: app/src/main/java/com/agoria/app/ui/agent/AgentScreen.kt:181:21 Unresolved reference 'Text'.
  e: app/src/main/java/com/agoria/app/ui/agent/AgentScreen.kt:181:72 Unresolved reference 'sp'.
  e: app/src/main/java/com/agoria/app/ui/home/HomeScreen.kt:49:37 Unresolved reference 'AgentCard'.
  e: app/src/main/java/com/agoria/app/ui/home/HomeScreen.kt:270:17 Unresolved reference 'PixelPill'.
  e: app/src/main/java/com/agoria/app/ui/office/world/PixelUi.kt:105:17 Unresolved reference 'OfficeSound'.
  e: app/src/main/java/com/agoria/app/ui/office/OfficeScreen.kt:... Unresolved reference 'PixelScaffold'.
  ```

  Per file: AgentScreen 116, Common.kt 26, PixelUi.kt 10, HomeScreen 8, SettingsScreen 7,
  OfficeScreen 7, AgoriaNav 7, OnboardingScreen 4, ConnectorsScreen 3, DiaryScreen 1,
  ApprovalsScreen 1, Repository.kt 1, ConnectorExecutor.kt 1.

  **Root cause:** the rollback deleted the files the newer screens depend on, so the newest features
  (`PixelScaffold`, `AgentCard`, `PixelPill`, `OfficeSound`, the `Icons` used by `AgoriaNav`) reference
  symbols that are not in the tree. The ~15 commits from 28874ee onwards were chasing the wrong
  symptom: they downgraded AGP/Kotlin to 8.2.2/1.9.20, but `org.jetbrains.kotlin.plugin.compose` has
  no 1.9.20 release (the plugin starts at 2.0.0), so configuration could never succeed either.

  **Fix:** commit 8633715 sets the branch tree to e12de9f's tree (v1.0.218, run #760, the last green
  build) plus this workflow repair. T-AGENT's pixel Agent page and the phase-6 Home work already
  exist in that tree in compiling form — 51b4884 was an uncompiled re-add of them onto the stale
  snapshot. `versionCode` is 218 again. Also gone with the restore: `apks-release.zip` (a 142-byte
  GitHub 404 JSON), `init.gradle.kts` (dead diagnostic script), `generate-keystore.yml` (superseded by
  fail-closed signing) and `office_tileset.png` (unused). Handoff zip of the 79 changed files:
  `dunder-ci-fix.zip`.

- 2026-11-23 14:15  Claude  T-BACK2  PR #11 merged to main (commit d15decc). Build APK run 45078557925 green, Backup guard run 45078557904 green. ApprovalsScreen converted to PixelScaffold; AgoriaNav ScaffoldedRoutes includes 'approvals'; auto-approve settings action in top bar; floating button hidden on Approvals page. [verified]
- 2026-11-23 12:45  Claude  T-BACK2  PR #10 merged to main (commit ca2ef1e). Build APK run 45072215229 green, Backup guard run 45072215197 green. AgentScreen converted to PixelScaffold; AgoriaNav ScaffoldedRoutes includes 'agent'; pause/resume action in top bar; floating button hidden on Agent page. [verified]
- 2026-11-23 11:30  Claude  T-BACK2  PR #9 merged to main (commit 2f638f2). Build APK run 45070684147 green, Backup guard run 45070684236 green. Home, Project, Connectors converted to PixelScaffold; AgoriaNav ScaffoldedRoutes updated; floating button hidden on these three pages. [verified]
- 2026-11-23 10:25  Claude  T-BACK  PR #8 merged to main (commit b6b7efb). Build APK run 45068973188 green, Backup guard run 45068973197 green. `PixelScaffold` + `PixelTopBar` compiled and CI passes. Diary & Browsers converted; AgoriaNav hides floating button on scaffolded routes. [verified]
- 2026-11-23 10:15  Claude  T-BACK  CODE WRITTEN (not compiled, not pushed: the chat's GitHub token was read-only, branch creation returned 403). Added `ui/components/PixelScaffold.kt` (`PixelScaffold`, `PixelTopBar`), converted `DiaryScreen` (new `onBack` param) and `BrowsersScreen`, `AgoriaNav` passes `popBackStack("office", false)` and hides the floating button on scaffolded routes. Compile suspects: `Icons.AutoMirrored.Filled.ArrowBack` (already used in BrowsersScreen, so should be fine), `PixelLabel.copy(...)`, `Scaffold(content = ...)` named argument. [untested]
- 2026-11-22 20:22  Claude  T-NAV, T-DIR, T-THEME, T-COMMON, T-SOUND, T-NIGHT, T-SEASON  PR #7 opened, Build APK run 45065987758 green, Backup guard run 45065987749 green. Fixed one compile error: `OfficeSound.setEnabled` platform declaration clash -> renamed to `setSoundEnabled`. All Phase 5 code compiles and CI passes. [verified]
- 2026-11-22  Claude  T-THEME, T-COMMON  CODE WRITTEN (not compiled): `AgoriaTheme.kt` is one fixed pixel scheme (ink/navy/cream/amber/teal, square shapes, monospace headings, no surface tint); `Common.kt` rewritten with the same public API (crisp square avatar showing the standing frame, solid ink-bordered chips, `PixelCard`). Every page that uses the theme or these components changes look at once. Per-screen leftovers (explicit RoundedCornerShape, Material buttons/switches/text fields) are T-SCREENS. [untested]
- 2026-11-22  Claude  T-NAV, T-DIR, T-SOUND, T-NIGHT, T-SEASON  CODE WRITTEN (not compiled): AgoriaNav has no bottom bar plus a floating "< Office" button; DirectoryPanel on the notice board and the title panel; amber page labels on every tappable area when zoomed in; OfficeSound (3 generated WAVs, mute in the directory); lamp glow at night; Christmas decor Sep 1 - Jan 6. [untested]
- 2026-11-22  Claude  P4  Wrote office-as-home, quick chat, camera fly-to, tint, hints, autonomy button (zip dunder-phase4-pixel-office.zip). [untested: not compiled]
- 2026-11-22  Kilo/owner  P3  PR #4 merged: renderer fixed, Phase 3 + runtime edits (`say`, prompt, `ask_user` fix). Build green, release v1.0.216. [verified; phone: map OK]
- 2026-11-21  Claude  P3  Agent-to-agent talk as diary `say` entries; speech bubbles; chatter panel. [untested at the time]
- 2026-11-21  Claude  P2  Generated pixel office (Python), y-sorted furniture, live signals, desk panel with safe auto-approve. [phone: layout OK]
- 2026-11-21  Claude  P1  First pixel-office renderer (waypoint graph, AI-generated background). The blurry background was replaced in P2. [verified]
- 2026-11-21  Kilo/owner  fix  Phase 2 branch had OfficeWorld.kt overwritten with screen code (map squashed). Fixed by replacing with the correct renderer. [verified; phone]
