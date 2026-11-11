# Calabi for Android

Native Android application for Calabi. Package name: `net.calabi.app`; Android 8.0+; arm64-v8a and armeabi-v7a.

The application has its own version in `apps/client-android/VERSION`. The shared Go core is maintained in [calabinet/calabi](https://github.com/calabinet/calabi). Its version and complete public commit are pinned in `apps/client-android/core-source.json`.

## Build from a fresh checkout

Install Python 3.12+, Git, PowerShell 7, Go 1.25+, JDK 17, Android SDK platform 35, NDK r27, Gradle 8.11.1, and matching versions of gomobile and gobind. The mobile script uses a temporary Go module so it does not change the core dependency graph.

```powershell
python scripts/products/product.py prepare android --destination ../calabi-android-build
Set-Location ../calabi-android-build
pwsh -NoProfile -File scripts/mobile/build-core-android.ps1
# Use your installed Gradle, or a project wrapper if present.
gradle -p apps/client-android assembleDebug
```

The core source is fetched at the pinned commit. Build output contains the Android product version and the actual Go library version and commit. Development `--core-tree` overrides are explicitly marked and cannot produce release metadata.

## Signed release

Use the existing official signing certificate for an upgrade of the official application. A different key cannot replace an installed official APK. Keep keystores and passwords outside Git. For a self-hosted distribution use your own package/signing policy.

```powershell
pwsh -NoProfile -File scripts/mobile/build-release-android.ps1 -EdgeCa <public-ca.crt> -Keystore <release.jks> -Gradle <gradle.bat>
python scripts/products/product.py manifest android
```

The release script prompts for the password without echoing it and verifies the pinned certificate fingerprint, application version, production CA in both ABIs and API endpoint before staging. Artifacts and checksums live in `dist/release-android`. The version code remains `MAJOR*1000000 + MINOR*1000 + PATCH`; 3.0.0 is 3000000, higher than 2.7.0 (2070000).

Validate an upgrade over the previous signed release, preserving credentials, preferences and device identity, before publishing. Official APKs remain manually downloadable; this application currently has no automatic APK installer.

## Source and licensing

See `LICENSE`, `NOTICE` and the application's third-party notices. The core is built from the pinned public source through gomobile and included in the APK. This repository does not maintain a separate copy of the core.


## Verify release provenance before publishing

From the prepared public build, sign the product manifest with the established release key (kept outside Git), then preview the release:

```powershell
pwsh -NoProfile -File scripts/dev/build.ps1 go run ./scripts/updatekit sign-file --file dist/release-android/build-manifest.json --privkey <private-key-file> > dist/release-android/build-manifest.json.sig
python scripts/products/publish-release.py android
```

The preview verifies the manifest signature, installer hashes, product version, fixed core and complete installer set. `--publish` additionally requires an existing public tag at the recorded product source commit. Release tags and publication are separate operations. Desktop update descriptors retain the established desktop signing key and channel.


## Prepared source archive

From the prepared build, create a source archive for another build machine:

```sh
python scripts/products/product.py archive android --destination ../calabi-android-source.zip --edge-ca <public-ca.crt>
```

The archive includes only recorded public source inputs, their provenance and the optional public CA. It works without a Git checkout and refuses development overrides, changed inputs or private keys. For a formal desktop release, build Windows and macOS from the same selected product source commit; validation installers are separate from release publication.
