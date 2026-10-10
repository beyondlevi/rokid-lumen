// The generic navigation on real sites, logged out, as an online app shows them: m.youtube.com's
// search results (video cards under a fixed top bar and bottom tabs) and a long Wikipedia article.
import path from 'node:path';
import { check, helpers, newContext } from './lib.mjs';

// What the ring is on: the element, the ring's box, the scroll, and whether the ring holds every
// link to the same address that shows (a card's thumbnail and title).
const RING_STATE = () => {
  const el = window.lumen.highlighted();
  const ring = document.querySelector('[data-lumen="highlight"]');
  const r = ring && getComputedStyle(ring).display !== 'none' ? ring.getBoundingClientRect() : null;
  let holdsSame = false;
  if (el && r && el.href) {
    holdsSame = [...document.querySelectorAll('a[href]')]
      .filter((a) => a.href === el.href)
      .map((a) => a.getBoundingClientRect())
      .filter((a) => a.bottom > 0 && a.top < innerHeight && a.width && a.height)
      .every((a) => a.left >= r.left && a.right <= r.right && a.top >= r.top && a.bottom <= r.bottom);
  }
  return {
    tag: el ? el.tagName.toLowerCase() : null,
    href: el ? el.getAttribute('href') : null,
    label: el ? (el.getAttribute('aria-label') || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 50) : null,
    ring: r ? { left: Math.round(r.left), top: Math.round(r.top), width: Math.round(r.width), height: Math.round(r.height) } : null,
    scrollY: Math.round(scrollY),
    holdsSame,
  };
};

// The time one move takes in the page (the key press's own work), in ms.
const timedMove = (dir) => {
  const start = performance.now();
  window.lumen.nav.move(dir);
  return performance.now() - start;
};

const onScreen = (ring) => !!ring && ring.top >= 0 && ring.top + ring.height <= 600;

async function youtube(context, out) {
  const page = await context.newPage();
  const h = helpers(page);
  await page.goto('https://m.youtube.com/results?search_query=lofi', { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForSelector('a[href^="/watch"]', { timeout: 30000 });
  await page.waitForTimeout(2500);
  await h.enableNav();
  const steps = [];
  let firstCard = null;
  let scrolledCard = null;
  for (let i = 0; i < 12; i++) {
    await h.key('ArrowDown');
    await page.waitForTimeout(150);
    const s = await page.evaluate(RING_STATE);
    steps.push(s);
    const card = s.href && s.href.startsWith('/watch');
    if (card && !firstCard) {
      firstCard = s;
      await page.screenshot({ path: path.join(out, 'nav-youtube-search-first-card.png') });
    } else if (card && firstCard && s.scrollY > firstCard.scrollY + 200 && !scrolledCard) {
      scrolledCard = s;
      await page.screenshot({ path: path.join(out, 'nav-youtube-search-scrolled-card.png') });
    }
  }
  const trail = steps.map((s) => `${s.href ? s.href.slice(0, 20) : s.label}@${s.scrollY}`).join(' > ');
  check('youtube: the ring lands on a video card (thumbnail and title in one ring)', !!firstCard && firstCard.holdsSame && firstCard.ring.height > 300, JSON.stringify(firstCard));
  check('youtube: down scrolls the results to the next card, ring on screen', !!scrolledCard && onScreen(scrolledCard.ring) && scrolledCard.holdsSame, trail);
  const cards = steps.filter((s) => s.href && s.href.startsWith('/watch'));
  check('youtube: down goes card to card, never onto the fixed bottom tabs', cards.length >= 6 && !steps.some((s) => /^(Home|Shorts|You|Subscriptions)$/.test(s.label || '')), trail);
  const times = [];
  for (let i = 0; i < 4; i++) times.push(await page.evaluate(timedMove, 'down'));
  check('youtube: a move takes little time (one query, capped checks)', Math.max(...times) < 60, times.map((t) => t.toFixed(1)).join(', ') + ' ms');
  // The results mix in channels and playlists: on to the next video before Enter.
  let before = await page.evaluate(RING_STATE);
  for (let i = 0; i < 6 && !(before.href && before.href.startsWith('/watch')); i++) {
    await h.key('ArrowDown');
    before = await page.evaluate(RING_STATE);
  }
  await h.key('Enter');
  let opened = false;
  try {
    await page.waitForURL(/\/watch\?/, { timeout: 15000 });
    opened = true;
  } catch (e) {}
  if (opened) {
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(out, 'nav-youtube-watch-opened.png') });
  }
  check('youtube: Enter opens the ringed video', opened && page.url().includes(before.href.split('&')[0]), `${before.href} -> ${page.url()}`);
  await page.close();
}

async function wikipedia(context, out) {
  const page = await context.newPage();
  const h = helpers(page);
  await page.goto('https://en.wikipedia.org/wiki/Glasses', { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForSelector('#content a[href^="/wiki/"], main a[href^="/wiki/"]', { timeout: 30000 });
  await page.waitForTimeout(1500);
  await h.enableNav();
  const steps = [];
  let firstLink = null;
  let scrolledLink = null;
  for (let i = 0; i < 14; i++) {
    await h.key('ArrowDown');
    const s = await page.evaluate(RING_STATE);
    steps.push(s);
    if (s.tag === 'a' && s.href && s.href.includes('/wiki/') && !firstLink) {
      firstLink = s;
      await page.screenshot({ path: path.join(out, 'nav-wikipedia-first-link.png') });
    } else if (s.tag === 'a' && s.scrollY > 300 && !scrolledLink && onScreen(s.ring)) {
      scrolledLink = s;
      await page.screenshot({ path: path.join(out, 'nav-wikipedia-scrolled-link.png') });
    }
  }
  const trail = steps.map((s) => `${s.label}@${s.scrollY}`).join(' > ');
  check('wikipedia: the ring lands on the article\'s links', !!firstLink && steps.filter((s) => s.tag === 'a').length >= 8, trail);
  check('wikipedia: down scrolls the article and keeps the ring on screen', !!scrolledLink && steps.every((s) => !s.ring || onScreen(s.ring)), trail);
  const ups = [];
  for (let i = 0; i < 3; i++) {
    await h.key('ArrowUp');
    ups.push(await page.evaluate(RING_STATE));
  }
  check('wikipedia: up goes back through the links', ups.every((s) => s.tag) && ups[ups.length - 1].scrollY <= steps[steps.length - 1].scrollY,
    ups.map((s) => `${s.label}@${s.scrollY}`).join(' > '));
  const target = await page.evaluate(() => window.lumen.highlighted() && window.lumen.highlighted().href);
  // A text link opens another article; a picture's link opens the media viewer (#/media/...).
  const start = page.url();
  await h.key('Enter');
  let opened = false;
  try {
    await page.waitForURL((url) => url.href !== start, { timeout: 15000 });
    opened = true;
  } catch (e) {}
  check('wikipedia: Enter follows the ringed link', opened, `${target} -> ${page.url()}`);
  await page.close();
}

export async function run(browser, out) {
  const context = await newContext(browser);
  for (const part of [youtube, wikipedia]) {
    try {
      await part(context, out);
    } catch (e) {
      check(`${part.name}: ran to the end`, false, e.stack || String(e));
    }
  }
  await context.close();
}
