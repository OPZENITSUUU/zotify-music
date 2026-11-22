#!/usr/bin/env python3
"""Validate release tag and Gradle version metadata before a signed release build."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


TAG_PATTERN = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\.(0|[1-9][0-9]*))?$")
CODE_PATTERN = re.compile(r'^\s*extra\["bitchordVersionCode"\]\s*=\s*(\d+)\s*$', re.MULTILINE)
NAME_PATTERN = re.compile(r'^\s*extra\["bitchordVersionName"\]\s*=\s*"([^"]+)"\s*$', re.MULTILINE)


class ValidationError(ValueError):
    """Raised when release metadata is unsafe to publish."""


@dataclass(frozen=True)
class ReleaseVersion:
    tag: str
    version_name: str
    version_code: int
    semantic_version: tuple[int, int, int]


def parse_tag(tag: str) -> tuple[int, int, int]:
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise ValidationError(f"Invalid release tag {tag!r}; expected vMAJOR.MINOR or vMAJOR.MINOR.PATCH")
    major, minor, patch = match.groups()
    return int(major), int(minor), int(patch or 0)


def extract_gradle_version(source: str) -> tuple[str, int]:
    name_match = NAME_PATTERN.search(source)
    code_match = CODE_PATTERN.search(source)
    if not name_match or not code_match:
        raise ValidationError("Could not read bitchordVersionName and bitchordVersionCode from Gradle")
    version_name = name_match.group(1)
    version_code = int(code_match.group(1))
    if version_code <= 0:
        raise ValidationError("Gradle versionCode must be a positive integer")
    return version_name, version_code


def previous_tag_version_code(tag: str, repository: Path, gradle_file: str) -> int | None:
    target = parse_tag(tag)
    try:
        result = subprocess.run(
            ["git", "tag", "--list", "v*"],
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValidationError("Unable to enumerate Git tags for monotonic versionCode validation") from exc

    earlier: list[tuple[tuple[int, int, int], str]] = []
    for candidate in result.stdout.splitlines():
        try:
            semantic = parse_tag(candidate)
        except ValidationError:
            continue
        if semantic < target:
            earlier.append((semantic, candidate))

    # A historical release can have a malformed tag target (for example, a
    # release created from a feature branch while GitHub defaulted the tag to
    # the repository's default branch). Ignore that broken tag and recover the
    # monotonic versionCode baseline from the newest prior tag whose checked-in
    # Gradle metadata actually matches its tag.
    for _, previous_tag in sorted(earlier, reverse=True):
        try:
            previous_source = subprocess.run(
                ["git", "show", f"{previous_tag}:{gradle_file}"],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout
            previous_name, previous_code = extract_gradle_version(previous_source)
        except (OSError, subprocess.CalledProcessError, ValidationError):
            print(f"Warning: ignoring invalid historical release tag {previous_tag}", file=sys.stderr)
            continue
        if previous_name != previous_tag.removeprefix("v"):
            print(
                f"Warning: ignoring historical release tag {previous_tag}; "
                f"its Gradle versionName is {previous_name}",
                file=sys.stderr,
            )
            continue
        return previous_code
    return None


def validate_release(
    tag: str,
    gradle_source: str,
    previous_version_code: int | None = None,
    latest_release_tag: str | None = None,
    release_already_exists: bool = False,
) -> ReleaseVersion:
    semantic_version = parse_tag(tag)
    version_name, version_code = extract_gradle_version(gradle_source)
    if version_name != tag.removeprefix("v"):
        raise ValidationError(f"Tag {tag} does not match Gradle versionName {version_name}")
    if previous_version_code is not None and version_code <= previous_version_code:
        raise ValidationError(
            f"Gradle versionCode {version_code} must exceed previous versionCode {previous_version_code}"
        )
    if latest_release_tag:
        latest_semantic = parse_tag(latest_release_tag)
        if semantic_version <= latest_semantic:
            raise ValidationError(f"Release {tag} is not newer than latest published release {latest_release_tag}")
    if release_already_exists:
        raise ValidationError(f"A GitHub Release already exists for {tag}; refusing to overwrite it")
    return ReleaseVersion(tag, version_name, version_code, semantic_version)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--gradle-file", default="build.gradle.kts")
    parser.add_argument("--repository", default=".")
    parser.add_argument("--latest-release-tag", default="")
    parser.add_argument("--release-already-exists", action="store_true")
    parser.add_argument("--print-version-code", action="store_true")
    args = parser.parse_args(argv)

    repository = Path(args.repository).resolve()
    gradle_path = repository / args.gradle_file
    try:
        source = gradle_path.read_text(encoding="utf-8")
        previous_code = previous_tag_version_code(args.tag, repository, args.gradle_file)
        release = validate_release(
            tag=args.tag,
            gradle_source=source,
            previous_version_code=previous_code,
            latest_release_tag=args.latest_release_tag or None,
            release_already_exists=args.release_already_exists,
        )
    except (OSError, ValidationError) as exc:
        print(f"Release validation failed: {exc}", file=sys.stderr)
        return 1

    if args.print_version_code:
        print(release.version_code)
    else:
        print(
            f"Validated {release.tag}: versionName={release.version_name}, "
            f"versionCode={release.version_code}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
