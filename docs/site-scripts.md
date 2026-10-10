# Site scripts: the band on websites

An online app shows a website that was never made for the band. Lumen helps such a site in two
ways:

- **The band navigation**, on every online app's pages (GeckoView): a ring that the swipes move
  between the site's links and buttons, and that the index tap clicks.
- **The page API**, `window.lumen`, which a package's site scripts use to give a site gestures of
  its own (Instagram's Reels, YouTube's player).

How to package site scripts: [building-apps.md](building-apps.md#online-app-packages-and-site-scripts).
The code: `app/src/main/assets/mrbd-shim.js` (shipped in the extension's `page.js`).

## The band navigation

The host turns it on for an online app's pages (`lumen.nav.enabled` becomes `true`; the console
says `[Lumen] Band navigation on`). Offline apps never get it: they handle the keys themselves
(see [building-apps.md](building-apps.md#input)).

It takes only what the page leaves alone. A key the page handles (`preventDefault()` in any
`keydown` listener, a game's on `window` included) stays the page's, and a site script's band
handler (below) comes before both.

| Band | What the navigation does |
| --- | --- |
| Swipe | Moves the ring to the nearest clickable that way. The first swipe rings the first one fully on screen (top, then left). |
| Index tap (Enter) | Clicks the ringed element, as a tap at its centre. Without a ring, Enter goes to the page. |
| Middle tap (Back) | Unchanged: Escape to the page, then history, then close. |

What counts as clickable: `a[href]`, `button`, `input`, `select`, `textarea`, `summary`, the ARIA
roles `button`, `link`, `tab`, `menuitem`, `option`, `checkbox`, `switch` and `radio`,
`[tabindex]` (not `-1`), `contenteditable` and `[onclick]`; at least 8x8 px, on screen, not
`visibility: hidden` or `opacity: 0`, and on top (a tap at it would hit it, not a layer over it).

How it picks:

- **Direction:** a candidate's near edge must be past the ringed element's centre. The score is
  the gap along the move plus twice the gap across it (overlapping = 0); the lowest wins, then
  the one whose centre is best aligned.
- **One stop per thing:** a clickable inside another of nearly the same size gives way to the
  outer one, and links to the same address close together (a video's thumbnail and its title)
  are one stop, ringed as the card that holds them. Enter clicks the biggest of those links. A
  link around a picture is ringed around the picture.
- **Scrolling:** with nothing that way on screen, the ringed element's scrolling container (a
  list, a carousel, else the page) scrolls 70% of its size that way, instantly, and the nearest
  candidate that came into view is ringed. Inside a container, its own items come first; the
  page's other clickables come once it can't scroll further. The ringed element is kept in view,
  64 px clear of the page's top and bottom.
- **Fixed bars:** a site's fixed or sticky bars (YouTube's top bar and bottom tabs) are skipped
  while the content can still scroll that way, and reached at the page's end, or from another
  bar's item. From a bar, nothing scrolls.
- **Text fields:** a ringed text field (a password too) gets the focus, so Lumen's keyboard shows
  its hint and the index tap opens it (dictation, band handwriting or the phone); moving away
  blurs it.
- **The ring goes** when the page navigates away, when its element leaves the page or turns
  invisible, and while it's scrolled off screen (it comes back with it). The next swipe after
  it's gone starts from the first visible clickable again.

Each swipe makes one `querySelectorAll` and checks at most 400 candidates' style and position,
so it stays fast on a long feed (2 to 5 ms on m.youtube.com's results in desktop Firefox; the
glasses are slower).

## The page API

`window.lumen` exists on every page Lumen shows, on both engines, before the page's scripts run.
These members are there with or without the host bridge (`lumen.config` and `lumen.audio`,
from [building-apps.md](building-apps.md#what-the-host-adds-to-the-page), need it).

| Member | What it does |
| --- | --- |
| `lumen.band.on(handler)` | The band's keys and Back go to `handler` before the page. Returns `off()`. |
| `lumen.highlight(element)` | Lumen's ring around `element`; `null` takes it away. |
| `lumen.highlighted()` | The ringed element, or `null`. |
| `lumen.toast(text, options)` | A short message at the top of the screen (or a chip in the middle). |
| `lumen.click(element)` | A tap at the element's centre. |
| `lumen.nav` | The band navigation: `enabled`, `move(direction)`, `clear()`. |

### `lumen.band.on(handler)`

`handler(key, event)` gets `'up'`, `'down'`, `'left'`, `'right'`, `'enter'` or `'back'`, and
returns exactly `true` when it handled the key (an `async` handler's promise counts as not
handled). Handlers added later are asked first; the first that returns `true` ends it.

- The keys come from a capture listener on `window` that Lumen adds before any of the page's:
  only trusted presses without Ctrl, Alt, Shift or Meta, and not while composing. A handled
  key gets `preventDefault()` and `stopImmediatePropagation()`, so no listener of the page's
  sees its `keydown` or its `keyup`. `event` is that `keydown`.
- `'enter'` isn't offered while a text field (or a password) has the focus: Enter there is the
  field's own (the keyboard's Enter, its action). The band's index tap on a focused field opens
  Lumen's keyboard before it reaches the page.
- `'back'` (the middle tap) comes without an event, before the Escape that Back sends the page.
  Handled, Back is done: no Escape, and the app stays where it is.
- A handler that throws is logged (`[Lumen] band handler failed on <key>`) and counts as not
  handled, so the key still reaches the page.

```js
const off = lumen.band.on((key) => {
  if (key !== 'right' || !location.pathname.startsWith('/reels/')) return false;
  likeCurrentReel();
  return true;
});
```

### `lumen.highlight(element)` and `lumen.highlighted()`

The ring: a 3 px border in `rgba(255, 255, 255, 0.86)`, 4 px outside the element, 16 px
corners, a 4 px black halo, nothing filled. It's one fixed overlay on `<html>`
(`data-lumen="highlight"`, `aria-hidden`, `pointer-events: none`, top z-index) that follows the
element every frame while shown, and goes when the element leaves the page or turns invisible.
Ringing a text field focuses it; ringing anything else blurs a focused field. On the glasses
(a device pixel ratio of 0.8) Gecko draws the border 2 device pixels wide.

### `lumen.toast(text, options)`

A black pill at the top centre (24 px down): 48 px high, a 2 px border in
`rgba(255, 255, 255, 0.43)`, white 22 px text, weight 500. A new toast replaces the one shown;
an empty `text` just takes it away. Options:

- `icon`: `'heart'`, `'heart-filled'`, `'forward'` or `'back'`, drawn before the text.
- `ms`: how long it stays (default 1500).
- `center: true`: a chip in the middle of the screen, 64 px high with 28 px text (a player's
  "+10 s").

```js
lumen.toast(liked ? 'Liked' : 'Unliked', { icon: liked ? 'heart-filled' : 'heart' });
lumen.toast('+10 s', { icon: 'forward', center: true });
```

### `lumen.click(element)`

Focuses the element when it's focusable, then sends `pointerdown`, `mousedown`, `pointerup`,
`mouseup` and `click` (bubbling, cancelable, composed, with `clientX`/`clientY`) to what is at
its centre, so a handler on a child, or on the page's root as React's are, sees a real-looking
tap. Returns `false` for an element that isn't in the page.

### `lumen.nav`

- `enabled`: whether the arrows and Enter drive the navigation. A site script may set it to
  `false` while it runs a mode of its own (a tab bar, a player) and back to `true` after.
- `move(direction)`: one move (`'up'`, `'down'`, `'left'`, `'right'`), as a swipe would, even
  while `enabled` is `false`. Returns whether the ring moved or something scrolled.
- `clear()`: takes the ring away.

## Writing a site script

A site script runs in the page's own world at `document_start`, before the page's scripts, only
on the sites its package names, only while its app is in front, and only on GeckoView.

```js
// site/player.js: on a watch page, the index tap pauses and the side swipes skip 10 s.
(() => {
  lumen.band.on((key) => {
    const video = location.pathname === '/watch' && document.querySelector('video');
    if (!video) return false;
    if (key === 'enter') {
      if (video.paused) video.play(); else video.pause();
      return true;
    }
    if (key === 'left' || key === 'right') {
      video.currentTime += key === 'right' ? 10 : -10;
      lumen.toast(key === 'right' ? '+10 s' : '-10 s', { icon: key === 'right' ? 'forward' : 'back', center: true });
      return true;
    }
    return false;
  });
})();
```

- **The page isn't there yet** at `document_start`: look elements up when a key comes, or from a
  `MutationObserver`, never once at load.
- **Single-page sites change pages without loading** (Instagram, YouTube): check `location` in
  the handler, and return `false` where the script has nothing to do, so the band navigation
  takes over.
- **No markup from strings.** Instagram and YouTube enforce a strict Content Security Policy and
  Trusted Types: `innerHTML`, inline `style` attributes and inline scripts fail there. Use
  `lumen.toast` and `lumen.highlight`, or build elements with `createElement` and style them
  through `element.style`.
- **Text the wearer reads** comes in English and Portuguese, picked from `navigator.language`.
- **Logs** go to logcat with Gecko's console (`adb logcat -s BandGecko:D`); prefix them with the
  app's name.

## Testing

`scripts/e2e/run.mjs` checks the API and the navigation in Firefox set up as the glasses'
GeckoView (600x600 CSS px at a device pixel ratio of 0.8, Android's user agent, `page.js`
injected before the page's scripts), on local pages and on m.youtube.com and Wikipedia:

```sh
python3 scripts/gen-gecko-content.py            # after changing mrbd-shim.js
NODE_PATH=/path/to/node_modules node scripts/e2e/run.mjs            # Playwright with its Firefox
NODE_PATH=/path/to/node_modules node scripts/e2e/run.mjs --offline  # local pages only
```

The host's messages are window messages there: `window.postMessage({__mrbdFromHost: {type:
'bandNavigation', value: true}}, '*')` turns the navigation on and `{type: 'back'}` is the
middle tap. A site script can be tried the same way: add it with `context.addInitScript()` after
`page.js`, then press the arrow keys.
