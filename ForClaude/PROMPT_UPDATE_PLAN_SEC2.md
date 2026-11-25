# Prompt for another AI: land the SEC-2 plan update

Copy everything below the line into the other AI session. It assumes the AI can read and write `Ejthetired14/Dunder` (or at least paste text into the GitHub web editor).

---

## Your task

You are updating the backend task tracker of the GitHub repository `Ejthetired14/Dunder` (Android/Kotlin, branch `main`). A session already implemented, verified and merged one P0 task. Your job is to record it in `ForClaude/CLAUDE_BACKEND_PLAN.md`. This is a **documentation-only** edit: do not change code, do not re-implement the fix, do not "clean up" other tasks.

## What happened (facts to record, nothing more)

- **SEC-2 - Snapshots/backups contain plaintext secrets** was implemented in PR **#46**, commit **`a745e3f`**, merged to `main` as **`2c05a71`**.
- Code that landed:
  - New `app/src/main/java/com/agoria/app/util/SnapshotSecrets.kt`: one redaction pass over a snapshot. It removes (1) connector fields `ConnectorCatalog` marks `secret = true`, (2) keys named like a credential (`...token`, `...secret`, `apiKey`, `clientSecret`, `authorization`, `webhookUrl`, ...), and (3) credentials inside `agent_skills[].config`, which is JSON stored as text. `maxTokens`/`tokensUsed` are deliberately not matched.
  - `Repository.exportSnapshot(includeSecrets: Boolean = false)` runs `SnapshotSecrets.redactSnapshot(root)` once before returning.
  - `writeAutoBackup()` calls `exportSnapshot(includeSecrets = false)` explicitly.
  - `importSnapshot()` merges an imported connector config into the stored one instead of overwriting it, so a secret-free snapshot keeps local credentials.
  - `saveConnectorConfig()` derives status from the catalog: a blank required field leaves the connector `needs_setup` instead of `configured`.
  - `app/src/test/java/com/agoria/app/util/SnapshotSecretsTest.kt` (5 cases) and `org.json:json:20240303` as a test-only dependency (`android.jar` only has stubs).
- Verification, exact:
  - Local: `./gradlew :app:testDebugUnitTest --no-daemon --no-parallel` -> BUILD SUCCESSFUL in 4m 1s; `SnapshotSecretsTest` 5/5, `UpdatePolicyTest` 7/7; `compileDebugKotlin` clean apart from two pre-existing warnings.
  - GitHub Actions: Build APK run **1092** success (compile + unit tests); Backup config guard run **45708625614** success.
  - Post-merge `main`: Build APK run **1093** success.
- Not done, and deliberately left as separate tasks: **SEC-16** (optional passphrase-encrypted "include secrets" export), **SEC-17** (encrypt `AgentEntity.apiToken` at rest), **UI-1** (the Settings backup card still says "Save encrypted JSON to the Downloads folder").
- Do **not** set SEC-2 to `DONE`: its "Done when" also requires the optional encrypted export and at-rest token encryption, which are SEC-16/SEC-17. `IN-PROGRESS` is correct.

## How to apply the edit

1. Read `ForClaude/PLAN_UPDATE_2026-11-25_SEC2.patch` in the same folder. It is a normal unified diff against `main` at `2c05a71`.
2. Preferred: from the repository root run `git apply ForClaude/PLAN_UPDATE_2026-11-25_SEC2.patch`.
3. No shell (GitHub web editor): apply the same `-`/`+` changes by hand; the patch has enough context around each hunk.
4. If `git apply --check` fails, the file has drifted. Do **not** force the patch. Re-read the current text, apply the same *meaning* using the surrounding lines as anchors, and say in your summary that it drifted.
5. The patch adds:
   - SEC-2 status + implemented/verified bullets (CI green on PR #46, run IDs above) and the note that `README.md` needed no edit while the Settings wording is UI-1.
   - **SEC-16** and **SEC-17** at the end of section 2, after SEC-15.
   - **UI-1** at the end of section 5, before DOC-1.
   - The README-drift row for "Export a JSON snapshot (without secrets)", the section 1 Util row, TEST-1, and section 9.
   - One Changelog row and one new Work log entry in section 11.
6. It must also normalise the SEC-2 status line: keep `IN-PROGRESS (2026-11-25)`, commit `a745e3f`, and the pointer to SEC-16/SEC-17.

## Hard rules

- Keep every task ID stable; never renumber.
- Never delete or rewrite existing Work log entries; append only.
- One changelog line and one work-log entry, exactly as in the patch.
- No secrets, tokens, credentials, keystore data or user data anywhere.
- Do not claim anything beyond the facts above: there was no device or UI run, there is no "include secrets" UI, and `apiToken` is still plaintext in Room.
- If your environment can only replace whole files and refuses the ~89 KB tracker: do **not** truncate it. First split section 11 into `ForClaude/WORK_LOG.md` (leave a link in section 11), note the split in the Changelog, then apply the update. That split is also the long-term fix for this file size.

## Done when

- `git diff --stat` shows only `ForClaude/CLAUDE_BACKEND_PLAN.md` changed (plus the patch/prompt files if you were asked to keep them).
- `grep -n "SEC-16\|SEC-17\|UI-1\|run 1092\|45708625614" ForClaude/CLAUDE_BACKEND_PLAN.md` finds all of them.
- SEC-2 reads `IN-PROGRESS`, not `DONE`.
- Commit message: `docs(plan): record SEC-2 (secret-free exports) in the backend tracker`.
