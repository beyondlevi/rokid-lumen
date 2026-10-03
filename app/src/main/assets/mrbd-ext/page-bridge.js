// GeckoView has no addJavascriptInterface: this stands in for WebAppActivity's `MrbdHost`,
// passing calls to content.js by window messages (the app's replies come back the same way).
(function () {
  if (window.MrbdHost) return;
  var back = false;
  var keyboard = false;
  function send(message) { window.postMessage({ __mrbdToHost: message }, '*'); }
  window.addEventListener('message', function (event) {
    var data = event.data && event.data.__mrbdFromHost;
    if (!data) return;
    if (data.type === 'canGoBack') back = !!data.value;
    if (data.type === 'phoneKeyboard') keyboard = !!data.value;
    if (data.type === 'keyboardInput' && window.__mrbdKeyboardInput) window.__mrbdKeyboardInput(data.text);
    if (data.type === 'keyboardSync' && window.__mrbdKeyboardSync) window.__mrbdKeyboardSync();
    if (data.type === 'back' && window.__mrbdBack) window.__mrbdBack();
    if (data.type === 'speech' && window.__mrbdSpeech) window.__mrbdSpeech(data.id, data.event, data.code);
    if (data.type === 'composerInput' && window.__mrbdComposerInput) window.__mrbdComposerInput(data.text);
    if (data.type === 'composerClose' && window.__mrbdComposerClose) window.__mrbdComposerClose();
    if (data.type === 'keyboardWanted' && window.__mrbdKeyboardWanted) window.__mrbdKeyboardWanted();
    if (data.type === 'config' && window.__lumenConfig) window.__lumenConfig(data.id, data.values);
    if (data.type === 'configChanged' && window.__lumenConfigChanged) window.__lumenConfigChanged(data.values);
    if (data.type === 'audio' && window.__lumenAudio) window.__lumenAudio(data.event);
  });
  window.MrbdHost = {
    canGoBack: function () { return back; },
    install: function (url, name) { send({ type: 'install', url: url, name: name }); },
    speak: function (id, text, lang, rate, pitch) { send({ type: 'speak', id: id, text: text, lang: lang, rate: rate, pitch: pitch }); },
    cancelSpeech: function () { send({ type: 'cancelSpeech' }); },
    backResult: function (handled) { send({ type: 'backResult', handled: !!handled }); },
    openComposer: function (value, multiline) { send({ type: 'openComposer', value: value, multiline: !!multiline }); },
    noTextField: function () { send({ type: 'noTextField' }); },
    phoneKeyboard: function () { return keyboard; },
    textFocus: function (value, type, multiline, label, reason) {
      send({ type: 'textFocus', value: value, fieldType: type, multiline: !!multiline, label: label, reason: reason });
    },
    textBlur: function () { send({ type: 'textBlur' }); },
    getConfig: function (id) { send({ type: 'getConfig', id: id }); },
    audio: function (json) { send({ type: 'audio', message: JSON.parse(json) }); }
  };
})();
