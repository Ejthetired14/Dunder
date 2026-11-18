# App Performance & Build Download Improvements

## Priority 1: Add Direct Download for Builds
- [ ] Add `saveToDownloads()` in `Share.kt` using MediaStore API
- [ ] Add "Save to Device" button in `BuildsTab.kt` alongside "Share ZIP"
- [ ] Handle Android 10+ scoped storage correctly
- [ ] Show toast confirmation on success/failure

## Priority 2: Optimize Flow Collections
- [ ] Create `HomeScreenState` data class combining agents, diary, pendingApprovals, settings, runtimeState
- [ ] Add `homeScreenState` Flow in `AgoriaViewModel` using `combine()`
- [ ] Update `HomeScreen.kt` to use single combined state
- [ ] Create `AgentScreenState` for AgentScreen (messages, diary, tasks, builds, memories)
- [ ] Add `agentScreenState` Flow in ViewModel
- [ ] Update `AgentScreen.kt` to use single combined state
- [ ] Create `SettingsScreenState` for SettingsScreen
- [ ] Add `settingsScreenState` Flow in ViewModel
- [ ] Update `SettingsScreen.kt` to use single combined state
- [ ] Add `distinctUntilChanged()` to all combined flows

## Priority 3: Background Build Generation
- [ ] Move `BuildRunner.build()` to `Dispatchers.IO` in ViewModel
- [ ] Add `buildProgress` Flow in ViewModel (emits progress: "generating" → "writing" → "zipping" → "done")
- [ ] Add `activeBuild` state in ViewModel
- [ ] Show progress indicator in `BuildsTab.kt` during generation
- [ ] Add cancellation support for long-running builds

## Priority 4: Add Pagination
- [ ] Modify `BuildDao`: add `observePaged(agentId, limit, offset)` and `countForAgent(agentId)`
- [ ] Modify `DiaryDao`: add `observePaged(agentId, limit, offset)` and `countForAgent(agentId)`
- [ ] Modify `MessageDao`: add `observePaged(agentId, limit, offset)` and `countForAgent(agentId)`
- [ ] Update Repository with paged methods
- [ ] Add `BuildsPager` state in ViewModel (currentPage, hasMore, loadMore())
- [ ] Add "Load more" button in `BuildsTab.kt`, `DiaryTab.kt`, `ChatTab.kt`
- [ ] Add pagination to `AgentScreen` builds/diary/messages

## Priority 5: General Performance
- [ ] Add `LazyColumn` keys: `items(builds, key = { it.id })` everywhere
- [ ] Add `Modifier.consumeWindowInsets()` for edge-to-edge in main screens
- [ ] Enable `android.enableJetifier=true` (verify in gradle.properties)
- [ ] Profile and fix any recomposition issues with `key` parameters
- [ ] Add `android:hardwareAccelerated="true"` in Manifest (if not present)

---

## Implementation Order

1. **Priority 1** - Build download button (immediate UX improvement)
2. **Priority 2** - Flow optimization (biggest performance gain)
3. **Priority 3** - Background build generation (prevents UI freeze)
4. **Priority 4** - Pagination (scales for long-term use)
5. **Priority 5** - General polish

---

## Validation Checklist

- [ ] Build APK compiles without errors
- [ ] "Save to Device" saves ZIP to Downloads folder
- [ ] HomeScreen loads with single flow collection (verify in logs)
- [ ] AgentScreen loads with single flow collection
- [ ] SettingsScreen loads with single flow collection
- [ ] Build generation shows progress, doesn't freeze UI
- [ ] Pagination loads 20 items at a time, "Load more" works
- [ ] No visual regressions in UI
- [ ] APK size reasonable (< 50MB)

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| MediaStore API differences across Android versions | High | Test on API 29, 30, 33, 34+ |
| Flow combine breaking existing state | Medium | Use `distinctUntilChanged`, test each screen |
| Build cancellation leaving partial files | Low | Cleanup on cancellation in BuildRunner |
| Pagination offset causing duplicates | Medium | Use cursor-based or verify offset logic |

---

## Files to Modify

### New Files
- None

### Modified Files
- `Share.kt` - add `saveToDownloads()`
- `BuildsTab.kt` - add download button + progress UI
- `AgoriaViewModel.kt` - add combined state flows, pagination state, background build
- `HomeScreen.kt` - use combined flow
- `AgentScreen.kt` - use combined flow, add pagination UI
- `SettingsScreen.kt` - use combined flow
- `BuildRunner.kt` - add progress callbacks
- `Daos.kt` - add paged queries
- `Repository.kt` - add paged methods
- `DiaryTab.kt` - add pagination
- `ChatTab.kt` - add pagination