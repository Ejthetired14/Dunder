## What's New in v1.0.218

### Pixel Office UI Overhaul (T-BACK / T-BACK2)
- **Shared PixelScaffold**: All sub-pages now use a consistent ink top bar with 2 dp tan border, square BACK TO OFFICE button, and pixel-styled title
- **Diary & Browsers** (T-BACK): First screens converted to PixelScaffold
- **Home, Project, Connectors** (T-BACK2 batch 1): Converted to PixelScaffold with proper titles/subtitles
- **Agent Screen** (T-BACK2): Converted with pause/resume action in top bar
- **Approvals Screen** (T-BACK2): Converted with auto-approve settings action in top bar
- Floating "< Office" button hidden on all scaffolded routes

### Security Hardening (SEC-10)
- Pure Kotlin UpdatePolicy.kt: URL/host allowlist + SHA-256 verification
- Hardened UpdateManager.kt: Manual redirect following, no auto-redirects
- CI publishes sha256 in latest-release.json for in-app verification
- JUnit tests added for update policy

### Pixel Office Phase 5 (Merged Earlier)
- Office as the only top-level view (no bottom bar)
- Notice board directory with all pages
- Amber page labels on tappable objects when zoomed in
- Night shift: warm lamp glow around agents
- Christmas decor (parol lanterns, tree, lights) Sep 1 - Jan 6
- Sound effects with mute switch
- Agent-to-agent chat (say entries, speech bubbles, chatter panel)

### Test Device
- TECNO Spark 20C (Helio G36, 4 GB RAM, Android 13, 720x1612)

---

**APK**: [app-release-signed.apk](https://github.com/Ejthetired14/Dunder/releases/download/v1.0.218/app-release-signed.apk)
**SHA-256**: Published in latest-release.json for in-app verification