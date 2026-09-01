# Boreal Native Android automated builds

This repository is exclusively for the native Boreal Android app. Its release
workflow builds the checked-in Kotlin/Jetpack Compose Android project with JDK
17 and Android SDK 35, then publishes the unsigned APK as the
`Boreal-Native-Unsigned-APK` Actions artifact. APK signing remains outside
GitHub.

## Checked-in source

`build-boreal-android.yml` runs for relevant Android source/build changes, pull
requests, and manual dispatches. It executes the release unit-test task and the
required native build command:

```text
./gradlew --no-daemon :app:assembleRelease
```

## Verified source-ZIP intake

`build-boreal-android-source-zip.yml` provides a lightweight intake path for a
future Boreal source archive without replacing the checked-in project. Start it
manually and supply:

- a public HTTPS ZIP URL; and
- the ZIP's exact 64-character SHA-256 digest.

Those values may instead be configured as repository Actions variables named
`BOREAL_SOURCE_ZIP_URL` and `BOREAL_SOURCE_ZIP_SHA256`. Manual inputs take
precedence. The workflow rejects non-HTTPS URLs, verifies the digest before
extraction, rejects unsafe archive paths, locates the single Gradle project,
and produces the same unsigned APK artifact.

Do not put a signing keystore, certificate private key, password, access token,
or signed download URL in the repository or in workflow-dispatch inputs.
