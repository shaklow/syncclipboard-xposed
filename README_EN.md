# SyncClipboard Xposed

[**简体中文**](README.md) | **English**

An LSposed/Xposed module that syncs clipboard content between devices via [SyncClipboard Server](https://github.com/Jeric-X/SyncClipboard), WebDAV, or S3-compatible storage.

> **Note:** This module injects into `com.android.systemui` to obtain system-level clipboard access — no root required as long as LSposed is active.

## Features

- **Automatic upload** — detects local clipboard changes and uploads to server in the background
- **Automatic download** — polls or receives push notifications for remote clipboard changes
- **Clipboard history** — browse, search, and delete synced history records
- **SMS verification code** — auto-extracts and uploads verification codes from incoming SMS (optional)
- **Notification verification code** — listens to all app notifications to auto-extract codes (IM/email/banking, optional, requires notification access)
- **Quick settings tile** — one-tap toggle for auto-sync, long-press to open the app
- **Multiple backends** — SyncClipboard HTTP server (with SignalR push), WebDAV, S3-compatible
- **Backoff retry** — retries at 30s/60s/2min/5min intervals when the server is unreachable, stops after 4 consecutive failures to save battery

## Requirements

- Android 10+ (API 29+)
- [LSposed](https://github.com/LSPosed/LSPosed) (Zygisk mode recommended)
- A compatible clipboard sync server (see [SyncClipboard](https://github.com/Jeric-X/SyncClipboard))

## Module Scope

This module must be enabled for **`com.android.systemui`** in LSposed Manager.  

## Project Structure

```
clipboard-xposed/
├── app/        # Companion app — Settings, History UI, SMS receiver
├── bridge/     # IPC layer — broadcast-based bidirectional messaging between app ↔ SystemUI
├── common/     # Shared models, utilities, preferences
├── xposed/     # Hook module — injected into SystemUI, contains SyncEngine
└── shell/      # Minimal APK shell that packages all modules into one installable APK
```

## Build

**Requirements:**
- JDK 17 or newer (`java -version`)
- Android SDK with API 37 — set via `ANDROID_HOME` environment variable, or create `local.properties`:
  ```
  sdk.dir=/path/to/android-sdk        # Linux/macOS
  sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk  # Windows
  ```
- The debug keystore (`~/.android/debug.keystore`) is **auto-generated** by the build if it doesn't exist — no manual setup needed.

**Build commands:**

```bash
# Linux / macOS
./gradlew shell:assembleRelease

# Windows
gradlew.bat shell:assembleRelease
```

A single build produces 4 APKs simultaneously:

```
shell/build/outputs/apk/release/
├── shell-arm64-v8a-release.apk   
├── shell-armeabi-v7a-release.apk 
├── shell-x86_64-release.apk  
└── shell-universal-release.apk
```

> Release APKs are signed with the standard Android debug key (for sideloading). To use a custom keystore, edit `shell/build.gradle.kts` → `signingConfigs`.

## Architecture

The sync engine (`SyncEngine`) runs inside the `com.android.systemui` process and is initialized by `GeneralHooker` via LSposed. It registers an `OnPrimaryClipChangedListener` on the system `ClipboardManager` to detect all clipboard changes globally (no polling needed), uploads new content, and periodically fetches remote changes.

The companion app communicates with the engine via `SyncClipboardBridge` — a lightweight broadcast-based IPC layer defined in the `:bridge` module.

## License

Apache 2.0
