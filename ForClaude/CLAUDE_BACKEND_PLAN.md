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
| Service | `service/AutonomyService.kt`, `BootReceiver.kt` | Foreground service (`specialUse`) with a wake lock keeps the loop alive. |
| Util | `util/SecretCipher.kt`, `UpdateManager.kt`, `Files`, `Json`, `Notifier`, `Share`, `Time` | Keystore AES-GCM, self-update from GitHub Pages JSON. |
| CI | `.github/workflows/*.yml` | Build debug + release APK, sign on tags, publish release and Pages JSON. `backup-guard.yml` (SEC-12) checks the backup config on every push/PR. |
| Tools | `tools/check_backup_config.py`, `tools/test_check_backup_config.py`, `tools/office_gen` | Backup-config regression guard + its self-tests (stdlib Python only). |

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
| "Export a JSON snapshot (without secrets)" | `Repository.exportSnapshot()` exports connector configs **decrypted** and `agent.apiToken` in plaintext. See SEC-2. |
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
- Status: TODO | Effort: M
- Evidence: `Repository.exportSnapshot()` puts `connectorConfig(c.id)` (decrypted) and `a.apiToken` into JSON; `writeAutoBackup()` writes it unencrypted to `filesDir/backups/`; the same JSON is shared from Settings.
- Fix: default export has **no** secrets. Optional "include secrets" export is encrypted with a user passphrase (PBKDF2/scrypt + AES-GCM). Auto-backup never includes secrets. Encrypt `AgentEntity.apiToken` at rest with `SecretCipher` (it is currently plaintext in Room). Update README.
- Done when: unit test proves exported JSON contains no token/secret strings; auto-backup excludes secrets.

### SEC-3 - CI supply-chain and signing problems
- Status: TODO | Effort: S-M
- Evidence:
  - `.github/workflows/apply-phase2.yml`: with `contents: write`, downloads a patch from `https://paste.rs/...` (mutable, public) and pushes it. Anyone who can edit that paste, or a commit message containing the trigger token, controls the branch.
  - `.github/workflows/generate-keystore.yml`: prints keystore base64 and both passwords into the Actions log and uploads the `.jks` as an artifact. If the repo is public (likely, since updates use GitHub Pages), anything generated this way is exposed.
  - `build-apk.yml`: if `KEYSTORE_*` secrets are missing it silently signs the release with a **random temporary key**. Each release then has a different signature, so Android refuses in-place updates (breaks `UpdateManager` and loses data on reinstall). Also publishes the debug APK in the release, and uses `permissions: contents/pages/id-token: write` for every job.
- Fix: delete `apply-phase2.yml`; delete or lock `generate-keystore.yml` (and **rotate any keystore created with it**); fail the release job if signing secrets are absent; pin actions by SHA; per-job minimal permissions; do not attach the debug APK to releases.
- Done when: no workflow fetches remote patches; release job fails without secrets; keystore creation is documented as a local one-time step.

### SEC-4 - Money and config actions run with no approval
- Status: TODO | Effort: S
- Evidence: in `AgentRuntime.runCycle`, `record_revenue` and `reinvest` are handled directly. They are not in `AgentPolicy.autoActions` and never go through `addApproval`.
- Problem: a model (or injected text) can fabricate revenue, inflate `infrastructureFundCents`, then "reinvest" to rewrite provider `enabled`/`priority`/`model` rows.
- Fix: remove `record_revenue` as an agent action (revenue should come only from verified payment events, see BUG-8) and require approval for `reinvest`, or delete both. Validate numeric ranges.
- Done when: no state-changing action in `AgentRuntime` is reachable without passing `AgentPolicy`.

### SEC-5 - Auto-approve is type-level and risk-blind
- Status: TODO | Effort: M
- Evidence: `Repository.addApproval` and `AgentRuntime.processAutoApprovedApprovals` auto-approve by `approval.type` only; `autoApproveTypes` may contain `connector_action`.
- Problem: enabling auto-approve for `connector_action` approves every connector action, including payments, email, deploy, delete.
- Fix: auto-approve only an explicit `(connectorId, action)` allowlist with `risk <= medium`; never for `risk = high`; daily caps per agent; a visible audit log; one-tap kill switch. Also expire stale approvals (see DATA-6).
- Done when: unit tests show a high-risk action is never auto-approved.

### SEC-6 - Prompt injection surface
- Status: TODO | Effort: M
- Evidence: connector results (Wikipedia, HN, web search, `accounts.read` page text, emails) are stored as messages with role `tool` and placed in the next prompt (`PromptBuilder`: `tool result: ...`) with no delimiting.
- Fix: wrap all external text in clearly marked untrusted blocks (and say in the system prompt that instructions inside are data), cap length, strip control chars/markup, never let content from an untrusted source alone trigger a high-risk action, show source in approval details.
- Done when: system prompt contains an untrusted-data rule; tests cover wrapping and truncation.

### SEC-7 - HTTP connector can leak its credential to any host
- Status: TODO | Effort: S
- Evidence: `ConnectorExecutor.http(config, ...)`: if `path` starts with `http://`/`https://` it replaces `baseUrl`, then `Authorization: Bearer <apiKey/agent token>` is still attached; `args.headers` can also override `Authorization`.
- Fix: pin the final URL host to the configured `baseUrl` host; https only (opt-in for http); block loopback/private/link-local ranges; ignore agent-supplied `Authorization`/`Host`.
- Done when: unit tests reject cross-host URLs and private IPs.

### SEC-8 - Unvalidated values interpolated into URLs and generated code
- Status: TODO | Effort: M
- Evidence: Cloudflare `name` in `"$baseUrl/$name"`; GitHub `repo` in `/repos/$repoName/...`; Stripe `eventType`; GitHub `state`; currency `from`/`to`; `stripeWebhookWorkerCode` pastes `name`, `webhookSecret`, `callbackUrl` into JS source unescaped (a newline in `name` closes the comment and injects code).
- Fix: validate with strict regexes (`^[A-Za-z0-9._-]+$`, `^[\w.-]+/[\w.-]+$`, ISO currency codes) and `enc()` every query value; embed generated JS values via JSON-escaped literals.
- Done when: fuzz-style unit tests with `../`, `?`, newlines are rejected.

### SEC-9 - SecretCipher swallows failures
- Status: TODO | Effort: S
- Evidence: `SecretCipher.encrypt` returns `null` on any exception; `Repository.saveProviderKey/saveConnectorConfig` store that `null`; `decrypt` returns `null` on failure.
- Problem: a failed encrypt silently drops a secret the user thinks was saved; after restore/key invalidation, every secret looks like "Missing API key".
- Fix: return a result type; surface an error to the UI; mark connector/provider `needs_reentry` when decrypt fails; handle `KeyPermanentlyInvalidatedException`; never persist blank on failure.
- Done when: tests with a fake cipher cover failure paths and UI state shows "re-enter secret".

### SEC-10 - Self-updater accepts any URL and does no integrity check
- Status: TODO | Effort: M
- Evidence: `UpdateManager` downloads `apkUrl` from Pages JSON with no https/host check, no SHA-256, ignores `versionCode` (`parseVersionCode` unused), leaves partial files on failure. Android's same-signature rule limits damage, but see SEC-3 (random signing keys).
- Fix: require https and `github.com/<owner>/<repo>/releases/` host/path; publish `sha256` in `latest-release.json` from CI and verify before install; compare `versionCode`; delete partial downloads.
- Done when: install refuses mismatched hash or wrong host.

### SEC-11 - Stripe webhook secret flow is fake
- Status: TODO | Effort: M | Depends on: BUG-1
- Evidence: `AgentRuntime.generateWebhookSecret()` makes a random `whsec_...` that Stripe never issued; it is embedded in the Worker source and printed to the diary and chat (`setup_billing`).
- Fix: create the endpoint via Stripe API (`POST /v1/webhook_endpoints`) and use the returned `secret`; store it as a Worker secret binding, never in source; never log it; compare signatures in constant time. (Also see DEC-2: the whole webhook-to-phone design may be unworkable.)
- Done when: no secret reaches diary/messages/logs; signature check uses the real Stripe secret.

### SEC-12 - Regression guard for backup config (CI check)
- Status: IN-PROGRESS (2026-11-22) - implemented, **local tests pass; first CI run on `main` pending (needs push)**. Commit: _fill in after push_ | Effort: S | Depends on: SEC-1 verified
- Evidence: added 2026-11-22 after SEC-1. Nothing stops a later edit (or an AI session) from flipping `allowBackup` back to `true` or re-adding `<include>` rules to `backup_rules.xml` / `data_extraction_rules.xml`; the leak would be silent.
- Fix: add a small CI step (shell or Gradle task, run before the build in `build-apk.yml` or a new lint workflow) that fails when: `AndroidManifest.xml` lacks `android:allowBackup="false"`; either rules file contains an `<include` element; `data_extraction_rules.xml` lacks `<cloud-backup>` or `<device-transfer>` exclude blocks; the manifest has no `dataExtractionRules` attribute. A `grep`-based script under `tools/` is enough.
- Done when: CI fails on a PR that sets `allowBackup="true"` or adds an `<include>`, and passes on `main`.
- Implemented (2026-11-22):
  - `tools/check_backup_config.py` - stdlib-only Python (no Gradle/SDK needed). Uses a real XML parser instead of `grep`, so commented-out `<include>` lines do not cause false alarms. Checks: (1) main manifest `allowBackup` is explicitly `"false"` (missing counts as a failure, the Android default is `true`); (2) `android:dataExtractionRules` is present and resolves to a file in `res/xml`; (3) `fullBackupContent`, if set, resolves; (4) no `<include>` in either rules file; (5) `data_extraction_rules.xml` has `<cloud-backup>` **and** `<device-transfer>`, each excluding `root`, `file`, `database`, `sharedpref`, `external` with `path="."`; (6) legacy `backup_rules.xml` has the same excludes; (7) any variant manifest under `app/src/<variant>/` that sets `allowBackup="true"` fails. Emits GitHub `::error` annotations when run in Actions. Exit codes: 0 ok, 1 violations, 2 wrong `--root`.
  - `tools/test_check_backup_config.py` - 18 `unittest` cases build temp project trees: good config passes; commented include ignored; every regression above fails with the expected message; malformed XML and wrong root element fail; wrong `--root` exits 2.
  - `.github/workflows/backup-guard.yml` - new, separate workflow (`permissions: contents read`, 5 min timeout, no Gradle). Runs the self-tests, then the guard, on push to `main`/`kilo/**`, PRs to `main`, and manually. A separate workflow was chosen over a step in `build-apk.yml` so the check runs in seconds and still reports if the long Android build is skipped or fails.
- Verified (2026-11-22, local):
  - ✅ 18/18 self-tests pass (`python3 tools/test_check_backup_config.py`).
  - ✅ Guard passes on a config shaped like the real SEC-1 manifest + rules (reviewed via the GitHub API at `0c6e642`); flipping `allowBackup` to `true` makes it exit 1.
  - ❌ Not yet run in GitHub Actions on the real repo files.
- Remaining to close this task: (1) push; confirm the `Backup config guard` run is green on `main`; (2) open a throwaway PR that sets `allowBackup="true"` and confirm the check goes red, then close it; (3) optional: in repo Settings > Branches, make `Check backup config` a required check (a guard that is not required can be merged past); (4) put the commit link above and flip to `DONE`.

### SEC-13 - BootReceiver is exported and probably never gets LOCKED_BOOT_COMPLETED
- Status: TODO | Effort: S | Verify first: `service/BootReceiver.kt` was not reviewed
- Evidence (from `AndroidManifest.xml`, reviewed 2026-11-22): `<receiver android:name=".service.BootReceiver" android:enabled="true" android:exported="true">` with intent filters for `BOOT_COMPLETED` **and** `LOCKED_BOOT_COMPLETED`. It has no `android:directBootAware="true"`.
- Problem 1: system broadcasts such as `BOOT_COMPLETED` are delivered to non-exported receivers too, so `exported="true"` only lets any other app on the phone send this receiver crafted intents. Its job is to start the foreground autonomy service (see RUN-3), so a third-party app could try to trigger that.
- Problem 2: without `directBootAware`, the `LOCKED_BOOT_COMPLETED` filter is never delivered before first unlock; if it is made direct-boot aware, the receiver must not touch credential-encrypted storage (Room, `filesDir`, Keystore-backed secrets).
- Fix: set `android:exported="false"`; drop the `LOCKED_BOOT_COMPLETED` filter unless there is a real need; in `BootReceiver.onReceive` check `intent.action` against the expected system actions and read `settings.autonomyEnabled` before starting the service (ties into RUN-3, which also covers the stop-path flag). Extend `tools/check_backup_config.py` or add a sibling check so a receiver with a boot filter cannot be re-exported.
- Done when: manifest has `exported="false"` on `BootReceiver`; a device/emulator reboot starts the service only when autonomy is enabled; a crafted `am broadcast` from another app/package does not start it.

---

## 3. P0 - Features that are dead or broken

### BUG-1 - `stripe` connector is not in the catalog
- Status: TODO | Effort: M | Depends on: DEC-1
- Evidence: `ConnectorExecutor.dispatch` has `"stripe" -> stripe(...)`, but `ConnectorCatalog.all` has no `stripe` entry. `execute("stripe")` returns "has no definition"; `repo.connector("stripe")` is null; `syncConnectorCatalog()` deletes connector rows that are not in the catalog.
- Effect: `setup_billing`, `check_revenue`, Stripe events, webhook deployment cannot work.
- Fix: add a `ConnectorDef` (secretKey field, actions, `risk = high`, mutating flags) **or** delete the Stripe code if monetization is cut (DEC-1).
- Done when: a configured Stripe connector can run `list_products` against a test-mode key.

### BUG-2 - `setup_billing` and `deploy_service` approvals can never execute
- Status: TODO | Effort: M
- Evidence: both create approvals of type `connector_action` whose `details` hold `{productName,...}` / `{name,code,routes}`. `executeApproval` reads `connector_id`/`action_name` from details, finds none, returns "Approval data did not include a connector action." For `deploy_service` the code is also truncated to 2000 chars in the approval.
- Fix: create dedicated approval types with their own executors, or store a normalized `{connector_id, action_name, args}` payload. Store the full code (file or full text) and show it in the approval UI before approving.
- Done when: an approved deploy runs the exact code that was shown.

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
- Status: TODO | Effort: S
- Evidence: `ConnectorExecutor.http()` retries up to 3 times on `-1`, `429`, `>=500` for **all** methods with `Thread.sleep`.
- Effect: duplicate emails, Slack/Discord posts, GitHub issues, PayPal/Stripe objects.
- Fix: retry only GET/HEAD (and PUT/DELETE when safe), or POST only with an idempotency key; honor `Retry-After`; use coroutine `delay`, not `Thread.sleep`; make calls cancellable (see RUN-1).
- Done when: a MockWebServer test shows POST is sent once on 500.

### BUG-7 - String template mistakes in `AgentRuntime` / `PromptBuilder`
- Status: TODO | Effort: S
- Evidence: `'$request.connectorId'` (prints the object, not the id) in `approveConnectorRequest`; `"\${amountCents/100}"` style escapes in the `record_revenue`, `reinvest`, `check_revenue` diary strings print literal `${...}` text; `\$${...}` in `PromptBuilder` raw strings prints a backslash.
- Fix: use `${request.connectorId}` and real `$` handling; add a unit test that renders these messages.

### BUG-8 - Revenue tracking is fragile and can double count
- Status: TODO | Effort: M | Depends on: BUG-1
- Evidence: `check_revenue` de-duplicates only within the same agent (`recentRevenueForAgent(agentId, 100)` + `metadata.contains(eventId)`), so two agents polling the same Stripe event both book it; `parseStripeEvents` scrapes text lines and `rest.split(" at ")[1]` can throw; `userProfitSharePercent` is stored but unused and the two percentages are not validated to total 100; `calculateReinvestCost` invents prices that are not real spend.
- Fix: unique index on `(source, externalId)`; read events as JSON; store amounts as integer minor units with currency; one ledger table (fund = sum of ledger rows, see DATA-1); validate splits; remove fake costs.

### BUG-9 - BuildRunner reports fake success and leaves builds "building"
- Status: TODO | Effort: M
- Evidence: when the LLM fails or returns invalid JSON, `writeFallback` creates a 4-file stub and the run still ends as "Build complete" and `executeApproval` sets the approval to `executed`; `BuildProgress.Failed` is never emitted; `repo.addBuild` stores status `"building"` and nothing ever updates it; exceptions leave `_buildProgress` stuck; `LlmRequest.json = true` is ignored by `LlmClient`; `next_steps` are parsed then discarded; one shared progress flow for concurrent builds; files are truncated to 30,000 chars silently; no cancellation (`isActive` is never checked).
- Fix: mark stub builds as `fallback`/failed unless the user asked for a stub; set status to `ready`/`failed`; wrap in try/catch and emit `Failed`; keep `next_steps` in the manifest; key progress by approval id; support cancellation and cleanup.
- Done when: a failed model call shows a failed build, not a successful one.

### BUG-10 - LlmClient retry/fallback issues
- Status: TODO | Effort: M
- Evidence: `repeat(2) { ... return@repeat }` does not leave the loop, so a non-retryable 4xx is simply sent a second time; fixed `delay(600)` and no `Retry-After`; no per-provider cooldown or circuit breaker, so a rate-limited or dead provider is tried again every cycle by every agent (anonymous OVH allows 2 requests/minute); `Semaphore(2)` is held across the whole fallback chain; `call.execute()` inside `withContext` is not cancelled when the coroutine is cancelled; `LlmRequest.json` is never mapped to `response_format`.
- Fix: break out correctly; per-provider cooldown with exponential backoff and `Retry-After`; release the permit between providers; cancel the `Call` on coroutine cancellation (`suspendCancellableCoroutine` + `invokeOnCancellation`); send `response_format: json_object` where the provider supports it.
- Done when: MockWebServer tests cover 4xx, 429 with `Retry-After`, 500 fallback, cancellation.

### BUG-11 - Smaller connector defects
- Status: TODO | Effort: S each
- Telegram `get_updates` returns only update ids, not message text.
- Notion `create_page`: both branches of the `properties` builder are identical, and a database parent needs the database's real title property name (not literal `title`).
- GitHub `setupHelp` asks for `administration` permission; recommend least privilege (contents + issues).
- `ConnectorEntity`/catalog fields `zoneId`, `organizationId` are unused.
- `stripe.verify_key` takes an `apiKey` argument from the agent (puts a secret key into approval details and logs); remove.
- `huggingface` actions are non-mutating but send agent-controlled text to a third party; review risk level.

---

## 4. P1 - Data model, reliability, scale

### DATA-1 - Lost updates on settings and fund balance
- Status: TODO | Effort: M
- Evidence: `Repository.updateSettings` reads the whole row then upserts the whole row; `updateInfrastructureFund` is read-modify-write with no transaction; the UI and the runtime both write settings.
- Fix: atomic SQL (`UPDATE settings SET infrastructureFundCents = MAX(0, infrastructureFundCents + :delta)`), `@Transaction` DAO methods, or a ledger table (preferred, see BUG-8).

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
- Status: TODO | Effort: M
- Evidence: `exportSchema = false` (no schema JSON, no migration tests); on a failed upgrade `AppDatabase.create` renames the DB and silently starts empty, so the user just sees an empty office; `create()` opens the DB synchronously in `Application.onCreate` (main thread).
- Fix: `exportSchema = true` + `room.schemaLocation` + `MigrationTestHelper` tests for 1->4; surface "recovered from failed upgrade, restore from backup?" to the user; open the DB off the main thread.

### DATA-5 - Foreign keys delete or orphan important data
- Status: TODO | Effort: S
- Evidence: `RevenueEntity` has `onDelete = CASCADE` on agent, so deleting an agent erases its financial history; `builds` and `memories` have no FK (orphans after agent delete); `deleteProject` leaves tasks/builds with dangling `projectId`.
- Fix: ledger rows survive agent deletion (`SET_NULL` plus stored agent name); clean up or reassign orphans.

### DATA-6 - No approval de-duplication or expiry
- Status: TODO | Effort: S
- Evidence: `addApproval` always inserts; an agent can queue the same request each cycle; approved-but-old actions can execute long after they were proposed.
- Fix: de-dup key `(agentId, type, hash(details))` for pending items, max N pending per agent, TTL (for example 24 h) after which `pending` becomes `expired`.

### RUN-1 - Loop robustness and cancellation
- Status: TODO | Effort: M
- Evidence: `tickAll` runs agents sequentially, so one slow model call (up to 100 s per provider) stalls the office; no per-cycle timeout; `stop()` cancels the coroutine but blocking OkHttp calls keep running (see BUG-10); a failing agent is retried at the normal cooldown (`failureCount` is counted but not used for backoff); `processAutoApprovedApprovals` and the post-action auto-approve block call `executeApproval` without the agent mutex.
- Fix: `withTimeout` per cycle, exponential backoff using `failureCount`, take the mutex around all `executeApproval` calls, cancel in-flight HTTP on stop.

### RUN-2 - Token and request budget
- Status: TODO | Effort: M
- Evidence: 8 agents x one cycle per cooldown, each with a prompt built from up to 12 diary + 10 memory + 12 message entries plus catalog and peers, against free tiers (Kilo about 200 req/h/IP, OVH 2 req/min anonymous).
- Fix: global requests-per-hour and tokens-per-hour budget, adaptive cooldown, skip a cycle when nothing changed (event-driven wake on new user message/approval/result), prompt size cap with token estimate, per-provider quota tracking in `ProviderEntity`.

### RUN-3 - AutonomyService battery and lifecycle
- Status: TODO | Effort: M | Verify first: `BootReceiver.kt` was not reviewed
- Evidence: `AutonomyService.acquireWakeLock()` takes a `PARTIAL_WAKE_LOCK` with no timeout and holds it for the whole service life; the notification "Stop autonomy" action calls `runtime.stop()` but does not update `settings.autonomyEnabled`, so a boot receiver that reads that flag may restart it.
- Fix: hold the wake lock only while a cycle runs (with a timeout), write `autonomyEnabled=false` on every stop path, add a WorkManager watchdog, and test on Android 15 foreground-service-from-boot rules. See also SEC-13 (receiver export and boot filters).

### RUN-4 - Approval outcome is classified by string prefix
- Status: TODO | Effort: S
- Evidence: `executeApproval` decides `ok` with `outcome.startsWith("Failed")`, `"Build failed"`, `"Build skipped"`, etc.
- Fix: return a sealed `ExecutionResult(ok, summary, retryable)` from each executor.

### CONN-1 - Read-only gating ignores action aliases and `check_revenue`
- Status: TODO | Effort: S
- Evidence: `ConnectorCatalog.isReadOnly` looks up the raw action name, but `ConnectorExecutor.canonicalAction` maps aliases (`ls`, `cat`, `save`...) later, so aliases always need approval (fails closed, but noisy). `check_revenue` calls `useConnector` directly and skips gating entirely.
- Fix: canonicalize before the policy check; route every connector call through one gate.

### CONN-2 - `files` connector shares one workspace and has no quota
- Status: TODO | Effort: S
- Evidence: all agents write to `Files.workspace(context)`, the same tree as builds; `write` accepts 200,000 chars per call with no total limit; `findByName` walks the whole tree.
- Fix: per-agent subdirectory (read-share via explicit grant), total quota, depth/count limits for walks.

### CONN-3 - Bounded response bodies, closed responses
- Status: TODO | Effort: S
- Evidence: `httpOnce` and `duckDuckGoResults` read whole bodies with `string()`; `duckDuckGoResults` does not close the response on failure paths.
- Fix: cap body size (for example 1 MB) with a streaming read, always `use {}` the response.

### CONN-4 - Web search relies on scraping
- Status: TODO | Effort: M
- Evidence: `webSearch` falls back to parsing DuckDuckGo HTML with regexes.
- Fix: use a documented search API (Brave/Tavily/SearXNG, optional key) with the scraper as last resort.

### LLM-1 - JSON protocol robustness
- Status: TODO | Effort: M
- Evidence: `AgentPolicy.parseDecision` accepts only a clean JSON object; otherwise the model text becomes a diary "thought" and nothing happens.
- Fix: lenient extraction of the first balanced `{...}`; one automatic "return only JSON" retry; per-action schema validation with the error fed back next cycle; use `response_format` where supported (BUG-10).

---

## 5. P2 - Quality, tooling, structure

### TEST-1 - There are no tests
- Status: TODO | Effort: L
- Evidence: `app/build.gradle.kts` has no `testImplementation`/`androidTestImplementation` dependencies at all. (The only tests in the repo are the stdlib-Python self-tests for the SEC-12 guard in `tools/`; they do not cover the app.)
- Fix: add JUnit, MockK/Turbine, MockWebServer, Room testing. First tests: `AgentPolicy.parseDecision`, `ConnectorCatalog.isReadOnly`, path traversal in `BuildRunner` and `files`, `LlmClient` fallback, snapshot export/import round trip (no secrets), migrations 1->4, approval gating (high risk never auto-approved).

### CI-1 - CI hygiene
- Status: TODO | Effort: M
- Fix: run unit tests and lint; remove the 3x retry loops that hide flaky builds; drop `continue-on-error` on artifact uploads; move to current `actions/setup-java`; add ktlint/detekt; Dependabot for Gradle and Actions; verify the minified release build starts (`isMinifyEnabled = true`, rules in `proguard-rules.pro` untested, Room/org.json usage).

### OBS-1 - Audit log and log redaction
- Status: TODO | Effort: M
- Evidence: runtime log is in memory only (last 80 entries); `connector_logs.summary` stores up to 1000 chars of responses and can contain URLs with tokens (Telegram bot token is in the request URL).
- Fix: persisted audit trail (who approved what, with payload hash and result), redact tokens and secrets before writing diary/messages/logs.

### ARCH-1 - Split the two giant classes
- Status: TODO | Effort: L
- Evidence: `ConnectorExecutor.kt` ~129 KB and `AgentRuntime.kt` ~42 KB.
- Fix: `ConnectorHandler` interface per connector (declares actions, schema, mutating flag, rate limit), registered in one place; action handlers in a `Map<String, ActionHandler>`; constructor injection instead of building everything in `AgoriaApplication.onCreate`.

### DOC-1 - Bring README and docs in line with the code
- Status: TODO | Effort: S
- Fix: use the drift table in section 1; one product name; document backup and secret behavior after SEC-1/SEC-2 (SEC-1 now makes "database excluded from backup" accurate; also state that backup/transfer is fully disabled and in-app export is the only path).

---

## 6. Decisions needed from the owner

- **DEC-1 - Keep or cut monetization?** Status: NEEDS-DECISION. Today the whole Stripe/Cloudflare/revenue path is dead (BUG-1..5, SEC-4, SEC-11). Option A: finish it properly (ledger, verified events, approvals). Option B: remove `record_revenue`, `reinvest`, `check_revenue`, `setup_billing`, `deploy_service`, revenue tables usage, prompts, and the Stripe/Worker code.
- **DEC-2 - Webhooks cannot reach a phone.** `deploy_webhook_handler` forwards to `appCallbackUrl`, but the example is a LAN address (`http://192.168.x.x:8080/...`); a Cloudflare Worker cannot call that. Polling Stripe events (what `check_revenue` does) is the only workable path without a relay server. Decide: polling only, or add a small relay.
- **DEC-3 - Backup policy.** Cloud backup on or off? Is a passphrase-encrypted export acceptable? (Interim state since 2026-11-22: backup and device transfer are fully **off** via SEC-1, and guarded by SEC-12. If the owner decides to turn backup on, update `tools/check_backup_config.py` and its tests in the same change, or CI will fail on purpose.)
- **DEC-4 - Product name and package.** Agoria or Dunder (affects strings, User-Agent, release names, package id).
- **DEC-5 - Free-tier budget.** Target number of agent cycles per hour across all agents.

---

## 7. Carry-over from `.kilo/plans/` (backend-relevant items, status as seen in code)

| Item | Source plan | Seen in code? |
| --- | --- | --- |
| Paged DAO/Repository queries for builds, diary, messages | next-improvements P3 | Repository methods exist; DAO and UI use not verified |
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

`data/Daos.kt`, `data/SeedData.kt`, `core/ProviderCatalog.kt`, `core/AgentModels.kt`, `service/BootReceiver.kt`, `util/Files.kt`, `util/Json.kt`, `util/Notifier.kt`, `util/Share.kt`, `browser/*` (BrowserHub, AgentBrowserActivity), `viewer/*`, all of `ui/*`, `gradle.properties`, `proguard-rules.pro`, `tools/office_gen`, `docs/pixel-office.md`, `.gitignore`.

Reviewed since the first pass (2026-11-22): `app/src/main/AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`, `.github/workflows/build-apk.yml` (all while doing SEC-12). The manifest review produced SEC-13. Other manifest items looked at and left alone: `REQUEST_INSTALL_PACKAGES` is used by the self-updater (see SEC-10); `FileProvider` is correctly `exported="false"`; `AutonomyService` is `exported="false"`.

---

## 9. Suggested order of work

1. **Stop the bleeding (P0 security):** SEC-1 (implemented, verify), SEC-12 (implemented, verify on CI), SEC-2, SEC-3, SEC-4, SEC-5, SEC-7, SEC-13.
2. **Correctness core:** BUG-6, BUG-7, BUG-9, BUG-10, DATA-1, DATA-2, RUN-1.
3. **Resolve DEC-1, then either** BUG-1/2/4/5/8 + SEC-11 **or** delete the monetization code.
4. **Hardening:** SEC-6, SEC-8, SEC-9, SEC-10, DATA-3..6, RUN-2..4, CONN-1..4, LLM-1.
5. **Quality:** TEST-1 (start alongside step 1), CI-1, OBS-1, ARCH-1, DOC-1.

---

## 10. Changelog

| Date | Who | Change |
| --- | --- | --- |
| 2026-11-22 | Claude | Initial review of commit `6b7e7ed`; created this file. |
| 2026-11-22 | Claude | SEC-1 implemented (manifest `allowBackup="false"`, new `data_extraction_rules.xml`, exclude-all `backup_rules.xml`); status set to IN-PROGRESS pending build/device verification. Added SEC-12 (CI regression guard). Updated drift table, DEC-3 note, DOC-1, section 9. Added section 11 (Work log). |
| 2026-11-22 | Claude | SEC-1 CI build verified (GitHub Actions run 45034237240, commit `b78501b`); updated SEC-1 status with verification results; `bmgr`/`adb` check still pending. |
| 2026-11-22 | Claude | SEC-12 implemented (`tools/check_backup_config.py`, `tools/test_check_backup_config.py`, `.github/workflows/backup-guard.yml`); status IN-PROGRESS pending first CI run. Added SEC-13 (BootReceiver exported / boot filters) from the manifest review. Updated section 1 (CI/Tools rows), DEC-3, TEST-1, RUN-3 cross-reference, section 8 and section 9. Added work-log entry. |

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
