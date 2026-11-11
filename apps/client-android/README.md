# Calabi for Android

The phone joins its org's meshnet as a device. All networking — sign-in, the
control plane, WireGuard — is the Go core in [shared mobile interface](https://github.com/calabinet/calabi/tree/main/apps/client/mobile),
bound into this app with gomobile. The app draws the screens and owns the VPN
(`CalabiVpnService`).

It signs in to calabi.net (or a development control plane, below) and enrols
through it, or joins a self-hosted `calabi-coord` with an invite: scanned in the
app (zxing-android-embedded, which needs no Google services), opened as a
`calabi://join` link, or typed in. What it shows then is in
[the core self-hosting guide](https://github.com/calabinet/calabi/blob/main/docs/self-hosting.md#phones-and-the-desktop-console).
Joining a self-hosted coordinator needs neither `-EdgeCa` nor the build
properties below.

## Build

Use the Windows/PowerShell 7 [fresh-checkout instructions](../../README.md#build-from-a-fresh-checkout) for SDK setup and guarded installation of gomobile and gobind. The exact versions are pinned in `mobile-toolchain.json` (Go 1.26.3, JDK 17, NDK 27.3.13750724 and Gradle 8.11.1 for this source).

First prepare the pinned public core from a clean [Android checkout](https://github.com/calabinet/calabi-android):

```powershell
python scripts/products/product.py prepare android --destination ../calabi-android-build
Set-Location ../calabi-android-build
```

The Android version is `apps/client-android/VERSION`; `core-source.json` fixes the core's own version and public commit.

1. From the prepared build root, build the Go core into `apps/client-android/app/libs/calabicore.aar`. It embeds the edge CA the
   client trusts. Without `-EdgeCa` that is whatever the tree carries (the
   development edge CA, `CN=calabi-dev-edge-ca`), and the app cannot reach
   calabi.net.
   calabi.net's edge-CA root is carried in the **core repository's** signed `build-manifest.json`. Obtain and verify it from the [core release](https://github.com/calabinet/calabi/releases) matching `core-source.json`, and save it as `core-build-manifest.json`. Product manifests contain input hashes and do not have this certificate field:

   ```bash
   jq -r '.inputs.edge_ca.pem[]' core-build-manifest.json > calabi-edge-ca.pem
   ```

   ```powershell
   pwsh -NoProfile -File scripts/mobile/build-core-android.ps1 -EdgeCa calabi-edge-ca.pem -AndroidHome $env:ANDROID_HOME
   ```

2. Build the app (`local.properties` names the SDK, e.g. `sdk.dir=C\:\\Android\\Sdk`):

   ```powershell
   cd apps\client-android
   gradle assembleDebug
   ```

   The APK is `app/build/outputs/apk/debug/app-debug.apk`.

A development control plane: `-PcalabiBffUrl=http://192.0.2.10:8002
-PcalabiConsoleUrl=http://192.0.2.10:5173 -PcalabiCoordPlaintext=true`.

A signed release build: set `CALABI_ANDROID_KEYSTORE` (path to your `.jks`),
`CALABI_ANDROID_KEYSTORE_PASSWORD` and, if the alias is not `calabi`,
`CALABI_ANDROID_KEY_ALIAS`, then run `gradle assembleRelease` from `apps/client-android`. The default version comes from this product's `VERSION` file. `-PcalabiVersion` is supported for development builds; the official release script requires the APK version to match the product version.
Without the keystore variables the release APK comes out unsigned. The official
APK is built with `scripts/mobile/build-release-android.ps1` from the
prepared public product build; it checks that the APK is signed by the certificate in
`scripts/mobile/android-release-cert.sha256`.

## Layout

| File | What it does |
|---|---|
| `CalabiApp.kt` | Creates the Go core once per process |
| `AppPlatform.kt` | What the core asks of Android: establish the VPN, protect sockets, list networks, log |
| `CalabiVpnService.kt` | The VPN: foreground service, `establish()` on the core's request, network-change callbacks |
| `ConnectTileService.kt` | Quick Settings tile |
| `QuickTile.kt` | Getting the tile into the panel: the system's dialog on Android 13+, steps before that |
| `BootReceiver.kt` | Connect at startup |
| `BackgroundRun.kt` | Where each phone maker lets an app keep running in the background |
| `CoreClient.kt` | Calls the core's in-process `/v1/*` API |
| `ui/` | Sign-in, mesh, endpoints, settings (Jetpack Compose); `SelfHostedScreen.kt` is joining a self-hosted server |
