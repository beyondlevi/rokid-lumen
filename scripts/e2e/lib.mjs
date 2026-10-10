// Shared by the e2e checks: Firefox set up like the glasses' GeckoView (600x600 CSS px at a
// device pixel ratio of 0.8, Android's Gecko user agent) with the extension's page.js injected
// before the page's scripts, as Gecko's MAIN-world content script is.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const root = path.resolve(here, '../..');
export const PAGE_JS = path.join(root, 'app/src/main/assets/mrbd-ext/page.js');
const FIXTURES = path.join(here, 'fixtures');
export const USER_AGENT = 'Mozilla/5.0 (Android 12; Mobile; rv:144.0) Gecko/144.0 Firefox/144.0';

// Playwright isn't a dependency of this repository: `npm i -g playwright` or NODE_PATH pointing
// at a node_modules that has it (require() honours NODE_PATH, ES imports don't).
export function playwright() {
  const require = createRequire(import.meta.url);
  try {
    return require('playwright');
  } catch (e) {
    console.error('Playwright not found: install it, or set NODE_PATH to a node_modules that has it.');
    throw e;
  }
}

export function outDir() {
  const dir = process.env.LUMEN_E2E_OUT || path.join(process.env.TMPDIR || '/tmp', 'lumen-e2e');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

let failures = 0;
let passes = 0;
export function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ` (${detail})` : ''}`);
  if (ok) passes++;
  else failures++;
  return ok;
}
export const results = () => ({ passes, failures });

export async function launch() {
  const { firefox } = playwright();
  // Dark pages, as Lumen's GeckoView asks every site (preferredColorScheme sets this pref).
  return firefox.launch({ firefoxUserPrefs: { 'layout.css.devPixelsPerPx': '0.8', 'layout.css.prefers-color-scheme.content-override': 0 } });
}

// Records what the page sends the host (page-bridge.js posts it as __mrbdToHost) and the CSP
// violations, from before the page's own scripts.
const RECORDER = `
  window.__sent = [];
  window.__violations = [];
  window.addEventListener('message', function (event) {
    if (event.data && event.data.__mrbdToHost) window.__sent.push(event.data.__mrbdToHost);
  });
  document.addEventListener('securitypolicyviolation', function (event) {
    window.__violations.push(event.violatedDirective + ' ' + (event.blockedURI || ''));
  });
`;

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
// A strict policy, as Instagram's and YouTube's: no inline script or style at all.
export const STRICT_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:";

export async function newContext(browser) {
  const context = await browser.newContext({ viewport: { width: 600, height: 600 }, userAgent: USER_AGENT });
  await context.addInitScript({ path: PAGE_JS });
  await context.addInitScript({ content: RECORDER });
  // Fixtures on https://lumen.test/; a name starting with "csp-" gets the strict policy.
  await context.route('https://lumen.test/**', (route) => {
    const name = new URL(route.request().url()).pathname.slice(1) || 'index.html';
    const file = path.join(FIXTURES, name);
    if (!file.startsWith(FIXTURES) || !fs.existsSync(file)) return route.fulfill({ status: 404, body: 'missing' });
    const headers = { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' };
    if (path.basename(file).startsWith('csp-')) headers['content-security-policy'] = STRICT_CSP;
    return route.fulfill({ status: 200, headers, body: fs.readFileSync(file) });
  });
  return context;
}

// Page helpers: the host's messages, the band's keys and what the ring shows.
export function helpers(page) {
  const frame = () => page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const fromHost = (message) => page.evaluate((m) => window.postMessage({ __mrbdFromHost: m }, '*'), message);
  return {
    frame,
    fromHost,
    async enableNav() {
      await fromHost({ type: 'bandNavigation', value: true });
      await page.waitForFunction(() => window.lumen && window.lumen.nav.enabled);
    },
    async key(name, times = 1) {
      for (let i = 0; i < times; i++) {
        await page.keyboard.press(name);
        await frame();
      }
    },
    async back() {
      await fromHost({ type: 'back' });
      await page.waitForTimeout(250);
    },
    // The highlighted element's id (or text), the ring's box and the scroll positions.
    state: () => page.evaluate(() => {
      const el = window.lumen.highlighted();
      const ring = document.querySelector('[data-lumen="highlight"]');
      const shown = !!ring && getComputedStyle(ring).display !== 'none';
      const r = shown ? ring.getBoundingClientRect() : null;
      const active = document.activeElement;
      return {
        id: el ? (el.id || el.textContent.trim().slice(0, 40)) : null,
        tag: el ? el.tagName.toLowerCase() : null,
        href: el && el.href ? el.href : null,
        ring: r ? { left: r.left, top: r.top, width: r.width, height: r.height } : null,
        scrollY: window.scrollY,
        active: active && active !== document.body ? active.id || active.tagName.toLowerCase() : null,
      };
    }),
    rectOf: (selector) => page.evaluate((s) => {
      const r = document.querySelector(s).getBoundingClientRect();
      return { left: r.left, top: r.top, width: r.width, height: r.height };
    }, selector),
    sent: () => page.evaluate(() => window.__sent.slice()),
  };
}

// The ring is 7 px outside the element (4 px gap, 3 px border), kept inside the view.
export function ringAround(ring, rect, slack = 1.5) {
  if (!ring) return false;
  const want = {
    left: Math.max(0, rect.left - 7),
    top: Math.max(0, rect.top - 7),
    right: Math.min(600, rect.left + rect.width + 7),
    bottom: Math.min(600, rect.top + rect.height + 7),
  };
  return Math.abs(ring.left - want.left) <= slack && Math.abs(ring.top - want.top) <= slack &&
    Math.abs(ring.left + ring.width - want.right) <= slack && Math.abs(ring.top + ring.height - want.bottom) <= slack;
}
