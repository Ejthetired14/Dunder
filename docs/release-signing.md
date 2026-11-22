# Release signing (SEC-3)

CI signs release APKs only from repository secrets. It never generates a key.
If a secret is missing, the tagged release job fails on purpose: a random key per
release would give every release a different signature, and Android refuses
in-place updates (and the in-app updater would break).

## One-time setup (do this on your own machine, never in CI)

1. Create the key once:
   `keytool -genkeypair -v -keystore release-key.jks -alias dunder-release -keyalg RSA -keysize 2048 -validity 10000`
2. Base64 it: `base64 -w0 release-key.jks` (macOS: `base64 -i release-key.jks`).
3. Add repository secrets (Settings > Secrets and variables > Actions):
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and optionally `KEY_PASSWORD`
   (defaults to `KEYSTORE_PASSWORD`).
4. Keep `release-key.jks` and its passwords in a password manager. Never commit them,
   never paste them into issues, chats or workflow logs.
5. Push a tag `vX.Y.Z`; the workflow signs, verifies and publishes the signed APK only.

If a keystore was ever created with the removed `generate-keystore.yml` workflow,
treat it as exposed. Rotating it changes the signing key, so existing installs cannot
update in place (see DEC-6 in the backend plan).
