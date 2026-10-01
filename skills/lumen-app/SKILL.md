---
name: lumen-app
description: Build, package and test a web app for Rokid Lumen (Rokid glasses driven by the Meta Neural Band). Use when asked to create or port a Meta Ray-Ban Display style web app for Lumen, write its manifest (lumen_config, lumen_internet), use window.lumen.config, package a .mrbd.zip, or test it on the glasses with adb.
---

# Building a Rokid Lumen app

A Lumen app is a small web app, shown on Rokid glasses and driven by the Meta Neural Band
through key events. Full guide: `docs/building-apps.md` in the Rokid Lumen repository. This
skill is the short version.

## The target, in one table

| | |
| --- | --- |
| Viewport | 600x600 CSS px, shown on the HUD's 480x480 square |
| Display | Additive, monochrome green: black is transparent, bright fills cover the world |
| Input | `ArrowUp/Down/Left/Right` (swipes), `Enter` (index tap), `Escape` (Back, middle tap) |
| Engines | GeckoView 156 (default) or the system WebView, Chromium 95 |
| Offline origin | `http://127.0.0.1:<port>`, one port per app |
| Host API | `window.lumen.config`, `navigator.install`, shims for `navigation.canGoBack`, `speechSynthesis` |

## Checklist

1. **Layout for 600x600.** One screen at a time, no page scrolling; scroll lists inside the
   app and keep the focused item in view. Large text (the HUD is small and far away).
2. **Black background, light text, outlines.** Avoid filled bright panels and white
   backgrounds: on an additive display they block the wearer's view. Show focus with an outline
   or a brighter colour, not a filled block.
3. **Keyboard only.** Every control is focusable (`<button>`, `<a href>`, `tabindex="0"`) and
   shows focus. Handle the arrow keys yourself to move focus (`element.focus()`); there is no
   pointer. Activate on `Enter`.
4. **Back.** Handle `Escape` on `keydown` for in-app levels (close a dialog, go up) and call
   `event.preventDefault()` when you did. Don't prevent it at the top level: Lumen then goes back
   in history, or closes the app.
5. **Text input.** `Enter` on a text field opens Lumen's dictation composer; the text arrives
   through the value setter, `input`, then `change`. Don't build an on-screen keyboard. Don't
   expect text on focus alone.
6. **Settings from the phone.** Declare them in `lumen_config`; read them with
   `await window.lumen.config.get()`, follow them with `window.lumen.config.onChange(cb)`.
   Feature-detect `window.lumen` so the app also runs in a desktop browser.
7. **Internet.** Offline apps that fetch anything declare `"lumen_internet": true`. Expect it to
   arrive a few seconds after launch (the glasses may join the phone's hotspot first): show a
   connecting state and retry.
8. **Fonts.** Bundle your fonts in the package. Google Fonts links become a bundled Noto Sans
   in offline packages; any other Google family won't load offline.
9. **Package.** Build, zip the output with `index.html` and `manifest.webmanifest` at the root
   (or under one top-level folder), name it `<app>.mrbd.zip`. At most 200 MB unpacked and
   5,000 files. Use relative asset paths.
10. **Test on the glasses** (below), on GeckoView first.

## Manifest template

`manifest.webmanifest` at the package's root. Lumen reads only `id`, `short_name` (else
`name`), `version`, `icons` (the largest PNG), `lumen_internet` and `lumen_config`.

```json
{
  "id": "com.example.myapp",
  "name": "My App",
  "short_name": "My App",
  "version": "0.1.0",
  "icons": [{ "src": "icon-192.png", "sizes": "192x192", "type": "image/png" }],
  "lumen_internet": true,
  "lumen_config": [
    { "key": "server.url", "label": "Server URL", "type": "url" },
    { "key": "api.key", "label": "API key", "type": "secret" },
    { "key": "user.name", "label": "Your name", "type": "text" }
  ]
}
```

- Keep `id` stable: the same `id` updates the app and keeps its data and settings.
- `type` is `text` (default), `url` (must start with `https://` or `http://`) or `secret`
  (kept on the glasses, never shown back on the phone).
- A key that isn't set is missing from `get()`'s result: always have a default or a "set me
  up in the companion" screen.

## Reading the settings

```js
async function loadSettings() {
  if (!window.lumen) return { 'server.url': 'https://example.com' }; // desktop browser
  return window.lumen.config.get();
}
const stop = window.lumen?.config.onChange((values) => applySettings(values));
```

The host bridge answers only pages on the app's own origin: don't load the app's UI from
another origin or inside a cross-origin iframe.

## Pitfalls

- **Chromium 95 (system WebView).** Missing: the Navigation API (only `canGoBack` is shimmed),
  CSS `:has()`, container queries, CSS nesting, `dvh`/`svh` units, `structuredClone`,
  `Array.prototype.findLast`. If the app must run there, set Vite's `build.target: 'chrome95'`
  and avoid those features. GeckoView 156 is current and the default.
- **Cookies on the WebView are shared between apps.** Keep anything that signs in on GeckoView,
  where each app has its own context.
- **Focus.** Nothing is focused at load: focus your first control in code. Keep one focused
  element at all times, or the arrow keys do nothing.
- **Enter on a text field never reaches your `keydown` handler**: the composer takes it. Use a
  separate button to submit.
- **`Escape` arrives as a synthetic event** on `document.activeElement`; listen on `keydown`
  (bubbling to `document` is fine). Handling only `keyup` doesn't stop Lumen's Back.
- **Offline apps can't navigate to another origin.** Links out open nowhere. Fetching is fine
  with `lumen_internet`.
- **Client-side routes** work: a path without a file extension gets `index.html`. A missing
  file with an extension is a 404.
- **No pointer, no hover, no touch.** Don't rely on `:hover` or `click` coordinates.
- **Speech.** Where the engine has no `speechSynthesis` (the system WebView), the shim adds one
  on Android's TextToSpeech, with a single voice; its `pause()` and `resume()` do nothing.
  Feature-detect it like any other API.

## Testing on the glasses with adb

```sh
# Install the package (copies it to the drop folder and opens the grid, which installs it).
scripts/push-webapp.sh myapp.mrbd.zip

# Or from an HTTPS address, confirmed on the glasses:
adb shell am start -n dev.lumen.glasses/.InstallConfirmActivity \
  -a dev.lumen.glasses.INSTALL_PACKAGE -d https://example.com/myapp.mrbd.zip

# Logs (WebView console messages show under BandWebView).
adb logcat -v time -s BandWebApp:D BandGecko:D BandWebView:D BandLocalServer:D *:S

# Without the band (debug builds, Settings > Band > Simulated band on):
adb shell am broadcast -a dev.lumen.glasses.SIMULATE --es gesture swipe_down
adb shell am broadcast -a dev.lumen.glasses.SIMULATE --es gesture index_tap
adb shell am broadcast -a dev.lumen.glasses.SIMULATE --es gesture middle_tap

# Back from the keyboard:
adb shell input keyevent KEYCODE_ESCAPE
```

- Debug builds of the glasses app allow GeckoView remote debugging (Firefox `about:debugging`).
- Never `am force-stop` the glasses app: the firmware drops its accessibility service and the
  band. `adb install -r` is safe.
- Settings for the app are set in the companion's Apps tab; check the "Set up" badge there.
