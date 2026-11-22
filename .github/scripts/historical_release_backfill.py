#!/usr/bin/env python3
"""Build-only historical asset validation and explicitly authorized release backfill."""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from desktop_release_assets import ReleaseAssetError, parse_release_tag, prepare_desktop_build

HISTORICAL_TAG_COMMITS = {
    "v1.0.1": "221db2d7d851d1e42085b4a331bd36b634485791",
    "v1.0.2": "3476604a3d1aff190902ae3c756557d0cbc4fcaf",
    "v1.0.3": "f0f1a74dbd9ad24fed0d1cb7fe4bd70c3ad58ee6",
    "v1.0.4": "11999908516becc22bb05437535cb2af5b821f21",
    "v1.0.5": "c74d0cd79c60c7f74b6b0d12fb98da43e31fe8d1",
    "v1.0.6": "5f7031f006265aa7be2a49ea78585435d5f21e36",
    "v1.0.7": "5f7031f006265aa7be2a49ea78585435d5f21e36",
}
TV_VERSION_NAME_PATTERN = re.compile(r'(?m)^\s*versionName\s*=\s*"([^\"]+)"\s*$')
TV_VERSION_CODE_PATTERN = re.compile(r"(?m)^\s*versionCode\s*=\s*(\d+)\s*$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
COMMIT_PATTERN = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
BUILD_WORKFLOW_PATH = ".github/workflows/build-historical-release-assets.yml"
EXPECTED_DESKTOP_SUFFIXES = {
    "linux-x86_64.deb",
    "linux-x86_64.rpm",
    "windows-x86_64.exe",
    "windows-x86_64.msi",
}
MACOS_SUFFIXES = {
    ("macos-x86_64.dmg", "macos-x86_64.pkg"),
    ("macos-arm64.dmg", "macos-arm64.pkg"),
}


class BackfillError(ValueError):
    """Raised when a historical source, asset set, or publish request is unsafe."""


def validate_tag(tag: str) -> str:
    if tag not in HISTORICAL_TAG_COMMITS:
        raise BackfillError(f"Unsupported historical tag {tag!r}; only v1.0.1 through v1.0.7 are allowed")
    return tag


def extract_tv_metadata(source: str) -> tuple[str, int]:
    names = TV_VERSION_NAME_PATTERN.findall(source)
    codes = TV_VERSION_CODE_PATTERN.findall(source)
    if len(names) != 1 or len(codes) != 1:
        raise BackfillError("TV Gradle metadata must contain exactly one versionName and one versionCode")
    return names[0], int(codes[0])


def validate_source(tag: str, source_dir: Path, expected_sha: str | None = None) -> dict[str, Any]:
    validate_tag(tag)
    pinned_sha = HISTORICAL_TAG_COMMITS[tag]
    try:
        actual_sha = subprocess.run(
            ["git", "rev-parse", "HEAD^{commit}"],
            cwd=source_dir,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip().lower()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise BackfillError("Historical source checkout is not a readable Git commit") from exc
    if actual_sha != pinned_sha:
        raise BackfillError(f"Tag {tag} resolved to an unexpected commit; refusing to build changed tag source")
    if expected_sha and expected_sha.lower() != actual_sha:
        raise BackfillError("Checked-out source SHA does not match the validated tag SHA")

    try:
        tv_source = (source_dir / "tv/build.gradle.kts").read_text(encoding="utf-8")
        desktop_file = source_dir / "desktopApp/build.gradle.kts"
        tv_name, tv_code = extract_tv_metadata(tv_source)
        desktop = prepare_desktop_build(tag, desktop_file, apply=False)
    except (OSError, ReleaseAssetError) as exc:
        raise BackfillError(f"Tag source is missing required TV/Desktop release metadata: {exc}") from exc
    if (tv_name, tv_code) != ("1.8", 26):
        raise BackfillError(f"Historical TV metadata changed unexpectedly: {tv_name} (code {tv_code})")
    return {
        "tag": tag,
        "source_sha": actual_sha,
        "tv_version_name": tv_name,
        "tv_version_code": tv_code,
        "desktop_app_version": desktop.app_version,
        "desktop_version_code": desktop.desktop_version_code,
    }


def _expected_binary_names(tag: str, names: set[str]) -> set[str]:
    validate_tag(tag)
    tv_name = "ZotifyMusicTV-v1.8.apk"
    prefix = f"ZotifyMusic-Desktop-{tag}-"
    binaries = {name for name in names if not name.endswith(".sha256")}
    desktop = {name for name in binaries if name.startswith(prefix)}
    suffixes = {name.removeprefix(prefix) for name in desktop}
    mac_arch_sets = [arch for arch in MACOS_SUFFIXES if set(arch).issubset(suffixes)]
    required = set(EXPECTED_DESKTOP_SUFFIXES)
    if len(mac_arch_sets) != 1:
        raise BackfillError("Desktop manifest must contain exactly one complete macOS architecture pair")
    required.update(mac_arch_sets[0])
    expected = {tv_name} | {prefix + suffix for suffix in required}
    if binaries != expected:
        missing = sorted(expected - binaries)
        extra = sorted(binaries - expected)
        raise BackfillError(f"Historical asset inventory is incomplete or unexpected; missing={missing}, extra={extra}")
    return expected


def _asset_inventory(assets_dir: Path) -> dict[str, str]:
    if not assets_dir.is_dir():
        raise BackfillError(f"Asset directory does not exist: {assets_dir}")
    files = [path for path in assets_dir.iterdir() if path.is_file() and path.name != "manifest.json"]
    return {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in files}


def _validate_checksums(assets_dir: Path, names: set[str]) -> None:
    binaries = {name for name in names if not name.endswith(".sha256")}
    expected_names = binaries | {name + ".sha256" for name in binaries}
    if names != expected_names:
        missing = sorted(expected_names - names)
        extra = sorted(names - expected_names)
        raise BackfillError(f"Every package must have one checksum sidecar and no extra files; missing={missing}, extra={extra}")
    for name in binaries:
        sidecar = (assets_dir / f"{name}.sha256").read_text(encoding="ascii").strip().split()
        actual = hashlib.sha256((assets_dir / name).read_bytes()).hexdigest()
        if len(sidecar) != 2 or sidecar[0] != actual or sidecar[1] != name:
            raise BackfillError(f"Checksum sidecar is malformed or does not match {name}")


def create_manifest(tag: str, source_sha: str, assets_dir: Path, output: Path) -> tuple[dict[str, Any], str]:
    validate_tag(tag)
    source_sha = source_sha.lower()
    if not COMMIT_PATTERN.fullmatch(source_sha) or source_sha != HISTORICAL_TAG_COMMITS[tag]:
        raise BackfillError("Manifest source SHA is not the pinned commit for the selected historical tag")
    inventory = _asset_inventory(assets_dir)
    names = set(inventory)
    binaries = _expected_binary_names(tag, names)
    _validate_checksums(assets_dir, names)
    if {name for name in names if not name.endswith(".sha256")} != binaries:
        raise BackfillError("Manifest package inventory differs from the required TV/Desktop asset set")
    assets = [
        {"name": name, "size": (assets_dir / name).stat().st_size, "sha256": inventory[name]}
        for name in sorted(names)
    ]
    manifest = {"schema_version": 1, "tag": tag, "source_sha": source_sha, "assets": assets}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    return manifest, digest


def verify_manifest(
    tag: str,
    assets_dir: Path,
    manifest_path: Path,
    expected_sha256: str,
) -> dict[str, Any]:
    validate_tag(tag)
    expected_sha256 = expected_sha256.lower()
    if not SHA256_PATTERN.fullmatch(expected_sha256):
        raise BackfillError("The reviewed manifest SHA-256 must be exactly 64 hexadecimal characters")
    try:
        raw = manifest_path.read_bytes()
        manifest = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BackfillError("Historical asset manifest is missing or invalid JSON") from exc
    if hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise BackfillError("Historical manifest SHA-256 does not match the reviewed value")
    if (
        not isinstance(manifest, dict)
        or set(manifest) != {"schema_version", "tag", "source_sha", "assets"}
        or type(manifest.get("schema_version")) is not int
        or manifest.get("schema_version") != 1
    ):
        raise BackfillError("Unsupported historical asset manifest schema")
    if manifest.get("tag") != tag:
        raise BackfillError("Historical asset manifest tag does not match the requested release")
    source_sha = str(manifest.get("source_sha", "")).lower()
    if source_sha != HISTORICAL_TAG_COMMITS[tag]:
        raise BackfillError("Historical asset manifest source SHA is not pinned to this tag")

    entries = manifest.get("assets")
    if not isinstance(entries, list):
        raise BackfillError("Historical asset manifest has no asset list")
    names: list[str] = []
    for item in entries:
        if not isinstance(item, dict) or set(item) != {"name", "size", "sha256"}:
            raise BackfillError("Historical asset manifest contains a malformed entry")
        name = item["name"]
        digest = item["sha256"]
        size = item["size"]
        if (
            not isinstance(name, str)
            or Path(name).name != name
            or type(size) is not int
            or size < 1
        ):
            raise BackfillError("Historical asset manifest contains an unsafe filename or size")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise BackfillError(f"Historical asset manifest has an invalid digest for {name}")
        names.append(name)
    if len(names) != len(set(names)):
        raise BackfillError("Historical asset manifest contains duplicate asset names")
    inventory = _asset_inventory(assets_dir)
    expected_names = set(names)
    actual_names = set(inventory)
    if actual_names != expected_names:
        raise BackfillError(f"Downloaded build artifact differs from its manifest: missing={sorted(expected_names-actual_names)}, extra={sorted(actual_names-expected_names)}")
    for item in entries:
        name = item["name"]
        path = assets_dir / name
        if path.stat().st_size != item["size"] or inventory[name] != item["sha256"]:
            raise BackfillError(f"Historical asset failed size/SHA-256 verification: {name}")
    _expected_binary_names(tag, expected_names)
    _validate_checksums(assets_dir, expected_names)
    return manifest


def validate_release_record(tag: str, release: dict[str, Any]) -> dict[str, dict[str, Any]]:
    validate_tag(tag)
    if not isinstance(release, dict) or release.get("tag_name") != tag:
        raise BackfillError(f"No GitHub Release exists for exact tag {tag}")
    if release.get("draft") is not False or release.get("prerelease") is not False:
        raise BackfillError(f"Existing release {tag} must be public and not a draft or prerelease")
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise BackfillError("GitHub Release asset inventory is malformed")
    result: dict[str, dict[str, Any]] = {}
    for asset in assets:
        if not isinstance(asset, dict) or not isinstance(asset.get("name"), str):
            raise BackfillError("GitHub Release contains an asset with no name")
        name = asset["name"]
        asset_id = asset.get("id")
        if type(asset_id) is not int or asset_id < 1:
            raise BackfillError(f"GitHub Release asset {name} has no valid immutable asset ID")
        if name in result:
            raise BackfillError(f"GitHub Release contains duplicate asset name {name}")
        result[name] = asset
    return result


def verify_publish_confirmation(tag: str, manifest_sha256: str, confirmation: str) -> None:
    if not SHA256_PATTERN.fullmatch(manifest_sha256) or manifest_sha256 != manifest_sha256.lower():
        raise BackfillError("The reviewed manifest SHA-256 must be 64 lowercase hexadecimal characters")
    expected = f"PUBLISH {tag} {manifest_sha256}"
    if confirmation != expected:
        raise BackfillError(f"Explicit publication confirmation must exactly equal: {expected}")


def _api_request(url: str, token: str, *, data: bytes | None = None, content_type: str | None = None) -> bytes:
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            return response.read()
    except urllib.error.HTTPError as exc:
        raise BackfillError(f"GitHub API request failed with HTTP {exc.code}") from None
    except (OSError, urllib.error.URLError):
        raise BackfillError("GitHub API request failed; no release assets were replaced") from None


def _api_json(url: str, token: str) -> dict[str, Any]:
    try:
        result = json.loads(_api_request(url, token).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BackfillError("GitHub API returned invalid JSON") from exc
    if not isinstance(result, dict):
        raise BackfillError("GitHub API returned an unexpected response")
    return result


def _remote_asset_sha256(asset: dict[str, Any]) -> str:
    digest = asset.get("digest", "")
    remote_digest = digest.removeprefix("sha256:").lower() if isinstance(digest, str) else ""
    if SHA256_PATTERN.fullmatch(remote_digest):
        return remote_digest
    download_url = asset.get("browser_download_url")
    if not isinstance(download_url, str):
        raise BackfillError("Cannot safely verify an existing release asset")
    request = urllib.request.Request(download_url, headers={"Accept": "application/octet-stream"})
    try:
        hasher = hashlib.sha256()
        with urllib.request.urlopen(request, timeout=600) as response:
            while chunk := response.read(1024 * 1024):
                hasher.update(chunk)
        return hasher.hexdigest()
    except (OSError, urllib.error.URLError, urllib.error.HTTPError):
        raise BackfillError("Could not verify an existing release asset") from None


def _repo_api_base() -> str:
    api_base = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise BackfillError("GITHUB_REPOSITORY is unavailable or malformed")
    return f"{api_base}/repos/{repository}"


def _tag_commit_from_api(repo_api: str, tag: str, token: str) -> str:
    ref_path = urllib.parse.quote(f"tags/{tag}", safe="/")
    ref = _api_json(f"{repo_api}/git/ref/{ref_path}", token)
    obj = ref.get("object")
    for _ in range(6):
        if not isinstance(obj, dict) or not isinstance(obj.get("sha"), str):
            raise BackfillError("GitHub tag reference is malformed")
        if obj.get("type") == "commit":
            return obj["sha"].lower()
        if obj.get("type") != "tag":
            raise BackfillError("GitHub release tag does not point to a commit")
        annotated = _api_json(f"{repo_api}/git/tags/{obj['sha']}", token)
        obj = annotated.get("object")
    raise BackfillError("GitHub release tag has too many nested annotated tags")


def _validate_build_run(repo_api: str, run_id: str, repository: str, token: str) -> None:
    if not run_id.isdigit() or int(run_id) < 1:
        raise BackfillError("Build workflow run ID must be a positive integer")
    run = _api_json(f"{repo_api}/actions/runs/{run_id}", token)
    repo_info = _api_json(repo_api, token)
    default_branch = repo_info.get("default_branch")
    head_repository = run.get("head_repository") or {}
    workflow_ref = run.get("workflow_ref", "")
    if (
        run.get("path") != BUILD_WORKFLOW_PATH
        or run.get("status") != "completed"
        or run.get("conclusion") != "success"
        or run.get("event") != "workflow_dispatch"
        or head_repository.get("full_name") != repository
        or run.get("head_branch") != default_branch
        or not isinstance(workflow_ref, str)
        or not workflow_ref.endswith(f"@refs/heads/{default_branch}")
    ):
        raise BackfillError("Selected artifact must come from a successful build-only workflow run on the default branch")


def publish_assets(
    tag: str,
    assets_dir: Path,
    manifest_path: Path,
    expected_manifest_sha: str,
    build_run_id: str,
    confirmation: str,
) -> list[str]:
    validate_tag(tag)
    if os.environ.get("RELEASES_ENABLED") != "true":
        raise BackfillError("RELEASES_ENABLED is not exactly 'true'; public uploads are fail-closed")
    verify_publish_confirmation(tag, expected_manifest_sha, confirmation)
    manifest = verify_manifest(tag, assets_dir, manifest_path, expected_manifest_sha)
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise BackfillError("GITHUB_TOKEN is unavailable; no release asset was uploaded")
    repo_api = _repo_api_base()
    _validate_build_run(repo_api, build_run_id, repository, token)

    remote_tag_sha = _tag_commit_from_api(repo_api, tag, token)
    if remote_tag_sha != manifest["source_sha"]:
        raise BackfillError("The remote tag no longer points to the pinned source commit; no asset was uploaded")
    release_url = f"{repo_api}/releases/tags/{urllib.parse.quote(tag, safe='')}"
    release = _api_json(release_url, token)
    existing = validate_release_record(tag, release)
    entries = {item["name"]: item for item in manifest["assets"]}

    # Preflight every name before any upload. Matching assets from an interrupted previous run are
    # accepted only after remote SHA-256 verification; differing same-name assets are never replaced.
    matching_existing: set[str] = set()
    for name, asset in existing.items():
        if name not in entries:
            continue
        try:
            remote_digest = _remote_asset_sha256(asset)
        except BackfillError as exc:
            raise BackfillError(f"Could not verify existing asset {name}; refusing to replace it") from exc
        if remote_digest != entries[name]["sha256"]:
            raise BackfillError(f"A conflicting asset named {name} already exists; refusing to overwrite it")
        matching_existing.add(name)

    original_ids = {name: asset.get("id") for name, asset in existing.items()}
    original_body = release.get("body")
    upload_url = str(release.get("upload_url", "")).split("{", 1)[0]
    if not upload_url.startswith("https://uploads.github.com/"):
        raise BackfillError("Existing release upload endpoint is not a trusted GitHub upload URL")
    uploaded: list[str] = []
    for item in manifest["assets"]:
        name = item["name"]
        if name in matching_existing:
            continue
        path = assets_dir / name
        content_type = mimetypes.guess_type(name)[0] or "application/octet-stream"
        url = upload_url + "?name=" + urllib.parse.quote(name, safe="")
        _api_request(url, token, data=path.read_bytes(), content_type=content_type)
        uploaded.append(name)

    final_release = _api_json(release_url, token)
    final_assets = validate_release_record(tag, final_release)
    for name, original_id in original_ids.items():
        if name not in final_assets or final_assets[name].get("id") != original_id:
            raise BackfillError("An existing release asset changed unexpectedly; no cleanup or deletion was attempted")
    if final_release.get("body") != original_body:
        raise BackfillError("Existing release notes changed unexpectedly; no cleanup or deletion was attempted")
    for item in manifest["assets"]:
        name = item["name"]
        asset = final_assets.get(name)
        if not asset:
            raise BackfillError(f"Release is missing expected asset {name} after upload")
        remote_digest = _remote_asset_sha256(asset)
        if remote_digest != item["sha256"]:
            raise BackfillError(f"Uploaded release asset digest did not match for {name}")
    return uploaded


def _write_github_output(path: Path | None, values: dict[str, object]) -> None:
    if path is None:
        return
    with path.open("a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    source_parser = subparsers.add_parser("validate-source")
    source_parser.add_argument("--tag", required=True)
    source_parser.add_argument("--source", type=Path, required=True)
    source_parser.add_argument("--source-sha", default="")
    source_parser.add_argument("--github-output", type=Path)

    release_parser = subparsers.add_parser("validate-release")
    release_parser.add_argument("--tag", required=True)

    manifest_parser = subparsers.add_parser("create-manifest")
    manifest_parser.add_argument("--tag", required=True)
    manifest_parser.add_argument("--source-sha", required=True)
    manifest_parser.add_argument("--assets-dir", type=Path, required=True)
    manifest_parser.add_argument("--output", type=Path, required=True)
    manifest_parser.add_argument("--github-output", type=Path)

    publish_parser = subparsers.add_parser("publish")
    publish_parser.add_argument("--tag", required=True)
    publish_parser.add_argument("--assets-dir", type=Path, required=True)
    publish_parser.add_argument("--manifest", type=Path, required=True)
    publish_parser.add_argument("--manifest-sha256", required=True)
    publish_parser.add_argument("--build-run-id", required=True)

    args = parser.parse_args(argv)
    if args.command == "validate-source":
        info = validate_source(args.tag, args.source, args.source_sha or None)
        _write_github_output(args.github_output, info)
        print(json.dumps(info, sort_keys=True))
        return 0
    if args.command == "validate-release":
        validate_tag(args.tag)
        token = os.environ.get("GITHUB_TOKEN", "")
        if not token:
            raise BackfillError("GITHUB_TOKEN is unavailable; cannot verify the existing release")
        release = _api_json(
            f"{_repo_api_base()}/releases/tags/{urllib.parse.quote(args.tag, safe='')}",
            token,
        )
        assets = validate_release_record(args.tag, release)
        print(f"Validated existing public release {args.tag}; preserving {len(assets)} current assets and its release notes.")
        return 0
    if args.command == "create-manifest":
        manifest, digest = create_manifest(args.tag, args.source_sha, args.assets_dir, args.output)
        _write_github_output(args.github_output, {"manifest_sha256": digest})
        print(f"Created manifest for {args.tag} with {len(manifest['assets'])} downloadable files; SHA-256={digest}")
        return 0
    if args.command == "publish":
        confirmation = os.environ.get("HISTORICAL_PUBLISH_CONFIRMATION", "")
        uploaded = publish_assets(
            args.tag,
            args.assets_dir,
            args.manifest,
            args.manifest_sha256,
            args.build_run_id,
            confirmation,
        )
        if uploaded:
            print("Added assets without replacing existing files:")
            for name in uploaded:
                print(f"- {name}")
        else:
            print("All exact manifest assets were already present; no upload was needed.")
        return 0
    raise BackfillError("Unsupported command")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, BackfillError) as exc:
        print(f"Historical release backfill failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
