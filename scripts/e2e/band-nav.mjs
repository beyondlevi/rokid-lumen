// The page API and the generic band navigation (mrbd-shim.js, through the generated page.js) on
// local fixtures (scripts/e2e/fixtures, served as https://lumen.test/).
import path from 'node:path';
import { check, helpers, newContext, ringAround } from './lib.mjs';

const BASE = 'https://lumen.test/';

async function open(context, name) {
  const page = await context.newPage();
  const logs = [];
  page.on('console', (m) => logs.push(`${m.type()} ${m.text()}`));
  page.on('pageerror', (e) => logs.push(`pageerror ${e.message}`));
  await page.goto(BASE + name);
  return { page, logs, h: helpers(page) };
}

async function gridAndDisabled(context, out) {
  const { page, logs, h } = await open(context, 'grid.html');
  await h.key('ArrowDown');
  let s = await h.state();
  check('nav: off until the host says bandNavigation', s.id === null && s.ring === null, JSON.stringify(s));
  check('nav: window.lumen exists, with the API', await page.evaluate(() =>
    ['band', 'highlight', 'highlighted', 'toast', 'click', 'nav'].every((k) => k in window.lumen) && window.lumen.nav.enabled === false));
  await h.enableNav();
  await h.enableNav();
  check('nav: "Band navigation on" logged once', logs.filter((l) => l === 'info [Lumen] Band navigation on').length === 1, logs.join(' | '));
  await h.key('ArrowDown');
  s = await h.state();
  check('grid: the first key rings the first visible item (top, then left)', s.id === 'g1' && ringAround(s.ring, await h.rectOf('#g1')), JSON.stringify(s));
  const path1 = [];
  for (const [key, want] of [['ArrowRight', 'g2'], ['ArrowRight', 'g3'], ['ArrowDown', 'g6'], ['ArrowLeft', 'g5'], ['ArrowUp', 'g2'], ['ArrowDown', 'g5'], ['ArrowDown', 'g8'], ['ArrowLeft', 'g7'], ['ArrowUp', 'g4']]) {
    await h.key(key);
    s = await h.state();
    path1.push(`${key}->${s.id}`);
    if (s.id !== want) break;
  }
  check('grid: right, down, left and up move to the neighbour', path1.join(',') === 'ArrowRight->g2,ArrowRight->g3,ArrowDown->g6,ArrowLeft->g5,ArrowUp->g2,ArrowDown->g5,ArrowDown->g8,ArrowLeft->g7,ArrowUp->g4', path1.join(','));
  await h.key('ArrowLeft');
  s = await h.state();
  check('grid: nothing that way keeps the ring', s.id === 'g4', JSON.stringify(s));
  await page.screenshot({ path: path.join(out, 'nav-fixture-grid-ring.png') });
  await h.fromHost({ type: 'bandNavigation', value: false });
  await page.waitForFunction(() => !window.lumen.nav.enabled);
  await h.frame();
  s = await h.state();
  check('nav: turned off clears the ring', s.id === null && s.ring === null, JSON.stringify(s));
  await page.close();
}

async function list(context, out) {
  const { page, h } = await open(context, 'list.html');
  await h.enableNav();
  await h.key('ArrowDown', 5);
  let s = await h.state();
  check('list: down walks the list', s.id === 'i5' && s.scrollY === 0, JSON.stringify(s));
  await h.key('ArrowDown');
  s = await h.state();
  check('list: no item below scrolls the page 70% and keeps the ring (off screen, hidden)', s.id === 'i5' && s.scrollY === 420 && s.ring === null, JSON.stringify(s));
  await h.key('ArrowDown');
  s = await h.state();
  const six = await h.rectOf('#i6');
  check('list: the next item that came into view is ringed, kept clear of the bottom edge', s.id === 'i6' && s.scrollY > 840 && six.top + six.height <= 536.5 && ringAround(s.ring, six), JSON.stringify({ s, six }));
  await page.screenshot({ path: path.join(out, 'nav-fixture-list-scrolled.png') });
  const ups = [];
  for (let i = 0; i < 5 && s.id !== 'i5'; i++) {
    await h.key('ArrowUp');
    s = await h.state();
    ups.push(`${s.id}@${s.scrollY}`);
  }
  check('list: up scrolls back and reaches the item above the gap', s.id === 'i5' && s.scrollY < 420, ups.join(','));
  await page.close();
}

async function inner(context) {
  const { page, h } = await open(context, 'inner.html');
  await h.enableNav();
  await h.key('ArrowDown', 3);
  let s = await h.state();
  check('inner: down goes into the scrolling list', s.id === 'b2', JSON.stringify(s));
  const seen = [];
  let ringWhileOut = 'not checked';
  for (let i = 0; i < 6 && s.id !== 'b3'; i++) {
    await h.key('ArrowDown');
    s = await h.state();
    const innerTop = await page.evaluate(() => document.getElementById('inner').scrollTop);
    seen.push(`${s.id}@${innerTop}`);
    if (i === 0) ringWhileOut = s.ring;
  }
  check('inner: no item below in the list scrolls the list (70% of it), not the page', seen[0] === 'b2@140' && s.id === 'b3' && s.scrollY === 0, seen.join(','));
  check('inner: the button below the list waits until the list is done', !seen.some((x) => x.startsWith('after')), seen.join(','));
  check('inner: the ring is hidden while its item is scrolled out of the list', ringWhileOut === null, JSON.stringify(ringWhileOut));
  check('inner: the item that came into view is ringed inside the list', ringAround(s.ring, await h.rectOf('#b3')), JSON.stringify(s.ring));
  await h.key('ArrowDown');
  s = await h.state();
  check('inner: down reaches the last item', s.id === 'b4', JSON.stringify(s));
  await h.key('ArrowDown');
  s = await h.state();
  check('inner: past the end of the list, down leaves it', s.id === 'after', JSON.stringify(s));
  await page.close();
}

async function nested(context, out) {
  const { page, h } = await open(context, 'nested.html');
  await h.enableNav();
  await h.key('ArrowDown');
  let s = await h.state();
  check('nested: a card holding a link of nearly its size is one stop, the outer card', s.id === 'card' && ringAround(s.ring, await h.rectOf('#card')), JSON.stringify(s));
  await h.key('ArrowDown');
  s = await h.state();
  check('covered: a button under another layer is skipped', s.id === 'visible', JSON.stringify(s));
  await h.key('ArrowDown');
  s = await h.state();
  check('cards: the thumbnail and title (same address) are one stop, ringed as their card', s.id === 't1' && ringAround(s.ring, await h.rectOf('#v1')), JSON.stringify(s));
  await page.screenshot({ path: path.join(out, 'nav-fixture-card-ring.png') });
  await h.key('ArrowDown');
  s = await h.state();
  check('cards: down goes to the next card, past the card\'s own menu', s.id === 't2', JSON.stringify(s));
  await page.close();
}

async function picture(context) {
  const { page, h } = await open(context, 'picture.html');
  await h.enableNav();
  const link = await h.rectOf('#pic');
  await h.key('ArrowDown', 2);
  const s = await h.state();
  check('picture: an inline link around a picture is ringed around the picture', s.id === 'pic' && link.height < 40 && ringAround(s.ring, await h.rectOf('#img')), JSON.stringify({ s, link }));
  await page.close();
}

async function clicks(context) {
  const { page, h } = await open(context, 'click.html');
  await h.enableNav();
  await page.keyboard.press('Enter');
  let counts = await page.evaluate(() => Object.assign({}, window.counts));
  check('enter: without a ring, Enter goes to the page untouched', counts.click === 0 && counts.documentEnter === 1, JSON.stringify(counts));
  await h.key('ArrowDown');
  await h.key('Enter');
  counts = await page.evaluate(() => Object.assign({}, window.counts));
  const click = await page.evaluate(() => window.clicks[0]);
  const rect = await h.rectOf('#react');
  check('enter: clicks the ringed element (pointerdown, mousedown, pointerup, mouseup, click)',
    counts.pointerdown === 1 && counts.mousedown === 1 && counts.pointerup === 1 && counts.mouseup === 1 && counts.click === 1, JSON.stringify(counts));
  check('enter: the click is at the centre, on what is there, bubbling and composed',
    click && click.target === 'label' && Math.abs(click.x - (rect.left + rect.width / 2)) < 1 && Math.abs(click.y - (rect.top + rect.height / 2)) < 1 && click.bubbles && click.composed, JSON.stringify(click));
  check('enter: the clicked element has the focus', (await h.state()).active === 'react');
  await h.key('Enter');
  counts = await page.evaluate(() => Object.assign({}, window.counts));
  check('enter: again, one click per Enter (no native activation on top)', counts.click === 2, JSON.stringify(counts));
  await h.key('ArrowDown');
  let s = await h.state();
  check('field: a ringed text field gets the focus (Gecko then asks for the keyboard)', s.id === 'name' && s.active === 'name', JSON.stringify(s));
  const enters = counts.documentEnter;
  await h.key('Enter');
  await page.waitForTimeout(100);
  const sent = await h.sent();
  counts = await page.evaluate(() => Object.assign({}, window.counts));
  // The keyboard's Enter (its action): the page gets it; no click, and nothing for the host.
  check('field: Enter on it is the field\'s own: the page gets it, no click, no host message',
    counts.documentEnter === enters + 1 && counts.click === 2 && sent.length === 0, JSON.stringify({ counts, sent: sent.map((m) => m.type) }));
  await h.key('ArrowDown');
  s = await h.state();
  check('field: a ringed password field gets the focus too', s.id === 'secret' && s.active === 'secret', JSON.stringify(s));
  await h.key('ArrowDown');
  s = await h.state();
  check('field: moving away blurs it', s.id === 'after' && s.active !== 'secret', JSON.stringify(s));
  await h.key('Enter');
  counts = await page.evaluate(() => Object.assign({}, window.counts));
  check('enter: a real button gets one click', counts.after === 1, JSON.stringify(counts));
  await page.close();
}

async function game(context) {
  const { page, h } = await open(context, 'game.html');
  await h.enableNav();
  await h.key('ArrowDown', 3);
  await h.key('ArrowRight');
  const s = await h.state();
  const moves = await page.evaluate(() => window.moves.slice());
  check('game: a page that takes the arrows (on window, after Lumen) keeps them: no ring, no scroll',
    moves.length === 4 && s.id === null && s.ring === null && s.scrollY === 0, JSON.stringify({ moves, s }));
  await page.close();
}

async function band(context) {
  const { page, logs, h } = await open(context, 'band.html');
  const pageKeys = () => page.evaluate(() => window.pageKeys.splice(0));
  await page.evaluate(() => {
    window.got = [];
    window.offA = window.lumen.band.on((key, event) => {
      window.got.push(`A ${key} ${event ? event.type : '-'}`);
      return key === 'down' || key === 'back' || key === 'enter';
    });
  });
  await h.key('ArrowDown');
  let got = await page.evaluate(() => window.got.splice(0));
  let keys = await pageKeys();
  check('band.on: the handler gets the key with its event', got.join() === 'A down keydown', got.join());
  check('band.on: a handled key never reaches the page (keydown, keyup)', keys.length === 0, keys.join());
  await h.key('ArrowLeft');
  got = await page.evaluate(() => window.got.splice(0));
  keys = await pageKeys();
  check('band.on: an unhandled key goes on to the page', got.join() === 'A left keydown' && keys.join() === 'window ArrowLeft,document ArrowLeft,keyup ArrowLeft', `${got} / ${keys}`);
  await page.keyboard.press('Shift+ArrowDown');
  got = await page.evaluate(() => window.got.splice(0));
  keys = await pageKeys();
  check('band.on: a modified key is not the band\'s', got.length === 0 && keys.includes('document ArrowDown'), `${got} / ${keys}`);
  await page.evaluate(() => {
    window.offB = window.lumen.band.on((key) => { window.got.push(`B ${key}`); return key === 'down'; });
    window.offC = window.lumen.band.on(() => { throw new Error('a site script bug'); });
  });
  await h.key('ArrowDown');
  got = await page.evaluate(() => window.got.splice(0));
  check('band.on: the latest handler is asked first; one that throws is skipped', got.join() === 'B down', got.join());
  check('band.on: a throwing handler is logged as [Lumen]', logs.some((l) => l.startsWith('warning [Lumen] band handler failed on down')), logs.join(' | '));
  await h.key('ArrowRight');
  got = await page.evaluate(() => window.got.splice(0));
  keys = await pageKeys();
  check('band.on: a key no handler takes still reaches the page', got.join() === 'B right,A right keydown' && keys.includes('document ArrowRight'), `${got} / ${keys}`);
  await page.evaluate(() => { window.__sent.length = 0; });
  await h.back();
  got = await page.evaluate(() => window.got.splice(0));
  keys = await pageKeys();
  let sent = await h.sent();
  check('band.on: back goes to the handlers first, without an event', got.join() === 'B back,A back -', got.join());
  check('band.on: a handled back sends no Escape and tells the host backResult(true)',
    !keys.some((k) => k.includes('Escape')) && sent.some((m) => m.type === 'backResult' && m.handled === true), `${keys} / ${JSON.stringify(sent)}`);
  await page.focus('#field');
  await page.evaluate(() => { window.__sent.length = 0; });
  await pageKeys();
  await h.key('Enter');
  got = await page.evaluate(() => window.got.splice(0));
  keys = await pageKeys();
  sent = await h.sent();
  check('band.on: Enter on a text field is the field\'s (the keyboard\'s Enter), not offered',
    got.length === 0 && keys.includes('document Enter') && sent.length === 0, `${got} / ${keys} / ${JSON.stringify(sent)}`);
  await page.evaluate(() => document.activeElement.blur());
  await pageKeys();
  await h.key('Enter');
  got = await page.evaluate(() => window.got.splice(0));
  keys = await pageKeys();
  check('band.on: Enter elsewhere is offered and taken', got.join() === 'B enter,A enter keydown' && keys.length === 0, `${got} / ${keys}`);
  // The navigation comes after the handlers.
  await h.enableNav();
  await h.key('ArrowDown');
  let s = await h.state();
  check('band.on: a key a handler takes doesn\'t move the navigation', s.id === null, JSON.stringify(s));
  await page.evaluate(() => { window.offA(); window.offB(); window.offC(); window.got.length = 0; });
  await pageKeys();
  await h.key('ArrowDown');
  s = await h.state();
  got = await page.evaluate(() => window.got.splice(0));
  keys = await pageKeys();
  check('band.on: off() removes a handler; the page and the navigation get the key again', got.length === 0 && keys.includes('document ArrowDown') && s.id === 'one', `${got} / ${keys} / ${s.id}`);
  await page.evaluate(() => { window.__sent.length = 0; });
  await h.back();
  keys = await pageKeys();
  sent = await h.sent();
  check('back: without handlers the page gets Escape and the host backResult(false)',
    keys.includes('document Escape') && sent.some((m) => m.type === 'backResult' && m.handled === false), `${keys} / ${JSON.stringify(sent)}`);
  await page.close();
}

async function follow(context) {
  const { page, h } = await open(context, 'follow.html');
  await page.evaluate(() => window.lumen.highlight(document.getElementById('target')));
  await h.frame();
  let s = await h.state();
  check('highlight: lumen.highlight rings the element; highlighted() returns it', s.id === 'target' && ringAround(s.ring, await h.rectOf('#target')), JSON.stringify(s));
  await page.evaluate(() => window.scrollTo(0, 150));
  await h.frame();
  s = await h.state();
  const moved = await h.rectOf('#target');
  check('highlight: follows the element when the page scrolls', s.scrollY === 150 && ringAround(s.ring, moved), JSON.stringify({ s, moved }));
  await page.evaluate(() => { document.getElementById('target').style.visibility = 'hidden'; });
  await h.frame();
  s = await h.state();
  check('highlight: clears when the element turns invisible', s.id === null && s.ring === null, JSON.stringify(s));
  await page.evaluate(() => {
    const target = document.getElementById('target');
    target.style.visibility = '';
    window.lumen.highlight(target);
  });
  await h.frame();
  await page.evaluate(() => document.getElementById('target').remove());
  await h.frame();
  s = await h.state();
  check('highlight: clears when the element leaves the page', s.id === null && s.ring === null, JSON.stringify(s));
  await h.enableNav();
  await h.key('ArrowDown');
  s = await h.state();
  check('highlight: the next arrow starts from the first visible item', s.id === 'second', JSON.stringify(s));
  await page.evaluate(() => window.lumen.nav.clear());
  await h.frame();
  s = await h.state();
  check('nav.clear(): clears the ring', s.id === null && s.ring === null, JSON.stringify(s));
  const moved2 = await page.evaluate(() => [window.lumen.nav.move('down'), window.lumen.nav.move('sideways')]);
  s = await h.state();
  check('nav.move(): moves from script, false for an unknown direction', moved2[0] === true && moved2[1] === false && s.id === 'second', JSON.stringify({ moved2, s }));
  await page.close();
}

async function strictCsp(context, out) {
  const { page, h } = await open(context, 'csp-page.html');
  const before = await page.evaluate(() => ({ ran: window.pageScriptRan, violations: window.__violations.length, color: getComputedStyle(document.getElementById('inline')).color }));
  check('csp: the strict policy is on (the page\'s inline style blocked)', before.ran === true && before.violations >= 1 && before.color !== 'rgb(255, 0, 0)', JSON.stringify(before));
  await page.evaluate(() => {
    window.lumen.highlight(document.getElementById('like'));
    window.lumen.toast('Liked', { icon: 'heart-filled' });
  });
  await h.frame();
  const look = await page.evaluate(() => {
    const pick = (el, names) => { const cs = getComputedStyle(el); return Object.fromEntries(names.map((n) => [n, cs.getPropertyValue(n)])); };
    const ring = document.querySelector('[data-lumen="highlight"]');
    const toast = document.querySelector('[data-lumen="toast"]');
    const path = toast.querySelector('svg path');
    const tr = toast.getBoundingClientRect();
    return {
      ring: pick(ring, ['position', 'border-top-width', 'border-top-style', 'border-top-color', 'border-top-left-radius', 'box-shadow', 'background-color', 'pointer-events', 'z-index', 'padding-top']),
      ringHidden: ring.getAttribute('aria-hidden'),
      ringParent: ring.parentNode === document.documentElement,
      ringBorder: ring.style.getPropertyValue('border-top-width'),
      toastBorder: toast.style.getPropertyValue('border-top-width'),
      toast: pick(toast, ['position', 'top', 'height', 'background-color', 'border-top-width', 'border-top-color', 'border-top-left-radius', 'padding-left', 'padding-right', 'font-size', 'font-weight', 'color', 'z-index', 'pointer-events']),
      toastRole: toast.getAttribute('role'),
      toastCentre: (tr.left + tr.right) / 2,
      toastText: toast.textContent,
      icon: path && { fill: path.getAttribute('fill'), stroke: path.getAttribute('stroke'), width: path.getAttribute('stroke-width'), ns: path.namespaceURI },
      violations: window.__violations.length,
    };
  });
  const ring = look.ring;
  check('csp: the ring has the approved look (3 px white 0.86, radius 16, black 4 px halo, unfilled)',
    ring.position === 'fixed' && look.ringBorder === '3px' && ['3px', '2.5px'].includes(ring['border-top-width']) && ring['border-top-style'] === 'solid' && ring['border-top-color'] === 'rgba(255, 255, 255, 0.86)' &&
    ring['border-top-left-radius'] === '16px' && ring['box-shadow'] === 'rgb(0, 0, 0) 0px 0px 0px 4px' && ring['background-color'] === 'rgba(0, 0, 0, 0)' &&
    ring['pointer-events'] === 'none' && ring['z-index'] === '2147483647' && ring['padding-top'] === '0px', JSON.stringify(ring));
  check('csp: the ring is on <html>, aria-hidden, 4 px outside the element', look.ringParent && look.ringHidden === 'true' && ringAround((await h.state()).ring, await h.rectOf('#like')));
  const t = look.toast;
  check('csp: the toast has the approved look (black pill, 2 px border 0.43, 48 high, 22 px 500 white)',
    t.position === 'fixed' && t.top === '24px' && t.height === '48px' && t['background-color'] === 'rgb(0, 0, 0)' && look.toastBorder === '2px' && ['2px', '1.25px'].includes(t['border-top-width']) &&
    t['border-top-color'] === 'rgba(255, 255, 255, 0.43)' && t['border-top-left-radius'] === '9999px' && t['padding-left'] === '22px' && t['padding-right'] === '22px' &&
    t['font-size'] === '22px' && t['font-weight'] === '500' && t.color === 'rgb(255, 255, 255)' && t['pointer-events'] === 'none' && Math.abs(look.toastCentre - 300) < 1,
    JSON.stringify(t));
  check('csp: the toast has a filled heart (SVG, white, stroke 2.2) and its text', look.toastRole === 'status' && look.toastText === 'Liked' &&
    look.icon && look.icon.fill === '#fff' && look.icon.stroke === '#fff' && look.icon.width === '2.2' && look.icon.ns === 'http://www.w3.org/2000/svg', JSON.stringify(look.icon));
  check('csp: no policy violation from Lumen\'s overlays', look.violations === before.violations, `${before.violations} -> ${look.violations}`);
  await page.screenshot({ path: path.join(out, 'nav-fixture-csp-toast-and-ring.png') });
  await page.evaluate(() => window.lumen.toast('+10 s', { icon: 'forward', center: true, ms: 400 }));
  const chip = await page.evaluate(() => {
    const all = document.querySelectorAll('[data-lumen="toast"]');
    const el = all[0];
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return { count: all.length, height: cs.height, font: cs.fontSize, cx: (r.left + r.right) / 2, cy: (r.top + r.bottom) / 2, text: el.textContent };
  });
  check('toast: a new one replaces the old; center:true is the 64 px chip in the middle', chip.count === 1 && chip.text === '+10 s' && chip.height === '64px' && chip.font === '28px' &&
    Math.abs(chip.cx - 300) < 1 && Math.abs(chip.cy - 300) < 1, JSON.stringify(chip));
  await page.screenshot({ path: path.join(out, 'nav-fixture-csp-center-chip.png') });
  await page.waitForTimeout(600);
  check('toast: gone after options.ms', await page.evaluate(() => !document.querySelector('[data-lumen="toast"]')));
  await page.evaluate(() => window.lumen.toast('Unliked', { icon: 'heart' }));
  await page.waitForTimeout(1200);
  const still = await page.evaluate(() => !!document.querySelector('[data-lumen="toast"]'));
  await page.waitForTimeout(500);
  const gone = await page.evaluate(() => !document.querySelector('[data-lumen="toast"]'));
  check('toast: shown about 1500 ms by default', still && gone, `${still} ${gone}`);
  await page.close();
}

export async function run(browser, out) {
  const context = await newContext(browser);
  for (const part of [gridAndDisabled, list, inner, nested, picture, clicks, game, band, follow, strictCsp]) {
    try {
      await part(context, out);
    } catch (e) {
      check(`${part.name}: ran to the end`, false, e.stack || String(e));
    }
  }
  await context.close();
}
