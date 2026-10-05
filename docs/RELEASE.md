# Release process

The file `VERSION` in the repository root is the single source of the version.
A release is a repeatable pipeline:
release PR → merge to `main` → tag the merge commit → the workflow builds, checks and publishes.

Branch protection, tag protection and merge rules are in [REPOSITORY.md](REPOSITORY.md).

## Version numbers

- The project uses semantic versions.
  A stable version is `MAJOR.MINOR.PATCH`. A beta version is `MAJOR.MINOR.PATCH-beta.N`, and `N` starts at 1.
  No other suffix (`-rc`, `-alpha`) is allowed.
  The project is in the `0.0.x` beta phase.
  We release `1.0.0` only when features and stability are good enough for daily use.
- `VERSION` is one line of text, for example `0.0.1-beta.1`.
  `app/build.gradle.kts` reads it:
  `versionName = VERSION` and `versionCode = (MAJOR × 10000 + MINOR × 100 + PATCH) × 100 + stage`.
  The stage is `N` for a beta and `99` for a stable version.
  Examples: `0.0.1-beta.1` → `101`, `0.0.1` → `199`, `1.0.0` → `1000099`.
  Limits: `MINOR` and `PATCH` up to 99, `N` up to 98, `MAJOR` up to 2000. `0.0.0` is not allowed.
  With this rule, `versionCode` cannot be forgotten, and a stable version is always higher than its betas.
- `scripts/release_check.py` checks the repository with the same formula.
  It also uses `aapt2` to check the package name, `versionName` and `versionCode` in the built APK.
  The PR CI and the release workflow both run it.
- A beta release is a GitHub **pre-release**. It is not marked as latest. The release notes start with a beta notice.
  The tag is `vX.Y.Z-beta.N`. The pipeline detects the beta from the tag.
- Android does not install an APK with a lower `versionCode` over a higher one.
  After a version is public, each later version must be higher.

When to increase each part:

| Part | Condition |
|---|---|
| MAJOR | User data format is incompatible or needs manual migration. `minSdk` increases. The package name or signing key changes. |
| MINOR | New feature, new panel or keyboard. A new version of the dictionary, the voice model or a third-party runtime (check the license again). |
| PATCH | Bug fix, performance, text, security update of a dependency. |
| `-beta.N` | The N-th test snapshot of the same `X.Y.Z`. Increase `N` after each fix. Remove the suffix for the stable release. |

The Rime dictionaries are precompiled at build time by `prebuildRimeData` (`scripts/build_rime_prebuilt.py`).
The APK contains only the compiled binary dictionaries and a content hash (`rime-data.revision`).
After an upgrade, the app copies the dictionaries from the APK and deletes the old compiled files only if the hash changed.
The user dictionary is in a separate directory and is not affected.
If the dictionaries did not change, the upgrade reuses the files on the phone.
The phone never compiles dictionaries.

We maintain only the newest MINOR line. Security fixes ship as PATCH versions.

## Change log

[CHANGELOG.md](../CHANGELOG.md) follows Keep a Changelog.

- A normal PR adds user-visible changes to `## [Unreleased]`. It does **not** change `VERSION`.
- The newest section `## [version] - YYYY-MM-DD` must equal `VERSION`.
  Empty sections and versions or dates in the wrong order are not allowed.
  `release_check.py check` enforces this in CI, so the version and the change log change together.
- The body of this section is the GitHub release note. Write it so that users can understand it.
- For a withdrawn version, add ` [YANKED]` after the heading and publish a higher version (a beta increases `N`).

## Release assets

The pipeline builds only an `arm64-v8a` release APK:

```text
openIME-v{VERSION}-arm64-release.apk
```

The GitHub release has only this APK.
The release notes show its SHA-256 and the fingerprint of the signing certificate.
The third-party licenses are in the APK (`assets/licenses/`).
The release notes link to `THIRD_PARTY_NOTICES.md`.
The pipeline also creates `SHA256SUMS.txt` for its own check but does not upload it.

Debug builds keep `arm64-v8a` and `x86_64`.
They are for regression on devices and emulators and are never released.

## Signing key

The key is the permanent identity of the app.
Android installs an update over an installed app only if the same key signed both.
If you lose the key, all users must uninstall and install again.

Never put the keystore in the repository, an issue, a PR, an Actions artifact or a release.
Never publish with the debug keystore.

Run the one-time setup on a machine that you trust. Do not let another person run it for you:

```bash
bash scripts/setup_release_signing.sh
```

The script does these steps:

1. It creates a 4096-bit RSA keystore (default location `~/.openime-release/`) with random passwords.
2. It writes the four Actions secrets that `release.yml` reads:
   - `OPENIME_KEYSTORE_B64`: the keystore in Base64
   - `OPENIME_KEYSTORE_PASSWORD`, `OPENIME_KEY_ALIAS`, `OPENIME_KEY_PASSWORD`

The script does not print the passwords.
After it runs, **back up** `~/.openime-release/` at once.
Keep an encrypted offline copy and a copy in a password manager.
`credentials.txt` contains the passwords in plain text.
Move it into the password manager and do not leave it on a normal cloud drive.

After the first release, write the certificate SHA-256 to [`release-cert.sha256`](release-cert.sha256).
The release notes show it.
Each later release compares against this file and fails if the certificate does not match.
This stops an unnoticed key change.

You can also build a release locally with your own keystore.
Set the same environment variables (`OPENIME_KEYSTORE_PATH` is the file path, not Base64) and run `scripts/release_build.sh`.

## Release steps

1. Make sure that the latest CI run on `main` passes, including the API 29 and API 31 compatibility tests.
2. Open a release PR.
   Move `[Unreleased]` to `## [version] - YYYY-MM-DD` and change `VERSION`.
   Run this command locally first:

   ```bash
   python3 scripts/release_check.py check
   ```

   This PR changes release files, so CI runs the whole release pipeline as a rehearsal with a one-time key (see below).
3. After the merge, create an annotated tag on the merge commit on `main` and push it:

   ```bash
   git switch main && git pull
   git tag -a vX.Y.Z -m "openIME X.Y.Z"   # beta: vX.Y.Z-beta.N
   git push origin vX.Y.Z
   ```

   Only an administrator can create tags. Nobody can move or delete a tag after creation (see REPOSITORY.md).
4. `.github/workflows/release.yml` runs these steps:
   - It checks that the tag is on `main`.
   - It does not wait for CI. It builds with the unsigned configuration and runs `lintRelease` at the same time as the `main` CI. It reads no secrets.
   - It waits for the CI checks of the commit: Build and verify, Compatibility API 29 and Compatibility API 31.
     CI already ran the unit tests, so the workflow does not repeat them.
   - It checks that the tag format, `VERSION` and `CHANGELOG.md` agree.
     Then it runs `assembleRelease` with the real key. Only packaging and signing remain.
   - It verifies the APK: the signature is not the debug certificate, only `arm64-v8a` is present,
     the version inside the APK equals `VERSION`, and the certificate equals `release-cert.sha256`.
   - It creates the SHA-256 and the release notes (a beta gets a beta notice).
   - A second job has write permission and no access to the key. It creates a **draft** release, checks that the APK is attached, and only then publishes it.
     A beta is a pre-release and not the latest release.
5. After the release, download the APK and compare `sha256sum` with the release notes.
   Run `apksigner verify --print-certs`.
   Install the APK on a real phone, enable it and type.

### Rehearsal

The `Android Release` workflow runs the same build and checks, with a one-time key that exists only for that run.
It publishes nothing.
It runs in two cases:

- A manual start (Actions → Android Release → Run workflow).
- A PR changes `release.yml`, `release_build.sh`, `release_check.py`, `VERSION`, `CHANGELOG.md` or `app/build.gradle.kts`.

So the pipeline runs before the real release.
You can also rehearse locally. You need the four environment variables from above:

```bash
OPENIME_REHEARSAL=1 OPENIME_SKIP_TESTS=1 scripts/release_build.sh
```

### Failure and rollback

- **The workflow fails before publishing.**
  Fix the problem through a PR.
  An administrator deletes the remote tag (`git push origin :refs/tags/vX.Y.Z`) and tags again on the new commit.
  If a draft release remains, delete it first.
- **A published version has a problem.**
  Android does not allow a downgrade, so do not delete or rewrite the tag.
  Mark the version `[YANKED]` in the change log.
  Change the release to a pre-release and write the reason.
  Then publish a higher PATCH version.
- **The key leaks.**
  Stop releasing at once and delete the secrets in Settings.
  A new key forces all users to uninstall and install again.
  Before that, write the migration steps in the release notes: export user data, uninstall, install, import.

## Checks before a release

- `AndroidManifest.xml` has no `INTERNET` permission.
- `android:allowBackup="false"` is unchanged.
- `THIRD_PARTY_NOTICES.md` and `app/src/main/assets/licenses/` agree.
- If the voice model, the dictionary or a third-party runtime changed, check its license again. See [LICENSING.md](LICENSING.md).
- The main license is `LICENSE` (GPL-3.0-only).

## Social preview

The repository has `docs/images/social-preview.png` (1280 × 640). `scripts/generate_brand_assets.py` creates it.
The GitHub social preview is a setting, not a source file.
A repository administrator must upload the PNG in **Settings → General → Social preview**.
A commit cannot do this.
