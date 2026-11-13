# Changelog

## 3.1.0 — 2026-11-13

### Changes

- **Added:** Remote Desktop access to authorized Windows devices over the mesh, with certificate verification and optional encrypted credentials stored on this phone. Touchpad and direct-touch modes, Chinese text input and explicit text clipboard exchange are available.
- **Added:** A dedicated Remote tab with local desktop thumbnails, two cards per row by default, and shortcuts to reconnect to recently used devices.
- **Added:** Custom Remote Desktop ports from 1 to 65535, with saved credentials and recent connections isolated by account, organization, device and port.
- **Added:** Completed received files can be opened directly or shared through the Android system panel, without first saving another copy to a chosen location.
- **Changed:** The device-first mesh page now shares a compact, fixed account, organization and connection header with the other main pages. Connection controls, exit devices and subnet routes are grouped in a network panel.
- **Changed:** Remote Desktop keeps sign-in in portrait and enters landscape fullscreen after connecting. A collapsible floating toolbar groups input, display and session controls.
- **Changed:** File transfer, endpoints and settings use consistent panels, navigation and touch targets, with large-text layouts and the ten supported interface languages.
- **Changed:** The transfer center combines sending and receiving controls above a continuous record list, with search and direction filters kept together.
- **Changed:** Navigation headers stay fixed while content scrolls, including settings and endpoint detail pages. Receiving controls keep the same compact row when the mesh is disconnected.
- **Changed:** Save confirmations disappear automatically, while contextual notices keep recovery actions beside the message and avoid repeated red paragraphs for normal disconnected states.
- **Changed:** Remote Desktop sign-in provides a direct clear-credentials action and removes duplicate saved-credential and password guidance messages.
- **Changed:** Unavailable file-sending actions show the reason in the button label, including when the other device has not enabled receiving.
- **Fixed:** Remote connection availability and local history errors are reported separately, without asking an already-connected phone to reconnect or misreporting a remote-service failure as a history-storage error.
- **Changed:** Android packages now target 64-bit ARM systems only and use 16 KB alignment for native libraries and APK entries.
- **Fixed:** Direct-connection status and latency no longer force an extra line at normal text sizes. Android 10 fullscreen sessions no longer reserve an empty status-bar strip.

### Install or upgrade

Install calabi-android.apk over the existing official application. The package identifier remains net.calabi.app and the release signing certificate remains unchanged. Version 3.1.0 uses versionCode 3001000. Android 8.0 or later with a 64-bit ARM system (arm64-v8a) is required; 32-bit Android systems are no longer supported.

An in-place upgrade retains sign-in, device identity, credentials and preferences. Recent desktop previews are stored only on the device and are removed with their history entries. Official APK updates are installed manually.

### Verify downloads

Check the APK against SHA256SUMS and verify it with `apksigner verify --verbose --print-certs calabi-android.apk`.

Expected signing certificate SHA-256: `5800bc9938dd507b1a4fb7cc497f9b3ce50e3c20655fdcb5383aac4049f97d23`.

build-manifest.json and its detached signature record the product source, pinned core and APK hash.

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
