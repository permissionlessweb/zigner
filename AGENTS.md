# AGENTS.md — Zigner (rotkonetworks/zigner) — versioning & release

This file is the source of truth for how to bump versions and release the
Zigner cold-wallet app. Read it before changing any version string.

## Two repos, two separate version schemes

Zigner and Zafu are independent apps with independent versions. Never try to
"synchronize" them — bump whichever app you are actually shipping.

- Zigner (this repo): 0.x.y — Android cold-signer wallet.
- Zafu (/steam/rotko/zafu): 25.x.y — browser extension. Bump via its own
  `scripts/bump-version.sh` (see its AGENTS.md / scripts header).

## The ONE source of truth for Zigner's version: android/build.gradle

```
// android/build.gradle
def appVersionName = "0.8.2"          // <-- THE ONLY place you edit a version
static int computeVersionCode(String name) { /* M*1_000_000 + m*1_000 + p */ }
```

- `versionName` = `appVersionName` (shown to users, e.g. "0.8.2").
- `versionCode` is **derived automatically** by `computeVersionCode()` as
  `M * 1_000_000 + m * 1_000 + p` (e.g. "0.8.2" → 8002). Do **not** hand-edit
  versionCode — it follows the name for free.
- `AndroidManifest.xml` carries NO version fields — Gradle injects
  versionName/versionCode from defaultConfig at package time.

## Universal bump script (USE THIS, not manual edits)

```
./scripts/bump-version.sh 0.9.0
```

It edits `android/build.gradle`, prints the derived versionCode, and reassembles
the APK. Run from the repo root.

## iOS is separate — do NOT bump it here

The iOS app lives in `ios/PolkadotVault.xcodeproj` with `MARKETING_VERSION`
(currently a 7.x lineage independent of Android's 0.x). Bump iOS only when
shipping an iOS release, and only in the pbxproj / Info.plist. This script
deliberately leaves iOS alone.

## Release flow (Android)

1. `./scripts/bump-version.sh <newversion>` — e.g. `0.9.0`.
2. Build + smoke-test: `./gradlew :android:assembleRelease` (signed) or
   `:android:assembleDebug` for a local install.
3. Install to the test Pixel (device `37121FDJH005G0`):
   `adb install -r android/build/outputs/apk/<variant>/android-<variant>.apk`
4. Commit, push `master`, then tag: `git tag -a v0.9.0` + `git push --tags`.
   The `v*` tag triggers GitHub Actions "Android Release" (signed APK + GH
   Release). There is no Google Play upload; the F-Droid repo is updated by
   hand (`/steam/rotko/fdroid`).

## Pitfalls

- Only ONE version string should change. If you find yourself editing a version
  in more than one file for Android, stop — something has drifted; the gradle
  name is canonical.
- Don't use a stale `build/` APK — always reassemble after a bump.
- Cold wallet: confirm the user's seed/recovery backup before flashing a new
  build to the signer device.
