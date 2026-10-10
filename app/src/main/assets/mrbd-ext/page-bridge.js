// GeckoView has no addJavascriptInterface: this stands in for WebAppActivity's `MrbdHost`,
// passing calls to content.js by window messages (the app's replies come back the same way).
// Typing isn't here: Lumen's keyboard is the glasses' input method and types into the page's
// fields as any keyboard does.
(function () {
  if (window.MrbdHost) return;
  var back = false;
  function send(message) { window.postMessage({ __mrbdToHost: message }, '*'); }
  window.addEventListener('message', function (event) {
    var data = event.data && event.data.__mrbdFromHost;
    if (!data) return;
    if (data.type === 'canGoBack') back = !!data.value;
    if (data.type === 'back' && window.__mrbdBack) window.__mrbdBack();
    if (data.type === 'speech' && window.__mrbdSpeech) window.__mrbdSpeech(data.id, data.event, data.code);
    if (data.type === 'config' && window.__lumenConfig) window.__lumenConfig(data.id, data.values);
    if (data.type === 'configChanged' && window.__lumenConfigChanged) window.__lumenConfigChanged(data.values);
    if (data.type === 'audio' && window.__lumenAudio) window.__lumenAudio(data.event);
    if (data.type === 'bandNavigation' && window.__lumenBandNavigation) window.__lumenBandNavigation(data.value);
  });
  window.MrbdHost = {
    canGoBack: function () { return back; },
    install: function (url, name) { send({ type: 'install', url: url, name: name }); },
    speak: function (id, text, lang, rate, pitch) { send({ type: 'speak', id: id, text: text, lang: lang, rate: rate, pitch: pitch }); },
    cancelSpeech: function () { send({ type: 'cancelSpeech' }); },
    backResult: function (handled) { send({ type: 'backResult', handled: !!handled }); },
    getConfig: function (id) { send({ type: 'getConfig', id: id }); },
    audio: function (json) { send({ type: 'audio', message: JSON.parse(json) }); }
  };
})();
