// Relays between the page (through content.js) and the app, which listens on the native
// app "browser" (GeckoWebEngine). Connects on the first page message, and again after
// a disconnect, so a port opened before the app set its delegate isn't lost.
//
// Every page message goes to the app with the tab and the address of the page that sent it
// (from the browser, not from the page: the app acts only for the app's own origin), and the
// app's messages come back to that tab only (`tabId`), never to every tab.
let port = null;

function connect() {
  if (port) return port;
  port = browser.runtime.connectNative('browser');
  port.onMessage.addListener((message) => {
    if (!message || typeof message.tabId !== 'number') return;
    const tabId = message.tabId;
    delete message.tabId;
    browser.tabs.sendMessage(tabId, message).catch(() => {});
  });
  port.onDisconnect.addListener(() => { port = null; });
  return port;
}

browser.runtime.onMessage.addListener((message, sender) => {
  // Only a page's content script (a tab's top frame) speaks for a page.
  if (!message || typeof message !== 'object' || !sender.tab || typeof sender.tab.id !== 'number') return;
  connect().postMessage(Object.assign({}, message, { tabId: sender.tab.id, sender: sender.url || '' }));
});

// Online apps reach the internet through the phone when the glasses have none of their own:
// the app (PhoneInternet) says which proxy. Asked for every request (a cached answer sent the
// first page load direct, measured: the answer changes the moment the phone's network is up);
// concurrent requests share one question. The offline apps' loopback servers always go direct.
// The question has a deadline: an answer that never comes (no native app listening) would
// otherwise hold every request, since they all wait on the same question.
let pending = null;
const PROXY_ANSWER_MS = 3000;

function askProxy() {
  return Promise.race([
    browser.runtime.sendNativeMessage('browser', { type: 'proxy' }),
    new Promise((resolve, reject) => setTimeout(() => reject(new Error('no answer')), PROXY_ANSWER_MS)),
  ]);
}

function currentProxy() {
  if (!pending) {
    pending = askProxy().then((answer) => {
      const [host, port] = String(answer || '').split(':');
      // failoverTimeout: Gecko sets a proxy aside for this long after one failed connection
      // (default 30 min), sending requests direct meanwhile, which has no internet here
      // (measured: one failure while joining the phone's network broke an app's requests).
      return host && port ? { type: 'http', host, port: Number(port), failoverTimeout: 1 } : { type: 'direct' };
    }, () => ({ type: 'direct' })).finally(() => { pending = null; });
  }
  return pending;
}

browser.proxy.onRequest.addListener((request) => {
  const host = new URL(request.url).hostname;
  if (host === '127.0.0.1' || host === 'localhost') return { type: 'direct' };
  return currentProxy();
}, { urls: ['<all_urls>'] });
