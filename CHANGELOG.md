# Changelog

## 3.0.0 — 2026-11-11

## Changes

- **Changed:** The Android application now has its own public repository, version, signed APK checksums and release provenance.
- **Added:** Settings display the Android application version, actual linked Go core version and pinned core commit separately.
- **Changed:** Android builds fetch a fixed public core commit and use pinned mobile build tools. The Android application version can advance independently of the core version.

## Install or upgrade

Install calabi-android.apk over the existing official application. The package identifier remains net.calabi.app and the established release signing certificate is retained. Version 3.0.0 uses versionCode 3000000, higher than 2.7.0 (2070000).

Existing sign-in, device identity and preferences are retained by an in-place upgrade. The application continues to support mesh connections and file transfer. The package includes arm64-v8a and armeabi-v7a; Android 8.0 or later is required. Official APK updates are installed manually.

## Verify downloads

Check the APK against SHA256SUMS and verify its signing certificate:

```sh
apksigner verify --verbose --print-certs calabi-android.apk
```

Expected signing certificate SHA-256:
`5800bc9938dd507b1a4fb7cc497f9b3ce50e3c20655fdcb5383aac4049f97d23`.

build-manifest.json and its detached signature record the product source, pinned core and APK hash.

Source repository: https://github.com/calabinet/calabi-android
Product source commit: `bd39755d8d07c470c13505b221fc5bba547f4709`.
Bundled core: **2.8.0**, commit `62012a64fbe522105216b60eaee4c7529f8c6c7f`.
