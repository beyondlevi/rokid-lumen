# Contributing to Rokid Lumen

Thank you for helping. This page says how to build the project and what a change needs before
review. By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Build

You need:

- the Android SDK with platform 37.2 (GeckoView 156 is built against it) and the NDK;
- JDK 17 or later (CI uses 21);
- Rust (1.88 or later) with the `aarch64-linux-android` target and `cargo-ndk`
  (`rustup target add aarch64-linux-android`, `cargo install cargo-ndk`).

```sh
./build-rust.sh                  # the Rust bridge into band/src/main/jniLibs (both apps need it)
./gradlew assembleDebug          # app/ (glasses) and phone/ (companion)
```

`band/src/main/jniLibs` is not in the repository: run `./build-rust.sh` once before the first
Gradle build, and again after changing `rust/`. The script uses the newest NDK under
`$ANDROID_HOME/ndk` unless `ANDROID_NDK_HOME` is set.

## Before you open a pull request

Run what CI runs:

```sh
python3 scripts/check-english.py
(cd rust && cargo test --workspace)
./gradlew test lintDebug assembleDebug
```

and add tests for what you change: unit tests live in `app/src/test`, `phone/src/test`,
`protocol/src/test` and `rust/*/tests`.

In the pull request, say what you tested on real glasses and phone, and what you didn't.

## Conventions

- **English first.** Code, comments, identifiers, log lines, commit messages and docs are in
  English. `scripts/check-english.py` fails on Portuguese outside `values-pt*/`; a legitimate
  exception (a firmware label the code must match, a test fixture) goes in
  `scripts/english-allowlist.txt`.
- **Every app is multilingual.** No user-visible text in code: labels, messages, errors and
  content descriptions come from string resources (`res/values/strings.xml`), read with
  `getString(R.string.…)` or `stringResource(…)`. `values/` is English and always complete;
  translations live in qualified folders (`values-pt/` is Brazilian Portuguese and also serves
  pt-PT). Use `<plurals>` for counts and placeholders (`%1$s`) instead of concatenation.
- **Web apps and HUD scripts** follow the same rule: English by default, translations chosen
  from `navigator.language`.
- **Don't parse the UI's language.** Code that recognises another app's text (the self-arm
  reading Settings) reads that app's own labels in the device's language (`SettingsLabels`),
  with fixed lists only as a fallback.
- **The link protocol lives in `protocol/`** (`dev.lumen.protocol`). Change messages there,
  never by building JSON by hand in one app.
- **Branches:** `feature/…`, `fix/…`, `docs/…`, with a pull request against the default branch.

## Working with the devices

- Never `am force-stop` or `am start -S` the glasses app (`dev.lumen.glasses`): the firmware
  then drops its accessibility service and the band. `adb install -r` is safe.
- Debug builds can run without a band: **Settings > Band > Simulated band**, then
  `adb shell am broadcast -a dev.lumen.glasses.SIMULATE --es gesture swipe_right`.
- Logs:

  ```sh
  adb logcat -v time -s BandService:D BandRuntime:D BandSelfArm:D BandLocalSelfArm:D *:S
  adb logcat -v time -s BandWebApp:D BandGecko:D BandWebView:D BandLocalServer:D BandInternet:D *:S
  adb logcat -v time -s NbCompanion:D NbNotify:D NbNetwork:D NbProxy:D NbDictation:D *:S
  ```

## Licensing

Contributions are under the MIT License (see [LICENSE](LICENSE)). Code taken from another
project must keep its notice in [NOTICE](NOTICE).
