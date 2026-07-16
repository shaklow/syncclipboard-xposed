# SyncClipboard Xposed

An LSposed/Xposed module that syncs clipboard content between devices via [SyncClipboard Server](https://github.com/Jeric-X/SyncClipboard), WebDAV, or S3-compatible storage.

> **Note:** This module injects into `com.android.systemui` to obtain system-level clipboard access — no root required as long as LSposed is active.

## Features

- **Automatic upload** — detects local clipboard changes and uploads to server in the background
- **Automatic download** — polls or receives push notifications for remote clipboard changes
- **Clipboard history** — browse, search, and delete synced history records
- **SMS verification code** — auto-extracts and uploads verification codes from incoming SMS (optional)
- **Multiple backends** — SyncClipboard HTTP server (with SignalR push), WebDAV, S3-compatible
- **Material You / Miuix UI** — adaptive light/dark theme

## Requirements

- Android 10+ (API 29+)
- [LSposed](https://github.com/LSPosed/LSPosed) (Zygisk mode recommended)
- A compatible clipboard sync server (see [SyncClipboard](https://github.com/Jeric-X/SyncClipboard))

## Module Scope

This module must be enabled for **`com.android.systemui`** in LSposed Manager.  
The companion app (`io.github.erenche.syncclipboard`) does not need to be scoped.

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

Requirements: JDK 21, Android SDK (API 37)

```bash
./gradlew shell:assembleRelease
# APK → build/all-apks/release/shell-<version>-release.apk
```

## Architecture

The sync engine (`SyncEngine`) runs inside the `com.android.systemui` process and is initialized by `GeneralHooker` via LSposed. It registers an `OnPrimaryClipChangedListener` on the system `ClipboardManager` to detect all clipboard changes globally (no polling needed), uploads new content, and periodically fetches remote changes.

The companion app communicates with the engine via `SyncClipboardBridge` — a lightweight broadcast-based IPC layer defined in the `:bridge` module.

## License

Apache 2.0
