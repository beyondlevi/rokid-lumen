## What this changes

<!-- What and why, in a few lines. Link the issue it closes, if any. -->

## Tested

- [ ] `python3 scripts/check-english.py`
- [ ] `(cd rust && cargo test --workspace)` (if `rust/` changed)
- [ ] `./gradlew test lintDebug assembleDebug`
- [ ] New or changed behaviour has unit tests

On the devices (say what was tested on the glasses and the phone, and what wasn't):

-

## Checklist

- [ ] User-visible text is in string resources, English in `values/` (translations if you have them)
- [ ] Messages between the glasses and the phone changed only in `protocol/`
- [ ] Docs updated (README, `docs/`, CHANGELOG) where behaviour changed
- [ ] No personal data, keys or device identifiers in code, logs, tests or screenshots
