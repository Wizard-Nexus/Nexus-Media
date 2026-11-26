from __future__ import annotations

import json
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from tempfile import TemporaryDirectory
from unittest.mock import patch

from sync_nuvio_releases import (
    ASSET,
    DEVICE_ASSETS,
    SyncError,
    build_worktrees,
    digest,
    finish_release,
    latest_beta,
    prepare_build_checkout,
    read_feed,
    verify_publication,
    version_only_conflicts,
)


def release(tag, *, prerelease=False, draft=False):
    return {"tag_name": tag, "prerelease": prerelease, "draft": draft}


class UpstreamReleaseTests(unittest.TestCase):
    def test_reads_latest_published_beta_by_version(self):
        releases = [
            release("1.2.0-beta.9", prerelease=True),
            release("1.2.0-beta.10", prerelease=True),
            release("1.3.0-beta.1", prerelease=True, draft=True),
            release("1.2.0"),
        ]
        self.assertEqual(latest_beta(releases)["tag_name"], "1.2.0-beta.10")

    @patch("sync_nuvio_releases.api")
    def test_old_beta_is_skipped_after_upstream_stable_promotion(self, api):
        stable = release("1.2.0")
        api.side_effect = [stable, [stable, release("1.2.0-beta.4", prerelease=True)]]
        self.assertEqual(read_feed(None), {"stable": stable, "beta": None})

    @patch("sync_nuvio_releases.api")
    def test_stable_feed_rejects_prerelease(self, api):
        api.return_value = release("1.2.0-beta.4", prerelease=True)
        with self.assertRaises(SyncError):
            read_feed(None)

    def test_version_merge_keeps_nexus_numbers(self):
        merge = (
            '<<<<<<< HEAD\n        versionName = "0.1.2-beta.1"\n'
            '        versionCode = 1083\n=======\n        versionName = "1.2.0-beta.4"\n'
            '        versionCode = 1094\n>>>>>>> upstream\n'
        )
        self.assertEqual(
            version_only_conflicts(merge),
            '        versionName = "0.1.2-beta.1"\n        versionCode = 1083\n',
        )

    def test_nonversion_merge_is_left_for_resolution(self):
        merge = (
            '<<<<<<< HEAD\n        versionName = "0.1.2-beta.1"\n'
            '=======\n        versionName = "1.2.0-beta.4"\n'
            '        applicationId = "com.nuvio.tv"\n>>>>>>> upstream\n'
        )
        self.assertEqual(version_only_conflicts(merge), merge)

    def test_beta_asset_cannot_be_verified_as_stable(self):
        uploaded = release("v0.1.3-beta.1", prerelease=True, draft=True)
        uploaded["assets"] = [{"name": "Nexus-Media.apk", "digest": "sha256:expected"}]
        with self.assertRaises(SyncError):
            verify_publication(uploaded, {"channel": "stable", "digest": "sha256:expected"}, draft=True)
        verify_publication(uploaded, {"channel": "beta", "digest": "sha256:expected"}, draft=True)

    def test_marks_stable_latest_only_on_publication(self):
        with TemporaryDirectory(dir=Path.cwd()) as temporary:
            repo = Path(temporary)
            apk = repo / "Nexus-Media.apk"
            apk.write_bytes(b"verified APK fixture")
            asset_digest = digest(apk)
            previous = release("v0.1.1")
            draft = release("v0.1.3", draft=True)
            draft["assets"] = [{"name": apk.name, "digest": asset_digest}]
            published = dict(draft, draft=False)
            pending = repo / "pending-stable.json"
            pending.write_text(json.dumps({
                "tag": draft["tag_name"], "channel": "stable", "apk": str(apk),
                "digest": asset_digest, "upstream": "1.2.0-beta.4", "base": "previous",
                "commit": "tested", "stable": previous["tag_name"], "beta": None,
            }), encoding="utf-8")

            with (
                patch("sync_nuvio_releases.current_publications", side_effect=[
                    (previous, None, [previous]),
                    (previous, None, [draft, previous]),
                    (published, None, [published, previous]),
                ]),
                patch("sync_nuvio_releases.remote_heads", return_value={"stable": "previous"}),
                patch("sync_nuvio_releases.git"),
                patch("sync_nuvio_releases.run") as gh,
            ):
                finish_release(repo, pending)

            create, publish = (call.args for call in gh.call_args_list)
            self.assertEqual(create[1:3], ("release", "create"))
            self.assertIn("--draft", create)
            self.assertIn("--latest=false", create)
            self.assertEqual(publish[1:3], ("release", "edit"))
            self.assertIn("--draft=false", publish)
            self.assertIn("--latest", publish)
            self.assertFalse(pending.exists())

    def test_beta_draft_resumes_with_only_the_missing_device_apks(self):
        with TemporaryDirectory(dir=Path.cwd()) as temporary:
            repo = Path(temporary)
            names = [ASSET, *DEVICE_ASSETS]
            for name in names:
                (repo / name).write_bytes(name.encode("ascii"))
            digests = {name: digest(repo / name) for name in names}
            previous = release("v0.1.3")
            prior_beta = release("v0.1.4-beta.1", prerelease=True)
            partial = release("v0.1.5-beta.1", prerelease=True, draft=True)
            partial["assets"] = [{"name": ASSET, "digest": digests[ASSET]}]
            draft = dict(partial, assets=[
                {"name": name, "digest": sha} for name, sha in digests.items()
            ])
            published = dict(draft, draft=False)
            pending = repo / "pending-beta.json"
            pending.write_text(json.dumps({
                "tag": draft["tag_name"], "channel": "beta", "apk": str(repo / ASSET),
                "digest": digests[ASSET], "device_digests": {name: digests[name] for name in DEVICE_ASSETS},
                "upstream": "1.2.0-beta.4", "base": "previous", "commit": "tested",
                "stable": previous["tag_name"], "beta": prior_beta["tag_name"],
            }), encoding="utf-8")

            with (
                patch("sync_nuvio_releases.current_publications", side_effect=[
                    (previous, prior_beta, [partial, previous, prior_beta]),
                    (previous, prior_beta, [draft, previous, prior_beta]),
                    (previous, published, [published, previous, prior_beta]),
                ]),
                patch("sync_nuvio_releases.remote_heads", return_value={"beta": "previous"}),
                patch("sync_nuvio_releases.git"),
                patch("sync_nuvio_releases.run") as gh,
            ):
                finish_release(repo, pending)

            upload, publish = (call.args for call in gh.call_args_list)
            self.assertEqual(upload[1:3], ("release", "upload"))
            self.assertEqual(upload[4:7], tuple(repo / name for name in DEVICE_ASSETS))
            self.assertEqual(publish[1:3], ("release", "edit"))
            self.assertIn("--latest=false", publish)
            self.assertFalse(pending.exists())

    def test_beta_rejects_mismatched_or_missing_device_apks(self):
        devices = {name: f"sha256:{name}" for name in DEVICE_ASSETS}
        plan = {"channel": "beta", "device_digests": devices, "digest": "sha256:universal"}
        draft = release("v0.1.5-beta.1", prerelease=True, draft=True)
        draft["assets"] = [{"name": ASSET, "digest": plan["digest"]}]
        with self.assertRaises(SyncError):
            verify_publication(draft, plan, draft=True)
        draft["assets"].append({"name": DEVICE_ASSETS[0], "digest": "sha256:unexpected"})
        with self.assertRaises(SyncError):
            verify_publication(draft, plan, draft=True, allow_partial=True)

    def test_build_worktrees_reuse_local_checkout_names(self):
        with TemporaryDirectory(dir=Path.cwd()) as temporary:
            state = Path(temporary).resolve()
            config = state / "build-worktrees.json"
            config.write_text(json.dumps({"stable": "promote-stable-v0.1.3"}), encoding="utf-8")
            self.assertEqual(build_worktrees(state), {
                "stable": state / "promote-stable-v0.1.3", "beta": state / "build-beta",
            })
            config.write_text(json.dumps({"beta": "../outside"}), encoding="utf-8")
            with self.assertRaises(SyncError):
                build_worktrees(state)
            config.write_text(json.dumps({"stable": "build-beta"}), encoding="utf-8")
            with self.assertRaises(SyncError):
                build_worktrees(state)

    def test_build_checkout_moves_to_candidate_only_when_clean(self):
        with TemporaryDirectory(dir=Path.cwd()) as temporary:
            repo = Path(temporary).resolve()
            workspace = repo / "build-stable"
            workspace.mkdir()
            common = repo / ".git"
            identity = CompletedProcess([], 0, f"{workspace}\n{common}\n")
            origin = CompletedProcess([], 0, str(common))
            detached = CompletedProcess([], 1, "")
            with patch("sync_nuvio_releases.git", side_effect=[
                identity, origin, detached, CompletedProcess([], 0, " M app/build.gradle.kts"),
            ]) as git:
                with self.assertRaises(SyncError):
                    prepare_build_checkout(repo, workspace, "candidate")
                self.assertEqual(git.call_count, 4)

            with patch("sync_nuvio_releases.git", side_effect=[
                identity, origin, detached, CompletedProcess([], 0, ""),
                CompletedProcess([], 0, "previous"), CompletedProcess([], 0, ""),
            ]) as git:
                prepare_build_checkout(repo, workspace, "candidate")
                git.assert_called_with(workspace, "switch", "--detach", "--quiet", "--no-overwrite-ignore", "candidate")
