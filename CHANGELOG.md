# Changelog

<!-- The release workflow takes a version's notes from its "## [x.y.z]" heading up to the next
     "## " heading, so keep the brackets. -->

## [0.2.0-beta.7]

### Companion

- **Share logs** (Settings): both apps' logs in `rokid-lumen-logs-<date>-<time>.zip`, through
  Android's share sheet, no adb needed. The glasses send theirs over Rokid's link.

## [0.2.0-beta.6]

### Glasses

- **Quick replies under a notification**: opening one shows a row of the UI Toolkit's
  QuickReplyButtons. First the web app that declares that phone app (`lumen_notifications` in
  its manifest), which opens at the notification's page (WhatsApp: the chat); then reactions
  (👍 ❤️ 😂 🙏) and short replies (OK, On my way, Talk later) when the phone can answer it.

### Companion

- Replies from the glasses go through the notification's own reply action, as typing in the
  shade would (reactions as their emoji).
- The test notification (Notifications tab) has a reply action, so quick replies can be tried
  without messaging anyone: the answer shows in it.

### Web apps

- `lumen_notifications` in an offline app's manifest: the phone notifications it opens, and the
  page to open (`/chat/{shortcut}`).
- An offline app's routes may contain dots (`/chat/…@g.us`): only a missing file of a known type
  is a 404 now.

## [0.2.0-beta.5]

### Companion

- **Updates from GitHub**, for the companion and the glasses app: Settings > Updates shows both
  versions and what's new, and **Update both** installs the glasses first (over the phone's
  Wi-Fi), then the companion. It checks on open and every four hours; betas only when asked.
- Each download is checked (SHA-256, package, version, signer) before anything installs.

### Glasses

- A banner after an update: *Lumen updated*.
- The glasses tell the companion their version.

### Earlier in 0.2.0

- **Controls tab** (right of Apps): the batteries of the glasses, the band and the phone;
  camera, gallery and music; volume and brightness; do not disturb; Rokid settings; and the
  way back to the Rokid launcher. Back no longer leaves Lumen.
- The time and Wi-Fi at the top right of the home.
- The Notifications tab follows the band again; a right swipe goes to Apps.

## [0.1.0] (unreleased)

The first public release of Rokid Lumen: the glasses app (`dev.lumen.glasses`) and Rokid Lumen
Companion (`dev.lumen.companion`).

### The band

- The Meta Neural Band drives the glasses through an accessibility service: swipes move, the
  index tap selects, the middle tap is Back, the middle double tap turns the screen off and on,
  the middle hold turns the controls off and on, pinch and turn is the volume (or navigation).
- The index double tap can be mapped to Back, Home, Play or pause, an app, the apps grid, Rokid
  AI, the Hi Rokid shortcut, photos and videos, AR screenshots and videos, or handing the band
  to the phone.
- Band power saving: with the screen off the band stops its motion streams, and with power
  saving on its gestures too, until the glasses' button wakes the screen.
- The band's battery on the Rokid launcher's status row.
- The band link in Rust (`liblumen_band.so`), from air-gestures and kinesis.

### The glasses

- The apps grid: 3x3 pages with the phone's notifications first, the web apps and chosen native
  apps, and the glasses' settings last, arranged from the phone.
- MRBD web apps on GeckoView 156 (default) or the system WebView: offline `.mrbd.zip` packages
  served from a loopback server, one never-reused port each, and online HTTPS apps. Arrow keys,
  Enter and MRBD's Back from the band.
- `window.lumen.config` and the manifest's `lumen_config` and `lumen_internet`; the shim's
  `navigator.install`, `navigation.canGoBack`, `speechSynthesis` and sensor permission.
- Installs from outside the phone are confirmed on the glasses.
- Notifications: a banner over any app (open, dismiss, two swipes down to snooze for 15
  minutes, an optional dark background) and an inbox grouped by app, with dismiss on the
  glasses and the phone.
- Dictation: Enter on a text field opens the composer; the phone transcribes the glasses'
  microphone.
- Internet through the phone: a saved Wi-Fi first, else the phone's local-only hotspot and
  proxy, joined on its own.
- The self-arm: Wireless debugging on the glasses themselves gives the app the shell
  privileges to keep its accessibility service enabled across reboots and firmware force-stops,
  and to join the phone's hotspot. It leaves ADB over TCP on port 5555 enabled (see
  `docs/security.md`).

### The companion

- Tabs for Home, Apps, Band, Notifications and Settings.
- Apps: a preview of the HUD's grid, drag to reorder, add web apps and offline packages by
  address, per-app engine and settings (secrets stay on the glasses).
- Band: the glasses' gestures and band settings, the band on the phone (media, volume,
  brightness, flashlight, screen gestures, arrow keys, opening apps) and handing it over.
- Notifications: news-only banners, blocked apps, hidden text, banner only with the phone's
  screen off, private notifications as their public version.
- Dictation engines: the Android recognizer, OpenAI, ElevenLabs, Azure, and Vosk offline in
  English or Portuguese (models checked by SHA-256); API keys encrypted on the phone.
- The local-only hotspot and the HTTP proxy for the glasses, public destinations only.

### Known issues

See [docs/security.md](docs/security.md#known-open-issues). The code derived from R08 Access
Bridge has no license yet; the terms are being arranged with its author (see NOTICE).
