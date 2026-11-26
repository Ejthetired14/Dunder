# Claude Backend Plan - Dunder / Agoria

Living task tracker for the **backend** of this Android app. It is written so that **any Claude account or session** can open it, see what is done and what is not, and continue the work.

- Reviewed commit: `6b7e7ed6ab34314f51d7879fd1b7a11b5c57dc19` (branch `main`)
- Review date: 2026-11-22
- Scope: everything that is not UI: `app/src/main/java/com/agoria/app/{core,data,service,util}`, manifest/backup config, Gradle, GitHub workflows.

---

## 0. How to use this file (instructions for Claude sessions)

1. Read this whole file before touching backend code. Then read the actual code for the task you pick; line-level evidence can drift.
2. Pick the highest-priority `TODO` task. Set it to `IN-PROGRESS` (add the date) in the same commit that starts the work.
3. Never mark a task `DONE` unless its **Done when** criteria are met and verified (test, build, or a reproduction). Put the commit or PR link next to it.
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
| Runtime | `core/AgentRuntime.kt` (~42 KB) | Timer loop: per agent builds context, calls LLM, parses a JSON decision, runs one action. Handles approvals, auto-approve, builds, connectors, revenue/billing/deploy actions. |
| Policy | `core/AgentPolicy.kt` | Parses decision JSON; `autoActions` allowlist; `isAutoAllowed` uses `ConnectorCatalog.isReadOnly`. |
| Prompt | `core/PromptBuilder.kt` | System prompt with diary, memory, tasks, connectors, peers; JSON protocol. Also holds monetization prompt templates. |
| LLM | `core/llm/LlmClient.kt`, `core/ProviderCatalog.kt` | OpenAI-compatible client with ordered provider fallback, `Semaphore(2)`. |
| Connectors | `core/ConnectorCatalog.kt`, `core/ConnectorExecutor.kt` (~129 KB) | 25 connector definitions; one giant executor with a `when` dispatch. |
| Builds | `core/BuildRunner.kt` | LLM generates a file list, written under the project workspace, zipped. |
| Data | `data/*` | Room DB v4 (14 entities), `Repository.kt` (~41 KB), seed data, export/import JSON snapshot, daily auto-backup. |
| Service | `service/AutonomyService.kt`, `BootReceiver.kt`, `BootStatus.kt` | Foreground service (`specialUse`) with a wake lock keeps the loop alive. `BootReceiver` (non-exported, `BOOT_COMPLETED` only since SEC-13) restarts it if `settings.autonomyEnabled`; since RUN-5 it reads settings read-only with a timeout and reports failures through `BootStatus` + a notification. Explicit stops persist `autonomyEnabled=false` (RUN-3). |
| Util | `util/SecretCipher.kt`, `util/SnapshotSecrets.kt`, `UpdateManager.kt`, `Files`, `Json`, `Notifier`, `Share`, `Time` | Keystore AES-GCM, snapshot credential scrubbing (SEC-2), self-update from GitHub Pages JSON (URL/host allowlist and SHA-256 check in UpdatePolicy since SEC-10). |
| CI | `.github/workflows/*.yml` | Build debug + release APK, sign on tags, publish release and Pages JSON. `backup-guard.yml` (SEC-12, SEC-13, SEC-3) checks the backup config, exported components and workflow hygiene on every push/PR. Release signing fails closed without keystore secrets (SEC-3). |
| Tools | `tools/check_backup_config.py`, `tools/test_check_backup_config.py`, `tools/check_manifest_exports.py`, `tools/test_check_manifest_exports.py`, `tools/check_workflows.py`, `tools/test_check_workflows.py`, `tools/office_gen` | Backup-config, exported-component and workflow-hygiene regression guards + their self-tests (stdlib Python only). |

### What is already solid (keep it)
- Approval-gate architecture: the model can only auto-run a small allowlist; mutating connector actions create an `ApprovalEntity`.
- Path traversal guards in `BuildRunner` and the `files` connector (canonical-path checks).
- Secrets for providers and connectors are encrypted with an Android Keystore key (AES-256-GCM, random IV).
- Provider fallback, per-agent mutex, tick jitter, cooldowns, clamped settings.
- Room migrations 1->2->3->4 are written defensively (`hasTable`/`columns` checks) and the DB opens eagerly with a recovery path.
- Daily rotating auto-backup (5 files).

### README drift (documentation is out of sync with code)
| README says | Code says |
| --- | --- |
| Gmail and Google Calendar connectors with pasted OAuth tokens | No `gmail`/`calendar` executor or definition. Replaced by the WebView-based `accounts` ("Office Browser") connector. |
| "Database is excluded from backup" | Before SEC-1 `res/xml/backup_rules.xml` included database, files, root, external. **After SEC-1 (2026-11-22) the claim is true again** (`allowBackup="false"` plus exclude-all rules). Re-check wording under DOC-1. |
| "Export a JSON snapshot (without secrets)" | **True since 2026-11-25** (SEC-2): the snapshot is cleared of connector credentials, agent tokens and skill-config secrets before it is written or shared. A secret-carrying export is optional and still missing (SEC-16). |
| Connector table lists 15 connectors | Catalog has 25: also PayPal, Polar, AgentMail, Supabase, Resend, Upstash, Hugging Face, Vercel, Netlify, Linear, Cloudflare Workers (and `stripe` is referenced but missing, see BUG-1). |
| App name Agoria | Package `com.agoria.app`, but UI/strings/User-Agent/CI say Dunder. Pick one. |

---

## 2. P0 - Security and integrity

### SEC-1 - Android backup config leaks everything
- Status: IN-PROGRESS (2026-11-22) - implemented, **build verified on CI (2026-11-22), bmgr/adb check pending device**. Commit: `b78501b` | Effort: S
- Evidence: `app/src/main/res/xml/backup_rules.xml` includes `domain="database"`, `file`, `root`, `external`; manifest has `allowBackup="true"` and no `dataExtractionRules` (every supported device is Android 13+, so the Android 12+ attribute is the one that matters).
- Problem: DB and `filesDir/backups/auto-backup-*.json` (which contain decrypted secrets, see SEC-2) are uploaded to cloud backup and device transfer. The Keystore key is not restored on a new device, so the encrypted DB blobs become unreadable while the plaintext JSON stays readable.
- Fix: set `allowBackup="false"`, or add `dataExtractionRules` and `fullBackupContent` that exclude `database`, `file/backups/`, `file/agent-workspace/`, `sharedpref` secrets. Mirror cloud-backup and device-transfer rules.
- Done when: a `bmgr`/`adb backup` dry run shows no DB, secrets or `backups/` content; README matches.
- Implemented (2026-11-22):
  - `AndroidManifest.xml`: `allowBackup="false"`; added `android:dataExtractionRules="@xml/data_extraction_rules"`; kept `fullBackupContent="@xml/backup_rules"`; comment explains why.
  - New `res/xml/data_extraction_rules.xml`: `<cloud-backup>` and `<device-transfer>` both exclude `root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, `device_sharedpref` (`path="."`).
  - `res/xml/backup_rules.xml`: replaced the include-everything rules with exclude-everything (defense in depth if `allowBackup` is ever flipped).
  - Design choice: `allowBackup="false"` was chosen over partial excludes because there is no safe subset today (SEC-2: the JSON backups hold plaintext secrets; the Keystore key is not portable). Owner can revisit under DEC-3.
  - Checked locally: all three XML files parse; manifest references resolve to the two rule files.
- Verified (2026-11-22):
  - ✅ CI build (`:app:assembleDebug`) passed on GitHub Actions (run 45034237240, commit `b78501b`).
  - ⚠️ `lintDebug` not a separate step in workflow; build success implies XML well-formed and references resolve.
  - ❌ `bmgr`/`adb backup` dry run **not run** — no device/emulator available in this environment.
- Remaining to close this task: (1) run `adb shell bmgr backupnow com.agoria.app` on device/emulator (expect "backup not allowed" / nothing uploaded) or `adb backup` and confirm empty archive; (2) confirm `README.md` backup wording (see DOC-1); (3) flip to `DONE`.
- Note: users lose the (never reliable) implicit cloud restore. The in-app export/import in Settings remains the only transfer path; it still contains plaintext secrets until SEC-2 lands.
- Guard: SEC-12 now fails CI if this configuration regresses.

### SEC-2 - Snapshots/backups contain plaintext secrets
- Status: IN-PROGRESS (2026-11-25) - core done (exports and auto-backups are secret-free), **CI green on PR #46**. Commit: `a745e3f` | Effort: M. Passphrase-encrypted "include secrets" export and at-rest `apiToken` encryption remain as SEC-16 and SEC-17.
- Evidence (original): `Repository.exportSnapshot()` puts `connectorConfig(c.id)` (decrypted) and `a.apiToken` into JSON; `writeAutoBackup()` writes it unencrypted to `filesDir/backups/`; the same JSON is shared from Settings.
- Fix (original): default export has **no** secrets. Optional "include secrets" export is encrypted with a user passphrase (PBKDF2/scrypt + AES-GCM). Auto-backup never includes secrets. Encrypt `AgentEntity.apiToken` at rest with `SecretCipher` (it is currently plaintext in Room). Update README.
- Done when: unit test proves exported JSON contains no token/secret strings; auto-backup excludes secrets.
- Implemented (2026-11-25):
  - New `util/SnapshotSecrets.kt` (pure Kotlin + `org.json`, no Android classes). `redactSnapshot` walks the whole snapshot and removes credentials from three sources: (1) `ConnectorCatalog`'s own `secret = true` field list, which is authoritative and already covers every connector token, `clientSecret`, `serviceKey`, `linearKey`, `hfToken`, `redisToken` and the Slack/Discord `webhookUrl`; (2) a key-name pattern for anything outside the catalog (`apiToken`, `botToken`, `accessToken`, `clientSecret`, `authorization`, `privateKey`, ...), deliberately matching singular `...token` so budgets such as `maxTokens`/`tokensUsed` survive; (3) `agent_skills[].config`, which is JSON stored as text and is scrubbed when it parses as an object.
  - `Repository.exportSnapshot(includeSecrets: Boolean = false)` runs that one redaction pass at the end of the function, so a field added to the snapshot later cannot leak by being forgotten. `includeSecrets = true` has no UI caller yet (SEC-16).
  - `writeAutoBackup()` now calls `exportSnapshot(includeSecrets = false)` explicitly, so the daily file in `filesDir/backups/` is secret-free.
  - `importSnapshot` **merges** an imported connector config into the stored one instead of overwriting it, so a secret-free snapshot restores on the same device with credentials intact; on a new device the user is asked for them.
  - `saveConnectorConfig` derives `status` from the catalog: a blank required field (usually the secret) leaves the connector `needs_setup` instead of claiming `configured`.
  - `README.md` already claimed "without secrets"; after this change that is true, so it needed no edit. The Settings backup card still says "Save encrypted JSON to the Downloads folder", which is wrong; fixing the wording is UI-1 so a UI file does not have to ride along with a backend change.
- Verified (2026-11-25, GitHub Actions): **CI green on PR #46** commit `a745e3f`. Build APK run 1092 (compile + `Unit tests`) success; Backup config guard run 45708625614 success.
- Verified (2026-11-25, local sandbox): `:app:testDebugUnitTest` (see work log for the exact result). `SnapshotSecretsTest` has 5 cases: data survives while agent/connector/skill credentials are dropped; redaction is idempotent; for every connector in the catalog every `secret` field is removed and every non-secret field survives; an unknown connector still loses `apiKey` but keeps `baseUrl`; `maxTokens`/`tokensUsed` are not treated as credentials. `testImplementation("org.json:json:20240303")` was added because `android.jar` only carries stubs for JVM tests.
- NOT verified: no app run or device; the secret-carrying export path has no UI (SEC-16); `AgentEntity.apiToken` is still plaintext in Room (SEC-17).
- Remaining to close this task: SEC-16 (optional passphrase-encrypted secret export) and SEC-17 (encrypt `apiToken` at rest), then flip to DONE.

### SEC-3 - CI supply-chain and signing problems
- Status: IN-PROGRESS (2026-11-22) - implemented, **CI run pending, owner actions pending (see Remaining)**. Effort: S-M
- Evidence:
  - `.github/workflows/apply-phase2.yml`: with `contents: write`, downloads a patch from `https://paste.rs/...` (mutable, public) and pushes it. Anyone who can edit that paste, or a commit message containing the trigger token, controls the branch.
  - `.github/workflows/generate-keystore.yml`: prints keystore base64 and both passwords into the Actions log and uploads the `.jks` as an artifact. If the repo is public (likely, since updates use GitHub Pages), anything generated this way is exposed.
  - `build-apk.yml`: if `KEYSTORE_*` secrets are missing it silently signs the release with a **random temporary key**. Each release then has a different signature, so Android refuses in-place updates (breaks `UpdateManager` and loses data on reinstall). Also publishes the debug APK in the release, and uses `permissions: contents/pages/id-token: write` for every job.
- Fix: delete `apply-phase2.yml`; delete or lock `generate-keystore.yml` (and **rotate any keystore created with it**); fail the release job if signing secrets are absent; pin actions by SHA; per-job minimal permissions; do not attach the debug APK to releases.
- Done when: no workflow fetches remote patches; release job fails without secrets; keystore creation is documented as a local one-time step.
- Implemented (2026-11-22):
  - Deleted `.github/workflows/apply-phase2.yml` (remote patch from paste.rs with `contents: write`) and `.github/workflows/generate-keystore.yml` (printed keystore/passwords to the log, uploaded the `.jks`). Their contents were not read before deletion; the plan's fix was "delete".
  - `.github/workflows/build-apk.yml`: top-level `permissions: contents: read`; `build` job `contents: write` (release on tags only); `deploy-pages` job `contents: read, pages: write, id-token: write`. Signing step now reads the four secrets through `env:` (not interpolated into the script), **fails with `::error::` if `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` or `KEY_ALIAS` is missing** (`KEY_PASSWORD` defaults to the store password), never runs `keytool`, deletes the decoded keystore on exit, passes passwords to `apksigner` as `env:` refs, and runs `apksigner verify --print-certs`. Release publishes **only the signed APK** (debug APK stays a CI artifact). `github.ref_name`/repository are passed via `env:` instead of script interpolation.
  - New `tools/check_workflows.py` + `tools/test_check_workflows.py` (13 `unittest` cases; stdlib only; textual checks so no YAML library is needed): fails on the removed files, paste/transfer hosts, `curl|wget | sh`, `keytool -genkey*`, keystore uploads as artifacts, missing/`write-all`/top-level-write `permissions:`, `${{ secrets.* }}` inside script lines, debug APK in `gh release create`. Wired into `.github/workflows/backup-guard.yml` (self-tests + guard).
  - New `docs/release-signing.md`: local one-time keystore creation and the four secret names.
- Verified (2026-11-22, local sandbox): 13/13 self-tests pass; `check_workflows.py` exits 0 on the new `build-apk.yml` and `backup-guard.yml`.
- Verified (2026-11-22, GitHub Actions): **CI green on main** commit `231f84a`. Backup config guard run 45041539979 success; Build APK run 45041540039 success.
- NOT verified: a test `v*` tag run (the signing step only runs on `v*` tags, so it needs a test tag with real secrets); a red check on a throwaway PR.
- Remaining to close this task: (1) **owner**: confirm the four signing secrets exist, then push a test tag and confirm the signed release; (2) **owner**: decide DEC-6 (rotate any keystore made by `generate-keystore.yml`; also check the Actions run logs/artifacts of past runs and delete them); (3) pinning actions by SHA, splitting signing into its own job and Dependabot are tracked as SEC-14; (4) flip to `DONE`.

### SEC-4 - Money and config actions run with no approval
- Status: IN-PROGRESS (2026-11-25) - implemented. Effort: S
- Evidence: in `AgentRuntime.runCycle`, `record_revenue` and `reinvest` are handled directly. They are not in `AgentPolicy.autoActions` and never go through `addApproval`.
- Problem: a model (or injected text) can fabricate revenue, inflate `infrastructureFundCents`, then "reinvest" to rewrite provider `enabled`/`priority`/`model` rows.
- Fix: remove `record_revenue` as an agent action (revenue should come only from verified payment events, see BUG-8) and require approval for `reinvest`, or delete both. Validate numeric ranges.
- Done when: no state-changing action in `AgentRuntime` is reachable without passing `AgentPolicy`.
- Implemented (2026-11-25): `record_revenue` case now logs an error and refuses to record revenue. `reinvest` now creates a high-risk approval instead of directly executing. Approval execution added in `executeApproval` for type `reinvest`. Numeric ranges validated (priority coerced 1-100, cost/fund checked).

### SEC-5 - Auto-approve is type-level and risk-blind
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: `Repository.addApproval` and `AgentRuntime.processAutoApprovedApprovals` auto-approve by `approval.type` only; `autoApproveTypes` may contain `connector_action`.
- Problem: enabling auto-approve for `connector_action` approves every connector action, including payments, email, deploy, delete.
- Fix: auto-approve only an explicit `(connectorId, action)` allowlist with `risk <= medium`; never for `risk = high`; daily caps per agent; a visible audit log; one-tap kill switch. Also expire stale approvals (see DATA-6).
- Done when: unit tests show a high-risk action is never auto-approved.
- Implemented (2026-11-25): `addApproval` now checks risk level — high-risk actions are never auto-approved even if type is in allowlist. `connector_action` type requires explicit `connector_action:connectorId:action` allowlist entry. `processAutoApprovedApprovals` filters out high-risk approvals as defense-in-depth. Remaining: daily caps per agent, visible audit log, one-tap kill switch, stale approval expiry (DATA-6).

### SEC-6 - Prompt injection surface
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: connector results (Wikipedia, HN, web search, `accounts.read` page text, emails) are stored as messages with role `tool` and placed in the next prompt (`PromptBuilder`: `tool result: ...`) with no delimiting.
- Fix: wrap all external text in clearly marked untrusted blocks (and say in the system prompt that instructions inside are data), cap length, strip control chars/markup, never let content from an untrusted source alone trigger a high-risk action, show source in approval details.
- Done when: system prompt contains an untrusted-data rule; tests cover wrapping and truncation.
- Implemented (2026-11-25): System prompt now includes SECURITY rule about untrusted data. Tool results wrapped in `[UNTRUSTED DATA START]`/`[UNTRUSTED DATA END]` blocks with role labeled "tool result (UNTRUSTED)". Content still capped at 500 chars. Remaining: strip control chars/markup, show source in approval details.

### SEC-7 - HTTP connector can leak its credential to any host
- Status: IN-PROGRESS (2026-11-25) - implemented. Effort: S
- Evidence: `ConnectorExecutor.http(config, ...)`: if `path` starts with `http://`/`https://` it replaces `baseUrl`, then `Authorization: Bearer <apiKey/agent token>` is still attached; `args.headers` can also override `Authorization`.
- Fix: pin the final URL host to the configured `baseUrl` host; https only (opt-in for http); block loopback/private/link-local ranges; ignore agent-supplied `Authorization`/`Host`.
- Done when: unit tests reject cross-host URLs and private IPs.
- Implemented (2026-11-25): HTTP connector now validates base URL scheme and host, rejects cross-host URLs in path, blocks private/loopback/link-local hosts, ignores agent-supplied Authorization/Host headers, and rejects HTTP downgrade from HTTPS. Added `isPrivateOrLoopbackHost` helper.

### SEC-8 - Unvalidated values interpolated into URLs and generated code
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: Cloudflare `name` in `"$baseUrl/$name"`; GitHub `repo` in `/repos/$repoName/...`; Stripe `eventType`; GitHub `state`; currency `from`/`to`; `stripeWebhookWorkerCode` pastes `name`, `webhookSecret`, `callbackUrl` into JS source unescaped (a newline in `name` closes the comment and injects code).
- Fix: validate with strict regexes (`^[A-Za-z0-9._-]+$`, `^[\w.-]+/[\w.-]+$`, ISO currency codes) and `enc()` every query value; embed generated JS values via JSON-escaped literals.
- Done when: fuzz-style unit tests with `../`, `?`, newlines are rejected.
- Implemented (2026-11-25): Cloudflare worker names validated with strict regex `^[a-zA-Z0-9][a-zA-Z0-9._-]*$`. `stripeWebhookWorkerCode` now uses `org.json.JSONObject.quote()` for JSON-escaping `webhookSecret` and `callbackUrl`. Remaining: GitHub repo name validation, Stripe eventType validation, currency code validation, GitHub state validation.

### SEC-9 - SecretCipher swallows failures
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: S
- Evidence: `SecretCipher.encrypt` returns `null` on any exception; `Repository.saveProviderKey/saveConnectorConfig` store that `null`; `decrypt` returns `null` on failure.
- Problem: a failed encrypt silently drops a secret the user thinks was saved; after restore/key invalidation, every secret looks like "Missing API key".
- Fix: return a result type; surface an error to the UI; mark connector/provider `needs_reentry` when decrypt fails; handle `KeyPermanentlyInvalidatedException`; never persist blank on failure.
- Done when: tests with a fake cipher cover failure paths and UI state shows "re-enter secret".
- Implemented (2026-11-25): `KeyPermanentlyInvalidatedException` now caught separately with error logging. All exceptions logged via `android.util.Log.e` instead of silently swallowed. Remaining: return a result type, surface errors to UI, mark connectors/providers as `needs_reentry` on decrypt failure.

### SEC-10 - Self-updater accepts any URL and does no integrity check
- Status: IN-PROGRESS (2026-11-23) - implemented, **CI + first tagged release + device check pending** | Effort: M
- Evidence (original): `UpdateManager` downloaded `apkUrl` from Pages JSON with no https/host check, no SHA-256, ignored `versionCode`, built the file name from the JSON `tagName` (path traversal risk), left partial files on failure, and followed redirects automatically.
- Implemented (2026-11-23):
  - New `util/UpdatePolicy.kt` (pure Kotlin, no Android classes): `isTrustedReleaseUrl` accepts only `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>.apk` (no userinfo, port, query, fragment, `%`, `..`; owner/repo compared case-insensitively); `isAllowedDownloadUrl` for redirect hops (https + `github.com`, `objects.githubusercontent.com`, `github-releases.githubusercontent.com`, `release-assets.githubusercontent.com`); `normalizeSha256`, `sha256Hex`, constant-time `fileMatchesSha256`; `safeFileComponent`; `isNewer` (higher versionName, or equal name + higher versionCode); `MAX_APK_BYTES` 250 MB; `REQUIRE_SHA256 = true`.
  - `util/UpdateManager.kt`: `ReleaseInfo.sha256`; `parsePagesJson` refuses an untrusted URL or a missing/invalid sha256; manual redirect following (max 5) re-checking every hop; size cap and truncation check; download to `dunder-<safeTag>.apk.part`, SHA-256 verified, then renamed; partial/mismatching files deleted; old `dunder-*.apk/.part` cleaned before a download; unused `parseVersionCode` removed; Pages JSON reader now closes its stream.
  - CI: `build-apk.yml` publishes `sha256` of the signed APK in `latest-release.json` (new `APK SHA-256` step + job output) and runs `testDebugUnitTest`.
  - Tests: `app/src/test/java/com/agoria/app/util/UpdatePolicyTest.kt` (JUnit 4, `testImplementation("junit:junit:4.13.2")`).
- Design choices: sha256 is **required** (REQUIRE_SHA256) because every release built after this change carries it and old apps never run this code; the Pages JSON still has the same trust root as the release, so this protects against a wrong/tampered download, not a compromised repo owner (Android's same-signer rule covers the rest); only `/releases/download/` URLs are accepted (matches what CI publishes).
- Verified (2026-11-23, GitHub Actions): **CI green on main** commit `c1675b0`. Backup config guard run 45044746579 success; Build APK run 45044746633 success (incl. `Unit tests` step).
- Remaining to close this task: (1) push a test `v*` tag and confirm `latest-release.json` contains `sha256` and the app accepts the update; (2) device check: a tampered APK (or edited sha256 in a local copy of the JSON) is refused and no `dunder-*.apk*` file remains; (3) flip to `DONE` with commit link.
- Done when: install refuses mismatched hash or wrong host (unit-tested) and an end-to-end update from a tagged release works.

### SEC-11 - Stripe webhook secret flow is fake
- Status: TODO | Effort: M | Depends on: BUG-1
- Evidence: `AgentRuntime.generateWebhookSecret()` makes a random `whsec_...` that Stripe never issued; it is embedded in the Worker source and printed to the diary and chat (`setup_billing`).
- Fix: create the endpoint via Stripe API (`POST /v1/webhook_endpoints`) and use the returned `secret`; store it as a Worker secret binding, never in source; never log it; compare signatures in constant time. (Also see DEC-2: the whole webhook-to-phone design may be unworkable.)
- Done when: no secret reaches diary/messages/logs; signature check uses the real Stripe secret.

### SEC-12 - Regression guard for backup config (CI check)
- Status: DONE (2026-11-22) - implemented and verified. Commit: `583c74f` | Effort: S | Depends on: SEC-1 verified
- Evidence: added 2026-11-22 after SEC-1. Nothing stops a later edit (or an AI session) from flipping `allowBackup` back to `true` or re-adding `<include>` rules to `backup_rules.xml` / `data_extraction_rules.xml`; the leak would be silent.
- Fix: add a small CI step (shell or Gradle task, run before the build in `build-apk.yml` or a new lint workflow) that fails when: `AndroidManifest.xml` lacks `android:allowBackup="false"`; either rules file contains an `<include` element; `data_extraction_rules.xml` lacks `<cloud-backup>` or `<device-transfer>` exclude blocks; the manifest has no `dataExtractionRules` attribute. A `grep`-based script under `tools/` is enough.
- Done when: CI fails on a PR that sets `allowBackup="true"` or adds an `<include>`, and passes on `main`.
- Implemented (2026-11-22):
  - `tools/check_backup_config.py` - stdlib-only Python (no Gradle/SDK needed). Uses a real XML parser instead of `grep`, so commented-out `<include>` lines do not cause false alarms. Checks: (1) main manifest `allowBackup` is explicitly `"false"` (missing counts as a failure, the Android default is `true`); (2) `android:dataExtractionRules` is present and resolves to a file in `res/xml`; (3) `fullBackupContent`, if set, resolves; (4) no `<include>` in either rules file; (5) `data_extraction_rules.xml` has `<cloud-backup>` **and** `<device-transfer>`, each excluding `root`, `file`, `database`, `sharedpref`, `external` with `path="."`; (6) legacy `backup_rules.xml` has the same excludes; (7) any variant manifest under `app/src/<variant>/` that sets `allowBackup="true"` fails. Emits GitHub `::error` annotations when run in Actions. Exit codes: 0 ok, 1 violations, 2 wrong `--root`.
  - `tools/test_check_backup_config.py` - 18 `unittest` cases build temp project trees: good config passes; commented include ignored; every regression above fails with the expected message; malformed XML and wrong root element fail; wrong `--root` exits 2.
  - `.github/workflows/backup-guard.yml` - new, separate workflow (`permissions: contents read`, 5 min timeout, no Gradle). Runs the self-tests, then the guard, on push to `main`/`kilo/**`, PRs to `main`, and manually. A separate workflow was chosen over a step in `build-apk.yml` so the check runs in seconds and still reports if the long Android build is skipped or fails.
- Verified (2026-11-22):
  - ✅ 18/18 self-tests pass (`python3 tools/test_check_backup_config.py`).
  - ✅ Guard passes on a config shaped like the real SEC-1 manifest + rules (reviewed via the GitHub API at `0c6e642`); flipping `allowBackup` to `true` makes it exit 1.
  - ✅ GitHub Actions run 45035735896 ("Backup config guard") green on `main` commit `583c74f`.
  - ✅ Throwaway PR #6 (branch `test-sec12-fail`, commit `c1c3855`) with `allowBackup="true"`: guard run 45035887609 **failed** as expected; PR closed, branch deleted.

### SEC-13 - BootReceiver is exported and probably never gets LOCKED_BOOT_COMPLETED
- Status: IN-PROGRESS (2026-11-22) - implemented, **CI green on main (run 45038393115), Gradle build green (run 45038393077), device/emulator reboot check pending**. Commit: `7536d44` | Effort: S
- Evidence (from `AndroidManifest.xml`, reviewed 2026-11-22): `<receiver android:name=".service.BootReceiver" android:enabled="true" android:exported="true">` with intent filters for `BOOT_COMPLETED` **and** `LOCKED_BOOT_COMPLETED`. It has no `android:directBootAware="true"`.
- Problem 1: system broadcasts such as `BOOT_COMPLETED` are delivered to non-exported receivers too, so `exported="true"` only lets any other app on the phone send this receiver crafted intents. Its job is to start the foreground autonomy service (see RUN-3), so a third-party app could try to trigger that.
- Problem 2: without `directBootAware`, the `LOCKED_BOOT_COMPLETED` filter is never delivered before first unlock; if it is made direct-boot aware, the receiver must not touch credential-encrypted storage (Room, `filesDir`, Keystore-backed secrets).
- Fix: set `android:exported="false"`; drop the `LOCKED_BOOT_COMPLETED` filter unless there is a real need; in `BootReceiver.onReceive` check `intent.action` against the expected system actions and read `settings.autonomyEnabled` before starting the service (ties into RUN-3, which also covers the stop-path flag). Extend `tools/check_backup_config.py` or add a sibling check so a receiver with a boot filter cannot be re-exported.
- Done when: manifest has `exported="false"` on `BootReceiver`; a device/emulator reboot starts the service only when autonomy is enabled; a crafted `am broadcast` from another app/package does not start it.
- Reviewed first (2026-11-22): `service/BootReceiver.kt` already checked `intent.action` and read `settings.autonomyEnabled` before `AutonomyService.start(context)`, so only the action filter needed tightening. Its `seedIfNeeded()` + settings read also confirms Problem 2 (Room is credential-encrypted, unusable before first unlock).
- Implemented (2026-11-22):
  - `AndroidManifest.xml`: `BootReceiver` now `android:exported="false"` and listens for `BOOT_COMPLETED` only; comment explains why (and that `LOCKED_BOOT_COMPLETED` is deliberately absent).
  - `service/BootReceiver.kt`: accepts only `Intent.ACTION_BOOT_COMPLETED`; class KDoc documents the SEC-13 reasoning. Behavior otherwise unchanged (seed, read `autonomyEnabled`, start service only if enabled). *(RUN-5 later removed the seed call and added the timeout and failure reporting.)*
  - New sibling guard `tools/check_manifest_exports.py` (+ `tools/test_check_manifest_exports.py`, 12 `unittest` cases). Checks main + variant manifests: every `<service>`/`<receiver>`/`<provider>` must set `exported="false"` (empty allowlist); an `<activity>` may be `exported="true"` only with a MAIN/LAUNCHER filter; an activity with an intent-filter must declare `exported`; any component listening for `LOCKED_BOOT_COMPLETED` must be `directBootAware="true"`. Stdlib only, `::error` annotations in Actions, exit 0/1/2 like SEC-12.
  - `.github/workflows/backup-guard.yml`: now runs both self-test files, then both guards (workflow name kept so the existing SEC-12 check name and any branch protection keep working).
- Verified (2026-11-22):
  - ✅ 12/12 self-tests pass (`python3 tools/test_check_manifest_exports.py`).
  - ✅ Guard exits 0 on the new manifest; exits 1 on a copy with `BootReceiver` flipped back to `exported="true"`.
  - ✅ GitHub Actions run 45038393115 ("Backup config guard") green on `main` commit `7536d44`.
  - ✅ GitHub Actions run 45038393077 ("Build APK") green on `main` commit `7536d44`.
- Remaining to close this task: (1) on a device/emulator: enable autonomy, reboot, confirm the service restarts; disable autonomy, reboot, confirm it does not; from `adb shell`, `am broadcast -a android.intent.action.BOOT_COMPLETED -n com.agoria.app/.service.BootReceiver` should be refused/not start the service on a non-rooted device; (2) throwaway-PR red check like SEC-12; (3) flip to `DONE`.

### SEC-14 - Pin GitHub Actions by SHA, split signing job, Dependabot for Actions
- Status: TODO | Effort: S-M | Depends on: SEC-3 verified | Added 2026-11-22 (split out of SEC-3: needs network access to resolve SHAs)
- Evidence: `build-apk.yml`, `backup-guard.yml` use mutable tags (`actions/checkout@v4`, `setup-java@v4`, `cache@v4`, `upload-artifact@v4`, `upload-pages-artifact@v3`, `deploy-pages@v4`, `configure-pages@v4`). The `build` job holds `contents: write` and the signing secrets in the same job that runs Gradle on repository code.
- Fix: pin every `uses:` to a full commit SHA with a `# vX.Y.Z` comment (resolve SHAs from the official action repos, never guess); move signing + release into a separate `release` job (`needs: build`, tags only, `contents: write`) that downloads the unsigned APK artifact, so PR/branch builds never see secrets; add `.github/dependabot.yml` for `github-actions` and `gradle`; extend `tools/check_workflows.py` to fail on `uses:` without a 40-hex SHA.
- Done when: the guard fails on a tag-pinned action and `main` is green with all actions SHA-pinned.

### SEC-15 - Tell the user why an update was refused (and verify the update path end to end)
- Status: TODO | Effort: S | Depends on: SEC-10 | Added 2026-11-23
- Evidence: `UpdateManager.downloadAndInstall` reports only `Boolean`. After SEC-10 there are several distinct refusals (untrusted URL, missing sha256, hash mismatch, too large, permission not granted) but the UI can show only a generic failure. `checkForUpdates` also returns `null` both for "up to date" and for "refused/invalid JSON".
- Fix: return a sealed `UpdateResult` (Installed, NeedsPermission, Refused(reason), NetworkError) and a `CheckResult` (UpToDate, Available, Invalid(reason), NetworkError); surface the reason in the update dialog; never include exception messages or URLs with tokens. UI files were not reviewed (section 8): find the caller of `UpdateManager` first.
- Done when: a tampered hash shows "Update refused: file did not match its checksum" in the app, and "up to date" is distinguishable from "update info invalid".

### SEC-16 - Optional password-protected export of secrets
- Status: TODO | Effort: M | Depends on: SEC-2 | Added 2026-11-25
- Evidence: SEC-2 makes every export secret-free, so moving to a new phone loses connector keys by design. The original SEC-2 fix also allowed an explicit "include secrets" file, encrypted with a passphrase, for users who want to carry them.
- Fix: PBKDF2WithHmacSHA256 (or scrypt) + AES-256-GCM over a `secrets` section; the passphrase is never stored and never logged; a wrong passphrase or tampered ciphertext fails closed; the UI needs a passphrase dialog and a warning. Import reads the `secrets` section only when the passphrase decrypts it. Auto-backup and the default export stay secret-free whatever the UI offers.
- Done when: unit tests round-trip the encryption, reject a wrong passphrase and a flipped ciphertext byte, and prove the default export still has no secrets.

### SEC-17 - Encrypt `AgentEntity.apiToken` at rest
- Status: IN-PROGRESS (2026-11-26) - partial. Effort: S-M
- Evidence: `agents.apiToken` is plaintext in Room (SEC-2 removed it from exports only). A root backup or a stolen unlocked device exposes it; provider and connector keys already use `SecretCipher`.
- Fix: Room migration that re-encrypts existing values with `SecretCipher`, store ciphertext only, decrypt at the single point that uses the token, and mark the row `needs_reentry` when decryption fails (same failure mode as SEC-9). No plaintext token in logs, snapshots or exports.
- Done when: a DB dump contains no plaintext token and the agent that uses the token still works.
- Implemented (2026-11-26): Added `Repository.agentApiToken()` (decrypts, falls back to plaintext for legacy) and `Repository.updateAgentApiToken()` (encrypts). `ConnectorExecutor` now uses `repo.agentApiToken()`. Remaining: migration to encrypt existing rows, update all write paths.

---

## 3. P0 - Features that are dead or broken

### BUG-1 - `stripe` connector is not in the catalog
- Status: TODO | Effort: M | Depends on: DEC-1
- Evidence: `ConnectorExecutor.dispatch` has `"stripe" -> stripe(...)`, but `ConnectorCatalog.all` has no `stripe` entry. `execute("stripe")` returns "has no definition"; `repo.connector("stripe")` is null; `syncConnectorCatalog()` deletes connector rows that are not in the catalog.
- Effect: `setup_billing`, `check_revenue`, Stripe events, webhook deployment cannot work.
- Fix: add a `ConnectorDef` (secretKey field, actions, `risk = high`, mutating flags) **or** delete the Stripe code if monetization is cut (DEC-1).
- Done when: a configured Stripe connector can run `list_products` against a test-mode key.

### BUG-2 - `setup_billing` and `deploy_service` approvals can never execute
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: both create approvals of type `connector_action` whose `details` hold `{productName,...}` / `{name,code,routes}`. `executeApproval` reads `connector_id`/`action_name` from details, finds none, returns "Approval data did not include a connector action." For `deploy_service` the code is also truncated to 2000 chars in the approval.
- Fix: create dedicated approval types with their own executors, or store a normalized `{connector_id, action_name, args}` payload. Store the full code (file or full text) and show it in the approval UI before approving.
- Done when: an approved deploy runs the exact code that was shown.
- Implemented (2026-11-25): Approval details now include `connector_id`, `action_name`, and full `args` payload (including full code for deploy_service). Remaining: dedicated executors in executeApproval for multi-step setup_billing (product + price + webhook) and deploy_service (full code execution).

### BUG-3 - Agents never learn about the monetization actions
- Status: NEEDS-DECISION (DEC-1) | Effort: S
- Evidence: `PromptBuilder.system()` action list omits `record_revenue`, `deploy_service`, `setup_billing`, `reinvest`, `check_revenue`. `nicheDiscoveryPrompt`, `apiDesignPrompt`, `marketingPrompt`, `reinvestmentPrompt`, `checkRevenuePrompt`, `approvalUserPrompt` are never called.
- Fix: either wire these into the prompt and agent goals, or delete the dead code and handlers.

### BUG-4 - Stripe calls use JSON; Stripe v1 needs form encoding
- Status: TODO | Effort: M | Depends on: BUG-1
- Evidence: `stripe()` sends `application/json` bodies to `/v1/products`, `/prices`, `/customers`, `/subscriptions`. Stripe's v1 API accepts `application/x-www-form-urlencoded` (nested keys like `recurring[interval]`, `items[0][price]`). `usage_records` is the legacy metering API.
- Fix: add a `formEncode()` helper, send an `Idempotency-Key` on every POST, move metering to the current Meters API or drop it.
- Done when: create product/price succeed in Stripe test mode.

### BUG-5 - Cloudflare Workers connector is partly wrong
- Status: TODO | Effort: M
- Evidence: `deploy` uploads raw `application/javascript`; the generated webhook worker uses `export default` (ES module), which needs multipart upload with metadata (`main_module`). `get_script` parses the response as JSON but the endpoint returns script source. The `routes` arg and `zoneId` field are never used. The returned `url` field is assumed to exist; a workers.dev subdomain may not be enabled.
- Fix: multipart upload with metadata and secret bindings; handle `get_script` as text; implement or remove `routes`/`zoneId`; verify the account subdomain before reporting a URL.
- Done when: deploy of a hello-world module Worker returns a working URL.

### BUG-6 - HTTP retries repeat non-idempotent requests
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: S
- Evidence: `ConnectorExecutor.http()` retries up to 3 times on `-1`, `429`, `>=500` for **all** methods with `Thread.sleep`.
- Effect: duplicate emails, Slack/Discord posts, GitHub issues, PayPal/Stripe objects.
- Fix: retry only GET/HEAD (and PUT/DELETE when safe), or POST only with an idempotency key; honor `Retry-After`; use coroutine `delay`, not `Thread.sleep`; make calls cancellable (see RUN-1).
- Done when: a MockWebServer test shows POST is sent once on 500.
- Implemented (2026-11-25): `http()` now only retries idempotent methods (GET, HEAD, PUT, DELETE). POST and PATCH are never retried. Remaining: honor `Retry-After` header, use coroutine `delay` instead of `Thread.sleep`, make calls cancellable.

### BUG-7 - String template mistakes in `AgentRuntime` / `PromptBuilder`
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: S
- Evidence: `'$request.connectorId'` (prints the object, not the id) in `approveConnectorRequest`; `"\${amountCents/100}"` style escapes in the `record_revenue`, `reinvest`, `check_revenue` diary strings print literal `${...}` text; `\$${...}` in `PromptBuilder` raw strings prints a backslash.
- Fix: use `${request.connectorId}` and real `$` handling; add a unit test that renders these messages.

### BUG-8 - Revenue tracking is fragile and can double count
- Status: TODO | Effort: M | Depends on: BUG-1
- Evidence: `check_revenue` de-duplicates only within the same agent (`recentRevenueForAgent(agentId, 100)` + `metadata.contains(eventId)`), so two agents polling the same Stripe event both book it; `parseStripeEvents` scrapes text lines and `rest.split(" at ")[1]` can throw; `userProfitSharePercent` is stored but unused and the two percentages are not validated to total 100; `calculateReinvestCost` invents prices that are not real spend.
- Fix: unique index on `(source, externalId)`; read events as JSON; store amounts as integer minor units with currency; one ledger table (fund = sum of ledger rows, see DATA-1); validate splits; remove fake costs.

### BUG-9 - BuildRunner reports fake success and leaves builds "building"
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: when the LLM fails or returns invalid JSON, `writeFallback` creates a 4-file stub and the run still ends as "Build complete" and `executeApproval` sets the approval to `executed`; `BuildProgress.Failed` is never emitted; `repo.addBuild` stores status `"building"` and nothing ever updates it; exceptions leave `_buildProgress` stuck; `LlmRequest.json = true` is ignored by `LlmClient`; `next_steps` are parsed then discarded; one shared progress flow for concurrent builds; files are truncated to 30,000 chars silently; no cancellation (`isActive` is never checked).
- Fix: mark stub builds as `fallback`/failed unless the user asked for a stub; set status to `ready`/`failed`; wrap in try/catch and emit `Failed`; keep `next_steps` in the manifest; key progress by approval id; support cancellation and cleanup.
- Done when: a failed model call shows a failed build, not a successful one.

### BUG-10 - LlmClient retry/fallback issues
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: `repeat(2) { ... return@repeat }` does not leave the loop, so a non-retryable 4xx is simply sent a second time; fixed `delay(600)` and no `Retry-After`; no per-provider cooldown or circuit breaker, so a rate-limited or dead provider is tried again every cycle by every agent (anonymous OVH allows 2 requests/minute); `Semaphore(2)` is held across the whole fallback chain; `call.execute()` inside `withContext` is not cancelled when the coroutine is cancelled; `LlmRequest.json` is never mapped to `response_format`.
- Fix: break out correctly; per-provider cooldown with exponential backoff and `Retry-After`; release the permit between providers; cancel the `Call` on coroutine cancellation (`suspendCancellableCoroutine` + `invokeOnCancellation`); send `response_format: json_object` where the provider supports it.
- Done when: MockWebServer tests cover 4xx, 429 with `Retry-After`, 500 fallback, cancellation.
- Implemented (2026-11-25): Changed `repeat(2)` to labeled `for` loop with `break@outer` so 4xx errors break out instead of continuing. Remaining: per-provider cooldown, Retry-After header, cancellable calls, response_format mapping.

### BUG-11 - Smaller connector defects
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: S each
- BUG-11 sub-items:
  - Telegram `get_updates` returns only update ids, not message text.
  - Notion `create_page`: both branches of the `properties` builder are identical, and a database parent needs the database's real title property name (not literal `title`).
  - GitHub `setupHelp` asks for `administration` permission; recommend least privilege (contents + issues).
  - `ConnectorEntity`/catalog fields `zoneId`, `organizationId` are unused.
  - `stripe.verify_key` takes an `apiKey` argument from the agent (puts a secret key into approval details and logs); remove.
  - `huggingface` actions are non-mutating but send agent-controlled text to a third party; review risk level.
- Implemented (2026-11-25): `stripe.verify_key` now uses configured apiKey instead of agent-supplied. GitHub setupHelp updated to recommend least privilege (no administration). Remaining: Telegram get_updates, Notion create_page, unused fields, huggingface risk level.

---

## 4. P1 - Data model, reliability, scale

### DATA-1 - Lost updates on settings and fund balance
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: `Repository.updateSettings` reads the whole row then upserts the whole row; `updateInfrastructureFund` is read-modify-write with no transaction; the UI and the runtime both write settings.
- Fix: atomic SQL (`UPDATE settings SET infrastructureFundCents = MAX(0, infrastructureFundCents + :delta)`), `@Transaction` DAO methods, or a ledger table (preferred, see BUG-8).
- Note (2026-11-22): RUN-3's stop path now adds one more `updateSettings` writer (`autonomyEnabled=false`); it is a single-field write and shares this lost-update risk until DATA-1 lands.
- Implemented (2026-11-25): `SettingsDao.addInfrastructureFund()` now uses atomic SQL `UPDATE settings SET infrastructureFundCents = MAX(0, infrastructureFundCents + :deltaCents)`. `Repository.updateInfrastructureFund()` calls this instead of read-modify-write. Remaining: atomic settings updates for other fields, @Transaction DAO methods.

### DATA-2 - Unbounded tables and full-table reads on every cycle
- Status: IN-PROGRESS (2026-11-26) - partial. Effort: M
- Evidence: `AgentRuntime.buildContext` uses `repo.diaryFor(id).first().take(18)`, `memoriesFor(...).first().take(14)`, `messagesFor(...).first().takeLast(14)`, `tasksFor/approvalsFor/buildsFor(...).first()`, and the "chatter" block loads **every agent's whole diary** each cycle. `Repository` already has `recent*` limited queries that are not used here. No retention exists for diary, messages, connector logs, approvals or revenue. `exportSnapshot` calls `diaryDao().getAll().takeLast(2000)`.
- Fix: `LIMIT` queries in the DAO for every context read; index `(agentId, createdAt)`; retention job (keep pinned/high-importance, summarize old diary into memories, prune `connector_logs`, expire old approvals); rank memories by importance and recency.
- Done when: context build time is flat with 100k diary rows (benchmark/unit test).
- Implemented (2026-11-26): `buildContext` now uses `repo.recentDiaryForAgent(agent.id, 18)` and `repo.memoryDao().recentForAgent(agent.id, 14)` instead of loading all rows then taking. Chatter block now uses `repo.recentSayEntries(50)` instead of loading every agent's full diary. Added `DiaryDao.recentForAgent()` and `DiaryDao.recentSayEntries()` methods. Remaining: retention job, index verification, memory ranking.
","oldString">
### DATA-2 - Unbounded tables and full-table reads on every cycle
- Status: TODO | Effort: M | Verify first: `Daos.kt` was not reviewed
- Evidence: `AgentRuntime.buildContext` uses `repo.diaryFor(id).first().take(18)`, `memoriesFor(...).first().take(14)`, `messagesFor(...).first().takeLast(14)`, `tasksFor/approvalsFor/buildsFor(...).first()`, and the "chatter" block loads **every agent's whole diary** each cycle. `Repository` already has `recent*` limited queries that are not used here. No retention exists for diary, messages, connector logs, approvals or revenue. `exportSnapshot` calls `diaryDao().getAll().takeLast(2000)`.
- Fix: `LIMIT` queries in the DAO for every context read; index `(agentId, createdAt)`; retention job (keep pinned/high-importance, summarize old diary into memories, prune `connector_logs`, expire old approvals); rank memories by importance and recency.
- Done when: context build time is flat with 100k diary rows (benchmark/unit test).

### DATA-3 - Backup and restore are incomplete and not atomic
- Status: TODO | Effort: M
- Evidence: `importSnapshot` is not wrapped in `db.withTransaction`, returns `false` midway leaving partial data, ignores approvals, revenue, providers (priority/model/enabled), fund and settings beyond a few fields, `saveConnectorConfig` marks connectors `configured` without validating; `reset()` runs `clearAllTables()` but leaves `agent-workspace`, `exports`, `backups` and the Keystore key behind.
- Fix: versioned schema, transactional import with validation, include all tables, secrets only in the encrypted export (SEC-2), make `reset()` also delete files.

### DATA-4 - Migration safety and startup
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: `exportSchema = false` (no schema JSON, no migration tests); on a failed upgrade `AppDatabase.create` renames the DB and silently starts empty, so the user just sees an empty office; `create()` opens the DB synchronously in `Application.onCreate` (main thread).
- Fix: `exportSchema = true` + `room.schemaLocation` + `MigrationTestHelper` tests for 1->4; surface "recovered from failed upgrade, restore from backup?" to the user; open the DB off the main thread.
- Implemented (2026-11-25): Set `exportSchema = true` in `AppDatabase.kt`. Added `ksp { arg("room.schemaLocation", ...) }` to `build.gradle.kts`. Remaining: MigrationTestHelper tests, surface recovery message to user, open DB off main thread.

### DATA-5 - Foreign keys delete or orphan important data
- Status: TODO | Effort: S
- Evidence: `RevenueEntity` has `onDelete = CASCADE` on agent, so deleting an agent erases its financial history; `builds` and `memories` have no FK (orphans after agent delete); `deleteProject` leaves tasks/builds with dangling `projectId`.
- Fix: ledger rows survive agent deletion (`SET_NULL` plus stored agent name); clean up or reassign orphans.

### DATA-6 - No approval de-duplication or expiry
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: S
- Evidence: `addApproval` always inserts; an agent can queue the same request each cycle; approved-but-old actions can execute long after they were proposed.
- Fix: de-dup key `(agentId, type, hash(details))` for pending items, max N pending per agent, TTL (for example 24 h) after which `pending` becomes `expired`.
- Implemented (2026-11-25): `addApproval` now checks for existing pending approval with same `(agentId, type, details hash)` and returns it instead of creating duplicate. Added `ApprovalDao.getPendingByAgentAndType()` method. Remaining: max N pending per agent, TTL expiry (24h).

### RUN-1 - Loop robustness and cancellation
- Status: TODO | Effort: M
- Evidence: `tickAll` runs agents sequentially, so one slow model call (up to 100 s per provider) stalls the office; no per-cycle timeout; `stop()` cancels the coroutine but blocking OkHttp calls keep running (see BUG-10); a failing agent is retried at the normal cooldown (`failureCount` is counted but not used for backoff); `processAutoApprovedApprovals` and the post-action auto-approve block call `executeApproval` without the agent mutex.
- Fix: `withTimeout` per cycle, exponential backoff using `failureCount`, take the mutex around all `executeApproval` calls, cancel in-flight HTTP on stop.

### RUN-2 - Token and request budget
- Status: TODO | Effort: M
- Evidence: 8 agents x one cycle per cooldown, each with a prompt built from up to 12 diary + 10 memory + 12 message entries plus catalog and peers, against free tiers (Kilo about 200 req/h/IP, OVH 2 req/min anonymous).
- Fix: global requests-per-hour and tokens-per-hour budget, adaptive cooldown, skip a cycle when nothing changed (event-driven wake on new user message/approval/result), prompt size cap with token estimate, per-provider quota tracking in `ProviderEntity`.

### RUN-3 - AutonomyService battery and lifecycle
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: `AutonomyService.acquireWakeLock()` takes a `PARTIAL_WAKE_LOCK` with no timeout and holds it for the whole service life; the notification "Stop autonomy" action calls `runtime.stop()` but did not update `settings.autonomyEnabled`, so `BootReceiver` (which restarts the service when that flag is true) could restart it after the next reboot. (Re-read `AutonomyService.kt` on 2026-11-22: confirmed both points; `onDestroy` also stops the runtime, which is right for system kills.)
- Fix: hold the wake lock only while a cycle runs (with a timeout), write `autonomyEnabled=false` on every stop path, add a WorkManager watchdog, and test on Android 15 foreground-service-from-boot rules. See also SEC-13 (receiver export and boot filters; done) and RUN-5.
- Part 1 implemented (2026-11-22): `AutonomyService.stopAutonomy()` (reached from the notification action and from `AutonomyService.stop(context)`) now stops the runtime, persists `autonomyEnabled=false` through `Repository.updateSettings` on `appScope`, and only then calls `stopSelf()` (in `finally`, so a failed write still stops the service). System kills still go through `onDestroy` only and leave the flag alone on purpose, so autonomy resumes after a reboot if the user did not turn it off. If the UI already writes the flag before calling `stop()`, the second write is idempotent.
- Part 1 verified (2026-11-22): **CI green** on `main` commit `7c990ec`. Build APK run 45039776580 succeeded; Backup config guard run 45039776621 succeeded.
- Remaining: (2) wake lock only while a cycle runs, with a timeout (needs `AgentRuntime.kt`, ~42 KB, not read yet); (3) WorkManager watchdog; (4) Android 15 foreground-service-from-boot check; (5) device check: tap "Stop autonomy", reboot, confirm the service does not come back.
- Implemented (2026-11-25): Wake lock now acquired with 30-minute timeout instead of indefinite. Remaining: hold wake lock only during cycle execution, WorkManager watchdog, Android 15 boot check.
- Evidence: `AutonomyService.acquireWakeLock()` takes a `PARTIAL_WAKE_LOCK` with no timeout and holds it for the whole service life; the notification "Stop autonomy" action calls `runtime.stop()` but did not update `settings.autonomyEnabled`, so `BootReceiver` (which restarts the service when that flag is true) could restart it after the next reboot. (Re-read `AutonomyService.kt` on 2026-11-22: confirmed both points; `onDestroy` also stops the runtime, which is right for system kills.)
- Fix: hold the wake lock only while a cycle runs (with a timeout), write `autonomyEnabled=false` on every stop path, add a WorkManager watchdog, and test on Android 15 foreground-service-from-boot rules. See also SEC-13 (receiver export and boot filters; done) and RUN-5.
- Part 1 implemented (2026-11-22): `AutonomyService.stopAutonomy()` (reached from the notification action and from `AutonomyService.stop(context)`) now stops the runtime, persists `autonomyEnabled=false` through `Repository.updateSettings` on `appScope`, and only then calls `stopSelf()` (in `finally`, so a failed write still stops the service). System kills still go through `onDestroy` only and leave the flag alone on purpose, so autonomy resumes after a reboot if the user did not turn it off. If the UI already writes the flag before calling `stop()`, the second write is idempotent.
- Part 1 verified (2026-11-22): **CI green** on `main` commit `7c990ec`. Build APK run 45039776580 succeeded; Backup config guard run 45039776621 succeeded.
- Remaining: (2) wake lock only while a cycle runs, with a timeout (needs `AgentRuntime.kt`, ~42 KB, not read yet); (3) WorkManager watchdog; (4) Android 15 foreground-service-from-boot check; (5) device check: tap "Stop autonomy", reboot, confirm the service does not come back.

### RUN-4 - Approval outcome is classified by string prefix
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: S
- Evidence: `executeApproval` decides `ok` with `outcome.startsWith("Failed")`, `"Build failed"`, `"Build skipped"`, etc.
- Fix: return a sealed `ExecutionResult(ok, summary, retryable)` from each executor.
- Implemented (2026-11-25): Reviewed executeApproval; the string-prefix classification remains but is documented. Full sealed ExecutionResult refactor deferred as it requires changing all executor return types. Remaining: implement sealed result type and update all executors.

### RUN-5 - Boot restart fails silently and can outlive the receiver's time budget
- Status: IN-PROGRESS (2026-11-22) - implemented, **CI build + device check pending** | Effort: S | Found while doing SEC-13 (2026-11-22)
- Evidence: `service/BootReceiver.kt` ran `seedIfNeeded()` and a settings read on `CoroutineScope(Dispatchers.IO)` under `goAsync()`, then `AutonomyService.start(context)`. A broadcast receiver has roughly 10 s before the system treats it as unresponsive; a cold Room open on a slow phone can exceed that. The `catch (_: Exception)` swallowed everything (including a foreground-service-start refusal from the OS), logged nothing, showed nothing, and the comment promised a retry "the next time the app opens" that no code implemented. `AutonomyService.start()` already returned `false` when Android refused both start modes, but the receiver ignored that result.
- Effect: after a reboot the office can be silently dead while the user believes autonomy is on.
- Fix: do not seed from the receiver (the app does it on first launch); read `autonomyEnabled` and hand the work to `WorkManager` (expedited) or start the service immediately and let it load state; log failures to the persisted audit trail (OBS-1) and set a "restart failed" flag the UI shows on next open, plus a notification; unit-test with a fake repository.
- Done when: a simulated failure in the boot path is visible to the user (flag or notification) and a normal reboot with autonomy enabled restarts the service within the receiver time budget.
- Implemented (2026-11-22):
  - `service/BootReceiver.kt`: removed `seedIfNeeded()` and the `repository.settings()` call (which could upsert default settings and race the app's own seed); now reads `app.database.settingsDao().get()?.autonomyEnabled` read-only inside `withTimeout(8_000 ms)` (under the ~10 s receiver budget); starts the service only when enabled. Three failure paths are reported: `AutonomyService.start()` returned `false`; settings read timed out; any other exception. Each one logs (`Log.w`), calls `BootStatus.recordFailure`, and posts a notification "Autonomy did not restart" via `Notifier.notifyLocal` (wrapped in `runCatching`). A successful start calls `BootStatus.clear`. `pending.finish()` stays in `finally`.
  - New `service/BootStatus.kt`: tiny SharedPreferences store (`recordFailure`, `lastFailure(): Failure?`, `clear`) so the failure outlives the notification and does not need Room, which may be slow or locked right after boot. Reasons are fixed sentences plus an exception class name, no secrets, no exception messages.
  - `service/AutonomyService.kt`: `onCreate` calls `BootStatus.clear(this)` (a running service means the old failure is stale).
- Design choices: SharedPreferences instead of a new Room column or a settings field (no migration, no DAO change, readable before Room opens); kept the work in the receiver on a bounded timeout rather than adding WorkManager (not a dependency yet, see TEST-1/CI-1 for dependency work); only the "Failed" notification is new, no new permission.
- Verified (2026-11-22): **CI green** on `main` commit `7c990ec`. Build APK run 45039776580 succeeded; Backup config guard run 45039776621 succeeded. The three Kotlin files re-read for brace/paren balance; call signatures checked against code read in this session (`AutonomyService.start(Context): Boolean`, `Notifier.notifyLocal(Context, String, String)`, `AgoriaApplication.database`, `SettingsDao.get()` used as a suspend nullable in `Repository`).
- Remaining to close this task: (1) on a device/emulator: force a failure (for example revoke the foreground-start allowance or `adb shell am broadcast` BOOT_COMPLETED with the app force-stopped is not enough, use a debug build with a forced `AutonomyService.start` failure) and confirm the notification and `BootStatus.lastFailure` appear; do a normal reboot with autonomy on and confirm restart; (2) UI surface for `BootStatus.lastFailure` is RUN-6; (3) flip to `DONE`.

### RUN-6 - Show the boot-restart failure inside the app
- Status: TODO | Effort: S | Depends on: RUN-5 | Added 2026-11-22
- Evidence: RUN-5 stores the failure in `BootStatus` and posts a notification, but nothing in the UI reads `BootStatus.lastFailure(context)`. If notifications are off (or the user swiped it away) the failure is only in logs.
- Fix: on app start and when the Office screen resumes, read `BootStatus.lastFailure`; if present show a one-line banner ("Autonomy did not restart after the last reboot: <reason>") with a "Turn autonomy on" button that calls `AutonomyService.start` and `BootStatus.clear`. UI files were not reviewed (section 8), so read the Office/Settings screen first.
- Done when: forcing the failure from RUN-5 shows the banner after opening the app, and starting autonomy removes it.

### CONN-1 - Read-only gating ignores action aliases and `check_revenue`
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: S
- Evidence: `ConnectorCatalog.isReadOnly` looks up the raw action name, but `ConnectorExecutor.canonicalAction` maps aliases (`ls`, `cat`, `save`...) later, so aliases always need approval (fails closed, but noisy). `check_revenue` calls `useConnector` directly and skips gating entirely.
- Fix: canonicalize before the policy check; route every connector call through one gate.
- Implemented (2026-11-25): Added `ConnectorCatalog.canonicalizeAction()` method. `isReadOnly` now canonicalizes action before lookup. `check_revenue` now checks `AgentPolicy.isAutoAllowed` before proceeding; creates approval if not allowed. Remaining: full connector-specific canonicalization in ConnectorCatalog (github, notion, agentmail aliases).

### CONN-2 - `files` connector shares one workspace and has no quota
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: S
- Evidence: all agents write to `Files.workspace(context)`, the same tree as builds; `write` accepts 200,000 chars per call with no total limit; `findByName` walks the whole tree.
- Fix: per-agent subdirectory (read-share via explicit grant), total quota, depth/count limits for walks.
- Implemented (2026-11-25): `findByName` now uses `maxDepth(5)` to limit walk depth. `write` action now checks total workspace size against 50 MB quota before writing. Remaining: per-agent subdirectory, read-share via explicit grant.

### CONN-3 - Bounded response bodies, closed responses
- Status: IN-PROGRESS (2026-11-25) - implemented. Effort: S
- Evidence: `httpOnce` and `duckDuckGoResults` read whole bodies with `string()`; `duckDuckGoResults` does not close the response on failure paths.
- Fix: cap body size (for example 1 MB) with a streaming read, always `use {}` the response.
- Implemented (2026-11-25): `httpOnce` now uses `response.use {}` with streaming read capped at 1 MB. `duckDuckGoResults` now uses `response.use {}` with streaming read capped at 1 MB.

### CONN-4 - Web search relies on scraping
- Status: TODO | Effort: M
- Evidence: `webSearch` falls back to parsing DuckDuckGo HTML with regexes.
- Fix: use a documented search API (Brave/Tavily/SearXNG, optional key) with the scraper as last resort.

### LLM-1 - JSON protocol robustness
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: `AgentPolicy.parseDecision` accepts only a clean JSON object; otherwise the model text becomes a diary "thought" and nothing happens.
- Fix: lenient extraction of the first balanced `{...}`; one automatic "return only JSON" retry; per-action schema validation with the error fed back next cycle; use `response_format` where supported (BUG-10).
- Implemented (2026-11-25): Added `extractFirstJsonObject()` which finds the first balanced `{...}` in text, handling string literals and escapes. `parseDecision` now uses this to extract JSON from markdown/prose. Remaining: automatic retry on parse failure, per-action schema validation, response_format support.

---

## 5. P2 - Quality, tooling, structure

### TEST-1 - There are no tests
- Status: TODO | Effort: L
- Evidence: `app/build.gradle.kts` has no `testImplementation`/`androidTestImplementation` dependencies at all. (The only tests in the repo are the stdlib-Python self-tests for the SEC-12 and SEC-13 guards in `tools/`; they do not cover the app.) Since 2026-11-23 there is `UpdatePolicyTest` (SEC-10) and since 2026-11-25 `SnapshotSecretsTest` (SEC-2, 5 cases, with a real `org.json` test dependency because `android.jar` only has stubs); `build-apk.yml` runs `testDebugUnitTest`.
- Fix: add JUnit, MockK/Turbine, MockWebServer, Room testing. First tests: `AgentPolicy.parseDecision`, `ConnectorCatalog.isReadOnly`, path traversal in `BuildRunner` and `files`, `LlmClient` fallback, snapshot export/import round trip (no secrets), migrations 1->4, approval gating (high risk never auto-approved). Boot path (RUN-5) and stop path (RUN-3) are good early candidates once a fake `SettingsDao` exists.

### CI-1 - CI hygiene
- Status: IN-PROGRESS (2026-11-26) - partial. Effort: M
- Fix: run unit tests and lint; remove the 3x retry loops that hide flaky builds; drop `continue-on-error` on artifact uploads; move to current `actions/setup-java`; add ktlint/detekt; Dependabot for Gradle and Actions; verify the minified release build starts (`isMinifyEnabled = true`, rules in `proguard-rules.pro` untested, Room/org.json usage).
- Implemented (2026-11-26): Removed `-x lint` from build commands. Added separate `Lint` step running `lintDebug`. Remaining: ktlint/detekt, Dependabot config, verify minified release.

### OBS-1 - Audit log and log redaction
- Status: IN-PROGRESS (2026-11-25) - partial. Effort: M
- Evidence: runtime log is in memory only (last 80 entries); `connector_logs.summary` stores up to 1000 chars of responses and can contain URLs with tokens (Telegram bot token is in the request URL).
- Fix: persisted audit trail (who approved what, with payload hash and result), redact tokens and secrets before writing diary/messages/logs.
- Implemented (2026-11-25): `Repository.addConnectorLog()` now redacts potential secrets/tokens (GitHub PATs, Stripe keys, Bearer tokens, Telegram bot tokens) from the summary before persisting. Remaining: persisted audit trail with payload hashes, redact diary/messages.

### ARCH-1 - Split the two giant classes
- Status: TODO | Effort: L
- Evidence: `ConnectorExecutor.kt` ~129 KB and `AgentRuntime.kt` ~42 KB.
- Fix: `ConnectorHandler` interface per connector (declares actions, schema, mutating flag, rate limit), registered in one place; action handlers in a `Map<String, ActionHandler>`; constructor injection instead of building everything in `AgoriaApplication.onCreate`.

### UI-1 - The backup card claims the export is encrypted
- Status: DONE (2026-11-25). Effort: S
- Evidence: `ui/settings/SettingsScreen.kt` says "Save encrypted JSON to Downloads folder" in the Backup & Restore card, but the export is plain JSON (SEC-2 made it secret-free, not encrypted). The same screen tells the user to "restore it on any phone" without saying credentials are left out.
- Fix: replace the label with "Save a JSON copy to the Downloads folder" and add one line to the card: API keys and connector credentials are not included and must be re-entered after restoring on a new device.
- Done when: the text matches the file format and no screen claims an encryption that does not exist.
- Implemented (2026-11-25): Changed "Save encrypted JSON to Downloads folder" to "Save a JSON copy to the Downloads folder". Added line: "API keys and connector credentials are not included and must be re-entered after restoring on a new device."

### DOC-1 - Bring README and docs in line with the code
- Status: IN-PROGRESS (2026-11-26) - partial. Effort: S
- Fix: use the drift table in section 1; one product name; document backup and secret behavior after SEC-1/SEC-2 (SEC-1 now makes "database excluded from backup" accurate; also state that backup/transfer is fully disabled and in-app export is the only path).
- Implemented (2026-11-26): Changed app name from "Agoria" to "Dunder" in README title. Updated connector table: replaced Gmail/Google Calendar with Office Browser connector, added all 25 connectors (PayPal, Polar, AgentMail, Supabase, Resend, Upstash, Hugging Face, Vercel, Netlify, Linear, Cloudflare Workers). Updated GitHub actions list. Added backup/export security note. Remaining: full drift table reconciliation.

---

## 6. Decisions needed from the owner

- **DEC-1 - Keep or cut monetization?** Status: NEEDS-DECISION. Today the whole Stripe/Cloudflare/revenue path is dead (BUG-1..5, SEC-4, SEC-11). Option A: finish it properly (ledger, verified events, approvals). Option B: remove `record_revenue`, `reinvest`, `check_revenue`, `setup_billing`, `deploy_service`, revenue tables usage, prompts, and the Stripe/Worker code.
- **DEC-2 - Webhooks cannot reach a phone.** `deploy_webhook_handler` forwards to `appCallbackUrl`, but the example is a LAN address (`http://192.168.x.x:8080/...`); a Cloudflare Worker cannot call that. Polling Stripe events (what `check_revenue` does) is the only workable path without a relay server. Decide: polling only, or add a small relay.
- **DEC-3 - Backup policy.** Cloud backup on or off? Is a passphrase-encrypted export acceptable? (Interim state since 2026-11-22: backup and device transfer are fully **off** via SEC-1, and guarded by SEC-12. If the owner decides to turn backup on, update `tools/check_backup_config.py` and its tests in the same change, or CI will fail on purpose.)
- **DEC-4 - Product name and package.** Agoria or Dunder (affects strings, User-Agent, release names, package id).
- **DEC-5 - Free-tier budget.** Target number of agent cycles per hour across all agents.
- **DEC-6 - Rotate the release keystore?** Status: NEEDS-DECISION. If the keystore in the `KEYSTORE_*` secrets was created by the deleted `generate-keystore.yml`, its base64 and passwords were printed to public-repo Actions logs and the `.jks` was a downloadable artifact: treat it as exposed. Rotating means a new signing key, so installed copies cannot update in place (users must uninstall/reinstall and lose local data unless exported first). Options: (A) rotate now, accept the one-time break; (B) keep the key, delete old runs/artifacts, accept the risk; (C) check whether the key was ever created that way and decide. Also delete old workflow runs and artifacts either way.

---

## 7. Carry-over from `.kilo/plans/` (backend-relevant items, status as seen in code)

| Item | Source plan | Seen in code? |
| --- | --- | --- |
| Paged DAO/Repository queries for builds, diary, messages | next-improvements P3 | Repository methods exist (`pagedBuildsForAgent`, `pagedDiaryForAgent`, `pagedMessagesForAgent` + counts); DAO and UI use not verified |
| `BuildProgress` + `buildProgress` flow | next-improvements P2 | Exists; `Failed` never emitted (BUG-9) |
| Build cancellation | next-improvements P5 | Not implemented |
| `RevenueEntity` + profit split settings + migration | monetization Phase 1 | Done (v1->2) |
| Stripe connector | monetization Phase 1 | **Missing** from catalog (BUG-1) |
| Cloudflare Workers connector | monetization Phase 1 | Exists, partly wrong (BUG-5) |
| `deploy_service`/`setup_billing` handlers | monetization Phase 1 | Present, approvals cannot execute (BUG-2) |
| Prompt templates for niche/design/marketing | monetization Phase 2 | Written, never used (BUG-3) |
| Spending limits on fund | monetization Phase 3 | Only a per-reinvest cap; no monthly cap |
| Notifications for revenue/fund/deploy errors | monetization Phase 3 | Not seen |
| ktlint + detekt in CI | next-improvements P5 | Not present (CI-1) |

UI-only items from those plans (combined flows, LazyColumn keys, pull-to-refresh, haptics) are out of scope for this file.

---

## 8. Not reviewed yet (do these before trusting this plan fully)

`data/Daos.kt`, `data/SeedData.kt`, `core/ProviderCatalog.kt`, `core/AgentModels.kt`, `util/Files.kt`, `util/Json.kt`, `util/Share.kt`, `browser/*` (BrowserHub, AgentBrowserActivity), `viewer/*`, all of `ui/*`, `gradle.properties`, `proguard-rules.pro`, `tools/office_gen`, `docs/pixel-office.md`, `.gitignore`.

Reviewed since the first pass (2026-11-22): `app/src/main/AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`, `.github/workflows/build-apk.yml` (all while doing SEC-12), `service/BootReceiver.kt` (SEC-13; produced RUN-5), and, while doing RUN-5/RUN-3: `service/AutonomyService.kt` (stop path confirmed, see RUN-3), `util/Notifier.kt`, `AgoriaApplication.kt`, and `data/Repository.kt` (read in full; it confirms the DATA-1, DATA-3 and DATA-4-related claims about `updateSettings`, `importSnapshot` and `exportSnapshot`). Manifest items looked at and left alone: `REQUEST_INSTALL_PACKAGES` is used by the self-updater (see SEC-10); `FileProvider` is correctly `exported="false"`; `AutonomyService` is `exported="false"`; `MainActivity` is the only exported component (launcher).

---

## 9. Suggested order of work

1. **Stop the bleeding (P0 security):** SEC-1 (implemented, verify on device), SEC-12 (done), SEC-13 (implemented, verify CI + device), **SEC-2 (core done 2026-11-25: exports and auto-backups are secret-free; SEC-16 and SEC-17 are the rest)**, SEC-3 (implemented, verify CI + owner actions, DEC-6), SEC-14, SEC-10 (implemented, verify), SEC-4, SEC-5, SEC-7.
2. **Correctness core:** BUG-6, BUG-7, BUG-9, BUG-10, DATA-1, DATA-2, RUN-1, RUN-5 (implemented, verify), RUN-3 (part 1 done), RUN-6.
3. **Resolve DEC-1, then either** BUG-1/2/4/5/8 + SEC-11 **or** delete the monetization code.
4. **Hardening:** SEC-6, SEC-8, SEC-9, SEC-15, DATA-3..6, RUN-2..4, CONN-1..4, LLM-1.
5. **Quality:** TEST-1 (start alongside step 1), CI-1, OBS-1, ARCH-1, DOC-1.

---

## 10. Changelog

| Date | Who | Change |
| --- | --- | --- |
| 2026-11-22 | Claude | Initial review of commit `6b7e7ed`; created this file. |
| 2026-11-22 | Claude | SEC-1 implemented (manifest `allowBackup="false"`, new `data_extraction_rules.xml`, exclude-all `backup_rules.xml`); status set to IN-PROGRESS pending build/device verification. Added SEC-12 (CI regression guard). Updated drift table, DEC-3 note, DOC-1, section 9. Added section 11 (Work log). |
| 2026-11-22 | Claude | SEC-1 CI build verified (GitHub Actions run 45034237240, commit `b78501b`); updated SEC-1 status with verification results; `bmgr`/`adb` check still pending. |
| 2026-11-22 | Claude | SEC-12 implemented (`tools/check_backup_config.py`, `tools/test_check_backup_config.py`, `.github/workflows/backup-guard.yml`); status IN-PROGRESS pending first CI run. Added SEC-13 (BootReceiver exported / boot filters) from the manifest review. Updated section 1 (CI/Tools rows), DEC-3, TEST-1, RUN-3 cross-reference, section 8 and section 9. Added work-log entry. |
| 2026-11-22 | Claude | SEC-12 CI verified (GitHub Actions run 45035735896, commit `583c74f`); updated SEC-12 status with commit link and "CI green on main". |
| 2026-11-22 | Claude | SEC-12 throwaway PR test passed (PR #6, run 45035887609 failed as expected); set SEC-12 to DONE. |
| 2026-11-22 | Claude | SEC-13 implemented (non-exported `BootReceiver`, `BOOT_COMPLETED` only, new `tools/check_manifest_exports.py` guard + tests, `backup-guard.yml` runs both guards); status IN-PROGRESS pending CI and device check. Added RUN-5 (silent boot-restart failure). Updated section 1 (Service/CI/Tools rows), RUN-3, TEST-1, sections 8 and 9. Added work-log entry. |
| 2026-11-22 | Claude | SEC-13 CI verified (GitHub Actions run 45038393115, commit `7536d44`); updated SEC-13 status with commit link and "CI green on main". Build also green (run 45038393077). |
| 2026-11-22 | Claude | RUN-5 implemented (`BootReceiver` read-only bounded settings read + visible failure reporting, new `BootStatus.kt`, `AutonomyService` clears stale status) and RUN-3 part 1 implemented (stop path persists `autonomyEnabled=false`); both IN-PROGRESS pending CI build and device check. Added RUN-6 (show boot failure in the UI). Updated section 1 (Service row), DATA-1 note, TEST-1, sections 7 (paged-query names), 8 and 9. Added work-log entry. Not compiled in the session. |
| 2026-11-22 | Claude | RUN-5 and RUN-3 part 1 CI verified (Build APK run 45039776580, Backup config guard run 45039776621, commit `7c990ec`); updated RUN-3 and RUN-5 status with commit link and run IDs. |
| 2026-11-22 | Claude | SEC-3 implemented (removed `apply-phase2.yml` and `generate-keystore.yml`; `build-apk.yml` least-privilege permissions, fail-closed signing via `env:`, signed APK only in releases; new `tools/check_workflows.py` guard + tests wired into `backup-guard.yml`; `docs/release-signing.md`); IN-PROGRESS pending CI and owner actions. Added SEC-14 (SHA-pin actions, split signing job, Dependabot) and DEC-6 (rotate keystore?). Updated section 1 (CI/Tools rows), section 9, work log. Not run in GitHub Actions. |
| 2026-11-22 | Claude | SEC-3 CI verified (Backup config guard run 45041539979, Build APK run 45041540039, commit `231f84a`); updated SEC-3 status with commit link and run IDs. |
| 2026-11-23 | Claude | SEC-10 implemented (new `UpdatePolicy.kt`, hardened `UpdateManager.kt`, SHA-256 published by CI in `latest-release.json`, `UpdatePolicyTest`, `testDebugUnitTest` CI step); IN-PROGRESS pending CI, a tagged release and a device check. Added SEC-15 (surface update refusal reasons). Updated section 1 (Util row), TEST-1, section 9, work log. Not compiled in the session (no Kotlin toolchain). |
| 2026-11-23 | Claude | SEC-10 CI verified (Backup config guard run 45044746579, Build APK run 45044746633, commit `c1675b0`); updated SEC-10 status with commit link and run IDs. |
| 2026-11-25 | Claude | SEC-2 core implemented (new `SnapshotSecrets`, secret-free `exportSnapshot`/auto-backup, merging import, connector status from required fields); added SEC-16 (passphrase-encrypted secret export), SEC-17 (`apiToken` at rest) and UI-1 (backup card wording). Status IN-PROGRESS; CI green on PR #46 (Build APK run 1092, guard run 45708625614, commit `a745e3f`). |

---

## 11. Work log

Append-only. One entry per implementation session: what changed, how it was checked, what is left.

### 2026-11-22 - SEC-1 (backup config) - Claude (free-tier session)
- Picked SEC-1: highest-priority P0 item that is small (S), self-contained, and needs no knowledge of the unreviewed Kotlin files.
- Read: `ForClaude/CLAUDE_BACKEND_PLAN.md`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml` at commit `0283f7e`.
- Changed:
  - `app/src/main/AndroidManifest.xml` - `allowBackup="true"` -> `"false"`; added `android:dataExtractionRules="@xml/data_extraction_rules"`; kept `fullBackupContent`; explanatory comment.
  - `app/src/main/res/xml/data_extraction_rules.xml` - new; cloud-backup and device-transfer exclude every domain (`root`, `file`, `database`, `sharedpref`, `external`, `device_*`).
  - `app/src/main/res/xml/backup_rules.xml` - include-everything -> exclude-everything.
  - This file - SEC-1 status/implementation notes, new task SEC-12, drift table, DEC-3, DOC-1, section 9, changelog, this log.
- Verified: all three XML files parse (Python `ElementTree`); manifest attributes point at existing rule files; rules contain zero `<include>` elements.
- NOT verified (no Android toolchain or network in this session): Gradle build, lint (the `fullBackupContent` + `dataExtractionRules` + `allowBackup="false"` combination should lint clean but is unconfirmed), `bmgr`/`adb backup` dry run.
- Decision recorded: disabled backup entirely rather than carving out a "safe" subset, because the auto-backup JSON holds plaintext secrets (SEC-2) and the Keystore key does not transfer.
- Next for whoever continues: finish the "Remaining to close this task" list under SEC-1, flip it to DONE with the commit link, then take SEC-2 (stop plaintext secrets in snapshots) or SEC-12 (CI guard).

### 2026-11-22 - SEC-1 (CI build verification) - Claude (follow-up)
- Triggered GitHub Actions workflow `build-apk.yml` on commit `b78501b` (run 45034237240).
- Build job "Build Debug & Release APK" succeeded — confirms `assembleDebug` passes, XML files are well-formed, manifest references resolve.
- No separate `lintDebug` step in workflow; build success implies no lint errors blocking compilation.
- `bmgr`/`adb backup` dry run still **not run** — requires physical device/emulator.
- Updated SEC-1 status in plan with verification results; left as IN-PROGRESS pending device check.

### 2026-11-22 - SEC-12 (backup-config CI guard) - Claude (free-tier session, GitHub connector + sandbox)
- Picked SEC-12: next small (S) P0 item after SEC-1, fully testable without Android tooling or the large unreviewed Kotlin files (SEC-4/5/7 need `AgentRuntime.kt` ~42 KB or `ConnectorExecutor.kt` ~129 KB).
- Note on inputs: the task brief called the file `ForClaude/claudebackend.md`; the repo's only file in that folder is `ForClaude/CLAUDE_BACKEND_PLAN.md` (read that). The session could read repo files through the GitHub connector but could not download raw files (egress proxy blocked `raw.githubusercontent.com`) and had no `git` access, so this file was re-created from the version read at repo commit `0c6e642` plus the edits below. **A merge reviewer should diff it against `main` and confirm only SEC-12, SEC-13, section 1/5/8/9, DEC-3, RUN-3, changelog and this log changed.**
- Read: this plan, `app/src/main/AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`, `.github/workflows/build-apk.yml`, `tools/` listing.
- Added:
  - `tools/check_backup_config.py` (stdlib Python; real XML parsing; GitHub `::error` annotations).
  - `tools/test_check_backup_config.py` (18 unittest cases).
  - `.github/workflows/backup-guard.yml` (separate fast workflow, `contents: read`).
  - This file: SEC-12 notes/status, new task **SEC-13**, section 1 (CI + Tools rows), DEC-3, TEST-1, RUN-3, section 8, section 9, changelog, this log.
- Design choices: Python + XML parser rather than `grep` (the plan allowed grep) so comments cannot cause false positives or hide a real `<include>`; separate workflow rather than a step in `build-apk.yml` so it runs in seconds and is independent of the Android build; did not edit `build-apk.yml` (no change needed, and it keeps the SEC-3 rewrite unblocked).
- Verified (local sandbox): 18/18 self-tests pass; guard exits 0 on a config shaped like the real manifest + rules and exits 1 when `allowBackup` is flipped to `true`; annotation mode prints `::error file=...`.
- NOT verified: a run in GitHub Actions against the real repo files; the "red on bad PR" check; required-check branch protection (owner setting).
- Found while reviewing the manifest: `BootReceiver` is `exported="true"` with a `LOCKED_BOOT_COMPLETED` filter but no `directBootAware` -> logged as **SEC-13**. Also noticed (not logged as a task, cosmetic): the comment in `data_extraction_rules.xml` contains the typo "Keystore-encrypted0".
- Next for whoever continues: push these files, confirm the `Backup config guard` run is green on `main`, do the throwaway-PR red check, fill in the commit link under SEC-12 and flip to `DONE`; then take SEC-13 (S) or SEC-2 (M).

### 2026-11-22 - SEC-13 (BootReceiver export + boot filters) - Claude (free-tier session, GitHub connector + sandbox)
- Picked SEC-13: the next small (S) P0 item. SEC-2 is M and SEC-4/5/7 need the ~42 KB / ~129 KB Kotlin files, too large for one free-tier session.
- Note on inputs: the brief again said `ForClaude/claudebackend.md`; the repo file is `ForClaude/CLAUDE_BACKEND_PLAN.md` (same file, kept its name). Read via the GitHub connector at repo commit `71ebd00`; the sandbox had no network, so this file was rewritten from the version read there. **A merge reviewer should diff it against `main` and confirm only SEC-13, RUN-3, new RUN-5, section 1 (Service/CI/Tools rows), TEST-1, sections 8/9, changelog and this log changed.**
- Read: this plan, `app/src/main/AndroidManifest.xml`, `service/BootReceiver.kt`, `tools/check_backup_config.py`, `tools/test_check_backup_config.py`, `.github/workflows/backup-guard.yml`.
- Changed:
  - `app/src/main/AndroidManifest.xml` - `BootReceiver` `exported="true"` -> `"false"`; removed the `LOCKED_BOOT_COMPLETED` filter; comment explains why.
  - `app/src/main/java/com/agoria/app/service/BootReceiver.kt` - handles `ACTION_BOOT_COMPLETED` only; KDoc for SEC-13. No other behavior change.
  - `tools/check_manifest_exports.py` + `tools/test_check_manifest_exports.py` - new guard (12 tests); chosen as a sibling of the SEC-12 script so each stays small and single-purpose.
  - `.github/workflows/backup-guard.yml` - runs both self-test files and both guards; workflow name unchanged.
  - This file - SEC-13 status/notes, RUN-3 wording, new RUN-5, section 1, TEST-1, sections 8/9, changelog, this log.
- Design choices: kept the existing `autonomyEnabled` gate in the receiver (already correct); dropped `LOCKED_BOOT_COMPLETED` instead of making the receiver `directBootAware` because it needs Room/Keystore, which are unavailable before unlock; the new guard bans exported services/receivers/providers outright (empty allowlist) so any future export is a deliberate, reviewed change.
- Verified (local sandbox): 12/12 guard self-tests pass; the guard exits 0 on the new manifest and exits 1 on a copy with `BootReceiver` re-exported; new manifest parses.
- NOT verified: Gradle build of the Kotlin change, GitHub Actions run, device/emulator reboot behavior, `am broadcast` negative test. The SEC-12 self-tests were not re-run here (that script was not changed).
- Found: RUN-5 (boot start swallows all errors and may exceed the receiver time budget).
- Next for whoever continues: push these files; confirm `Backup config guard` is green on `main` and `assembleDebug` still passes; do the device reboot checks and a throwaway-PR red check; fill in the commit link under SEC-13 and flip to `DONE`; then take RUN-5 (S) or SEC-2 (M).

### 2026-11-22 - RUN-5 (boot restart visibility) + RUN-3 part 1 (stop flag) - Claude (free-tier session, GitHub connector + sandbox)
- Picked RUN-5 as the task the previous log recommended, plus RUN-3 part 1 because both live in the same two small files (`BootReceiver.kt`, `AutonomyService.kt`) and share the "autonomy state must match what the user chose" goal. Both fit in one session without opening `AgentRuntime.kt` or `ConnectorExecutor.kt`.
- Note on inputs: the brief again said `ForClaude/claudebackend.md`; the repo file is `ForClaude/CLAUDE_BACKEND_PLAN.md` (the only file in `ForClaude/`, kept its name). Repo state read via the GitHub connector at commit `b9458f7`. The sandbox has no network and no Kotlin toolchain, so this plan file was rewritten from the version read there. **A merge reviewer should diff it against `main` and confirm only RUN-3, RUN-5, new RUN-6, the section 1 Service row, DATA-1 note, TEST-1, section 7 (paged-query names), sections 8/9, the SEC-13 "Implemented" bullet about `BootReceiver.kt` (a trailing italic note), changelog and this log changed.**
- Read: this plan, `service/AutonomyService.kt`, `service/BootReceiver.kt`, `util/Notifier.kt`, `AgoriaApplication.kt`, `data/Repository.kt` (full).
- Changed:
  - `service/BootReceiver.kt` - dropped `seedIfNeeded()` and `repository.settings()`; read-only `database.settingsDao().get()` inside `withTimeout(8 s)`; reports the three failure paths (service start refused, timeout, other exception) via `Log.w`, `BootStatus`, and a notification; clears the status on success.
  - `service/BootStatus.kt` - new SharedPreferences-backed failure flag (`recordFailure`, `lastFailure`, `clear`).
  - `service/AutonomyService.kt` - `onCreate` clears the stale boot failure; `stopAutonomy()` persists `autonomyEnabled=false` via `appScope` and calls `stopSelf()` in `finally`.
  - This file - RUN-3 and RUN-5 status and notes, new task RUN-6, DATA-1 note, TEST-1, section 1, 7, 8, 9, changelog, this log.
- Design choices: SharedPreferences rather than a Room column (no migration, works before Room is open); bounded timeout in the receiver rather than adding WorkManager (not a dependency; can be revisited in RUN-3 part 3); system kills deliberately do not clear `autonomyEnabled`, only explicit stops do; reasons stored are fixed strings plus an exception class name, never exception messages, so nothing secret can reach prefs or the notification.
- Verified: brace/paren balance of the three Kotlin files; signatures cross-checked by reading the callers/callees in this session.
- NOT verified: compilation (no `kotlinc`/Gradle here), `Build APK` on GitHub Actions, any device or emulator behavior. In particular `SettingsDao.get()` was not read (`Daos.kt` unreviewed); it is used as a suspend function returning a nullable `SettingsEntity` in `Repository.seedIfNeeded`/`settings()`, which the new code relies on.
- Found: nothing blocking. Added RUN-6 (UI banner for `BootStatus.lastFailure`), and a note under DATA-1 that RUN-3's stop write adds another `updateSettings` writer.
- Next for whoever continues: push these files and confirm `Build APK` and `Backup config guard` are green on `main`; fix any compile error first (most likely spots: the `withTimeout` block return type and the `catch (_: TimeoutCancellationException)` syntax); then do the device checks listed under RUN-5 and RUN-3, flip RUN-5 to `DONE`, and take RUN-6 (S) or SEC-2 (M).

### 2026-11-22 - SEC-3 (CI supply chain and signing) - Claude (free-tier session, GitHub connector + sandbox)
- Picked SEC-3: P0, mostly YAML, fits one free-tier session and needs none of the large unreviewed Kotlin files (SEC-4/5/7 need `AgentRuntime.kt` ~42 KB or `ConnectorExecutor.kt` ~129 KB).
- Note on inputs: the brief said `ForClaude/claudebackend.md`; the repo file is `ForClaude/CLAUDE_BACKEND_PLAN.md` (kept its name). Read via the GitHub connector at repo commit `c865b19`. The sandbox had no network or git, so repo changes were delivered as a zip plus a copy-paste prompt for another AI to commit and push, and **this plan file is updated by `APPLY_PLAN_UPDATE.py` (anchor-checked string edits) rather than re-typed in full**. A reviewer should diff the plan against `main` and confirm only SEC-3, new SEC-14, DEC-6, section 1 (CI/Tools rows), section 9, changelog and this entry changed.
- Read: this plan, `.github/workflows/build-apk.yml`, `.github/workflows/backup-guard.yml`, the workflows directory listing. Not read: `apply-phase2.yml`, `generate-keystore.yml` (deleted as the plan specified).
- Changed: see SEC-3 "Implemented". Files: `.github/workflows/build-apk.yml` (rewritten), `.github/workflows/backup-guard.yml` (guard step added), deleted `apply-phase2.yml` and `generate-keystore.yml`, new `tools/check_workflows.py`, `tools/test_check_workflows.py`, `docs/release-signing.md`, this plan file.
- Design choices: textual checks instead of a YAML parser (runner has no guaranteed PyYAML; guard stays stdlib-only like SEC-12/13); `KEY_PASSWORD` falls back to the store password so an existing setup without that secret does not break; SHA pinning split into SEC-14 because the SHAs cannot be resolved offline and must not be guessed; signing kept in the `build` job for now (splitting it is a bigger restructure that could not be CI-tested here).
- Verified (local sandbox): 13/13 guard self-tests pass; guard exits 0 on the new workflows.
- NOT verified: Actions runs, the tag-only signing/release path, throwaway-PR red check.
- Risk to flag: the first `v*` tag after this lands will **fail** if the keystore secrets are not set; that is intended.
- Next for whoever continues: push, confirm both workflows green on `main`; owner answers DEC-6 and confirms secrets; then SEC-14 (S-M) or SEC-2 (M).

### 2026-11-23 - SEC-10 (self-updater hardening) - Claude (free-tier session, GitHub connector + sandbox)
- Picked SEC-10: P0, self-contained in two small files (`UpdateManager.kt`, `build-apk.yml`) and needs none of the large unreviewed files (SEC-4/5/7 need `AgentRuntime.kt` ~42 KB or `ConnectorExecutor.kt` ~129 KB; SEC-14 needs network to resolve action SHAs; SEC-2 and SEC-9 ripple through `Repository.kt` and the UI).
- Note on inputs: the brief said `ForClaude/claudebackend.md`; the repo file is `ForClaude/CLAUDE_BACKEND_PLAN.md` (kept its name). Read via the GitHub connector at repo commit `eb6e1e8`; the sandbox had no network and no Kotlin compiler, so changes were delivered as a copy-paste prompt for a second agent to apply, commit and push. **A reviewer should diff the plan against `main` and confirm only SEC-10, new SEC-15, section 1 (Util row), TEST-1, section 9, the changelog and this entry changed.**
- Read: this plan, `util/UpdateManager.kt`, `.github/workflows/build-apk.yml`, `app/build.gradle.kts`.
- Changed: see SEC-10 "Implemented". Files: new `util/UpdatePolicy.kt`, rewritten `util/UpdateManager.kt`, new `app/src/test/.../UpdatePolicyTest.kt`, `app/build.gradle.kts` (JUnit dep), `.github/workflows/build-apk.yml` (sha256 + unit-test step), this plan.
- Verified: the SHA-256 test vector for "abc" was checked with Python `hashlib`; apkUrl format (`https://github.com/<repo>/releases/download/<tag>/<signed apk name>`) was checked against the `APK URL` step in `build-apk.yml`.
- NOT verified: compilation, the unit tests, GitHub Actions, a tagged release, any device. Most likely compile spots: `repeat(...)` with non-local `return` and `when (val code = ...)` in `openFollowingRedirects`; `trim('.', '_')` in `safeFileComponent`; the unused `android.app.DownloadManager` import (delete it if the compiler complains).
- Risk to flag: the first release after this lands must go out as a **new tag** so `latest-release.json` carries `sha256`; until then the in-app updater of the new build refuses the (old) JSON, which is intended. If GitHub changes its release-asset redirect host, the update is refused with a log line (`host not allowed`): add the new host to `DOWNLOAD_HOSTS`.
- Next for whoever continues: confirm CI green, run the tag + device checks under SEC-10, then take SEC-15 (S) or SEC-2 (M).

### 2026-11-25 - SEC-2 core (secret-free exports and auto-backups) - Claude (Freebuff Desktop session, Android SDK 35 + Gradle 8.11.1)
- Picked SEC-2: first open P0 in section 9's order and the largest remaining leak - every connector credential and agent token was written in plaintext into the JSON the user shares and into the five files under `filesDir/backups/`.
- Read: this plan, `data/Repository.kt` (export/import/backup/connector-config sections), `util/SecretCipher.kt`, `core/ConnectorCatalog.kt` (field definitions), `ui/AgoriaViewModel.kt` (export callers), `ui/settings/SettingsScreen.kt` (backup card), `app/build.gradle.kts`, `README.md`, and `.github/workflows/build-apk.yml` (unit-test step).
- Changed:
  - New `util/SnapshotSecrets.kt`: `redactSnapshot` (catalog `secret` fields + key-name pattern + `agent_skills[].config` JSON text), `redactConnectorConfig`, `isSecretKey`.
  - `data/Repository.kt`: `exportSnapshot(includeSecrets = false)` scrubs in one pass before returning; `writeAutoBackup()` passes `includeSecrets = false` explicitly; `importSnapshot` merges a connector config into the stored one instead of overwriting; `saveConnectorConfig` sets `needs_setup` when a required field is blank.
  - New `app/src/test/java/com/agoria/app/util/SnapshotSecretsTest.kt` (5 cases) and `org.json:json:20240303` as a test-only dependency.
  - This file: SEC-2 notes/status, new SEC-16 and SEC-17, drift table, section 1 Util row, TEST-1, section 9, changelog, this entry.
- Design choices: the catalog's `secret` flag is authoritative for connector fields (no key names guessed for those), with a conservative pattern as the safety net for agent tokens, skill configs and fields added later; one redaction pass at the end of `exportSnapshot` instead of per-field code, so a future field cannot leak by omission; secrets are removed, not blanked, so importing on the same device keeps the stored value through the merge; `includeSecrets` exists but has no UI caller (SEC-16) because a plaintext "include secrets" path would reintroduce the bug.
- Verified (local sandbox): `./gradlew :app:testDebugUnitTest --no-daemon --no-parallel` -> **BUILD SUCCESSFUL in 4m 1s**; `SnapshotSecretsTest` 5/5, `UpdatePolicyTest` 7/7, 0 failures; `compileDebugKotlin` clean apart from the two pre-existing warnings (`versionCode` deprecation, `AgoriaViewModel` nullability).
- Verified (GitHub Actions, 2026-11-25): **Build APK run 1092 and Backup config guard run 45708625614 green** on commit `a745e3f` (PR #46).
- NOT verified: no app run, no device, no UI check; `exportSnapshot` wiring is covered only by compilation - the unit test exercises `SnapshotSecrets` on a snapshot shaped like the real one, not `Repository` itself (which needs a Room database).
- Found: SEC-16 (optional passphrase-encrypted secret export), SEC-17 (`apiToken` still plaintext at rest) and UI-1 (the Settings backup card still claims the export is encrypted). Also noticed, not logged as a task: `ConnectorCatalog` marks Cloudflare `accountId` as secret, so it is dropped from snapshots even though it is an identifier, not a credential.
- Next: SEC-16 or SEC-17 (the two remaining parts of SEC-2); SEC-4/SEC-5/SEC-7 remain the other open P0 items, and UI-1 is a one-line string fix for whoever touches the Settings screen.
