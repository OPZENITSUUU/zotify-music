#!/usr/bin/env python3
"""Prepare tag-specific Desktop package versions and collect native release assets."""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import re
import shutil
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

TAG_PATTERN = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
LITERAL_VERSION_CODE = re.compile(r"(?m)^(?P<indent>[ \t]*)val desktopVersionCode\s*=\s*(?P<code>\d+)\s*$")
PROPERTY_VERSION_CODE = re.compile(
    r'(?m)^\s*val desktopVersionCode\s*:\s*Int\s*=\s*providers\.gradleProperty\("bitchord\.desktopVersionCode"\)'
)
VERSION_PROPERTY = 'providers.gradleProperty("bitchord.version")'


class ReleaseAssetError(ValueError):
    """Raised when release metadata or package outputs are unsafe to publish."""


@dataclass(frozen=True)
class DesktopReleaseMetadata:
    tag: str
    app_version: str
    desktop_version_code: int
    native_package_version: str


def parse_release_tag(tag: str) -> DesktopReleaseMetadata:
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise ReleaseAssetError(f"Invalid release tag {tag!r}; expected vMAJOR.MINOR.PATCH")
    major, minor, patch = (int(part) for part in match.groups())
    if max(major, minor, patch) > 65535:
        raise ReleaseAssetError("Desktop native package version components must not exceed 65535")
    return DesktopReleaseMetadata(
        tag=tag,
        app_version=f"{major}.{minor}.{patch}",
        desktop_version_code=patch,
        native_package_version=f"{major}.{minor}.{patch}",
    )


def prepare_desktop_build(tag: str, gradle_file: Path, apply: bool = False) -> DesktopReleaseMetadata:
    metadata = parse_release_tag(tag)
    try:
        source = gradle_file.read_text(encoding="utf-8")
    except OSError as exc:
        raise ReleaseAssetError(f"Unable to read Desktop Gradle file: {gradle_file}") from exc
    if VERSION_PROPERTY not in source:
        raise ReleaseAssetError("Desktop build does not accept the release version property")

    literal_matches = list(LITERAL_VERSION_CODE.finditer(source))
    property_matches = list(PROPERTY_VERSION_CODE.finditer(source))
    if len(literal_matches) == 1 and not property_matches:
        match = literal_matches[0]
        replacement = f"{match.group('indent')}val desktopVersionCode = {metadata.desktop_version_code}"
        prepared = source[:match.start()] + replacement + source[match.end():]
        if apply:
            gradle_file.write_text(prepared, encoding="utf-8")
    elif not literal_matches and len(property_matches) == 1:
        prepared = source
    else:
        raise ReleaseAssetError(
            "Expected exactly one literal Desktop version-code declaration in an old tag, "
            "or exactly one supported Gradle-property declaration in a current build"
        )

    if len(re.findall(r"(?m)^\s*val desktopVersionCode\b", prepared)) != 1:
        raise ReleaseAssetError("Desktop version-code declaration is ambiguous after preparation")
    return metadata


def native_target(platform_name: str, machine_name: str | None = None) -> tuple[str, tuple[str, ...]]:
    machine = (machine_name or platform.machine()).lower()
    if machine in {"amd64", "x64"}:
        machine = "x86_64"
    if machine in {"aarch64", "arm64"}:
        machine = "arm64"
    if machine not in {"x86_64", "arm64"}:
        raise ReleaseAssetError(f"Unsupported Desktop runner architecture: {machine}")
    if platform_name == "linux":
        return f"linux-{machine}", ("deb", "rpm")
    if platform_name == "windows":
        if machine != "x86_64":
            raise ReleaseAssetError("Windows release packages currently support x86_64 only")
        return f"windows-{machine}", ("exe", "msi")
    if platform_name == "macos":
        return f"macos-{machine}", ("dmg", "pkg")
    raise ReleaseAssetError(f"Unsupported Desktop platform: {platform_name}")


def collect_desktop_assets(
    tag: str,
    platform_name: str,
    binaries_root: Path,
    output_dir: Path,
    machine_name: str | None = None,
) -> list[Path]:
    metadata = parse_release_tag(tag)
    target, extensions = native_target(platform_name, machine_name)
    output_dir.mkdir(parents=True, exist_ok=True)
    collected: list[Path] = []
    for extension in extensions:
        candidates = sorted(
            path for path in binaries_root.rglob(f"*.{extension}") if path.is_file() and path.stat().st_size > 0
        )
        if len(candidates) != 1:
            raise ReleaseAssetError(
                f"Expected exactly one non-empty .{extension} package under {binaries_root}, found {len(candidates)}"
            )
        asset = output_dir / f"ZotifyMusic-Desktop-{metadata.tag}-{target}.{extension}"
        shutil.copy2(candidates[0], asset)
        digest = hashlib.sha256(asset.read_bytes()).hexdigest()
        sidecar = asset.with_name(asset.name + ".sha256")
        sidecar.write_text(f"{digest}  {asset.name}\n", encoding="ascii")
        if hashlib.sha256(asset.read_bytes()).hexdigest() != sidecar.read_text(encoding="ascii").split()[0]:
            raise ReleaseAssetError(f"SHA-256 verification failed for {asset.name}")
        collected.extend((asset, sidecar))
    return collected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--gradle-file", type=Path)
    parser.add_argument("--apply", action="store_true", help="apply the historical installer-version override")
    parser.add_argument("--platform", choices=("linux", "windows", "macos"))
    parser.add_argument("--binaries-root", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--machine")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()

    metadata = parse_release_tag(args.tag)
    if args.gradle_file:
        metadata = prepare_desktop_build(args.tag, args.gradle_file, apply=args.apply)
    assets: list[Path] = []
    if args.platform:
        if not args.binaries_root or not args.output_dir:
            raise ReleaseAssetError("--platform requires both --binaries-root and --output-dir")
        assets = collect_desktop_assets(args.tag, args.platform, args.binaries_root, args.output_dir, args.machine)

    result = asdict(metadata)
    result["assets"] = [str(path) for path in assets]
    print(json.dumps(result, sort_keys=True))
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"app_version={metadata.app_version}\n")
            output.write(f"desktop_version_code={metadata.desktop_version_code}\n")
            output.write(f"native_package_version={metadata.native_package_version}\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ReleaseAssetError) as exc:
        print(f"Desktop release asset preparation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
