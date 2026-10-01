# Guidelines for Rokid Lumen

Rules for anyone (people or coding agents) changing this repository. The README explains what
the project is and how it works; this file says how to change it.

## Every app is multilingual, English first

The glasses app (`app/`), the phone companion (`phone/`) and every web app or HUD text we ship
are built for several languages from the start. **English is the default and the priority.**

- **No user-visible text in code.** Labels, messages, notifications, errors shown to the user,
  accessibility labels and content descriptions all come from string resources
  (`res/values/strings.xml`), read with `getString(R.string.…)` or `stringResource(…)`. Log lines
  and developer-only output (adb, debug hooks) stay in code, in English.
- **`values/` is English.** The default resources hold the English text, so any language
  without a translation falls back to English. Other languages live in qualified folders:
  Portuguese in `values-pt/` (written in Brazilian Portuguese; the plain language folder also
  serves devices set to pt-PT, as the glasses are), then others as they come. A regional
  folder (`values-pt-rPT/`) only when that variant really differs.
- **English is written first and kept complete.** A new string is added in English; its
  translations follow in the same PR when we have them. A missing translation is acceptable;
  a missing English string is not.
- **Plurals and formatting through resources**: `<plurals>` for counts, placeholders
  (`%1$s`, `%1$d`) instead of concatenation, since word order changes between languages.
- **Don't parse the UI's language.** Code that has to recognize text on screen (the self-arm
  reading Settings) reads the other app's own labels in the device's language
  (`SettingsLabels`), with fixed lists only as a fallback.
- **Web apps and HUD scripts** follow the same rule: English text by default, translations
  selected from the device language (`navigator.language`).
- **Docs and code are in English**: README, comments, commit messages, identifiers.

## Changes

- Work on a branch (`feature/…`, `fix/…`, `docs/…`) and open a pull request against `master`.
  A stacked PR's base must be changed to `master` before it is merged, or it lands on the
  branch below it instead.
- The messages between the glasses and the companion are defined once, in `protocol/`
  (`dev.lumen.protocol`). Change them there, never by hand-building JSON in one app.
- Run `./gradlew test lintDebug assembleDebug` before asking for review, and say in the PR
  what was tested on the devices and what wasn't.

## Devices

- Never `am force-stop` or `am start -S` the glasses app (`dev.lumen.glasses`): the firmware
  drops its accessibility service and the band. `adb install -r` is safe.
- The Kotlin/Java packages stay `dev.airgestures` even though the apps are `dev.lumen.*`: the
  Rust bridge's JNI symbols carry the Java package.
