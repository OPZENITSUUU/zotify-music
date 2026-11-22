from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from desktop_release_assets import (
    ReleaseAssetError,
    collect_desktop_assets,
    native_target,
    parse_release_tag,
    prepare_desktop_build,
)


BUILD_SOURCE = '''val appVersion: String = providers.gradleProperty("bitchord.version").orNull ?: "1.8-beta1"
val desktopVersionCode = 29
val nativePackageVersion = appVersion.substringBefore('-').split('.').take(2).joinToString(".") + ".$desktopVersionCode"
packageName = "Zotify Music"
upgradeUuid = "8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913"
'''

PROPERTY_BUILD_SOURCE = '''val appVersion: String = providers.gradleProperty("bitchord.version").orNull ?: "1.8-beta1"
val desktopVersionCode: Int = providers.gradleProperty("bitchord.desktopVersionCode").orNull?.toIntOrNull() ?: 29
val nativePackageVersion = appVersion.substringBefore('-').split('.').take(2).joinToString(".") + ".$desktopVersionCode"
packageName = "Zotify Music"
upgradeUuid = "8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913"
'''


class DesktopReleaseAssetsTest(unittest.TestCase):
    def test_release_tag_drives_display_and_native_installer_versions(self) -> None:
        for tag, version, code in (
            ("v1.0.1", "1.0.1", 1),
            ("v1.0.5", "1.0.5", 5),
            ("v1.0.7", "1.0.7", 7),
            ("v1.1.0", "1.1.0", 0),
        ):
            with self.subTest(tag=tag):
                metadata = parse_release_tag(tag)
                self.assertEqual(version, metadata.app_version)
                self.assertEqual(code, metadata.desktop_version_code)
                self.assertEqual(version, metadata.native_package_version)

    def test_rejects_non_semver_and_installer_overflow_tags(self) -> None:
        for tag in ("1.0.1", "v1.0", "v01.0.1", "v1.0.1-beta1", "v1.0.65536", "v65536.0.0"):
            with self.subTest(tag=tag), self.assertRaises(ReleaseAssetError):
                parse_release_tag(tag)

    def test_historical_literal_code_is_overridden_without_touching_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            build_file = Path(temporary) / "build.gradle.kts"
            build_file.write_text(BUILD_SOURCE, encoding="utf-8")
            metadata = prepare_desktop_build("v1.0.5", build_file, apply=True)
            result = build_file.read_text(encoding="utf-8")
        self.assertEqual(5, metadata.desktop_version_code)
        self.assertIn("val desktopVersionCode = 5", result)
        self.assertIn('packageName = "Zotify Music"', result)
        self.assertIn('upgradeUuid = "8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913"', result)
        self.assertEqual(BUILD_SOURCE.count("\n"), result.count("\n"))

    def test_current_property_based_code_is_supported_without_rewriting(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            build_file = Path(temporary) / "build.gradle.kts"
            build_file.write_text(PROPERTY_BUILD_SOURCE, encoding="utf-8")
            metadata = prepare_desktop_build("v1.0.12", build_file, apply=True)
            result = build_file.read_text(encoding="utf-8")
        self.assertEqual(12, metadata.desktop_version_code)
        self.assertEqual(PROPERTY_BUILD_SOURCE, result)

    def test_rejects_missing_or_ambiguous_version_declarations(self) -> None:
        for source in (
            'val appVersion = "1.8-beta1"\nval desktopVersionCode = 29\n',
            BUILD_SOURCE + "val desktopVersionCode = 30\n",
        ):
            with tempfile.TemporaryDirectory() as temporary:
                build_file = Path(temporary) / "build.gradle.kts"
                build_file.write_text(source, encoding="utf-8")
                with self.subTest(source=source), self.assertRaises(ReleaseAssetError):
                    prepare_desktop_build("v1.0.5", build_file, apply=True)

    def test_platform_output_formats_and_architecture_labels(self) -> None:
        self.assertEqual(("linux-x86_64", ("deb", "rpm")), native_target("linux", "x86_64"))
        self.assertEqual(("windows-x86_64", ("exe", "msi")), native_target("windows", "AMD64"))
        self.assertEqual(("macos-arm64", ("dmg", "pkg")), native_target("macos", "aarch64"))
        with self.assertRaises(ReleaseAssetError):
            native_target("windows", "arm64")

    def test_collects_each_native_package_with_verified_checksum(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binaries = root / "binaries"
            output = root / "dist"
            binaries.mkdir()
            (binaries / "ZotifyMusic.deb").write_bytes(b"debian package fixture")
            (binaries / "ZotifyMusic.rpm").write_bytes(b"rpm package fixture")
            assets = collect_desktop_assets("v1.0.5", "linux", binaries, output, "x86_64")
            self.assertEqual(4, len(assets))
            for asset in output.glob("*.sha256"):
                expected = asset.read_text(encoding="ascii").split()[0]
                package = output / asset.name.removesuffix(".sha256")
                self.assertEqual(expected, hashlib.sha256(package.read_bytes()).hexdigest())
                self.assertIn("v1.0.5-linux-x86_64", package.name)

    def test_collection_fails_closed_if_a_platform_package_is_missing_or_ambiguous(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root.parent / "dist"
            root.mkdir(exist_ok=True)
            (root / "one.deb").write_bytes(b"one")
            with self.assertRaisesRegex(ReleaseAssetError, r"\.rpm.*found 0"):
                collect_desktop_assets("v1.0.1", "linux", root, output, "x86_64")
            (root / "one.rpm").write_bytes(b"one")
            (root / "two.rpm").write_bytes(b"two")
            with self.assertRaisesRegex(ReleaseAssetError, r"\.rpm.*found 2"):
                collect_desktop_assets("v1.0.1", "linux", root, output, "x86_64")


if __name__ == "__main__":
    unittest.main()
