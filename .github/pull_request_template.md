## Summary

<!-- Explain why you make this change and what it changes. Use a few sentences. -->

## Affected areas

- [ ] Input engine, candidates, word splitting
- [ ] InputConnection, editor behavior
- [ ] Keyboard layout, insets, floating keyboard
- [ ] Tool panels, settings
- [ ] Voice model, audio permission
- [ ] Build, tests or documents

## Verification

- [ ] `:app:testDebugUnitTest`
- [ ] `:app:lintDebug`
- [ ] `:app:assembleDebug`
- [ ] Real input method regression (if it applies)
- [ ] `git diff --check`

Test device, Android version and commands:

## Change log and version

- [ ] I added each user-visible change to `[Unreleased]` in `CHANGELOG.md`. Internal changes do not need an entry.
- [ ] I did not change `VERSION`. Only a release PR changes it. See `docs/RELEASE.md`.
- [ ] The PR title states the result. It becomes the commit title, and the description becomes the commit body.

## Privacy and delivery

- [ ] The change has no passwords, clipboard content, recordings, device logs or personal paths.
- [ ] The change keeps the source and license of each model, dictionary and third-party file.
- [ ] If the change affects user data or settings formats, the description explains upgrade compatibility.
