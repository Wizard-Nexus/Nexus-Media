# Nexus Media

Nexus Media is an Android TV and Google TV fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV). It adds Live TV and a Sports view for Xtream playlists while retaining Nuvio's movie and series features.

## Install on a TV

Enter **6234336** in Downloader by AFTVnews or download [Nexus-Media.apk](https://github.com/Wizard-Nexus/Nexus-Media/releases/latest/download/Nexus-Media.apk). Open the APK and select **Install**. If your TV prompts you, allow Downloader to install apps.

Scan the sign-in QR code to connect your Nuvio account. Open **Live TV** to add your Xtream server and login or paste a full Xtream playlist URL. Nexus Media uses the sources you configure.

## Build from source

Use Android Studio or a JDK with Android SDK Platform 36 installed. Run the Gradle wrapper from this checkout.

- Debug APK using the development package ID. Run `./gradlew :app:assembleFullDebug --configure-on-demand`.
- Release APK. Set `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS`, and `NUVIO_RELEASE_KEY_PASSWORD` for your signing key. Run `./gradlew :app:assembleFullRelease --configure-on-demand -PnexusUniversalApk=true`.

For each TV update, increment the Android version code, keep the release app ID and key, and attach the universal APK as `Nexus-Media.apk` to the latest GitHub release. Tag the commit that produced the APK so the release's source archive matches the download.

## Licence and attribution

The app is licensed under the [GNU General Public License v3.0](./LICENSE). It is based on [Nuvio TV 1.1.1](https://github.com/NuvioMedia/NuvioTV/tree/4cb3e34). Upstream projects include [Nuvio Engine](https://github.com/NuvioMedia/nuvio-engine), [Nuvio libass](https://github.com/NuvioMedia/nuvio-libass), and [AndroidX Media3](https://github.com/androidx/media).
