// Relays between the page (through content.js) and the app, which listens on the native
// app "browser" (GeckoWebEngine). Connects at startup, so the app can hand over the site
// scripts of the app in front before any page loads, on the first page message, and again
// after a disconnect. A port opened before the app set its delegate is closed at once: the
// connection is tried again every CONNECT_RETRY_MS for CONNECT_FOR_MS, then left to the next
// page message.
//
// Every page message goes to the app with the tab and the address of the page that sent it
// (from the browser, not from the page: the app acts only for the app's own origin), and the
// app's messages come back to that tab only (`tabId`), never to every tab. An app message
// without a tab is for this script (the site scripts).
let port = null;
const CONNECT_RETRY_MS = 250;
const CONNECT_FOR_MS = 10000;
let connectUntil = 0;
let connectTimer = null;

function connect() {
  if (port) return port;
  const opened = browser.runtime.connectNative('browser');
  port = opened;
  // The app speaks first on a port it took (the site scripts' set).
  let used = false;
  opened.onMessage.addListener((message) => {
    used = true;
    if (!message || typeof message !== 'object') return;
    if (typeof message.tabId !== 'number') {
      fromApp(message);
      return;
    }
    const tabId = message.tabId;
    delete message.tabId;
    browser.tabs.sendMessage(tabId, message).catch(() => {});
  });
  opened.onDisconnect.addListener(() => {
    if (port === opened) port = null;
    // A port the app had taken: open another. A refused one is left to the retries going on
    // (or to the next page message), so an app that never takes it isn't asked forever.
    if (used) keepConnecting();
  });
  return opened;
}

/** Connects now, and again while the port keeps closing, for CONNECT_FOR_MS from now. */
function keepConnecting() {
  connectUntil = Date.now() + CONNECT_FOR_MS;
  if (connectTimer === null) tryConnect();
}

function tryConnect() {
  connectTimer = null;
  try {
    if (!port) connect();
  } catch (error) {
    port = null;
  }
  // A refused port closes after this returns (onDisconnect): check again a little later.
  connectTimer = setTimeout(() => {
    connectTimer = null;
    if (!port && Date.now() < connectUntil) tryConnect();
  }, CONNECT_RETRY_MS);
}

browser.runtime.onMessage.addListener((message, sender) => {
  // Only a page's content script (a tab's top frame) speaks for a page.
  if (!message || typeof message !== 'object' || !sender.tab || typeof sender.tab.id !== 'number') return;
  connect().postMessage(Object.assign({}, message, { tabId: sender.tab.id, sender: sender.url || '' }));
});

function fromApp(message) {
  if (message.type === 'siteScripts') setSiteScripts(message);
}

// Site scripts: the scripts of the app in front (an online app's package, lumen_scripts), run
// in the pages of the sites they name, in the page's own world at document_start. The app
// sends the whole set whenever the app in front changes, named by a key; each set replaces the
// one before (none clears it) and is acknowledged with that key, so the app knows when its
// first page may load. Sets are applied one at a time, in the order they came.
let registered = [];
let registeredKey = null;
let applying = Promise.resolve();

function setSiteScripts(message) {
  const key = String(message.key || '');
  const scripts = Array.isArray(message.scripts) ? message.scripts : [];
  applying = applying
    .then(() => replaceSiteScripts(key, scripts))
    .catch((error) => ({ ok: false, error: String((error && error.message) || error) }))
    .then((result) => {
      try {
        connect().postMessage(Object.assign({ type: 'siteScriptsReady', key }, result));
      } catch (error) {
        // No port: the app sends the set again when one opens.
      }
    });
}

async function replaceSiteScripts(key, scripts) {
  // The same set again (the port was opened again): already in place.
  if (key === registeredKey) return { ok: true };
  const previous = registered;
  registered = [];
  registeredKey = null;
  await Promise.all(previous.map((registration) => registration.unregister().catch(() => {})));
  let error = null;
  for (const script of scripts) {
    const options = {
      matches: script.matches,
      js: script.js ? [{ code: script.js }] : [],
      css: script.css ? [{ code: script.css }] : [],
      runAt: 'document_start',
      allFrames: false,
    };
    try {
      registered.push(await browser.contentScripts.register(Object.assign({ world: 'MAIN' }, options)));
    } catch (refused) {
      // A Gecko without the page's world here: the isolated world still gets the page's DOM.
      console.warn('Site script refused in the page\'s world (' + refused.message + '); trying the isolated world');
      try {
        registered.push(await browser.contentScripts.register(options));
        error = error || 'world';
      } catch (failed) {
        console.warn('Site script refused: ' + failed.message);
        error = failed.message || 'refused';
      }
    }
  }
  if (error) return { ok: false, error };
  registeredKey = key;
  return { ok: true };
}

keepConnecting();

// Online apps reach the internet through the phone when the glasses have none of their own:
// the app (PhoneInternet) says which proxy. Asked for every request (a cached answer sent the
// first page load direct, measured: the answer changes the moment the phone's network is up);
// concurrent requests share one question. The offline apps' loopback servers always go direct.
// The question has a deadline: an answer that never comes (no native app listening) would
// otherwise hold every request, since they all wait on the same question.
let pending = null;
const PROXY_ANSWER_MS = 3000;
// A proxy answer is reused this long: a page loading dozens of pictures asked the app once per
// picture. Only the phone's proxy is kept; "direct" is asked again every time, so the first
// request after the phone's network comes up already takes it.
const PROXY_KEEP_MS = 5000;
let kept = null;
let keptAt = 0;

function askProxy() {
  return Promise.race([
    browser.runtime.sendNativeMessage('browser', { type: 'proxy' }),
    new Promise((resolve, reject) => setTimeout(() => reject(new Error('no answer')), PROXY_ANSWER_MS)),
  ]);
}

function currentProxy() {
  if (kept && Date.now() - keptAt < PROXY_KEEP_MS) return kept;
  if (!pending) {
    pending = askProxy().then((answer) => {
      const [host, port] = String(answer || '').split(':');
      // failoverTimeout: Gecko sets a proxy aside for this long after one failed connection
      // (default 30 min), sending requests direct meanwhile, which has no internet here
      // (measured: one failure while joining the phone's network broke an app's requests).
      const proxy = host && port ? { type: 'http', host, port: Number(port), failoverTimeout: 1 } : { type: 'direct' };
      kept = proxy.type === 'http' ? proxy : null;
      keptAt = Date.now();
      return proxy;
    }, () => { kept = null; return { type: 'direct' }; }).finally(() => { pending = null; });
  }
  return pending;
}

browser.proxy.onRequest.addListener((request) => {
  const host = new URL(request.url).hostname;
  if (host === '127.0.0.1' || host === 'localhost') return { type: 'direct' };
  return currentProxy();
}, { urls: ['<all_urls>'] });
