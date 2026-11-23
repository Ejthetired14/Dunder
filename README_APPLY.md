# dunder-phase7-settings-onboarding.zip  (untested, uncompiled)

Run from the repo root, on a branch:

    unzip -o dunder-phase7-settings-onboarding.zip
    python3 tools/apply_t_back2_settings.py
    python3 tools/update_claudefrontend.py
    ./gradlew assembleDebug testDebugUnitTest

Files: AgoriaNav.kt and OnboardingScreen.kt are full replacements. SettingsScreen.kt and
claudefrontend.md are patched by the two scripts (anchor-checked, idempotent).
Then open a PR, wait for a green "Build Debug & Release APK", merge to main.
