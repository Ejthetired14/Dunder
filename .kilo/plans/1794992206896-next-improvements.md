# Next Improvements Plan

## Current State Analysis

**What's implemented:**
- Basic app: agents, diary, messages, tasks, builds, approvals, connectors, providers, revenue
- GitHub Actions workflow builds APKs & creates releases (v1.0.8 live)
- BuildRunner runs on `Dispatchers.IO`
- Individual flow collection per screen (no combined flows)
- BuildsTab: only "Share ZIP" button
- Edge-to-edge in MainActivity
- Monetization: RevenueEntity, Stripe keys, 50/50 profit split, infrastructure fund cap

**What's missing (from conversation summary claims vs reality):**
| Feature | Claimed | Actual |
|---------|---------|--------|
| Combined flows (Home/Agent/Settings) | ✅ Done | ❌ Not in ViewModel |
| Build progress UI | ✅ Done | ❌ Not in BuildsTab |
| "Save to Device" button | ✅ Done | ❌ Only "Share ZIP" exists |
| Pagination (builds/diary/messages) | ✅ Done | ❌ No paged DAO queries |
| LazyColumn keys | ✅ Done | ❌ Using `forEach` not `items(key=)` |

---

## Priority 1: Combined Flow States (Performance)

**Goal:** Reduce redundant DB queries & recompositions by combining per-screen flows.

### Tasks
- [ ] Add `HomeScreenState` data class in ViewModel combining: agents, latestDiary(5), pendingApprovals, settings, runtimeState
- [ ] Add `homeScreenState` Flow using `combine()` + `distinctUntilChanged()` + `stateIn(WhileSubscribed)`
- [ ] Update `HomeScreen.kt` to use single `homeScreenState` collection
- [ ] Add `SettingsScreenState` combining: settings, providers, revenue
- [ ] Add `settingsScreenState` Flow in ViewModel
- [ ] Update `SettingsScreen.kt` to use single collection
- [ ] Add `AgentScreenState(agentId)` function combining: messages, diary, tasks, builds, memories
- [ ] Add `agentScreenState(agentId)` Flow in ViewModel
- [ ] Update `AgentScreen.kt` to use single collection

**Files:** `AgoriaViewModel.kt`, `HomeScreen.kt`, `SettingsScreen.kt`, `AgentScreen.kt`

---

## Priority 2: Build Downloads & Progress UI

**Goal:** Users can save build ZIPs to Downloads folder + see generation progress.

### Tasks
- [ ] Add `saveToDownloads(context: Context, file: File, fileName: String): Boolean` to `Share.kt` using MediaStore API (API 29+)
- [ ] Add `BuildProgress` sealed interface in `BuildRunner.kt`:
  - `Generating(stage: String)`
  - `WritingFiles(written: Int, total: Int)`
  - `Zipping(progress: Float)`
  - `Done`
  - `Error(message: String)`
- [ ] Modify `BuildRunner.build()` to accept `onProgress: (BuildProgress) -> Unit` callback
- [ ] Emit progress at each stage: contacting model → writing files → zipping → done
- [ ] Expose `buildProgress: StateFlow<BuildProgress?>` in `AgentRuntime` → `AgoriaViewModel`
- [ ] Update `BuildsTab` to show progress banner (spinner + stage text + linear progress for WritingFiles)
- [ ] Add "Save to Device" button next to "Share ZIP" in `BuildsTab`
- [ ] Show toast on save success/failure

**Files:** `Share.kt`, `BuildRunner.kt`, `AgentRuntime.kt`, `AgoriaViewModel.kt`, `AgentScreen.kt` (BuildsTab)

---

## Priority 3: Pagination for Builds, Diary, Messages

**Goal:** Handle large datasets without loading everything into memory.

### Tasks
- [ ] Add paged queries to DAOs:
  - `BuildDao.observePagedForAgent(agentId, limit, offset)` + `countForAgent(agentId)`
  - `DiaryDao.observePagedForAgent(agentId, limit, offset)` + `countForAgent(agentId)`
  - `MessageDao.observePagedForAgent(agentId, limit, offset)` + `countForAgent(agentId)`
- [ ] Add Repository methods: `buildsForAgentPaged()`, `diaryForAgentPaged()`, `messagesForAgentPaged()` + count methods
- [ ] Add `BuildsPager` state in ViewModel: `builds`, `currentPage`, `hasMore`, `isLoading`
- [ ] Add `loadMoreBuilds(agentId)` function in ViewModel
- [ ] Update `BuildsTab` to use `LazyColumn` with `items(key={it.id})` + "Load more" button at bottom
- [ ] Repeat for `DiaryTab` and `ChatTab` (messages)

**Files:** `Daos.kt`, `Repository.kt`, `AgoriaViewModel.kt`, `AgentScreen.kt` (BuildsTab, DiaryTab, ChatTab), `DiaryScreen.kt`

---

## Priority 4: LazyColumn Keys & List Optimizations

**Goal:** Proper item identity for efficient recomposition & scrolling.

### Tasks
- [ ] Update all `LazyColumn`/`LazyRow` to use `items(list, key = { it.id })`
- [ ] Targets: `HomeScreen` (agents LazyRow, diary LazyColumn), `AgentScreen` (BuildsTab, DiaryTab, TasksTab, MemoryTab, ChatTab), `DiaryScreen`, `OfficeScreen`
- [ ] Add `key` to `OfficeScreen` agent cards if using LazyRow

**Files:** `HomeScreen.kt`, `AgentScreen.kt`, `DiaryScreen.kt`, `OfficeScreen.kt`

---

## Priority 5: General Polish

**Goal:** Better UX, accessibility, robustness.

### Tasks
- [ ] Add pull-to-refresh in `HomeScreen`, `AgentScreen` (diary/messages), `DiaryScreen`
- [ ] Improve empty states with illustrations/actions
- [ ] Add haptic feedback on primary buttons
- [ ] Add build cancellation support (check `isActive` in BuildRunner)
- [ ] Fix `continue-on-error: true` in workflow artifact uploads (quota hit anyway)
- [ ] Migrate workflow to `actions/setup-java@v5` (deprecation warning)
- [ ] Add `ktlint` + `detekt` to CI
- [ ] Unit tests for ViewModel combined flows

**Files:** Various screens, `.github/workflows/build-apk.yml`, `build.gradle.kts`

---

## Implementation Order

1. **Priority 1** (Combined Flows) — Biggest performance win, unblocks screen-level optimization
2. **Priority 2** (Build Downloads + Progress) — High user visibility, unblocks testing
3. **Priority 3** (Pagination) — Scales for long-term use
4. **Priority 4** (LazyColumn Keys) — Quick wins, low risk
5. **Priority 5** (Polish) — Ongoing

---

## Validation Checklist

- [ ] HomeScreen loads with single flow collection (verify in logs)
- [ ] AgentScreen loads with single flow collection per agent
- [ ] SettingsScreen loads with single flow collection
- [ ] "Save to Device" saves ZIP to Downloads folder (API 29+)
- [ ] Build generation shows progress banner, doesn't freeze UI
- [ ] Pagination loads 20 items at a time, "Load more" works
- [ ] LazyColumn keys prevent item flicker on updates
- [ ] No visual regressions
- [ ] APK builds successfully in CI

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| MediaStore API differences across Android versions | High | Test on API 29, 30, 33, 34+; use `Environment.DIRECTORY_DOWNLOADS` |
| Flow combine breaking existing state | Medium | Use `distinctUntilChanged`, test each screen independently |
| Build cancellation leaving partial files | Low | Cleanup on cancellation in BuildRunner |
| Pagination offset causing duplicates | Medium | Use cursor-based or verify offset logic with `countForAgent` |
| Combined flow initial load latency | Low | `stateIn(WhileSubscribed(5000))` keeps warm for 5s |

---

## Files to Modify

### New Files
- None

### Modified Files
- `AgoriaViewModel.kt` — combined flows, pagination state, build progress
- `HomeScreen.kt` — use homeScreenState
- `SettingsScreen.kt` — use settingsScreenState
- `AgentScreen.kt` — use agentScreenState, BuildsTab progress + download + pagination
- `DiaryScreen.kt` — pagination + LazyColumn keys
- `BuildRunner.kt` — BuildProgress callback
- `AgentRuntime.kt` — expose buildProgress
- `Share.kt` — saveToDownloads
- `Daos.kt` — paged queries
- `Repository.kt` — paged methods
- `.github/workflows/build-apk.yml` — fix deprecation warnings
- `build.gradle.kts` — add ktlint/detekt