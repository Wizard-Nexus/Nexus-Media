# Nexus Media

Nexus Media is an Android TV and Google TV fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV). It adds Live TV and a Sports view for Xtream playlists while retaining Nuvio's movie and series features.

## Install on a TV

- **Stable** is the regular Nexus Media app. Enter **6234336** in Downloader by AFTVnews or [download Stable](https://github.com/Wizard-Nexus/Nexus-Media/releases/latest/download/Nexus-Media.apk).
- **Beta** installs as **Nexus Media Beta** alongside Stable. Use the [latest Beta download](https://wizard-nexus.github.io/Nexus-Media/beta/).

Open the APK and select **Install**. If your TV prompts you, allow Downloader to install apps. Scan the sign-in QR code to connect your Nuvio account. Open **Live TV** to add your Xtream playlist.

## Build from source

Use Android Studio or a JDK with Android SDK Platform 36 installed. Set `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS`, and `NUVIO_RELEASE_KEY_PASSWORD` when signing. Run the Gradle wrapper from the checkout.

- Development build. `./gradlew :app:assembleFullDebug --configure-on-demand`
- Beta APK. `./gradlew :app:assembleFullBeta --configure-on-demand -PnexusUniversalApk=true`
- Stable APK. `./gradlew :app:assembleFullRelease --configure-on-demand -PnexusUniversalApk=true`

## Release flow

Work on `codex/beta`. Give each test build a higher Android version code and a prerelease version such as `0.1.2-beta.2`. Publish the signed `Nexus-Media-Beta.apk` from its matching source tag as a GitHub prerelease with **Latest** off. Once the tagged APK is verified, update `beta/index.html` on `codex/beta-download` to point to that asset.

When a Beta is accepted on TV, promote its tested source to `codex/stable` and set the final version without the prerelease suffix. Build and test the signed Stable APK as an upgrade over the current Stable installation. Publish it as `Nexus-Media.apk` and mark that release **Latest**. The two apps have separate Android package IDs. Keep the same signing key for subsequent updates.


## Licence and attribution

The app is licensed under the [GNU General Public License v3.0](./LICENSE). It is based on [Nuvio TV 1.1.1](https://github.com/NuvioMedia/NuvioTV/tree/4cb3e34). Upstream projects include [Nuvio Engine](https://github.com/NuvioMedia/nuvio-engine), [Nuvio libass](https://github.com/NuvioMedia/nuvio-libass), and [AndroidX Media3](https://github.com/androidx/media).
