# AGENTS.md

Repository-specific guidance for coding agents working in this project.

## Scope

- Android app for syncing supported Omron blood pressure monitors over BLE
- Primary verified hardware target is `HEM-7380T1`
- Additional Omron models may exist as experimental definitions without real-device verification

## Current architecture

- `omron/`: device registry, parser definitions, BLE sync client
- `sync/`: sync preferences, shared orchestration, WorkManager background sync
- `healthconnect/`: Health Connect export
- `data/`: local persistence
- `export/`: CSV export
- UI is fragment-based with `Results`, `Settings`, and `Sync Log` screens

## Project-specific expectations

- Prefer extending the model-driven Omron structure instead of hardcoding `HEM-7380T1` logic in UI or sync flow.
- Do not assume all devices have multiple users.
- Treat non-`HEM-7380T1` device support as experimental unless the user explicitly verifies it on hardware.
- Keep Health Connect sync one-way unless the user asks for read/reconciliation support.
- When touching background sync, preserve the current design:
  - WorkManager scheduling
  - foreground execution during actual sync
  - user-selectable interval

## Build and test notes

- Normal Gradle command:
  - `./gradlew :app:assembleDebug`
- For NixOS agent work, prefer entering `nix-shell` from the repo root.
- `shell.nix` provides the tools that have been missing during agent runs:
  - `patchelf`
  - `adb`
  - `gh`
  - `rg`
  - `jdk17`
- Inside `nix-shell`, prefer:
  - `gradlew-nix <tasks>` instead of calling `./gradlew` directly
  - `adb-nix <args>` instead of calling the SDK `adb` directly when debugging from the shell
- The shell patches both of these SDK tools into `/tmp`:
  - the newest `aapt2`
  - `platform-tools/adb`
- `gradlew-nix` passes `-Pandroid.aapt2FromMavenOverride=...` automatically.
- `connectedDebugAndroidTest` may still work better from Android Studio, because Gradle can insist on using the SDK-owned `adb` path instead of a shell wrapper.
- The shell expects the Android SDK to be discoverable from either:
  - `local.properties` via `sdk.dir`
  - `ANDROID_SDK_ROOT`
- Instrumentation tests are expected to run from Android Studio or with `:app:connectedDebugAndroidTest`.
- Espresso/test dependency versions were updated to work on newer Android test devices. Do not casually downgrade them.

## Editing guidance

- Prefer `apply_patch` for manual edits.
- Keep UI changes aligned with the current Material 3 direction already in the app.
- If adding new settings, thread them through:
  - `MainUiState`
  - `SettingsFragment`
  - `MainActivity`
  - persisted preferences if the setting affects sync behavior

## Git hygiene

- Leave unrelated `.idea` changes alone unless the user explicitly wants them committed.
- In particular, `.idea/deploymentTargetSelector.xml` has been intentionally left out of recent commits.

## Validation priorities

When changing sync behavior, prioritize:

1. `:app:assembleDebug`
2. unit tests for parser/export logic
3. instrumentation tests for UI/settings flows
4. real-device verification on `HEM-7380T1` when applicable
