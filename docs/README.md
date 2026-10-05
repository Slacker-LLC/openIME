# Documentation

The [README](../README.md) in the repository root covers installation and a quick start.
This folder holds the documents for maintainers and contributors.

## Design

- [ARCHITECTURE.md](ARCHITECTURE.md): runtime, state ownership, packages and product capabilities.
- [DECISIONS.md](DECISIONS.md): design decisions and their reasons.
- [NINE_KEY_REFERENCE.md](NINE_KEY_REFERENCE.md): nine-key behavior, reference input methods and the delete gesture.
- [LOCAL_VOICE_MODEL.md](LOCAL_VOICE_MODEL.md): on-device voice input, models and runtime.
- [COORDINATE_SYSTEM.md](COORDINATE_SYSTEM.md): normalized coordinates and window-adaptive layout.
- [APP_UI_SPEC.md](APP_UI_SPEC.md): app screen sizes, spacing, colors, haptics and acceptance rules.
- [COMPATIBILITY.md](COMPATIBILITY.md): editors, physical keyboards, displays, and crash and freeze handling.

## Testing

- [TEST_ARCHITECTURE.md](TEST_ARCHITECTURE.md): test layers, the debug harness and the CI gate.
- [TEST_SOP.md](TEST_SOP.md): the L0 to L3 test procedure.
- [TEST_SOP_CHECKLIST.md](TEST_SOP_CHECKLIST.md): manual acceptance checklist for several devices.
- [../scripts/README.md](../scripts/README.md): test, release and repository scripts.

## Release and repository

- [RELEASE.md](RELEASE.md): version rules, change log, signing, tag release, rehearsal and rollback.
- [REPOSITORY.md](REPOSITORY.md): branches, merges, protection of `main` and tags, security and dependencies.
- [LICENSING.md](LICENSING.md): licenses of the main project and third-party components.
- [release-cert.sha256](release-cert.sha256): SHA-256 of the release signing certificate.

Other files: [CONTRIBUTING.md](../CONTRIBUTING.md), [SECURITY.md](../SECURITY.md), [CHANGELOG.md](../CHANGELOG.md) and [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

Local test evidence goes to ignored folders such as `.local/test-runs/`, `docs/visual/` and `docs/perf/`.
Commit only evidence that is selected, free of personal data and useful for a long time.
