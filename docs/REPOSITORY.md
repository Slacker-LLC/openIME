# Repository management

This document lists the governance rules of the GitHub repository.
[`scripts/apply_repo_settings.sh`](../scripts/apply_repo_settings.sh) applies them.
To change a rule, change that script and this document.
Then an administrator runs the script again.
Do not change a rule only on the web page.

## Branch model

- Development happens on trunk. `main` is always releasable.
- Create a short-lived branch from the latest `main`. Name it `type/topic`.
  The types are `feat`, `fix`, `docs`, `chore`, `ci`, `refactor` and `test`. Example: `fix/pinyin-candidate`.
- Create a `release/X.Y` branch from an old tag only to maintain an old version line.
  Normal hotfixes go to `main` and ship as a PATCH version.
- GitHub deletes a branch after its merge.
  Maintainers clean up branches that have no commit and no PR for 30 days. They do this once a month.

## Merges

- Only **squash merge** is allowed. Merge commits and rebase merges are not allowed. The history stays linear.
- The squash commit uses the **PR title and the PR description**. It does not list each branch commit.
  A PR title must state the result by itself.
  Use a Conventional Commits prefix (`feat:`, `fix:`, `docs:`, `ci:`).
  The description gives the purpose, the effect, the verification and the known limits.

## Protection of `main`

- Every change goes through a PR. The check **Build and verify** must pass.
  It runs unit tests, lint, build, and version and change log checks.
- Force pushes and deletion are not allowed. History must stay linear. All review conversations must be resolved.
- Administrators are not forced to follow the rules (`enforce_admins=false`).
  The owner can then fix `main` directly in an emergency. This is not a normal workflow.
- No approval is required (single maintainer).
  When a second maintainer joins, set `required_approving_review_count` to 1 and add the person to `.github/CODEOWNERS`.
- The API compatibility tests (26, 29, 31, 34) run on every PR and fail when a test fails.
  They do not block the merge, so that an emulator problem cannot stop a release.
  To enforce them, add `Compatibility API <level>` to `contexts` in `apply_repo_settings.sh`.
  The release workflow requires Build and verify, Compatibility API 29 and Compatibility API 31.
- If you rename a job in `android.yml`, change `contexts` too.
  Otherwise a PR waits for a check that does not exist.

## Tags

The ruleset `release-tags` covers `v*`.
Only a repository administrator can create such a tag.
After creation, nobody except an administrator can move or delete it.
A release tag is `vX.Y.Z` or `vX.Y.Z-beta.N` and must equal `VERSION`. See [RELEASE.md](RELEASE.md).

## Security settings

- Secret scanning and push protection are on. Dependabot alerts and security updates are on.
- Private vulnerability reporting is on (Security → Report a vulnerability). See [SECURITY.md](../SECURITY.md).
- The default Actions token is read-only (`default_workflow_permissions=read`). Actions cannot approve PRs.
  A job that needs write access declares it in the workflow.
  Only the build job in `release.yml` reads the signing secrets, and it has no write access.
  The publish job has write access and cannot read the secrets.
- [RELEASE.md](RELEASE.md) describes how to create and back up the signing secrets.
  The keystore never enters the repository, and `.gitignore` blocks `*.jks`, `*.keystore` and `*.p12`.

## Dependencies

`.github/dependabot.yml` checks GitHub Actions and Gradle dependencies every Monday.
It groups minor and patch updates into one PR.
Major Gradle versions (AGP, Kotlin and others) are tied to the pinned NDK and SDK. People upgrade them by hand.

These pinned assets never update automatically:
the Rime Ice dictionaries (see `THIRD_PARTY_NOTICES.md`), the librime dependencies (`scripts/fetch_rime_deps.sh`), and the sherpa-onnx AAR and voice models (Git LFS).

## Large files and hygiene

- Large binaries (`*.onnx`, `*.aar`) use Git LFS.
  Do not commit APKs (`.gitignore` blocks them), screenshots outside `docs/images/`, UI dumps or device logs.
- `output/` holds local output of the design reference scripts. Git ignores it.
- The debug APK is about 380 MB. This is more than the 100 MB GitHub file limit.
  Download test APKs from the CI artifact `openIME-test-apks` of a PR. It is kept for 1 day.

## Apply the settings again

```bash
bash scripts/apply_repo_settings.sh --dry-run   # print the requests only
bash scripts/apply_repo_settings.sh             # apply; needs repository admin rights and a logged-in gh
```

The wiki is off, because all documents are in `docs/`.
Only the web page can upload the social preview image. See RELEASE.md.
