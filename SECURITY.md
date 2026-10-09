# Security policy

Do not report a security problem in a public issue.
This applies to any problem that can expose typed text, clipboard content, passwords, recordings or model files.

## Report a problem

Use **Security → Report a vulnerability** in this repository.
This sends the report to the maintainers in private.

If this option is not available, contact a maintainer.
Send only the minimum information that is necessary to reproduce the problem.
Do not send real user data.

Include these items in your report:

- the affected version or commit, the APK type and the Android version
- the steps to reproduce the problem and its effect
- logs or screenshots with personal data removed
- a workaround, if you know one

Tell us if the problem occurs in one of these cases:
a password field, a denied voice permission, a switch between editors, the clipboard, or model loading.

The maintainers first confirm the problem.
They then decide the fix, the release notes and the date of disclosure.
Do not publish details before the fix is released.

## Supported versions

| Version | Status |
|---|---|
| The latest release (beta or stable) | Receives security fixes. We publish them as a new patch or beta version and list them in the change log. |
| Older releases and debug builds | Not maintained. Update to the latest release. |

To verify an APK, compare its SHA-256 checksum with the release notes.
Compare its signing certificate with [docs/release-cert.sha256](docs/release-cert.sha256).
See the [release process](docs/RELEASE.md).
