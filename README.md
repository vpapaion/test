# FileHaven — Android file manager

An open-source, local-first file manager built with **Kotlin + Jetpack Compose + Material 3**.

## Features

- Choose and remember a folder using Android's **Storage Access Framework** (SAF).
- Browse nested folders, open or share documents, and see file metadata.
- Search the current folder, sort by name / date / size, and refresh.
- Create folders and text files; rename or delete files and folders (with confirmation).
- Copy or cut a file/folder and paste it into the current folder (supports recursive folders).
- Dark theme. No ads, network permission, analytics, or unrestricted storage permission.

### Important Android storage constraints

This app manages **only the folder tree you explicitly grant** and its accessible descendants. Android 11 and later restrict choosing certain protected folders (for example the root of internal storage, `Android/data`, and sometimes `Download`). This is an Android platform restriction, not a defect. For a general manager across all device storage, a different access model and greater permissions would be needed.

Copy/cut/paste is provider-dependent and is implemented as streaming copy followed by delete for a cut. The copy stage is cleaned up on failure; **moving is not an atomic operation**, and partial removal may still occur if a provider fails during deletion. Do not use this first version for critical or irreplaceable data without a backup.

## Build

Open this directory in recent Android Studio, let Gradle sync, and run `app` on an emulator or device with **Android 8.0 (API 26)+**. Requires JDK 17 and Android SDK 37. CI builds with **Gradle 9.6.0** and **Android Gradle Plugin 9.5.0**.

From a machine with Gradle 9.6.0 installed:

```bash
gradle :app:assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

On GitHub, visit **Actions → Build Android APK → latest successful run → Artifacts** to download the debug APK. It is a debug build for testing, not a signed Play Store release.

## Architecture

- `MainActivity.kt`: Compose screen, SAF folder picker, persistent permission, navigation, background operations.
- `FileOperations.kt`: SAF operations, collision-safe streaming recursive copy and cut.

## Privacy / safety

The application requests no internet and no blanket storage permissions. It does not send your data to GitHub or elsewhere. Deletions are permanent as far as the selected document provider is concerned; there is no recycle bin yet.

## Planned improvements

Multi-select, thumbnails, breadcrumbs, more comprehensive file search, and integration tests against local and cloud document providers.
