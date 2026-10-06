# PingVault

PingVault is an Android notification archive. It stores notifications locally after the user grants notification access. The app does not read historical notifications from before access was enabled.

## Current scope

- Capture posted notifications locally
- Browse, search, filter, save, and delete archived entries
- Preserve media that is explicitly exposed in notification extras or messaging-style message data
- Keep notification records and copied media on device

## Build

Open this repository in Android Studio and sync Gradle. The project targets Android 12+ initially; Android notification-listener access is required.
