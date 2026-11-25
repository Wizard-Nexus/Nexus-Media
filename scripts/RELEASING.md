# Building and releasing Nexus Media

## Build from source

Use Android Studio or a JDK with Android SDK Platform 36 installed. Set `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS`, and `NUVIO_RELEASE_KEY_PASSWORD` when signing. Run the Gradle wrapper from the checkout.

- Development build. `./gradlew :app:assembleFullDebug --configure-on-demand`
- Signed APK for either channel. `./gradlew :app:assembleFullRelease --configure-on-demand -PnexusUniversalApk=true`

## Release flow

Work on `codex/beta`. Use a prerelease version such as `0.1.2-beta.2` and increase the Android version code across both channels. Publish the signed `Nexus-Media.apk` from its matching source tag as a GitHub prerelease with **Latest** off. The Beta release script sets those flags.

Once a Beta is verified on Android TV, promote its tested source to `codex/stable` and set a final version without the prerelease suffix. Verify the signed Stable APK upgrades the current Stable build and the Beta being promoted. Publish it as `Nexus-Media.apk` and mark that release **Latest** so the existing Downloader code serves it. Use package ID `app.nexusmedia.tv` and the same signing key for both channels.


## Upstream releases

The **Nexus Media Upstream Releases** Windows task runs at sign-in and hourly while the release PC is signed in. It checks Nuvio TV's published Stable and Beta tags and prepares each merge in a separate Git worktree. A reusable detached build checkout for each channel keeps Gradle outputs across releases. The release build uses the Gradle daemon and local build cache, and reruns the release unit tests. Each APK must pass the app and updater unit tests, keep the Nexus Media signing certificate, and upgrade the previous app versions on a dedicated Android TV emulator. The existing signing setup is used locally after the build has finished.

Beta publishes as a GitHub prerelease. A tested Stable release becomes the APK served by Downloader code **6234336**. The task records its last result in `.gradle/nexus-upstream/last-run.json`. A merge conflict leaves its worktree at `.gradle/nexus-upstream/preview-<channel>-<upstream-tag>`. Resolve and commit the merge there so a later run can build it. Failed builds include a verification log in `.gradle/nexus-upstream/candidates/<version>/verification.log`.

The default build checkouts are `.gradle/nexus-upstream/build-stable` and `.gradle/nexus-upstream/build-beta`. The release PC can reuse existing compiled checkouts by recording their directory names under `stable` and `beta` in `.gradle/nexus-upstream/build-worktrees.json`. Build checkouts must belong to Nexus Media and have a clean, detached HEAD.

Run `python scripts/sync_nuvio_releases.py --check` to check the release feeds without publishing. The Windows task can be disabled in Task Scheduler.

## Attribution

Nexus Media is based on [Nuvio TV](https://github.com/NuvioMedia/NuvioTV) and is licensed under the [GNU General Public License v3.0](../LICENSE). Key upstream projects include [Nuvio Engine](https://github.com/NuvioMedia/nuvio-engine), [Nuvio libass](https://github.com/NuvioMedia/nuvio-libass), and [AndroidX Media3](https://github.com/androidx/media).
