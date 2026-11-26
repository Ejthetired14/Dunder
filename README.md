# Agoria

Agoria is a local autonomous AI office for Android 13+. Eight agents live inside the app, think on their own, keep a diary, create tasks, and propose builds. Anything that builds a project or touches the outside world asks you first.

The app is built around the free model APIs in [awesome-free-llm-apis](https://github.com/mnfst/awesome-free-llm-apis). The visual direction and office assets come from [Parcha-ai/ai-office](https://github.com/Parcha-ai/ai-office).

## What it does

- **Autonomous agents** — each agent has a persona, a goal, a long-term memory, a private diary, and a status. The runtime runs a cycle on a timer: it writes a diary entry and chooses at most one action.
- **A diary of thoughts** — every cycle produces a diary entry (thought, observation, plan, decision, lesson, error, build, connector, review). Read it in the agent screen or the global Diary tab.
- **Approval gates** — building a downloadable project, sending email, posting to Slack/Discord/Telegram, creating GitHub issues, and making external HTTP requests require an approval. You see the exact payload and approve or deny with a note.
- **Local builds** — an approved build is generated as a small starter project inside the app and packaged as a ZIP. Share it from the Builds tab. Nothing is published.
- **Connectors** — agents can request Gmail, Google Calendar, GitHub, Slack, Discord, Notion, Telegram, a generic HTTP connector, local files, Wikipedia, Hacker News, web search, weather, and currency. You configure credentials in the Connectors screen.
- **Provider fallback** — the app tries enabled providers in priority order. Keyless providers (Kilo, OVHcloud, LLM7) are enabled by default; add your own keys for Groq, OpenRouter, Gemini, Cohere, Cloudflare, NVIDIA, Z AI, and more.
- **Foreground autonomy** — a foreground service keeps the loop alive while the app is backgrounded. A boot receiver restarts it after reboot if autonomy was enabled.
- **Office view** — a visual floor with one desk per agent. Tap a desk to open the agent's chat, diary, tasks, memory, and builds.
- **Export** — export a JSON snapshot of the office (without secrets — API tokens and connector credentials are never included) and share it. Cloud backup and device transfer are fully disabled; in-app export is the only restore path.

## Android

- `minSdk 33` (Android 13)
- `targetSdk 35`
- Kotlin 2.0.21, Jetpack Compose, Material 3, Room, Navigation Compose
- Java 17, Gradle 8.11.1

Tested target: Android 13. The UI is phone-first; it is not optimized for tablets or foldables yet.

## Build the APK

### GitHub Actions (recommended)

The workflow at [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) builds a debug APK on every push, pull request, or manual run.

1. Push this repository to GitHub.
2. Open the **Actions** tab.
3. Run or wait for **Build APK**.
4. Download the **`app-debug.apk`** artifact.
5. Copy the APK to your Android 13 device and install it. You may need to allow "Install unknown apps" for your file manager or browser.

### Local build

```bash
# JDK 17 + Android SDK 35 + build-tools 35.0.0
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. Open the app and enter your name.
2. On the Home screen, turn on **Autonomy**.
3. Allow notifications if Android asks. This lets the foreground service show that the agents are running.
4. Optionally disable battery optimization for Agoria in Settings → Battery settings. Some Chinese and Samsung ROMs are aggressive about background services.
5. Watch the agents write diary entries. Open an agent to chat or edit their persona and goal.
6. When an agent proposes a build or a connector, review it in **Approvals**.

The app seeds eight agents on first launch:

| Agent | Role | Goal |
| --- | --- | --- |
| Michael | Visionary | Help the office ship one useful thing this week |
| Jim | Builder | Build one useful tool or connector |
| Pam | Designer | Give the office a visual identity and story |
| Dwight | Researcher | Benchmark the free providers and report reliability |
| Angela | Analyst | Keep a ledger of approvals, builds, and connector calls |
| Kevin | Connector | Connect the office to the outside world |
| Toby | Scribe | Keep the diary and write a weekly digest |
| Stanley | Critic | Review the work and find the weakest link |

## Free model providers

The app ships with a catalog based on the [awesome-free-llm-apis](https://github.com/mnfst/awesome-free-llm-apis) list:

- **Keyless / optional key**: Kilo Code Gateway, OVHcloud AI Endpoints, LLM7.io
- **Free API key**: Groq, OpenRouter, Google Gemini, Cohere, Cloudflare Workers AI, NVIDIA NIM, Z AI, Aion Labs, Gonka Broker, SiliconFlow, ModelScope

Provider defaults and free-tier notes are in [`ProviderCatalog.kt`](app/src/main/java/com/agoria/app/core/ProviderCatalog.kt). Free tiers change; check each provider's docs for current limits and terms. Some free providers log prompts. Do not put secrets in prompts.

### Why the agents sometimes fail

- A provider can be rate-limited or temporarily down. The runtime falls back to the next enabled provider.
- Anonymous OVH is limited to 2 requests/minute per IP per model. Kilo is 200 requests/hour per IP on the free pool.
- Android may kill a background service on aggressive ROMs. The foreground notification and boot receiver help, but they are not a guarantee.
- A model may return prose instead of JSON. The runtime stores it as a diary entry and keeps going.

## Connectors

Connectors are capability bundles with explicit actions and risk levels. Read-only public connectors can run immediately. Mutating or private connectors require an approval, and the approval shows the exact action and arguments.

| Connector | Auth | Actions |
| --- | --- | --- |
| Wikipedia | none | search, summary |
| Hacker News | none | top, search |
| Web Search | none | search |
| Weather (Open-Meteo) | none | current, forecast |
| Currency (Frankfurter) | none | convert, latest |
| This Device | none | info, notify |
| Local Files | none | list, read, write |
| HTTP Request | API key + base URL | request |
| Gmail | OAuth access token | list, get, send |
| Google Calendar | OAuth access token | list, create |
| GitHub | personal access token | get_repo, list_issues, create_issue |
| Slack | incoming webhook | post |
| Discord | webhook | post |
| Notion | integration token | query, create_page |
| Telegram | bot token | send, get_updates |

Gmail and Calendar use pasted OAuth 2.0 access tokens with the scopes listed in the setup dialog. The app does not implement a full OAuth consent flow yet. Tokens expire (Google access tokens are usually valid for about an hour); paste a fresh one when needed.

## Safety model

- The app has no shell tool and no arbitrary code execution on the device.
- External actions are gated by an approval record that stores the agent, type, risk, payload, decision, and result.
- Build files are sandboxed under the app's private storage. The build runner rejects `..` and absolute paths.
- API keys and connector credentials are encrypted with a key in the Android Keystore before they are stored in Room. The database is local, excluded from backup (`allowBackup="false"`), and never leaves the device except through the in-app JSON export (which deliberately contains no secrets).
- The app is a personal test build, not a hardened multi-user service. Anyone with access to the unlocked device can read the app's data. Use a strong device lock.
- Free providers may log prompts and outputs. Do not send personal or confidential data through a free tier.

## Project layout

```
app/src/main/java/com/agoria/app/
├── AgoriaApplication.kt          # app graph: database, repository, runtime
├── MainActivity.kt
├── core/
│   ├── ProviderCatalog.kt        # free provider specs and defaults
│   ├── ConnectorCatalog.kt       # connector definitions and risk
│   ├── ConnectorExecutor.kt      # real connector implementations
│   ├── BuildRunner.kt            # approved build -> files -> ZIP
│   ├── AgentPolicy.kt            # action parsing and approval rules
│   ├── AgentRuntime.kt           # autonomous loop, chat, approvals
│   ├── PromptBuilder.kt          # persona + context + JSON protocol
│   └── llm/LlmClient.kt          # OpenAI-compatible fallback client
├── data/
│   ├── Entities.kt               # Room entities
│   ├── Daos.kt
│   ├── AppDatabase.kt
│   ├── SeedData.kt               # the eight starting agents
│   └── Repository.kt
├── service/
│   ├── AutonomyService.kt        # foreground service
│   └── BootReceiver.kt
├── ui/
│   ├── AgoriaRoot.kt             # onboarding vs. main app
│   ├── AgoriaNav.kt
│   ├── AgoriaViewModel.kt
│   ├── theme/
│   ├── components/
│   ├── home/ office/ agent/ diary/ approvals/ connectors/ settings/
└── util/
    ├── SecretCipher.kt           # Android Keystore encryption
    ├── Json.kt Files.kt Time.kt
    ├── Notifier.kt Share.kt
```

## Build artifact format

An approved build produces a folder under `files/agent-workspace/<agentId>/<approvalId>/` containing:

- `README.md`
- `PLAN.md`
- `manifest.json` (agent, approval id, goal, file list, timestamp)
- whatever source files the model generated (for example `src/main.kt`, `index.html`, tests)

The folder is zipped to `externalFilesDir/exports/agoria-<name>-<approval>.zip` and shared through a `FileProvider`. The Builds tab lists every artifact with a **Share ZIP** button.

## Assets

- Office sprite sheet: `app/src/main/res/drawable/office_sprites.png` from [Parcha-ai/ai-office](https://github.com/Parcha-ai/ai-office)
- Office tileset: `app/src/main/res/drawable/office_tileset.png` from the same project
- Office thumbnail: `app/src/main/res/drawable/office_thumbnail.jpg` from the same project

The avatars are cropped from the 384×256 sprite sheet: each of the eight characters has three 32×32 frames in a 12-column sheet.

## Known limitations

- No multi-user sync, no cloud backend, no account system.
- No full OAuth flow; connectors use pasted tokens.
- Builds are text projects, not a full IDE or a Gradle/Android build service.
- Background reliability depends on the device and ROM. Foreground service + boot receiver is the best-effort approach without a server.
- The free provider catalog is a snapshot. Model names and limits can change.
- The app is not localized; strings are in English.

## License and credits

The original Agoria code in this repository is provided as-is for personal experimentation. The office assets are from [Parcha-ai/ai-office](https://github.com/Parcha-ai/ai-office) and remain subject to their original terms. The free API list is from [mnfst/awesome-free-llm-apis](https://github.com/mnfst/awesome-free-llm-apis).