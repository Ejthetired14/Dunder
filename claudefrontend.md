# claudefrontend.md — Dunder pixel-office frontend: plan, tasks, log

Any AI (or person) can pick this file up and continue. Read "Rules" first, then take the
top open task on the board. When you finish something, change its status and add a log line.
Keep this file honest: if something was not compiled or not tested on a phone, say so.

Last updated by: Claude (Anthropic chat), Monday 23 Nov 2026 (Agent restyle + OfficeScreen round).

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

1. **Verify the newest commit builds.** The latest log entry (Home restyle) was written without a
   compiler and pushed to main on the owner's request. Run/check the "Build Debug & Release APK"
   workflow. If it is red, fix the compile error first (suspects are listed in that log line).
2. Install on the phone and run the checklist in section 7.
3. Then take the top item of "Open" below.

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
6. **Build:** the repo's CI is "Build Debug & Release APK". Code written without a compiler should go
   on a branch + PR with a green build before merging. (The owner may explicitly ask for a direct push
   to main; if so, log it as UNVERIFIED and make the next AI check CI first, see 1b.) Push with the
   owner's credentials (the default GITHUB_TOKEN does not trigger the build workflow).
7. **Tell the truth in the log:** say what was compiled, what was run on a phone, what was guessed.
8. **Pixel components live in `ui/office/world/PixelUi.kt`:** `Pal`, `PixelLabel`, `PixelBody`,
   `PixelPanel`, `PixelButton(small=)`, `PixelIconButton`, `PixelTextField(singleLine=)`, `PixelSwitch`.
   Shared cards/chips are in `ui/components/Common.kt` (`PixelCard`, `Pill`, `StatusPill`...).
   Use these; do not add new Material `Card`, `Button`, `Switch`, `OutlinedTextField` or `AlertDialog`.

## 3. Architecture map

```
app/src/main/java/com/agoria/app/
  core/            AgentRuntime, AgentPolicy, PromptBuilder, AgentModels (agent brains)
  data/            Entities.kt (Room), repository
  ui/
    AgoriaNav.kt           navigation; office is the start and only top-level screen
    AgoriaViewModel.kt     all UI state (agents, diary, approvals, settings, runtimeState...)
    theme/AgoriaTheme.kt   Material theme (pixel colours, square shapes, monospace headings)
    components/Common.kt   shared pixel components (AgentAvatar, Pill, StatusPill, PixelCard...)
    components/PixelScaffold.kt  shared top bar (BACK TO OFFICE + title)
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
| P4 | Office as start screen, quick chat, camera fly-to, day/night tint, hints, autonomy button | DONE (CI, via PR #7) |

### This round
| ID | What | Status |
| --- | --- | --- |
| T-NAV | Remove the bottom bar; office is the only top-level view | VERIFIED (CI) |
| T-DIR | Notice board = directory card; amber page labels when zoomed in (scale >= 1.6) | VERIFIED (CI) |
| T-THEME | Global pixel theme (colours, square shapes, monospace headings, no tonal elevation) | VERIFIED (CI) |
| T-COMMON | Pixel versions of shared components in `Common.kt` | VERIFIED (CI) |
| T-SOUND | Sound effects with a mute switch in the directory | VERIFIED (CI) |
| T-NIGHT | Night shift: warm lamp glow around every agent when dark | VERIFIED (CI) |
| T-SEASON | Christmas decor, shown Sep 1 - Jan 6 | VERIFIED (CI) |
| T-BACK | Shared `PixelScaffold` (top bar with BACK TO OFFICE + title) | VERIFIED (CI) |
| T-BACK2 | Convert remaining pages to `PixelScaffold`, remove floating button | VERIFIED (CI) (PR #12) |
| T-HOME | Restyle Home page to the pixel look (hero card, approvals banner, runtime card, diary rows, hire dialog); add `PixelSwitch` (public, in `PixelUi.kt`) and `PixelButton(small=)`; multi-line `PixelTextField` | CODE WRITTEN, pushed to main, NOT COMPILED. Next AI: check CI first (see 1b). |
| T-AGENT | Restyle the Agent page: replace `RoundedCornerShape(16.dp)` bubble `Surface` with `PixelCard`/`PixelPanel`, Material buttons/fields with `PixelButton`/`PixelTextField`, `Switch` with `PixelSwitch`. **Do this next.** | CODE WRITTEN, not compiled. |
| T-OFFICE-ROUND | `OfficeScreen.kt`: remove `RoundedCornerShape(22.dp)` panel -> `PixelPanel`; grep the file for any other `RoundedCornerShape`/`CircleShape` | CODE WRITTEN, not compiled. Bundled with T-AGENT. |

### Open (not started), in priority order
| ID | What | Notes |
| --- | --- | --- |
| T-HIRE | Home has `AddAgentDialog` but nothing ever sets `showAdd = true`, so the dialog is dead code. Add a small "HIRE AGENT" `PixelButton(small = true)` next to "The agents" header (or confirm the owner does not want hiring and delete the dialog) | Found during T-HOME. Check `viewModel.addAgent` exists first (it is called, so it should). |
| T-FIELDS | Adopt `PixelTextField` / `PixelSwitch` in place of every remaining `OutlinedTextField`/`TextField`/`Switch` on Connectors, Project, Approvals, Agent, Settings | `PixelSwitch` is now in `PixelUi.kt`; Home, Onboarding and DeskPanel already use the pixel versions. 2-3 screens per PR. |
| T-HIRE | Home has `AddAgentDialog` but nothing ever sets `showAdd = true`, so the dialog is dead code. Add a small "HIRE AGENT" `PixelButton(small = true)` next to "The agents" header (or confirm the owner does not want hiring and delete the dialog) | Found during T-HOME. Check `viewModel.addAgent` exists first (it is called, so it should). |
| T-FIELDS | Adopt `PixelTextField` / `PixelSwitch` in place of every remaining `OutlinedTextField`/`TextField`/`Switch` on Connectors, Project, Approvals, Agent, Settings | `PixelSwitch` is now in `PixelUi.kt`; Home, Onboarding and DeskPanel already use the pixel versions. 2-3 screens per PR. |
| T-SETTINGS | Restyle Settings (51 KB) | One PR on its own. |
| T-MSG | Real `agent_messages` table instead of diary `say` entries | Needs Room version bump + migration. Spec in section 5. |
| T-ROUTINES | Per-agent routines (e.g. "checks the server room every morning") | Spec in section 5. |
| T-CARRY | Agent carries a finished build (crate icon) to a shelf in the workshop | Needs "build finished" signal. |
| T-A11Y | Content descriptions for hotspots; keep List view as the accessible fallback; make `PixelSwitch` announce on/off (semantics `Role.Switch`) | |
| T-TEST | Unit tests: `OfficeGrid.route`, `parseChatter`, `buildSignals`, `officeTint` | Pure Kotlin, no Android. |
| T-PERF | Profile on the Spark 20C (GPU overdraw, jank), drop labels/glow if needed | |

## 5. Specs for open tasks

### T-SCREENS: per-screen restyle (remaining: Agent, OfficeScreen, Connectors, Project, Approvals, Settings)
Files under `ui/`: `agent/`, `approvals/`, `connectors/`, `settings/`, `project/`. Done: Onboarding,
Diary, Browsers, Home. For each:
- Replace Material `Card`/`Surface` with `PixelCard` (Common.kt) or `PixelPanel`.
- Replace `FilledTonalButton`/`Button`/`TextButton` with `PixelButton` (use `small = true` in dense cards).
- Replace `Switch`, `Checkbox`, `OutlinedTextField` with `PixelSwitch`, pixel checkbox (add one if needed), `PixelTextField`.
- Dialogs: use `androidx.compose.ui.window.Dialog` + `PixelPanel` (see `AddAgentDialog` in `HomeScreen.kt`), not `AlertDialog`.
- Headings use `MaterialTheme.typography.titleLarge/titleMedium` (already monospace after T-THEME).
- No `RoundedCornerShape(...)` or `CircleShape` left (grep for them). Lists: separators are 2 dp ink lines.
- Do not hard-code colours in screens; use `Pal`.
Acceptance per screen: screenshot on the phone looks like the office HUD (cream/navy panels, ink borders).

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

- [ ] App opens straight on the office; there is no bottom bar anywhere.
- [ ] Tap the title panel (or the notice board): the directory opens; each button opens its page.
- [ ] Every page shows the ink top bar with BACK TO OFFICE; tapping it returns to the office; system Back too. **No floating "< Office" button anywhere, especially not over the Agent page.**
- [ ] Zoom in: amber labels appear over the library, web room, mail, server room, workshop, finance,
      war room, notice board and your desk, each naming the page it opens.
- [ ] Sound: alert when a new approval arrives, blip when agents talk, click on buttons; the
      directory switch turns it off and it stays off after restarting.
- [ ] Christmas decor visible (today is inside Sep 1 - Jan 6): parol lanterns in the hall, tree at the
      top of the hall, string lights on the walls.
- [ ] After 19:00 the office darkens and every agent has a warm glow.
- [ ] Other pages (Project, Approvals, Connect, Settings, agent page) have square corners, monospace headings and flat dark backgrounds. List which still look off (feeds T-SCREENS / T-FIELDS).
- [ ] Quick chat: send a message to an agent from its card; it thinks and replies on the card.
- [ ] Agent talk: tell one agent "say hi to <other agent>"; a speech bubble plays out.
- [ ] Diary and Browsers pages show the ink top bar with BACK TO OFFICE; tapping returns to the office; no floating "< Office" button on those pages.
- [ ] First run (clear app data): Welcome page has a square office thumbnail, a cream square name box (type a name, keyboard works), and a square amber ENTER THE OFFICE button that opens the office.
- [ ] **Home page (T-HOME):** hero card is a square navy panel with the office thumbnail on top (no rounded corners, no gradient); the autonomy switch is a square 56x32 teal/navy switch and toggles autonomy; with a pending approval an amber banner shows and opens Approvals; "RUN ONE CYCLE" and "NUDGE EVERYONE" are square buttons that fit on one row; diary rows are square bordered cards that open the agent; the keyboard works in any text box.
- [ ] **Desk panel switches** (auto-approve in your desk panel) now use the square `PixelSwitch`: tapping each one toggles it and the safe-type rules from Rules #3 still hold.

## 6. Log (newest first; append, never rewrite)

Format: `YYYY-MM-DD HH:MM  who  task  what happened  [verified / phone / untested]`

- 2026-11-23 22:15  Claude  T-AGENT, T-OFFICE-ROUND  CODE WRITTEN (not compiled, not pushed). `AgentScreen.kt` fully restyled: all `RoundedCornerShape` bubbles replaced with `PixelPanel`/`PixelCard`; `Switch` -> `PixelSwitch`; `Button` -> `PixelButton`/`PixelIconButton`; `OutlinedTextField` -> `PixelTextField`; `AlertDialog` -> `Dialog` + `PixelPanel` (EditAgentDialog, AddMemoryDialog, DeleteAgentDialog); custom tab row with `PixelPill`; `LinearProgressIndicator` -> `PixelProgressBar`; `Surface`/`Card`/`MaterialTheme.shapes.medium` removed. `OfficeScreen.kt`: `AgentDesk` `RoundedCornerShape(22.dp)` -> `PixelPanel`; latest diary card -> `PixelCard`. `grep -rn "RoundedCornerShape\|CircleShape" app/src/main/java/com/agoria/app/ui/agent app/src/main/java/com/agoria/app/ui/office/OfficeScreen.kt` is now empty. `PixelScaffold` used on Agent page (AgentScreen.kt already converted to PixelScaffold in PR #10, this just restyles content). [untested]
- 2026-11-23 (evening)  Claude  T-HOME, T-FIELDS(part)  CODE WRITTEN and pushed straight to main at the owner's request. **Not compiled, not run on a phone.** Files: `ui/home/HomeScreen.kt` rewritten (no `RoundedCornerShape`, no `Card`/`Surface`/`Button`/`Switch`/`OutlinedTextField`/`AlertDialog`, no gradient; hero is `PixelCard` + `OfficeThumbnail` + `PixelSwitch`; approvals banner is amber `PixelCard`; `RuntimeCard`/`DiaryRow` use `PixelCard`; hire dialog is `Dialog` + `PixelPanel` + `PixelTextField`); `PixelUi.kt` gained public `PixelSwitch(checked, modifier, onChange)` (onChange last so trailing-lambda calls work), `PixelButton(..., small = false)` (36 dp / 11 sp) and a multi-line mode for `PixelTextField` (`singleLine = false` -> min height 88 dp; no `minLines` used so it cannot break on older Compose); `DeskPanel.kt` lost its private Material-based `PixelSwitch` and now uses the shared one (same call sites). Compile suspects: `Dialog` import (`androidx.compose.ui.window.Dialog`), `Modifier.size(width =, height =)`, `PixelCard` content lambda is not a `BoxScope` (I only used plain `Row`/`Column` inside it), `Pill(color = Pal.amber)`, unused param `onOpenSettings` in `HomeScreen` (was already unused). Also: `showAdd` is never set to true, so the hire dialog is unreachable (logged as T-HIRE). [untested]
- 2026-11-23 15:30  Claude  T-BACK2, T-SCREENS(Onboarding), T-FIELDS  PR #12 merged to main (commit 707e01d). Build APK run 45093107160 green, Backup guard run 45093107185 green. `AgoriaNav` lost `ScaffoldedRoutes`, floating "< Office" button, and `Box` wrapper (fixes Agent page overlap bug). `PixelTextField` added to `PixelUi.kt` (cream fill, 2 dp ink border, ink cursor). `OnboardingScreen` restyled: removed `RoundedCornerShape`/`clip`, Material `Button`/`OutlinedTextField`; uses `PixelButton` + `PixelTextField`. `grep -rn "RoundedCornerShape" app/src/main/java/com/agoria/app/ui/OnboardingScreen.kt` empty. [verified]
- 2026-11-23 14:15  Claude  T-BACK2  PR #11 merged to main (commit d15decc). Build APK run 45078557925 green, Backup guard run 45078557904 green. ApprovalsScreen converted to PixelScaffold; AgoriaNav ScaffoldedRoutes includes 'approvals'; auto-approve settings action in top bar; floating button hidden on Approvals page. [verified]
- 2026-11-23 12:45  Claude  T-BACK2  PR #10 merged to main (commit ca2ef1e). Build APK run 45072215229 green, Backup guard run 45072215197 green. AgentScreen converted to PixelScaffold; AgoriaNav ScaffoldedRoutes includes 'agent'; pause/resume action in top bar; floating button hidden on Agent page. [verified]
- 2026-11-23 11:30  Claude  T-BACK2  PR #9 merged to main (commit 2f638f2). Build APK run 45070684147 green, Backup guard run 45070684236 green. Home, Project, Connectors converted to PixelScaffold; AgoriaNav ScaffoldedRoutes updated; floating button hidden on these three pages. [verified]
- 2026-11-23 10:25  Claude  T-BACK  PR #8 merged to main (commit b6b7efb). Build APK run 45068973188 green, Backup guard run 45068973197 green. `PixelScaffold` + `PixelTopBar` compiled and CI passes. Diary & Browsers converted; AgoriaNav hides floating button on scaffolded routes. [verified]
- 2026-11-23 10:15  Claude  T-BACK  CODE WRITTEN (not compiled, not pushed: the chat's GitHub token was read-only, branch creation returned 403). Added `ui/components/PixelScaffold.kt` (`PixelScaffold`, `PixelTopBar`), converted `DiaryScreen` (new `onBack` param) and `BrowsersScreen`, `AgoriaNav` passes `popBackStack("office", false)` and hides the floating button on scaffolded routes. [untested]
- 2026-11-22 20:22  Claude  T-NAV, T-DIR, T-THEME, T-COMMON, T-SOUND, T-NIGHT, T-SEASON  PR #7 opened, Build APK run 45065987758 green, Backup guard run 45065987749 green. Fixed one compile error: `OfficeSound.setEnabled` platform declaration clash -> renamed to `setSoundEnabled`. All Phase 5 code compiles and CI passes. [verified]
- 2026-11-22  Claude  T-THEME, T-COMMON  CODE WRITTEN (not compiled): `AgoriaTheme.kt` is one fixed pixel scheme (ink/navy/cream/amber/teal, square shapes, monospace headings, no surface tint); `Common.kt` rewritten with the same public API (crisp square avatar showing the standing frame, solid ink-bordered chips, `PixelCard`). Per-screen leftovers (explicit RoundedCornerShape, Material buttons/switches/text fields) are T-SCREENS. [untested]
- 2026-11-22  Claude  T-NAV, T-DIR, T-SOUND, T-NIGHT, T-SEASON  CODE WRITTEN (not compiled): AgoriaNav has no bottom bar plus a floating "< Office" button; DirectoryPanel on the notice board and the title panel; amber page labels on every tappable area when zoomed in; OfficeSound (3 generated WAVs, mute in the directory); lamp glow at night; Christmas decor Sep 1 - Jan 6. [untested]
- 2026-11-22  Claude  P4  Wrote office-as-home, quick chat, camera fly-to, tint, hints, autonomy button (zip dunder-phase4-pixel-office.zip). [untested: not compiled]
- 2026-11-22  Kilo/owner  P3  PR #4 merged: renderer fixed, Phase 3 + runtime edits (`say`, prompt, `ask_user` fix). Build green, release v1.0.216. [verified; phone: map OK]
- 2026-11-21  Claude  P3  Agent-to-agent talk as diary `say` entries; speech bubbles; chatter panel. [untested at the time]
- 2026-11-21  Claude  P2  Generated pixel office (Python), y-sorted furniture, live signals, desk panel with safe auto-approve. [phone: layout OK]
- 2026-11-21  Claude  P1  First pixel-office renderer (waypoint graph, AI-generated background). The blurry background was replaced in P2. [verified]
- 2026-11-21  Kilo/owner  fix  Phase 2 branch had OfficeWorld.kt overwritten with screen code (map squashed). Fixed by replacing with the correct renderer. [verified; phone]
