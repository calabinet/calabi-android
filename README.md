# Calabi for Android

Native Android application for Calabi. Package name: `net.calabi.app`; Android 8.0+; arm64-v8a only (64-bit Android).

The application has its own version in `apps/client-android/VERSION`. The shared Go core is maintained in [calabinet/calabi](https://github.com/calabinet/calabi). Its version and complete public commit are pinned in `apps/client-android/core-source.json`.

## Download

Install the signed APK from [Android releases](https://github.com/calabinet/calabi-android/releases/latest). Checksums, certificate details and product provenance belong to that release, separately from the core CLI archives.

## Build from a fresh checkout

The provided Android build scripts run on Windows with PowerShell 7. Install Python 3.12+, Git, Go 1.26.3, JDK 17, Android SDK platform/build-tools 35, NDK 27.3.13750724 and Gradle 8.11.1. `apps/client-android/mobile-toolchain.json` is the source of truth for the pinned Go, NDK, Gradle and gomobile/gobind versions. The mobile script uses a temporary Go module so it does not change the core dependency graph.

```powershell
python scripts/products/product.py prepare android --destination ../calabi-android-build
Set-Location ../calabi-android-build
$env:ANDROID_HOME = "C:/Android/Sdk" # replace with your installed SDK
$mobile = Get-Content apps/client-android/mobile-toolchain.json -Raw | ConvertFrom-Json
pwsh -NoProfile -File scripts/dev/build.ps1 go install "golang.org/x/mobile/cmd/gomobile@$($mobile.gomobile)"
pwsh -NoProfile -File scripts/dev/build.ps1 go install "golang.org/x/mobile/cmd/gobind@$($mobile.gomobile)"
pwsh -NoProfile -File scripts/mobile/build-core-android.ps1 -AndroidHome $env:ANDROID_HOME
pwsh -NoProfile -File scripts/mobile/build-rdp-android.ps1 -AndroidHome $env:ANDROID_HOME
# Use your installed Gradle, or a project wrapper if present.
pwsh -NoProfile -File scripts/dev/build.ps1 gradle -p apps/client-android assembleDebug
```

The SDK path above is an example; install its pinned NDK and add Gradle, CMake, Ninja and Git for Windows (Bash, Perl and make) to PATH. The RDP script downloads hash-pinned FreeRDP/OpenSSL sources and packages their license notices. Without `-EdgeCa`, this builds a development mobile core, which cannot authenticate production calabi.net edges. To build for a deployment, pass its existing public CA with `-EdgeCa` (see the [application build guide](apps/client-android/README.md#build)).

The core source is fetched at the pinned commit. Build output contains the Android product version and the actual Go library version and commit. Development `--core-tree` overrides are explicitly marked and cannot produce release metadata.

## Signed release

Use the existing official signing certificate for an upgrade of the official application. A different key cannot replace an installed official APK. Keep keystores and passwords outside Git. For your own distribution, use your own key and certificate fingerprint (`-CertSha256`); it cannot upgrade an installed official APK, and the official publish preview will reject a different signer.

```powershell
pwsh -NoProfile -File scripts/mobile/build-release-android.ps1 -EdgeCa "C:/certificates/edge-ca.crt" -Keystore "C:/release-keys/android-release.jks" -Gradle "C:/Gradle/gradle-8.11.1/bin/gradle.bat"
python scripts/products/product.py manifest android
```

The release script prompts for the password without echoing it and verifies the pinned certificate fingerprint, application version, production CA in the arm64 library and API endpoint before staging. Artifacts and checksums live in `dist/release-android`. The version code remains `MAJOR*1000000 + MINOR*1000 + PATCH`; 3.0.0 is 3000000, higher than 2.7.0 (2070000).

Validate an upgrade over the previous signed release, preserving credentials, preferences and device identity, before publishing. Official APKs remain manually downloadable; this application currently has no automatic APK installer.

## Source and licensing

See `LICENSE`, `NOTICE` and the application's third-party notices. The core is built from the pinned public source through gomobile and included in the APK. This repository does not maintain a separate copy of the core. Report vulnerabilities using the [Calabi security policy](https://github.com/calabinet/calabi/blob/main/SECURITY.md).


## Verify release provenance before publishing

From the prepared public build, sign the product manifest with the established release key (kept outside Git), then preview the release:

```powershell
pwsh -NoProfile -File scripts/dev/build.ps1 go run ./scripts/updatekit sign-file --file dist/release-android/build-manifest.json --privkey "C:/release-keys/update-private.key" > dist/release-android/build-manifest.json.sig
python scripts/products/publish-release.py android
```

The preview verifies the manifest signature, installer hashes, product version, fixed core and complete installer set. `--publish` additionally requires an existing public tag at the recorded product source commit. Release tags and publication are separate operations. Android APK updates are distributed manually; this release preview does not publish a desktop update descriptor.


## Prepared source archive

From the prepared build, create a source archive for another build machine:

```sh
python scripts/products/product.py archive android --destination ../calabi-android-source.zip --edge-ca "C:/certificates/edge-ca.crt"
```

The archive includes only recorded public source inputs, their provenance and the optional public CA. It works without a Git checkout and refuses development overrides, changed inputs or private keys. Use the selected Android product commit and its pinned core on the destination build machine. A source ZIP is not an APK or an application update.
