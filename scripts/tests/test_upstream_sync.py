from __future__ import annotations

import unittest
from unittest.mock import patch

from sync_nuvio_releases import (
    SyncError,
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
