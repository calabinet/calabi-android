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

Android builds support `arm64-v8a` only. A 64-bit Android system is required; 32-bit-only devices cannot install new builds.

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

2. Build the arm64 RDP libraries. Put CMake, Ninja and Git for Windows (including Bash, Perl and make) on PATH. The script downloads and verifies pinned FreeRDP 3.33.0 and OpenSSL 3.6.5 sources and installs their license notices with the libraries:

   ```powershell
   pwsh -NoProfile -File scripts/mobile/build-rdp-android.ps1 -AndroidHome $env:ANDROID_HOME
   ```

3. Build the app (`local.properties` names the SDK, e.g. `sdk.dir=C\:\\Android\\Sdk`):

   ```powershell
   cd apps\client-android
   pwsh -NoProfile -File ../../scripts/dev/build.ps1 ./gradlew.bat assembleDebug
   ```

   The APK is `app/build/outputs/apk/debug/app-debug.apk`.

A development control plane: `-PcalabiBffUrl=http://192.0.2.10:8002
-PcalabiConsoleUrl=http://192.0.2.10:5173 -PcalabiCoordPlaintext=true`.

A signed release build: set `CALABI_ANDROID_KEYSTORE` (path to your `.jks`),
`CALABI_ANDROID_KEYSTORE_PASSWORD` and, if the alias is not `calabi`,
`CALABI_ANDROID_KEY_ALIAS`, then run `pwsh -NoProfile -File ../../scripts/dev/build.ps1 ./gradlew.bat assembleRelease` from `apps/client-android`. The default version comes from this product's `VERSION` file. `-PcalabiVersion` is supported for development builds; the official release script requires the APK version to match the product version.
Without the keystore variables the release APK comes out unsigned. The official
APK is built with `scripts/mobile/build-release-android.ps1` from the
prepared public product build; it checks that the APK is signed by the certificate in
`scripts/mobile/android-release-cert.sha256`.

The release script builds the Go core, the RDP libraries and the APK in that order. Gradle checks that all four RDP libraries and both license notices exist before building. Native libraries use 16 KB ELF alignment; APK packaging must also preserve 16 KB ZIP alignment.

## Navigation and sending files

Mesh puts devices first, with persistent search and online filters. A compact shared header combines the account and organization entry with connection status; tap the status to open connection controls, exit-device selection and subnet routing. The header stays fixed across the main pages. Settings has separate pages for account and organization, connection, device and background operation, usage, support, and application details.

Send files opens a full page with a scrollable device/file list and a fixed selection summary and Send button. Back returns to the transfer center without discarding the selection. Selections also survive tab switches and configuration changes, scoped to the current network and organization. A receiving device must be available before sending. Removing a cached source still requires confirmation.

Shared controls have a minimum 48 dp touch target and grow with text. Large fonts on narrow screens use two rows of main navigation; long action labels wrap into additional rows. The keyboard hides main navigation while searching. Back first dismisses the keyboard and retains the query; returning from settings or endpoint details restores the previous search and scroll position. Submitted settings changes show progress, prevent repeated submission and retain success or failure feedback until dismissed. Editors close only after a successful save.

Android resources cover the same ten languages as the website: English, Simplified and Traditional Chinese, German, Spanish, French, Italian, Japanese, Korean and Portuguese. Run `python scripts/mobile/check-android-i18n.py` from the repository root to check resource coverage, duplicate keys, format arguments and plural forms. `AndroidTranslationsTest` checks locale lookup and formatting on a device; set `-PcalabiTestRunner=net.calabi.app.AndroidTranslationsTest` when building the instrumentation APK.

`AndroidLintRegressionTest` also checks the installed manifest, backup exclusions, launcher icons, RDP cursor transforms, custom-port credential isolation and local recent-session previews. Build it with `-PcalabiTestRunner=net.calabi.app.AndroidLintRegressionTest`, then run `adb shell am instrument -w net.calabi.app.test/net.calabi.app.AndroidLintRegressionTest`. Full `:app:lintDebug` checks remain enabled. Local lint exceptions explain the supported ABI, user-requested VPN battery allowance, session-only RDP view construction and portrait sign-in requirement.

## Remote desktop

Tap a device to open its details, copy its IP address, send files, or connect to an online Windows device with Remote desktop. Unavailable file actions explain that the phone must be connected and the other device must allow receiving. The shared core authorizes the current account and target, verifies its certificate and binds the connection to the mesh. Windows must support incoming RDP and have it enabled. Sign in with a Windows password rather than a PIN; the port below the password defaults to 3389 and accepts 1–65535. Saved credentials are encrypted on this phone and scoped to the account, organization, device and port.

Sign-in remains portrait. A successful session enters landscape fullscreen, with a small control entry that expands the floating toolbar. You can rotate the session manually and access input, display and session tools. The Remote tab shows local thumbnails of recently connected desktops, two per row by default or one at large text sizes. Removing a recent entry deletes its preview but keeps saved credentials. Previews remain on the device and are not uploaded.

The bottom toolbar offers the keyboard, current touch mode and Tools, and can be collapsed. Tools opens a scrollable bottom sheet for input, display, help, connection details and disconnect. The keyboard has a companion row for Tab/Esc/Enter/Backspace and one-shot Ctrl/Alt/Shift. Trackpad and direct touch, right-click, scrolling, dragging, zoom, fit and original pixels remain available. Returning from an active session asks before disconnecting; closing a tool sheet or the keyboard returns to the session first. It accepts keyboard and mouse input, Chinese IME text and explicit text clipboard exchange. Background sessions are retained for up to 60 seconds, then disconnected; returning validates authorization before restoring input. A temporary network interruption pauses input and offers up to 60 seconds of automatic recovery with authorization and certificate verification. Recovery preserves the viewport and clears pending input and clipboard operations; it stops on identity changes, authentication failure, explicit disconnect or timeout. Clipboard recovery after a network interruption remains deferred; disconnect and reconnect manually if text exchange is unavailable. Only one session is retained. File and image clipboard transfer is outside this version's scope.

Before connecting, choose Data saver (960×540, 16-bit, presentation limited to 15 fps), Balanced (1280×720, 32-bit, 30 fps), or Sharp (1920×1080, 32-bit, 60 fps). The choice is remembered locally and applies to the next connection. These are requested formats and presentation limits, not guaranteed frame rates. On Android 10 or later, Prefer hardware decoding can select a named hardware H.264 decoder for Balanced or Sharp. The current candidate requests AVC420 only; AVC444 is disabled while its color compatibility is investigated. The computer may select compatible encoding instead. Decode failures reconnect once with compatible graphics, subject to the same authorization and certificate checks. Tools → Connection details shows the actual graphics mode, negotiated desktop size, and session counters. Hardware decoding acceptance is still pending; see the runbook for verified results and limitations. A connection that provides no usable desktop within 30 seconds ends with a retry message.

Display size, orientation and keyboard changes preserve the current session. Pending frame notifications are coalesced; classic graphics copy the updated region when valid and use a full copy after a resize or reconnect.

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
