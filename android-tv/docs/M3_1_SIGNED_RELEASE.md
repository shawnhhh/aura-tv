# M3.1 — Stable APK update-signing path (requires owner setup)

## Problem

GitHub-hosted debug builds use an ephemeral debug signing key. APKs built on
different CI runners may not install as updates over one another and may show
a signature-conflict error. Removing an old APK can erase local favorites,
profiles, and playback history. A repeatable production-signing identity is
necessary before calling HERRIN ready for regular updates.

## Implementation

- `android-tv/app/build.gradle.kts` uses optional signing identity values only
  from environment variables, never project source or local.properties.
- A separate manual `HERRIN Android Signed Release` GitHub Actions workflow
  runs **only on reviewed master**, demands all four configured secrets,
  runs unit/lint checks, verifies the resulting signed APK with `apksigner`,
  and uploads the release artifact.
- Debug PR builds continue without any signing secrets.
- `versionCode` is now 2, `versionName` 0.3.1-alpha. All future upgrades
  distributed with this identity must maintain the package name, signing
  certificate and monotonically increasing version code.

## Owner-only initial signing setup (keep private)

On a trusted Mac with JDK/keytool, generate one durable private key:

```sh
keytool -genkeypair -v -keystore herrin-release.jks -alias herrin_release -keyalg RSA -keysize 3072 -validity 3650
```

Back up the JKS and its passwords in a secure location away from Git.
Set these repository Actions secrets in GitHub yourself:

- `HERRIN_RELEASE_KEYSTORE_B64` — base64 of the JKS bytes (Mac:
  `base64 -i herrin-release.jks | pbcopy`).
- `HERRIN_RELEASE_STORE_PASSWORD` — the JKS password.
- `HERRIN_RELEASE_KEY_ALIAS` — `herrin_release`, unless another alias chosen.
- `HERRIN_RELEASE_KEY_PASSWORD` — the key password.

Never paste signing secrets into issues, PRs, source code, screenshots or chat.
Only after relevant draft PRs have been reviewed/merged to master, manually
run the Signed Release workflow on master and install the release APK.

## Test / release gates

Cloud debug build/regressions passing does **not** prove release signing;
the first signed build and signature fingerprint still require owner-provided
secrets and manual workflow execution.

An already-installed ephemeral-debug-signed APK generally cannot update into
the newly signed build directly. Preserve/copy required app data before any
uninstall; this version does not yet implement automatic data migration
between differently signed apps.

Do not claim 120fps, lawful third-party source access or FongMi GPL compliance
based on the signed build alone.
