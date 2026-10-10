// Tests for app/src/main/assets/mrbd-ext/background.js: `node --test scripts/test-*.mjs`.
// The script runs in a fresh context with a fake `browser` (native ports, content scripts,
// tabs, proxy) and a clock the test moves, the way Gecko runs the extension's background.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../app/src/main/assets/mrbd-ext/background.js', import.meta.url), 'utf8');

function listeners() {
  const list = [];
  return { list, addListener: (fn) => list.push(fn) };
}

function background({ refuseWorld = false } = {}) {
  let now = 1000;
  let timers = [];
  const ports = [];
  const registered = [];
  const tabMessages = [];
  const pageListeners = listeners();
  const browser = {
    runtime: {
      onMessage: pageListeners,
      connectNative(app) {
        const port = { app, sent: [], onMessage: listeners(), onDisconnect: listeners() };
        port.postMessage = (message) => port.sent.push(JSON.parse(JSON.stringify(message)));
        // What the app does: refuse (no delegate yet), or speak on it, or close it.
        port.refuse = () => port.onDisconnect.list.forEach((fn) => fn(port));
        port.fromApp = (message) => port.onMessage.list.forEach((fn) => fn(message));
        ports.push(port);
        return port;
      },
      sendNativeMessage: async () => '',
    },
    tabs: { sendMessage: async (tabId, message) => { tabMessages.push({ tabId, message }); } },
    contentScripts: {
      async register(options) {
        if (refuseWorld && 'world' in options) throw new Error('Unexpected property "world"');
        const registration = { options, active: true };
        registration.unregister = async () => { registration.active = false; };
        registered.push(registration);
        return registration;
      },
    },
    proxy: { onRequest: listeners() },
  };
  const context = {
    browser,
    console: { warn: () => {} },
    URL,
    Date: { now: () => now },
    setTimeout: (fn, ms) => { timers.push({ at: now + ms, fn }); return timers.length; },
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  return {
    ports,
    registered,
    tabMessages,
    page: (message, sender) => pageListeners.list.forEach((fn) => fn(message, sender)),
    // Moves the clock, running the timers that come due on the way.
    advance(ms) {
      const end = now + ms;
      for (;;) {
        timers.sort((a, b) => a.at - b.at);
        const next = timers[0];
        if (!next || next.at > end) break;
        timers.shift();
        now = next.at;
        next.fn();
      }
      now = end;
    },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('connects at startup and retries while the app refuses, for 10 s at most', () => {
  const bg = background();
  assert.equal(bg.ports.length, 1);
  assert.equal(bg.ports[0].app, 'browser');
  bg.ports[0].refuse();
  bg.advance(250);
  assert.equal(bg.ports.length, 2);
  // Refused every time: about 40 tries, then none until a page speaks.
  for (let i = 0; i < 60; i += 1) {
    bg.ports[bg.ports.length - 1].refuse();
    bg.advance(250);
  }
  const tries = bg.ports.length;
  assert.ok(tries >= 38 && tries <= 42, `tries: ${tries}`);
  bg.advance(60000);
  assert.equal(bg.ports.length, tries);
  bg.page({ type: 'hello' }, { tab: { id: 3 }, url: 'https://a.example/' });
  assert.equal(bg.ports.length, tries + 1);
});

test('a port the app took and closed is opened again', () => {
  const bg = background();
  const first = bg.ports[0];
  first.fromApp({ type: 'siteScripts', key: '', scripts: [] });
  bg.advance(1000);
  assert.equal(bg.ports.length, 1);
  first.refuse();
  bg.advance(250);
  assert.equal(bg.ports.length, 2);
});

test('site scripts are registered in the page world, replaced, kept and cleared', async () => {
  const bg = background();
  const port = bg.ports[0];
  const ig = { matches: ['https://www.instagram.com/*'], js: 'window.ig = 1', css: 'a{}' };
  port.fromApp({ type: 'siteScripts', key: 'ig@1#aa', scripts: [ig] });
  await settle();
  assert.equal(bg.registered.length, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(bg.registered[0].options)), {
    world: 'MAIN',
    matches: ['https://www.instagram.com/*'],
    js: [{ code: 'window.ig = 1' }],
    css: [{ code: 'a{}' }],
    runAt: 'document_start',
    allFrames: false,
  });
  assert.deepEqual(port.sent, [{ type: 'siteScriptsReady', key: 'ig@1#aa', ok: true }]);

  // The same set again (a new port): nothing registered twice.
  port.fromApp({ type: 'siteScripts', key: 'ig@1#aa', scripts: [ig] });
  await settle();
  assert.equal(bg.registered.length, 1);
  assert.equal(port.sent.length, 2);

  // Another app's set replaces it; two sets in a row are applied in order.
  port.fromApp({ type: 'siteScripts', key: 'yt@1#bb', scripts: [{ matches: ['https://m.youtube.com/*'], js: 'yt()', css: '' }] });
  port.fromApp({ type: 'siteScripts', key: '', scripts: [] });
  await settle();
  await settle();
  assert.equal(bg.registered.length, 2);
  assert.equal(bg.registered[0].active, false);
  assert.equal(bg.registered[1].active, false);
  assert.equal(bg.registered[1].options.css.length, 0);
  assert.deepEqual(port.sent.slice(2).map((m) => m.key), ['yt@1#bb', '']);
  assert.ok(port.sent.every((m) => m.ok));
});

test('without the page world, the isolated one, reported as not ok', async () => {
  const bg = background({ refuseWorld: true });
  const port = bg.ports[0];
  port.fromApp({ type: 'siteScripts', key: 'k', scripts: [{ matches: ['https://x.example/*'], js: 'x()', css: '' }] });
  await settle();
  assert.equal(bg.registered.length, 1);
  assert.equal('world' in bg.registered[0].options, false);
  assert.deepEqual(port.sent, [{ type: 'siteScriptsReady', key: 'k', ok: false, error: 'world' }]);
});

test('page messages still go to the app with their tab, and the app answers that tab', async () => {
  const bg = background();
  const port = bg.ports[0];
  bg.page({ type: 'hello' }, { tab: { id: 7 }, url: 'https://a.example/x' });
  assert.deepEqual(port.sent, [{ type: 'hello', tabId: 7, sender: 'https://a.example/x' }]);
  // A frame without a tab doesn't speak for a page.
  bg.page({ type: 'hello' }, {});
  assert.equal(port.sent.length, 1);
  port.fromApp({ type: 'canGoBack', value: true, tabId: 7 });
  await settle();
  assert.deepEqual(bg.tabMessages, [{ tabId: 7, message: { type: 'canGoBack', value: true } }]);
  assert.equal(bg.registered.length, 0);
});
