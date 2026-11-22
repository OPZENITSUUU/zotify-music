#!/usr/bin/env python3
"""Validate release tag and phone/TV Gradle version metadata before a signed build."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


TAG_PATTERN = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
CODE_PATTERN = re.compile(r'^\s*extra\["bitchordVersionCode"\]\s*=\s*(\d+)\s*$', re.MULTILINE)
NAME_PATTERN = re.compile(r'^\s*extra\["bitchordVersionName"\]\s*=\s*"([^"]+)"\s*$', re.MULTILINE)
TV_CODE_PATTERN = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE)
TV_NAME_PATTERN = re.compile(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', re.MULTILINE)
TV_VERSION_NAME_PATTERN = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\.(0|[1-9][0-9]*))?$")


class ValidationError(ValueError):
    """Raised when release metadata is unsafe to publish."""


@dataclass(frozen=True)
class ReleaseVersion:
    tag: str
    version_name: str
    version_code: int
    tv_version_name: str
    tv_version_code: int
    minimum_supported_version_code: int
    semantic_version: tuple[int, int, int]


def parse_tag(tag: str) -> tuple[int, int, int]:
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise ValidationError(f"Invalid release tag {tag!r}; expected vMAJOR.MINOR.PATCH")
    return tuple(int(part) for part in match.groups())


def _one_match(pattern: re.Pattern[str], source: str, description: str) -> str:
    matches = pattern.findall(source)
    if len(matches) != 1:
        raise ValidationError(f"Expected exactly one {description} declaration in Gradle metadata")
    return matches[0]


def extract_gradle_version(source: str) -> tuple[str, int]:
    version_name = _one_match(NAME_PATTERN, source, "phone versionName")
    version_code = int(_one_match(CODE_PATTERN, source, "phone versionCode"))
    if version_code <= 0:
        raise ValidationError("Phone Gradle versionCode must be a positive integer")
    return version_name, version_code


def extract_tv_gradle_version(source: str) -> tuple[str, int]:
    version_name = _one_match(TV_NAME_PATTERN, source, "TV versionName")
    version_code = int(_one_match(TV_CODE_PATTERN, source, "TV versionCode"))
    if not TV_VERSION_NAME_PATTERN.fullmatch(version_name):
        raise ValidationError("TV Gradle versionName must be a numeric major.minor[.patch] version")
    if version_code <= 0:
        raise ValidationError("TV Gradle versionCode must be a positive integer")
    return version_name, version_code


def _prior_semantic_tags(tag: str, repository: Path) -> list[tuple[tuple[int, int, int], str]]:
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
    return sorted(earlier)


def _read_tag_file(tag: str, repository: Path, relative_path: str) -> str:
    try:
        return subprocess.run(
            ["git", "show", f"{tag}:{relative_path}"],
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValidationError(f"Unable to read {relative_path} metadata from previous tag {tag}") from exc


def previous_tag_version_code(tag: str, repository: Path, gradle_file: str) -> int | None:
    earlier = _prior_semantic_tags(tag, repository)
    if not earlier:
        return None
    _, previous_tag = earlier[-1]
    previous_source = _read_tag_file(previous_tag, repository, gradle_file)
    previous_name, previous_code = extract_gradle_version(previous_source)
    if previous_name != previous_tag.removeprefix("v"):
        print(
            f"Warning: previous tag {previous_tag} has Gradle versionName {previous_name}; "
            "continuing with versionCode monotonicity validation",
            file=sys.stderr,
        )
    return previous_code


def previous_tag_tv_version_code(tag: str, repository: Path, gradle_file: str) -> int | None:
    # Older phone releases may predate the TV application. Use the newest earlier
    # release that actually contains the TV module as the TV monotonicity baseline.
    for _, previous_tag in reversed(_prior_semantic_tags(tag, repository)):
        probe = subprocess.run(
            ["git", "ls-tree", "-r", "--name-only", previous_tag, "--", gradle_file],
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
        )
        if gradle_file in probe.stdout.splitlines():
            _, previous_code = extract_tv_gradle_version(_read_tag_file(previous_tag, repository, gradle_file))
            return previous_code
    return None


def validate_release(
    tag: str,
    gradle_source: str,
    tv_gradle_source: str,
    previous_version_code: int | None = None,
    previous_tv_version_code: int | None = None,
    latest_release_tag: str | None = None,
    release_already_exists: bool = False,
    minimum_supported_version_code: int = 0,
) -> ReleaseVersion:
    semantic_version = parse_tag(tag)
    version_name, version_code = extract_gradle_version(gradle_source)
    if version_name != tag.removeprefix("v"):
        raise ValidationError(f"Tag {tag} does not match Gradle versionName {version_name}")
    tv_version_name, tv_version_code = extract_tv_gradle_version(tv_gradle_source)
    if previous_version_code is not None and version_code <= previous_version_code:
        raise ValidationError(
            f"Phone Gradle versionCode {version_code} must exceed previous versionCode {previous_version_code}"
        )
    if previous_tv_version_code is not None and tv_version_code <= previous_tv_version_code:
        raise ValidationError(
            f"TV Gradle versionCode {tv_version_code} must exceed previous versionCode {previous_tv_version_code}"
        )
    if latest_release_tag:
        latest_semantic = parse_tag(latest_release_tag)
        if semantic_version <= latest_semantic:
            raise ValidationError(f"Release {tag} is not newer than latest published release {latest_release_tag}")
    if release_already_exists:
        raise ValidationError(f"A GitHub Release already exists for {tag}; refusing to overwrite it")
    if minimum_supported_version_code < 0 or minimum_supported_version_code > version_code:
        raise ValidationError(
            "Minimum supported Android phone version code must be zero or no greater than this release's versionCode"
        )
    return ReleaseVersion(
        tag,
        version_name,
        version_code,
        tv_version_name,
        tv_version_code,
        minimum_supported_version_code,
        semantic_version,
    )


def _reject_duplicate_json_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError(f"Release policy contains duplicate JSON key {key!r}")
        result[key] = value
    return result


def read_minimum_supported_version_code(policy_path: Path) -> int:
    try:
        policy = json.loads(
            policy_path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_json_keys,
        )
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"Unable to read release policy {policy_path.name}") from exc
    if not isinstance(policy, dict):
        raise ValidationError("Release policy must be a JSON object")
    value = policy.get("minimum_supported_android_phone_version_code")
    if type(value) is not int or value < 0:
        raise ValidationError("minimum_supported_android_phone_version_code must be a non-negative integer")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--gradle-file", default="build.gradle.kts")
    parser.add_argument("--tv-gradle-file", default="tv/build.gradle.kts")
    parser.add_argument("--repository", default=".")
    parser.add_argument("--policy-file", default=".github/release-policy.json")
    parser.add_argument("--latest-release-tag", default="")
    parser.add_argument("--release-already-exists", action="store_true")
    parser.add_argument("--print-version-code", action="store_true")
    parser.add_argument("--print-minimum-supported-version-code", action="store_true")
    parser.add_argument("--print-tv-version-code", action="store_true")
    parser.add_argument("--print-tv-version-name", action="store_true")
    args = parser.parse_args(argv)

    repository = Path(args.repository).resolve()
    gradle_path = repository / args.gradle_file
    tv_gradle_path = repository / args.tv_gradle_file
    try:
        source = gradle_path.read_text(encoding="utf-8")
        tv_source = tv_gradle_path.read_text(encoding="utf-8")
        minimum_supported_version_code = read_minimum_supported_version_code(repository / args.policy_file)
        previous_code = previous_tag_version_code(args.tag, repository, args.gradle_file)
        previous_tv_code = previous_tag_tv_version_code(args.tag, repository, args.tv_gradle_file)
        release = validate_release(
            tag=args.tag,
            gradle_source=source,
            tv_gradle_source=tv_source,
            previous_version_code=previous_code,
            previous_tv_version_code=previous_tv_code,
            latest_release_tag=args.latest_release_tag or None,
            release_already_exists=args.release_already_exists,
            minimum_supported_version_code=minimum_supported_version_code,
        )
    except (OSError, ValidationError) as exc:
        print(f"Release validation failed: {exc}", file=sys.stderr)
        return 1

    if args.print_version_code:
        print(release.version_code)
    elif args.print_minimum_supported_version_code:
        print(release.minimum_supported_version_code)
    elif args.print_tv_version_code:
        print(release.tv_version_code)
    elif args.print_tv_version_name:
        print(release.tv_version_name)
    else:
        print(
            f"Validated {release.tag}: phone versionName={release.version_name}, "
            f"versionCode={release.version_code}; TV versionName={release.tv_version_name}, "
            f"versionCode={release.tv_version_code}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
