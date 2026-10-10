# Changelog

<!-- The release workflow takes a version's notes from its "## [x.y.z]" heading up to the next
     "## " heading, so keep the brackets. -->

## [0.2.0-beta.21]

### Glasses

- **Status bar**: the time and the glasses' battery in the strip above the app, over any app
  but the Rokid launcher (which has its own), so a page is never covered. The battery turns
  amber at 20 % or less and shows a bolt while charging. Lumen's home moves its clock up there.
  Turn it off in the companion's Glasses settings.

### Web apps

- **No more blinking pages**: since beta.19 a page in any web app went black for a moment, and
  stayed black until a gesture when left still, about every 10 seconds on a busy page (Google
  News). Lumen asked Gecko to free memory whenever the glasses had less than 350 MB free, which
  is most of the time with a page open, and Gecko dropped what it had drawn. Lumen no longer
  asks: out of memory, the page goes and Lumen loads it again, as since beta.19.

## [0.2.0-beta.20]

### Glasses

- **Lumen's keyboard**: Lumen is the glasses' input method, in any app (web apps, Rokid's
  settings). A focused field shows a hint under the page; the index tap opens a panel to
  dictate, write with the band, or type on the phone (the companion offers its keyboard for that
  field and the text shows on the glasses as you type). The panel names the field and its Enter
  action (Search, Send, Sign in…) and offers it once there is text. A password gets writing and
  the phone, masked. It replaces the shim's typing in web apps: no script is injected for it any
  more. Turn it off in the companion's Glasses settings to go back to Rokid's keyboard.
- **Pictures in notifications**: a notification's photo (a chat photo, a post's picture) shows
  in its bubble, fetched from the phone when you open it, full screen on the index tap. Never for
  hidden or redacted notifications.
- **Reply with your own words**: a field and a send button above the quick replies, typed with
  Lumen's keyboard and sent through the notification's own reply on the phone, without opening
  the app. The reply shows in the conversation; a failure keeps the text.

## [0.2.0-beta.19]

### Band

- **Index hold and middle hold** are gestures of their own on every device: the phone's and the
  computer's profiles and the glasses. The index hold is a pinch held and let go without
  turning the wrist (turning keeps it pinch and turn).
- **Pause or resume the band** is an action: the middle hold's by default, any gesture's when
  mapped, and the only one that works while paused. A mapping without it doesn't stay paused.

### Glasses

- **Move the band from the glasses**: a Band tile in Controls shows where the band is and opens
  the device chooser (the glasses, the phone, the computers), then that device's profile; it is
  also an action, the index double tap's by default. Each step shows as a toast in Meta's style,
  and a hand-over that gets no answer brings the band back.
- A value changed in the Glasses settings from the phone shows at once, as sending.

### Companion

- **Write** on the phone: a gesture switches to the Lumen handwriting keyboard on the focused
  field and the band writes there; your keyboard comes back when the writing ends.
- **Apps tab**: changes show at once and reach the glasses even when Rokid's link is slow (a
  package dropped in or an app changed on the glasses reaches the phone too).

### Web apps

- **Sign in on another site's page**: an online app's page on another site (the Google sign-in
  of a YouTube app) now takes the phone's keyboard and the composer in its text fields. It still
  gets nothing else from the host (settings, microphone, speech, installs, Back).
- **Out of memory, the page goes and Lumen stays**: watching Instagram's Reels ran the glasses
  out of memory and Android stopped Lumen with the page. Now only the page goes; Lumen loads it
  again, and after a second time within two minutes shows a notice (index tap: try again).
  Gecko keeps less for later (no spare process, no pages kept for Back, smaller caches) and
  builds no accessibility trees, whose teardown crashed Lumen when a page's process died.
  Apps left open behind another screen are closed when memory runs low or the page in front
  runs out of it (a hidden YouTube held 175 MB next to Instagram's Reels); opened again, they
  load again, still signed in.
  Gecko is asked to free memory as soon as the glasses run low (it otherwise heard only when
  Android trimmed, after the page was already gone), and its crash helper process (40-70 MB,
  for crash reports Lumen never sends) no longer runs.
- **Video fits the display**: Instagram's videos play at 480 pixels on the short side at most,
  in H.264 when offered, and players asking about bigger sizes or more than 30 fps hear no.
- **Online app packages with site scripts**: a `.mrbd.zip` without `index.html` whose
  manifest's `start_url` is an `https://` address installs as an online app that brings scripts
  for the sites it names (`lumen_scripts`). GeckoView runs them in those pages, in the page's own
  world before its scripts, only while the app is in front; the install confirmation names the
  sites. Such a package can also show a gesture card the first three times the app opens
  (`lumen_gestures`); any gesture closes it. It takes over an app added by address for the same
  site, keeping its sign-ins and its place in the grid.
- **The band on any site**: in an online app, swipes move a highlight between what can be
  clicked on the page (the page scrolls when there's nothing more that way), the index tap opens
  it, and a page that handles the arrows itself keeps them. An online app without a gesture card
  says so ("Swipe: move · Index: open · Middle: back") the first three times it opens. Site
  scripts get the same pieces (`window.lumen.band`, `highlight`, `toast`, `click`, `nav`).
- **Dark pages**: GeckoView asks every site for its dark theme (`prefers-color-scheme: dark`):
  on the glasses black is see-through and a white page washes out the view.

## [0.2.0-beta.18]

### Companion

- **Air Mouse on the phone** (experimental): a phone profile can put it on a gesture. A cursor
  shows in the middle of the screen and your forearm moves it; the index pinch is a finger there
  (tap, long press, hold and move to drag and scroll), the middle pinch is Back, and the same
  gesture hides it. Its speed, steadiness and flick boost are sliders in the Phone settings. It
  goes through Screen gestures, the accessibility service the screen actions already use.
- The Glasses settings list every gesture of the glasses, with the actions grouped and
  **Restore the default gestures**, and the glasses' Air Mouse sliders.

### Glasses

- **Every band gesture is mappable** on the glasses (the navigation was fixed), with the same
  defaults as before.
- **Air Mouse on the glasses** (experimental): the same cursor on the display, from any gesture.
  It taps apps and web pages, and on Lumen's home the tab, app, control or notification under it.

## [0.2.0-beta.17]

### Companion

- **Air Mouse** (experimental): with the band on a computer, a gesture turns it on and the same
  gesture off; off and on again is the clutch, to set the arm somewhere comfortable while the
  pointer stays. The forearm moves the pointer like a mouse (relative, steadied while the arm is
  still, accelerated on a flick); the index pinch is the left button and the middle pinch the
  right one, held to drag; swipes keep their actions. Pointer speed, steadiness and flick boost
  are sliders in the Computer settings. Ported from kinesis' experimental air cursor.

### Band

- The orientation and gyro samples reach the air mouse (they were read and dropped before), with
  their lateness measured by the band's clock: late data is skipped and a held button let go.

## [0.2.0-beta.16]

### Companion

- **The band on a computer**: **Other device**, the switch's third choice, keeps the band on the
  phone and makes the phone a computer's Bluetooth keyboard and mouse, with nothing to install
  there. Pair from the computer's Bluetooth settings (a computer already paired with the phone
  is listed); the phone reconnects to the last one by itself.
- **Computer profiles**: *Notebook* (scrolling, desktops, Enter, Esc, writing) and *Presentation*
  (the arrows), plus your own, with keys, macOS desktops and Mission Control, the app switcher,
  scrolling, media, volume and brightness; the phone's switch gesture moves between them.
- **Writing on the computer**: a gesture turns the band's handwriting on and types each letter
  at the computer's cursor; the middle tap ends it. A notification on the phone shows the last
  letters.
- **The computer's settings**: its keyboard layout (ABC/US or Brazilian ABNT2), the scrolling
  speed, reversed scrolling. The Band tab's settings follow the band to the device it moves to.

### Glasses and companion

- Debug builds: the simulated band writes (`--es handwriting_text`), and `--es simulated`
  switches it from adb; the companion's `WRITE_TEXT` types into the handwriting keyboard or the
  computer while they write.

## [0.2.0-beta.15]

### Companion

- **Gesture profiles on the phone**: Media, Navigation and your own, each with every gesture, its
  pinch and turn and whether the band listens while the phone is locked. A switch gesture (the
  same in every profile) moves to the next one, and any gesture can go to the next, the previous
  or a given profile; the phone names the new profile in a short notification. Your layout from
  before became *My layout*.
- **The locked phone**: a profile that doesn't listen while locked turns the band off (its power
  saving) until the phone is unlocked.
- **The Band tab, redesigned**: where the band is and the switch on top, then the phone's and
  the glasses' settings apart; a page per profile, and **Key and pairing** on its own page.
- Home shows the band's state from the device it's with (it said *Off* while the band was on
  the phone).

### Glasses and companion

- **Pinch and turn follows the audio**: while something plays it's the volume; otherwise what
  you chose (the glasses: navigation, brightness, volume or nothing; the phone: brightness,
  arrows, volume or nothing).

### Band

- After a handwriting session, the hand still moving while the band is restored can't pause the
  band any more.

## [0.2.0-beta.14]

### Companion

- **Handwriting keyboard**: *Lumen handwriting*, a keyboard for the phone's own apps. With the band
  on the phone, a text field switches the band's handwriting on and each letter goes in at the
  cursor; the middle tap ends it and hides the keyboard, a pause stops it. Password fields never
  get the band; with the band on the glasses it offers to bring it over. Band tab: *Handwriting
  keyboard* shows whether it's on and opens the keyboard settings or the picker.

## [0.2.0-beta.13]

### Glasses

- **Write with the band**: the composer of a web app's text field now asks **Dictate or write?**
  (the last choice preselected). **Write** switches on the band's own handwriting model: write
  with a finger on any surface and each letter goes into the field; a push forward is a space,
  a sweep back deletes, the middle tap finishes (a pause does too). While it writes only the
  middle tap gets through, so a stroke can't move the screen or pause the band. The band is
  always put back to normal afterwards, at the next connection if the app stopped in the
  middle, and before it's handed to the phone.

### Band

- The bridge can switch the band's handwriting model on and off (from kinesis 0.5.0), with its
  settings found by name, every change read back and a recovery for a band left in the model.

## [0.2.0-beta.12]

### Companion

- **Generate the band's key with your Meta account**: on the setup page and the Band tab, the
  companion signs in on Meta's own page, claims a factory-reset band for this phone and sends the
  new key to the glasses, as kinesis and air-gestures do on a computer. No computer is needed for
  any step of the setup now. A warning comes first: claiming unlinks the band from Meta's app and
  glasses. Debug builds also have a test that signs in and reads the band's identity, stopping
  before anything is claimed.
  The claim gives up after a few connection attempts and says to factory reset the band (a band
  that wasn't reset shows up, then turns each connection down).
- **Export the key** (Band tab): the key as `air-gestures-band.json`, for a computer.
- **Install on the glasses** asks GitHub for the releases again instead of trusting the list the
  Updates page loaded from its cache: with an old cache it offered an older release than the
  glasses had had, and the install failed as a downgrade.
- The setup page no longer shows the glasses as prepared, or holding the band's key, while the
  glasses app isn't installed.

### Band

- The band link can run the ownership ceremony (from the kinesis port in band-core) over the
  phone's Bluetooth, with Meta's two answers coming from the companion. The new key is kept
  aside until the band accepts it, so a claim cut short never leaves a key the band doesn't know.
- After a factory reset the band has a new address: the link uses the address of the band its key
  belongs to, forgets bonds left from before the reset (they never come back), and keeps the
  address the band answers from. Before, the glasses kept waiting on the old bond.
- While a claim runs, the phone's own band link stays off the band (both used to compete for it).

## [0.2.0-beta.11]

### Companion

- **Setup, continued**: the setup page also runs the glasses' self-arm (**Prepare the glasses**,
  with its steps and what to fix in Hi Rokid when it stops) and gives the glasses the band's key
  (**Send the key to the glasses**, also on the Band tab): no cable for either.

### Glasses

- The self-arm can be started from the phone, wakes the display and keeps it on while it walks
  Settings (from the phone it often started with the display off and timed out), and reports
  its progress to the phone.
- The band's key can come from the phone over Rokid's link (imported like the drop folder's
  file; the band link starts over with it). The band's status says whether the glasses hold a key.

## [0.2.0-beta.10]

### Companion

- **Set up your glasses, no computer**: a setup page (and a card on Home until it's done) takes
  the four steps from the phone: Hi Rokid's authorization, the phone's Wi-Fi, **Install on the
  glasses** (the latest release's glasses APK, checked, through Rokid's link, as Rokid Nexus
  does) and turning Lumen on in the glasses' Accessibility settings, which the companion opens
  there. Done when the glasses app answers.
- Rokid's link keeps one callback for its app calls: install, query and open now wait their
  turn instead of taking each other's answers.

### Glasses

- **Setup entry**: what the phone opens on the glasses during setup. With the accessibility
  service off it opens Settings > Accessibility (Rokid Lumen at the top); with it on, the home.

## [0.2.0-beta.9]

### Glasses

- **The last row's names**: the Apps grid's last row (and the last row of the Notifications and
  Controls tabs) scrolls fully into view; its names were hidden under the bottom fade.
- Web apps can open a notification at the page its **tag** names (`{tag}` in
  `lumen_notifications`; Reddit's tag names the post). With it, Reddit for Lumen 0.2.2 opens a
  Reddit notification's post, and Unofficial Telegram for Lumen 0.3.1 a Telegram
  notification's chat.

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
