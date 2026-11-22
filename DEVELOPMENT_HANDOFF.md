# Zotify Music Finalization Handoff

Last updated: 2026-11-22 10:33 IST

## Repository and pull-request state

Repository: [OPZENITSUUU/zotify-music](https://github.com/OPZENITSUUU/zotify-music). Local branch: `fix/finalization-audit-2026-11-18`; intended PR #1 head branch: `fix/ci-and-docs-2026-11-15`.

PR #1 remains open; no PR was merged, no tag was created, and no release was published. The local merge of `origin/main` (`5f7031f006265aa7be2a49ea78585435d5f21e36`) was conflict-resolved as `dc33a7f`, then pushed only to PR #1's existing head branch `fix/ci-and-docs-2026-11-15`. A handoff-only follow-up, `17e13ae`, recorded the prior status; this final documentation-only correction updates that handoff. There are no unresolved paths. The PR's phone metadata v1.0.12/code 43 was retained. Main's appearance, localization, and Discord-bot changes were kept. This was not a merge of PR #1 into `main`.

## Compatibility and signing evidence

The finalization preserves phone and TV production application IDs `com.zotifymusic.player` and `com.zotifymusic.player.tv`, the distinct Android resource namespace, existing user-data paths, and the published production signing identity. Desktop package identity, stable Windows upgrade UUID, and per-user installation settings remain unchanged.

Earlier private audit evidence recorded that the supplied signing backup was usable and its certificate matched the expected production identity and the published v1.0.3 phone APK. No password, private key, keystore contents, or Base64 signing material has been printed, committed, or uploaded. Repository secret/variable metadata reads returned HTTP 403; no secret values were queried. GitHub Actions production signing secrets remain unverified/configured through an authorized mechanism, and `RELEASES_ENABLED` remains fail-closed. No public release mutation has been attempted.

Historical phone APKs are not rebuilt or replaced. In particular, the tag-source phone metadata mismatches at v1.0.5 and v1.0.7 remain documented and are handled as warnings by the validator; existing phone assets stay untouched.

## Historical Desktop and Android TV assets

Added a tag-derived Desktop helper, exact pinned-source verifier, checksummed asset inventory, and two separate workflows:

1. `build-historical-release-assets.yml` builds only the TV and Desktop packages from the exact historical tag source and uploads a reviewable artifact plus a SHA-256 manifest. It does not publish a release.
2. `publish-historical-release-assets.yml` requires the exact reviewed manifest hash, build-run ID, `RELEASES_ENABLED=true`, and literal `PUBLISH <tag> <manifest_sha256>` confirmation. It verifies the successful build workflow run, immutable tag SHA, source-pinned manifest, release assets, and remote SHA-256 values; preserves existing assets and release notes; and refuses conflicting same-name files rather than overwriting them.

Local Git tag verification passed for all seven releases: v1.0.1–v1.0.7 each resolve to their pinned commit. All seven tag sources report TV v1.8/code 26. Desktop installer version metadata derives from the tag, with installer codes 1–7. The v1.0.6 and v1.0.7 tags intentionally resolve to the same source commit. No TV or Desktop backfill package has yet been attached to a public release. Windows/macOS packages are not platform-code-signed and may prompt operating-system warnings.

The regular new-release workflow now writes the existing Last.fm values to a permission-restricted `local.properties` file before Android tests/builds. Android release signing continues to require the existing certificate fingerprint and stop on missing/mismatched inputs.

## Verification evidence

For PR head `17e13ae` and the locally generated `FINALIZATION.patch`:

- Release metadata, Desktop asset-helper, and historical backfill regression suite: **38 tests passed** (`python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v`). This includes manifest tampering/schema tests, exact confirmation, disabled release gate, streamed remote digest fallback, and refusal to upload over a conflicting asset.
- Workflow syntax: all **11** `.github/workflows/*.yml` files parsed using PyYAML; `bash -n` passed for **46** Bash workflow run blocks. `actionlint` and `shellcheck` are unavailable in this sandbox, and PowerShell blocks were not independently parsed here.
- Locale resources: **19** XML files parse; no file has duplicate resource names; the union of locale keys covers all **1,195** English resource names. Individual locales may intentionally use Android's normal English fallback.
- Historical tag/source metadata: **7/7** tag SHAs, TV metadata, and Desktop version/code derivations matched the verifier's pinned expectations.
- Real Linux installer verification for v1.0.5 succeeded: the DEB and RPM were built, and both package managers reported version `1.0.5`. CMake was unavailable, so the optional Desktop native analyzer was skipped.
- A local Android `:app:testDevDebugUnitTest` attempt stopped during Gradle configuration before tests ran: the sandbox's partial `/usr/lib/android-sdk` does not have the NDK license accepted (`ndk;27.0.12077973`). This is an environment prerequisite failure, not a passing test. SDK-dependent Android/TV builds and production-signature verification still require GitHub Actions.
- Previously, 7/7 required PR checks passed for commit `7bea77e099c0a5905d7905f9029a9b434f832836`; that result is historical and does **not** establish CI success for the current merge/workflow changes.
- GitHub API checks at 2026-11-22 10:32 IST show an empty status-check rollup and zero check runs/check suites for PR head `17e13ae`; no run was found for `dc33a7f` either. The Check workflow is active on the default branch and has an unrestricted `pull_request` trigger; PR #1 is open, non-draft, and based on `main`. Thus there is no CI result for the current changes—not a pass and not a failure. The reason GitHub did not create the expected run remains unresolved.

## Remaining blockers and exact next actions

1. Resolve why GitHub did not create Actions runs for PR head `17e13ae`, then run and inspect every required check on the exact final head. Fix failures and wait for all required checks to pass. Do not merge PR #1.
2. The `FINALIZATION.patch` was regenerated against `origin/main`; `git apply --check` passed on a clean archive of that base. Final whitespace/conflict checks passed, and no credential or local-properties files are staged.
3. Through an authorized, secure mechanism, confirm the existing production signing secrets are available to the TV build job without exposing their values. Do not replace the keystore or weaken the certificate fingerprint check. If secret configuration remains unavailable, TV production APK builds stay blocked.
4. After the required PR checks and production signing verification succeed, the build-only workflow can be run once present on the repository's default branch. Review every exact filename and digest in each generated manifest. This workflow has not yet run; do not merge solely to bypass a pending check.
5. Before any public backfill, obtain explicit authorization for the exact manifest and tag. Keep `RELEASES_ENABLED` disabled until that authorized publication action. The separate publisher must preserve all current release notes/assets and abort on any collision.
6. Do not merge PR #1, create tags, replace phone APKs, or publish assets without the required checks and explicit authorization.
