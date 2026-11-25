# Nexus Media

Nexus Media is an Android TV and Google TV fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV). It adds Live TV and a Sports view for Xtream playlists while retaining Nuvio's movie and series features.

## Install on a TV

Enter **6234336** in Downloader by AFTVnews or [download Stable](https://github.com/Wizard-Nexus/Nexus-Media/releases/latest/download/Nexus-Media.apk). Open the APK and select **Install**. If prompted, allow Downloader to install apps. Scan the QR code to connect your Nuvio account. Open **Live TV** to add your Xtream playlist.

Choose **Stable** or **Beta** in **Settings → About → Update channel**. Stable is the default for the Downloader install. Beta offers early updates. Both channels update the same app in place. If the installed Beta is ahead of Stable, selecting Stable makes the app wait for the next newer Stable release.

## Build from source

Use Android Studio or a JDK with Android SDK Platform 36 installed. Set `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS`, and `NUVIO_RELEASE_KEY_PASSWORD` when signing. Run the Gradle wrapper from the checkout.

- Development build. `./gradlew :app:assembleFullDebug --configure-on-demand`
- Signed APK for either channel. `./gradlew :app:assembleFullRelease --configure-on-demand -PnexusUniversalApk=true`

## Release flow

Work on `codex/beta`. Use a prerelease version such as `0.1.2-beta.2` and increase the Android version code across both channels. Publish the signed `Nexus-Media.apk` from its matching source tag as a GitHub prerelease with **Latest** off. The Beta release script sets those flags.

Once a Beta is verified on Android TV, promote its tested source to `codex/stable` and set a final version without the prerelease suffix. Verify the signed Stable APK upgrades the current Stable build and the Beta being promoted. Publish it as `Nexus-Media.apk` and mark that release **Latest** so the existing Downloader code serves it. Use package ID `app.nexusmedia.tv` and the same signing key for both channels.

## Licence and attribution

The app is licensed under the [GNU General Public License v3.0](./LICENSE). It is based on [Nuvio TV 1.1.1](https://github.com/NuvioMedia/NuvioTV/tree/4cb3e34). Upstream projects include [Nuvio Engine](https://github.com/NuvioMedia/nuvio-engine), [Nuvio libass](https://github.com/NuvioMedia/nuvio-libass), and [AndroidX Media3](https://github.com/androidx/media).
