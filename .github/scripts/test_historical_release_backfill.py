from __future__ import annotations

import hashlib
import io
import json
import os
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from historical_release_backfill import (
    BackfillError,
    HISTORICAL_TAG_COMMITS,
    _remote_asset_sha256,
    create_manifest,
    extract_tv_metadata,
    publish_assets,
    validate_release_record,
    validate_tag,
    verify_manifest,
    verify_publish_confirmation,
)


class HistoricalReleaseBackfillTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.tag = "v1.0.5"
        self.source_sha = HISTORICAL_TAG_COMMITS[self.tag]
        self.binaries = {
            "ZotifyMusicTV-v1.8.apk",
            "ZotifyMusic-Desktop-v1.0.5-linux-x86_64.deb",
            "ZotifyMusic-Desktop-v1.0.5-linux-x86_64.rpm",
            "ZotifyMusic-Desktop-v1.0.5-windows-x86_64.exe",
            "ZotifyMusic-Desktop-v1.0.5-windows-x86_64.msi",
            "ZotifyMusic-Desktop-v1.0.5-macos-arm64.dmg",
            "ZotifyMusic-Desktop-v1.0.5-macos-arm64.pkg",
        }
        self._write_assets(self.binaries)
        self.manifest_path = self.assets / "manifest.json"
        _, self.manifest_sha = create_manifest(
            self.tag, self.source_sha, self.assets, self.manifest_path
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_assets(self, names: set[str]) -> None:
        for name in names:
            content = f"fixture:{name}".encode("utf-8")
            (self.assets / name).write_bytes(content)
            digest = hashlib.sha256(content).hexdigest()
            (self.assets / f"{name}.sha256").write_text(f"{digest}  {name}\n", encoding="ascii")

    def test_allowlist_pins_all_seven_tags_to_specific_source_commits(self) -> None:
        self.assertEqual(
            {"v1.0.1", "v1.0.2", "v1.0.3", "v1.0.4", "v1.0.5", "v1.0.6", "v1.0.7"},
            set(HISTORICAL_TAG_COMMITS),
        )
        for tag in HISTORICAL_TAG_COMMITS:
            self.assertEqual(tag, validate_tag(tag))
        with self.assertRaises(BackfillError):
            validate_tag("v1.0.8")

    def test_tv_metadata_must_be_unique_and_expected(self) -> None:
        self.assertEqual(("1.8", 26), extract_tv_metadata('versionCode = 26\nversionName = "1.8"\n'))
        for source in (
            'versionCode = 26\n',
            'versionCode = 26\nversionCode = 27\nversionName = "1.8"\n',
        ):
            with self.subTest(source=source), self.assertRaises(BackfillError):
                extract_tv_metadata(source)

    def test_manifest_covers_exact_tv_and_six_desktop_installers(self) -> None:
        manifest = verify_manifest(self.tag, self.assets, self.manifest_path, self.manifest_sha)
        self.assertEqual(self.tag, manifest["tag"])
        self.assertEqual(self.source_sha, manifest["source_sha"])
        self.assertEqual(14, len(manifest["assets"]))
        self.assertIn("ZotifyMusicTV-v1.8.apk", {entry["name"] for entry in manifest["assets"]})

    def test_manifest_rejects_wrong_reviewed_hash_and_asset_tampering(self) -> None:
        with self.assertRaisesRegex(BackfillError, "does not match the reviewed value"):
            verify_manifest(self.tag, self.assets, self.manifest_path, "0" * 64)
        target = self.assets / "ZotifyMusicTV-v1.8.apk"
        target.write_bytes(b"tampered after manifest creation")
        with self.assertRaisesRegex(BackfillError, "failed size/SHA-256 verification"):
            verify_manifest(self.tag, self.assets, self.manifest_path, self.manifest_sha)

    def test_manifest_rejects_incomplete_or_extra_platform_packages(self) -> None:
        (self.assets / "ZotifyMusic-Desktop-v1.0.5-linux-x86_64.rpm").unlink()
        with self.assertRaises(BackfillError):
            create_manifest(self.tag, self.source_sha, self.assets, self.root / "manifest.json")

    def test_manifest_rejects_non_pinned_source_commit(self) -> None:
        with self.assertRaisesRegex(BackfillError, "not the pinned commit"):
            create_manifest(self.tag, "0" * 40, self.assets, self.root / "manifest.json")

    def test_manifest_rejects_wrong_schema_shape_and_boolean_sizes(self) -> None:
        mutations = (
            lambda manifest: manifest.update(extra="not allowed"),
            lambda manifest: manifest.update(schema_version=True),
            lambda manifest: manifest["assets"][0].update(size=True),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                manifest = json.loads(self.manifest_path.read_text(encoding="utf-8"))
                mutate(manifest)
                self.manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                digest = hashlib.sha256(self.manifest_path.read_bytes()).hexdigest()
                with self.assertRaises(BackfillError):
                    verify_manifest(self.tag, self.assets, self.manifest_path, digest)
                # Restore the valid manifest for the next mutation.
                create_manifest(self.tag, self.source_sha, self.assets, self.manifest_path)

    def test_existing_release_must_match_exact_public_tag_and_preserves_current_assets(self) -> None:
        release = {
            "tag_name": self.tag,
            "draft": False,
            "prerelease": False,
            "body": "existing notes stay unchanged",
            "assets": [{"id": 7, "name": "Zotify.Music.apk"}],
        }
        inventory = validate_release_record(self.tag, release)
        self.assertEqual({"Zotify.Music.apk"}, set(inventory))
        self.assertEqual("existing notes stay unchanged", release["body"])
        with self.assertRaises(BackfillError):
            validate_release_record("v1.0.6", release)
        release["draft"] = True
        with self.assertRaises(BackfillError):
            validate_release_record(self.tag, release)

    def test_publication_requires_exact_tag_and_manifest_confirmation(self) -> None:
        confirmation = f"PUBLISH {self.tag} {self.manifest_sha}"
        verify_publish_confirmation(self.tag, self.manifest_sha, confirmation)
        with self.assertRaises(BackfillError):
            verify_publish_confirmation(self.tag, self.manifest_sha, f"PUBLISH {self.tag} {'0' * 64}")
        with self.assertRaises(BackfillError):
            verify_publish_confirmation(self.tag, self.manifest_sha, "yes")
        with self.assertRaises(BackfillError):
            verify_publish_confirmation(self.tag, self.manifest_sha, f" {confirmation}")
        with self.assertRaises(BackfillError):
            verify_publish_confirmation(self.tag, self.manifest_sha.upper(), f"PUBLISH {self.tag} {self.manifest_sha.upper()}")

    def test_remote_asset_digest_falls_back_to_streamed_sha256(self) -> None:
        payload = b"existing release asset bytes"
        expected = hashlib.sha256(payload).hexdigest()
        response = io.BytesIO(payload)
        with patch("urllib.request.urlopen", return_value=response) as urlopen:
            actual = _remote_asset_sha256({"browser_download_url": "https://github.com/example/release/asset"})
        self.assertEqual(expected, actual)
        urlopen.assert_called_once()

    def test_remote_asset_digest_prefers_valid_github_digest_without_download(self) -> None:
        expected = "a" * 64
        with patch("urllib.request.urlopen") as urlopen:
            actual = _remote_asset_sha256({"digest": f"sha256:{expected}"})
        self.assertEqual(expected, actual)
        urlopen.assert_not_called()

    def test_remote_asset_digest_fails_closed_without_digest_or_url(self) -> None:
        with self.assertRaisesRegex(BackfillError, "Cannot safely verify"):
            _remote_asset_sha256({"name": "existing.bin"})

    def test_remote_asset_digest_fails_closed_when_download_is_unavailable(self) -> None:
        with patch("urllib.request.urlopen", side_effect=OSError("network unavailable")):
            with self.assertRaisesRegex(BackfillError, "Could not verify"):
                _remote_asset_sha256({"browser_download_url": "https://github.com/example/release/asset"})

    def test_publication_is_disabled_unless_release_switch_is_exactly_true(self) -> None:
        environment = {
            "RELEASES_ENABLED": "false",
            "GITHUB_TOKEN": "test-token",
            "GITHUB_REPOSITORY": "owner/repo",
            "HISTORICAL_PUBLISH_CONFIRMATION": f"PUBLISH {self.tag} {self.manifest_sha}",
        }
        with patch.dict(os.environ, environment, clear=False):
            with self.assertRaisesRegex(BackfillError, "not exactly 'true'"):
                publish_assets(self.tag, self.assets, self.manifest_path, self.manifest_sha, "42", environment["HISTORICAL_PUBLISH_CONFIRMATION"])

    def test_publication_refuses_conflicting_release_asset_before_any_upload(self) -> None:
        release_url = "https://api.github.com/repos/owner/repo/releases/tags/v1.0.5"
        release = {
            "tag_name": self.tag,
            "draft": False,
            "prerelease": False,
            "body": "preserve these notes",
            "upload_url": "https://uploads.github.com/repos/owner/repo/releases/1/assets{?name,label}",
            "assets": [{
                "id": 17,
                "name": "ZotifyMusicTV-v1.8.apk",
                "digest": f"sha256:{'0' * 64}",
            }],
        }
        run = {
            "path": ".github/workflows/build-historical-release-assets.yml",
            "status": "completed",
            "conclusion": "success",
            "event": "workflow_dispatch",
            "head_repository": {"full_name": "owner/repo"},
            "head_branch": "main",
            "workflow_ref": "owner/repo/.github/workflows/build-historical-release-assets.yml@refs/heads/main",
        }
        environment = {
            "RELEASES_ENABLED": "true",
            "GITHUB_TOKEN": "test-token",
            "GITHUB_REPOSITORY": "owner/repo",
            "GITHUB_API_URL": "https://api.github.com",
            "HISTORICAL_PUBLISH_CONFIRMATION": f"PUBLISH {self.tag} {self.manifest_sha}",
        }

        def api_json(url: str, token: str) -> dict:
            if url.endswith("/actions/runs/42"):
                return run
            if url == "https://api.github.com/repos/owner/repo":
                return {"default_branch": "main"}
            if url.endswith("/git/ref/tags/v1.0.5"):
                return {"object": {"type": "commit", "sha": self.source_sha}}
            if url == release_url:
                return release
            raise AssertionError(f"Unexpected API request: {url}")

        with patch.dict(os.environ, environment, clear=False):
            with patch("historical_release_backfill._api_json", side_effect=api_json):
                with patch("historical_release_backfill._api_request") as api_request:
                    with self.assertRaisesRegex(BackfillError, "conflicting asset named"):
                        publish_assets(
                            self.tag,
                            self.assets,
                            self.manifest_path,
                            self.manifest_sha,
                            "42",
                            environment["HISTORICAL_PUBLISH_CONFIRMATION"],
                        )
                    api_request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
