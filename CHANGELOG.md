# Changelog

<!-- The release workflow takes a version's notes from its "## [x.y.z]" heading up to the next
     "## " heading, so keep the brackets. -->

## [0.2.0-beta.8]

### Companion

- **Keyboard** (Home): the field focused in a web app on the glasses, and a page that types into
  it as you type; its Send key is an Enter on the glasses. While it's open, Enter on a field
  there goes to the app instead of opening dictation. Passwords, email, URL, phone and number
  fields get the matching keyboard.
- **Copies of a web app**: Apps > an app > **Add a copy** installs it again under another name
  (a personal and a work WhatsApp), with its own data and settings; **Rename** gives any web app
  a name that updates keep. Updating the original updates its copies.
- **Share logs** (Settings): both apps' logs in `rokid-lumen-logs-<date>-<time>.zip`, through
  Android's share sheet, no adb needed. The glasses send theirs over Rokid's link.

### Glasses

- A notification that a web app and its copy both take shows each one's name on its button
  (a copy has the original's icon).
- The Wi-Fi the glasses turned on for the phone's internet goes off again even when the phone's
  network drops during the 30 s after leaving the app.

### Licensing

- **R08 Access Bridge is now Apache-2.0**: Anezium licensed it on 2026-10-04, so the glasses'
  input, navigation, settings screen and self-arm derived from it are used under the Apache
  License 2.0. NOTICE lists the files and carries R08 Access Bridge's notice; each file says
  so in its header.

### Docs

- **New screenshots and a demo video** of 0.2.0-beta.7, in demo data only: the home's three tabs,
  the notification banner, inbox and quick replies, dictation, the WhatsApp, Telegram, Reddit
  and Volund OS apps in demo mode, the companion's tabs, and a 35-second clip (MP4 and GIF).
- **Debug builds**: a simulated notification takes its app's name and icon, its age, a banner
  and quick replies that answer on the glasses alone; the apps grid can be arranged from adb.

## [0.2.0-beta.7]

### Glasses

- **Battery**: the display goes off after 2 minutes without input (it was 10 days, Rokid's
  value); the companion's *Screen off after* sets it, *Never* keeps Rokid's. The band's
  gestures count as input.
- A web app the display or another screen hides is suspended (it no longer draws or runs, and
  comes back as it was) and closes the microphone; after 5 minutes hidden the phone's internet
  is let go until it's back. A page whose process Android ended loads again when it's shown.
- GeckoView draws at 30 fps, plays an animated GIF once, and makes no speculative connections,
  prefetches or Safe Browsing updates.
- The band streams motion only while pinch and turn can use it (not paused, the dial set to an
  action, the display on), and its link wakes 4 times a second when the band is quiet, not 20.
- The accessibility watchdog checks the app's process every 3 s with shell builtins and does
  its full check every 30 s (version 1 ran it every second, starting several processes each
  time).

### Companion

- A notification removed from the phone is sent to the glasses only when they have it, and a
  re-post that says the same isn't sent again.

### Web apps

- A hidden page gets `visibilitychange` and is suspended: refresh what's stale when it's
  visible again (docs/building-apps.md).
- The shim asks the app for the phone's proxy at most every 5 s, not once per request.

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

See [docs/security.md](docs/security.md#known-open-issues).
