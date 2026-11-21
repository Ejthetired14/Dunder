# Gradle Build Configuration Fix Plan

## Goal
Make the build resilient (works in CI when Google Maven is down), modernize dependencies, and clean up tech debt.

## Version Compatibility Matrix (Verified)

| Component | Current | Target | Notes |
|-----------|---------|--------|-------|
| AGP | 8.2.2 | **8.4.0** | 8.5.x requires Gradle 8.7+; 8.4.0 works with Gradle 8.5 |
| Kotlin | 1.9.20 | **2.0.21** | Requires AGP 8.3+ |
| Compose Compiler | 1.5.14 | **1.5.14** | Compatible with Kotlin 2.0.x |
| Compose BOM | 2023.08.00 | **2024.10.00** | Major version jump - see breaking changes below |
| Room | 2.6.0 | 2.6.1 | Compatible with KSP 2.x |
| KSP | 1.9.20-1.0.13 | **2.0.21-1.0.25** | Must match Kotlin version |
| security-crypto | 1.1.0-alpha06 | **1.1.0** | Stable released |
| OkHttp | 4.12.0 | 4.12.x | No breaking changes |
| Coroutines | 1.7.3 | 1.8.x | Compatible |

**Key constraint:** Gradle wrapper is 8.2.1 (from `gradle-wrapper.properties`). AGP 8.4.0 requires Gradle 8.5+. **Must upgrade Gradle wrapper first.**

## Breaking Changes: Compose BOM 2023.08 → 2024.10
- `Material3` APIs renamed/moved (e.g., `Surface` → `MaterialSurface`, `Typography` changes)
- `ModalBottomSheet` → `ModalBottomSheet` (behavior changes)
- `TextField` / `OutlinedTextField` consolidated
- `rememberLazyListState` → `rememberLazyListState` (same)
- Most migrations are mechanical (IDE auto-fix available)

## Tasks (ordered by dependency)

### 0. Upgrade Gradle Wrapper (Prerequisite)
**File:** `gradle/wrapper/gradle-wrapper.properties`
- `distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip`
- Run `./gradlew wrapper --gradle-version 8.5` to update

### 1. Add Mirror Repositories (Critical for CI)
**File:** `settings.gradle.kts`
- Add mirrors **before** `google()` in both `pluginManagement.repositories` and `dependencyResolutionManagement.repositories`:
```kotlin
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
```
- Keep `google()` as last resort fallback
- Apply to both plugin and dependency resolution

### 2. Create Version Catalog
**New file:** `gradle/libs.versions.toml`
- Centralize all versions (AGP, Kotlin, Compose BOM, Room, KSP, OkHttp, Coroutines, etc.)
- Define plugins and libraries sections
- Enable in `settings.gradle.kts`: `enableFeaturePreview("VERSION_CATALOGS")`

### 3. Upgrade Dependency Stack (Single Commit)
**Files:** `build.gradle.kts` (root), `app/build.gradle.kts`, `gradle/libs.versions.toml`
- Update all versions per matrix above
- Migrate `app/build.gradle.kts` to use version catalog references (`libs.androidx.compose.bom`, etc.)
- Update KSP to 2.0.21-1.0.25 (matches Kotlin 2.0.21)
- Verify `security-crypto:1.1.0` has required APIs (MasterKeys, EncryptedSharedPreferences)

### 4. Enable Release Optimizations
**File:** `app/build.gradle.kts`
- `buildTypes.release.isMinifyEnabled = true`
- `buildTypes.release.isShrinkResources = true`
- Keep default `proguard-android-optimize.txt`
- Add `-keep` rules only if reflection/serialization breaks

### 5. Remove Debug Artifact
**File:** `build.gradle.kts` (root)
- Delete `printTruststore` task

### 6. Verify CI Works
**File:** `.github/workflows/build-apk.yml`
- No changes needed if settings.gradle.kts mirrors work
- Keep manual SDK install + retry logic

## Validation Checklist
1. `./gradlew wrapper --gradle-version 8.5` updates wrapper successfully
2. `./gradlew :app:compileDebugKotlin` passes (validates Kotlin/Compose/KSP compatibility)
3. `./gradlew assembleDebug` succeeds locally
4. `./gradlew assembleRelease` produces optimized APK (verify size reduction)
5. `./gradlew :app:lint` passes (no new warnings from upgrades)
6. CI build passes on tag push
7. Test mirror fallback: temporarily block `dl.google.com`/`maven.google.com` in `/etc/hosts` and verify build still works

## Rollback Plan
- Each task in separate commit for easy `git revert`
- Tag `pre-gradle-fix` before starting
- If Compose BOM migration breaks UI: revert Task 3, keep Tasks 0,1,2,4,5

## Out of Scope
- Full dependency audit beyond listed upgrades
- ProGuard rule tuning beyond defaults
- Migrating to Kotlin DSL for version catalog (using TOML catalog)
- Jetifier removal (not needed with `android.enableJetifier=false` in gradle.properties)

## Open Questions
1. **Gradle 8.5 vs 8.7**: AGP 8.4 needs 8.5, AGP 8.5 needs 8.7. Staying at 8.4.0 is safer.
2. **Compose migration effort**: Estimate 2-4 hours for mechanical fixes. Acceptable?
3. **security-crypto 1.1.0**: Verify `MasterKeys.AES256_GCM_SPEC` and `EncryptedSharedPreferences` APIs unchanged.