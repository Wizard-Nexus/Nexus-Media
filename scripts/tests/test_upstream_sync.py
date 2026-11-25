from __future__ import annotations

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from sync_nuvio_releases import (
    SyncError,
    digest,
    finish_release,
    latest_beta,
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
