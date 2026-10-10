// Injected by the Rokid host before a Meta Ray-Ban Display web app's own scripts run.
// It fills in what MRBD's runtime offers and the glasses' WebView (Chromium 95) lacks,
// through the `MrbdHost` bridge (WebAppActivity). Nothing here runs on a real MRBD.
(function () {
  if (window.__mrbdRokidHost) return;
  window.__mrbdRokidHost = true;
  var host = window.MrbdHost;

  // MRBD asks for sensor access with the iOS-style static API; Android grants it silently.
  ['DeviceOrientationEvent', 'DeviceMotionEvent'].forEach(function (name) {
    var ctor = window[name];
    if (ctor && typeof ctor.requestPermission !== 'function') {
      ctor.requestPermission = function () { return Promise.resolve('granted'); };
    }
  });

  // MRBD's shell reads navigation.canGoBack (Navigation API, Chromium 102+). React DOM 19
  // treats any `navigation` object as the real API and listens on it, so this one is an
  // EventTarget with no current entry and no transition: React takes its History API path.
  if (!window.navigation) {
    var nav = new EventTarget();
    Object.defineProperty(nav, 'canGoBack', {
      get: function () { return host ? host.canGoBack() : history.length > 1; }
    });
    nav.canGoForward = false;
    nav.currentEntry = null;
    nav.transition = null;
    window.navigation = nav;
  }

  // Installing a web app adds it to the host's library.
  if (!navigator.install && host) {
    navigator.install = function (url, options) {
      host.install(String(url || location.href), (options && options.name) || document.title || '');
      return Promise.resolve();
    };
  }

  // Rokid Lumen's own API: the app's configuration, set from the phone (its manifest's
  // lumen_config). get() resolves to {key: value} (missing keys aren't set); onChange(cb)
  // calls cb with the new values whenever the phone changes them.
  if (!window.lumen && host && host.getConfig) {
    var configId = 1;
    var configPending = {};
    var configListeners = [];
    window.__lumenConfig = function (id, values) {
      var resolve = configPending[id];
      delete configPending[id];
      if (resolve) resolve(values || {});
    };
    window.__lumenConfigChanged = function (values) {
      configListeners.slice().forEach(function (cb) {
        try { cb(values || {}); } catch (e) { setTimeout(function () { throw e; }); }
      });
    };
    window.lumen = {
      config: {
        get: function () {
          return new Promise(function (resolve) {
            var id = configId++;
            configPending[id] = resolve;
            host.getConfig(id);
          });
        },
        onChange: function (cb) {
          if (typeof cb === 'function') configListeners.push(cb);
          return function () { configListeners = configListeners.filter(function (x) { return x !== cb; }); };
        }
      }
    };
  }

  // Rokid Lumen's audio API: the glasses silence a page's microphone, so the phone records the
  // glasses' mic and hands back an Ogg Opus voice note, and transcribes an audio with the
  // dictation engine chosen in the companion. record(options) resolves to a recording (onLevel,
  // onEnd, stop(), cancel()); transcribe(blob, options) to {text}, with options.onPartial(text)
  // as it grows. Failures are Errors with a `code` (busy, no-phone, unavailable, too-large,
  // unsupported-format, no-speech, engine, cancelled, timeout).
  if (window.lumen && host && host.audio && !window.lumen.audio) {
    var audioId = 1;
    var audioJobs = {};
    var MAX_RECORD_MS = 120000;
    var MAX_TRANSCRIBE_BYTES = 5 * 1024 * 1024;
    function audioError(code, message) {
      var error = new Error(message || code);
      error.code = code;
      return error;
    }
    function audioSend(message) { host.audio(JSON.stringify(message)); }
    function toBlob(base64, mime) {
      var raw = atob(base64);
      var bytes = new Uint8Array(raw.length);
      for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
      return new Blob([bytes], { type: mime });
    }
    function toBase64(blob) {
      return new Promise(function (resolve, reject) {
        var reader = new FileReader();
        reader.onload = function () { var url = String(reader.result); resolve(url.slice(url.indexOf(',') + 1)); };
        reader.onerror = function () { reject(audioError('unsupported-format', 'unreadable audio')); };
        reader.readAsDataURL(blob);
      });
    }
    window.__lumenAudio = function (event) {
      var job = event && audioJobs[event.id];
      if (!job) return;
      job.handle(event);
    };
    window.lumen.audio = {
      record: function (options) {
        var maxMs = Math.min(MAX_RECORD_MS, Math.max(1000, +(options && options.maxMs) || MAX_RECORD_MS));
        var id = 'r' + audioId++;
        return new Promise(function (resolve, reject) {
          var started = false;
          var stopping = null;
          // How it ended on its own (the length limit, an error): stop() answers with it.
          var ended = null;
          var recording = {
            // Also from the options, so nothing is missed between the start and the assignment.
            onLevel: (options && typeof options.onLevel === 'function') ? options.onLevel : null,
            onEnd: (options && typeof options.onEnd === 'function') ? options.onEnd : null,
            stop: function () {
              if (ended) return ended.result ? Promise.resolve(ended.result) : Promise.reject(ended.error);
              if (stopping) return stopping.promise;
              var d = {};
              d.promise = new Promise(function (res, rej) { d.resolve = res; d.reject = rej; });
              stopping = d;
              audioSend({ op: 'stop', id: id });
              return d.promise;
            },
            cancel: function () {
              if (!audioJobs[id]) return;
              delete audioJobs[id];
              audioSend({ op: 'cancel', id: id });
              if (stopping) stopping.reject(audioError('cancelled'));
            }
          };
          audioJobs[id] = {
            handle: function (event) {
              if (event.type === 'started') {
                if (!started) { started = true; resolve(recording); }
              } else if (event.type === 'level') {
                if (typeof recording.onLevel === 'function') recording.onLevel(+event.level || 0, +event.ms || 0);
              } else if (event.type === 'result') {
                delete audioJobs[id];
                var result = { blob: toBlob(event.data, event.mime), mimeType: event.mime, durationMs: +event.durationMs || 0 };
                if (stopping) stopping.resolve(result);
                else {
                  ended = { result: result };
                  if (typeof recording.onEnd === 'function') recording.onEnd('max', result);
                }
                if (!started) { started = true; resolve(recording); }
              } else if (event.type === 'error') {
                delete audioJobs[id];
                var error = audioError(event.code || 'unavailable', event.message);
                if (!started) return reject(error);
                if (stopping) stopping.reject(error);
                else {
                  ended = { error: error };
                  if (typeof recording.onEnd === 'function') recording.onEnd('error', undefined, error);
                }
              }
            }
          };
          audioSend({ op: 'record', id: id, maxMs: maxMs });
        });
      },
      transcribe: function (audio, options) {
        options = options || {};
        if (!(audio instanceof Blob)) return Promise.reject(audioError('unsupported-format', 'transcribe() takes a Blob'));
        if (audio.size > MAX_TRANSCRIBE_BYTES) return Promise.reject(audioError('too-large'));
        if (options.signal && options.signal.aborted) return Promise.reject(audioError('cancelled'));
        var id = 't' + audioId++;
        return toBase64(audio).then(function (data) {
          return new Promise(function (resolve, reject) {
            audioJobs[id] = {
              handle: function (event) {
                if (event.type === 'partial') {
                  if (typeof options.onPartial === 'function') options.onPartial(String(event.text || ''));
                } else if (event.type === 'transcript') {
                  delete audioJobs[id];
                  resolve({ text: String(event.text || '') });
                } else if (event.type === 'error') {
                  delete audioJobs[id];
                  reject(audioError(event.code, event.message));
                }
              }
            };
            if (options.signal) {
              options.signal.addEventListener('abort', function () {
                if (!audioJobs[id]) return;
                delete audioJobs[id];
                audioSend({ op: 'cancel', id: id });
                reject(audioError('cancelled'));
              });
            }
            audioSend({ op: 'transcribe', id: id, data: data, mime: audio.type || '', language: options.language || '' });
          });
        });
      }
    };

    // The standard APIs on the same path, so code written for a browser's microphone runs here
    // unchanged. getUserMedia({audio}) resolves to a real MediaStream fed live from the glasses'
    // microphone (16 kHz PCM from the phone, scheduled into a MediaStreamAudioDestinationNode),
    // so MediaRecorder, Web Audio and the like work on it as in any browser; stopping the track
    // gives the microphone back. No camera: a video request fails with NotFoundError.
    function domError(name, message) {
      try { return new DOMException(message || name, name); } catch (e) { var error = new Error(message || name); error.name = name; return error; }
    }
    var GUM_ERRORS = { 'busy': 'NotReadableError', 'unavailable': 'NotReadableError', 'no-phone': 'NotFoundError', 'timeout': 'NotReadableError', 'cancelled': 'AbortError' };
    function liveStream() {
      var id = 'l' + audioId++;
      return new Promise(function (resolve, reject) {
        var Context = window.AudioContext || window.webkitAudioContext;
        if (!Context) return reject(domError('NotSupportedError', 'no Web Audio'));
        var context = null;
        var destination = null;
        var next = 0;
        var track = null;
        var done = false;
        function finish() {
          if (done) return;
          done = true;
          delete audioJobs[id];
          if (track && track.readyState !== 'ended') {
            track.__lumenStop();
          }
          if (context) context.close().catch(function () {});
        }
        audioJobs[id] = {
          handle: function (event) {
            if (event.type === 'started') {
              if (context) return;
              context = new Context();
              destination = context.createMediaStreamDestination();
              if (context.resume) context.resume().catch(function () {});
              var stream = destination.stream;
              track = stream.getAudioTracks()[0];
              var stopTrack = track.stop.bind(track);
              track.__lumenStop = stopTrack;
              track.stop = function () {
                if (!done) audioSend({ op: 'stop', id: id });
                finish();
                stopTrack();
              };
              try { Object.defineProperty(track, 'label', { value: 'Glasses microphone' }); } catch (e) {}
              resolve(stream);
            } else if (event.type === 'pcm' && context && !done) {
              var raw = atob(event.data);
              var count = raw.length >> 1;
              var buffer = context.createBuffer(1, count, 16000);
              var channel = buffer.getChannelData(0);
              for (var i = 0; i < count; i++) {
                var v = raw.charCodeAt(2 * i) | (raw.charCodeAt(2 * i + 1) << 8);
                channel[i] = (v >= 32768 ? v - 65536 : v) / 32768;
              }
              var source = context.createBufferSource();
              source.buffer = buffer;
              source.connect(destination);
              // A little ahead of now, back to back; after a gap (a lost piece) it starts again.
              next = Math.max(next, context.currentTime + 0.05);
              source.start(next);
              next += buffer.duration;
            } else if (event.type === 'ended') {
              finish();
            } else if (event.type === 'error') {
              if (!context) {
                delete audioJobs[id];
                reject(domError(GUM_ERRORS[event.code] || 'NotReadableError', event.message || event.code));
              } else {
                finish();
              }
            }
          }
        };
        audioSend({ op: 'record', id: id, live: true, maxMs: 30 * 60000 });
      });
    }
    var mediaDevices = navigator.mediaDevices;
    if (!mediaDevices) {
      try { Object.defineProperty(navigator, 'mediaDevices', { value: {}, configurable: true }); } catch (e) {}
      mediaDevices = navigator.mediaDevices;
    }
    if (mediaDevices) {
      mediaDevices.getUserMedia = function (constraints) {
        if (!constraints || (!constraints.audio && !constraints.video)) return Promise.reject(new TypeError('audio or video required'));
        if (constraints.video) return Promise.reject(domError('NotFoundError', 'the glasses have no camera for web apps'));
        return liveStream();
      };
      mediaDevices.enumerateDevices = function () {
        return Promise.resolve([{ kind: 'audioinput', deviceId: 'glasses', groupId: 'glasses', label: 'Glasses microphone', toJSON: function () { return this; } }]);
      };
    }

    // The Web Speech API's recognition, on the glasses' dictation (the engine chosen in the
    // companion). lang is the engine's; one alternative; results as in Chrome.
    var Recognition = function () {
      this.lang = '';
      this.continuous = false;
      this.interimResults = false;
      this.maxAlternatives = 1;
      this.onstart = this.onaudiostart = this.onspeechstart = this.onresult = this.onnomatch =
        this.onerror = this.onspeechend = this.onaudioend = this.onend = null;
      this._listeners = {};
      this._id = null;
    };
    Recognition.prototype.addEventListener = function (type, fn) { (this._listeners[type] = this._listeners[type] || []).push(fn); };
    Recognition.prototype.removeEventListener = function (type, fn) {
      var list = this._listeners[type] || [];
      var i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    };
    Recognition.prototype.dispatchEvent = function (event) {
      var handler = this['on' + event.type];
      if (typeof handler === 'function') handler.call(this, event);
      (this._listeners[event.type] || []).slice().forEach(function (fn) { fn.call(this, event); }, this);
      return true;
    };
    Recognition.prototype._fire = function (type, extra) {
      var event = { type: type, target: this, currentTarget: this, timeStamp: Date.now() };
      if (extra) for (var k in extra) event[k] = extra[k];
      this.dispatchEvent(event);
    };
    function resultList(items) {
      var list = items.map(function (item) {
        var alternative = { transcript: item.text, confidence: item.final ? 0.9 : 0 };
        var result = [alternative];
        result.isFinal = item.final;
        result.item = function (i) { return result[i]; };
        return result;
      });
      list.item = function (i) { return list[i]; };
      return list;
    }
    Recognition.prototype.start = function () {
      if (this._id) throw domError('InvalidStateError', 'recognition has already started');
      var self = this;
      var id = 's' + audioId++;
      var finals = [];
      this._id = id;
      audioJobs[id] = {
        handle: function (event) {
          if (event.type === 'start') {
            self._fire('start');
            self._fire('audiostart');
          } else if (event.type === 'result') {
            var items = finals.slice();
            var index = finals.length;
            if (event.final) finals.push({ text: event.text, final: true });
            items.push({ text: event.text, final: !!event.final });
            if (!self.continuous && event.final) items = [items[items.length - 1]], index = 0;
            self._fire('result', { results: resultList(items), resultIndex: index });
          } else if (event.type === 'error') {
            self._fire('error', { error: event.error || 'network', message: event.message || '' });
          } else if (event.type === 'end') {
            delete audioJobs[id];
            self._id = null;
            self._fire('audioend');
            self._fire('end');
          }
        }
      };
      audioSend({ op: 'recognize', id: id, continuous: !!this.continuous, interimResults: !!this.interimResults, lang: this.lang || '' });
    };
    Recognition.prototype.stop = function () { if (this._id) audioSend({ op: 'recognizeStop', id: this._id }); };
    Recognition.prototype.abort = function () { if (this._id) audioSend({ op: 'recognizeAbort', id: this._id }); };
    window.SpeechRecognition = Recognition;
    window.webkitSpeechRecognition = Recognition;
  }

  // Web Speech synthesis through Android's TextToSpeech (WebView has no speechSynthesis).
  if (!window.speechSynthesis && host) {
    var nextId = 1;
    var pending = {};
    var voice = { name: 'Android TTS', lang: 'en-US', voiceURI: 'android-tts', localService: true, default: true };

    var Utterance = function (text) {
      this.text = text == null ? '' : String(text);
      this.lang = 'en-US';
      this.rate = 1;
      this.pitch = 1;
      this.volume = 1;
      this.voice = null;
      this.onstart = this.onend = this.onerror = this.onpause = this.onresume = null;
      this._listeners = {};
    };
    Utterance.prototype.addEventListener = function (type, fn) {
      (this._listeners[type] = this._listeners[type] || []).push(fn);
    };
    Utterance.prototype.removeEventListener = function (type, fn) {
      var list = this._listeners[type] || [];
      var i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    };
    Utterance.prototype._emit = function (type, extra) {
      var event = { type: type, utterance: this, charIndex: 0, elapsedTime: 0, name: '' };
      if (extra) for (var k in extra) event[k] = extra[k];
      var handler = this['on' + type];
      if (typeof handler === 'function') handler.call(this, event);
      (this._listeners[type] || []).slice().forEach(function (fn) { fn.call(this, event); }, this);
    };

    var synthesis = {
      speaking: false,
      pending: false,
      paused: false,
      onvoiceschanged: null,
      getVoices: function () { return [voice]; },
      speak: function (utterance) {
        var id = nextId++;
        pending[id] = utterance;
        host.speak(id, utterance.text, utterance.lang || 'en-US', +utterance.rate || 1, +utterance.pitch || 1);
      },
      cancel: function () { host.cancelSpeech(); },
      pause: function () {},
      resume: function () {},
      addEventListener: function () {},
      removeEventListener: function () {}
    };
    window.SpeechSynthesisUtterance = Utterance;
    window.speechSynthesis = synthesis;
    // Called by the host: 'start', 'end' or 'error' (with an MRBD error code).
    window.__mrbdSpeech = function (id, type, code) {
      var utterance = pending[id];
      if (!utterance) return;
      if (type === 'start') synthesis.speaking = true;
      if (type !== 'start') { synthesis.speaking = false; delete pending[id]; }
      utterance._emit(type, type === 'error' ? { error: code || 'synthesis-failed' } : null);
    };
  }

  // MRBD's composer: activating a text field (Enter on it) opens the system's dictation panel
  // instead of reaching the page; its text comes back through the value setter and `input`,
  // then `change` when the panel closes. Focus alone never opens it.
  var TEXT_TYPES = ['text', 'search', 'email', 'url', 'tel', 'number'];
  var composerTarget = null;
  function isTextField(el) {
    if (!el || el.disabled || el.readOnly) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName !== 'INPUT') return false;
    return TEXT_TYPES.indexOf((el.getAttribute('type') || 'text').toLowerCase()) >= 0;
  }
  if (host && host.openComposer) {
    window.addEventListener('keydown', function (event) {
      // No guard on an earlier target: while the composer is open the host keeps Enter from
      // the page, and a composer the host closed without telling us mustn't block the next.
      if (event.key !== 'Enter') return;
      // The phone's keyboard open takes the composer's place: Enter goes on to the page.
      if (phoneKeyboard()) return;
      var el = document.activeElement;
      if (!isTextField(el)) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      composerTarget = el;
      host.openComposer(el.isContentEditable ? el.textContent : el.value,
        el.tagName === 'TEXTAREA' || el.isContentEditable);
    }, true);
  }
  // GeckoView asked for a keyboard (a field got focus): a field the composer takes waits for
  // Enter; anything else (a password, say) gets the system's keyboard.
  // The phone's keyboard types into any of these, a password included.
  window.__mrbdKeyboardWanted = function () {
    var el = document.activeElement;
    if (phoneKeyboard() && isKeyboardField(el)) return;
    if (host && host.noTextField && !isTextField(el)) host.noTextField();
  };
  function setFieldValue(el, text) {
    if (el.isContentEditable) {
      el.textContent = text;
    } else {
      // The prototype's setter, so frameworks that track the value (React) see the change.
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }
  window.__mrbdComposerInput = function (text) {
    if (composerTarget) setFieldValue(composerTarget, text);
  };
  window.__mrbdComposerClose = function () {
    var el = composerTarget;
    composerTarget = null;
    if (el) el.dispatchEvent(new Event('change', { bubbles: true }));
  };

  // Rokid Lumen's phone keyboard: the companion types into the page's focused field. The host
  // hears which field has focus (its value, type and label) and when none has; the phone's
  // text replaces the field's whole value, as the composer's does.
  function phoneKeyboard() { return !!(host && host.phoneKeyboard && host.phoneKeyboard()); }
  function isKeyboardField(el) {
    if (isTextField(el)) return true;
    return !!el && el.tagName === 'INPUT' && !el.disabled && !el.readOnly &&
      (el.getAttribute('type') || '').toLowerCase() === 'password';
  }
  function fieldLabel(el) {
    var text = el.getAttribute('aria-label') || el.getAttribute('placeholder') || '';
    if (!text && el.labels && el.labels.length) text = el.labels[0].textContent || '';
    if (!text) text = el.getAttribute('title') || el.getAttribute('name') || '';
    return String(text).replace(/\s+/g, ' ').trim().slice(0, 80);
  }
  function reportField(reason) {
    var el = document.activeElement;
    if (!isKeyboardField(el)) return host.textBlur();
    var type = el.tagName === 'INPUT' ? (el.getAttribute('type') || 'text').toLowerCase() : 'text';
    host.textFocus(el.isContentEditable ? el.textContent : el.value, type,
      el.tagName === 'TEXTAREA' || el.isContentEditable, fieldLabel(el), reason);
  }
  if (host && host.textFocus) {
    document.addEventListener('focusin', function (event) {
      if (isKeyboardField(event.target)) reportField('focus');
    }, true);
    document.addEventListener('focusout', function (event) {
      if (!isKeyboardField(event.target)) return;
      // Where the focus went is known only after the event.
      setTimeout(function () { if (!isKeyboardField(document.activeElement)) host.textBlur(); }, 0);
    }, true);
  }
  window.__mrbdKeyboardInput = function (text) {
    var el = document.activeElement;
    if (isKeyboardField(el)) setFieldValue(el, text);
  };
  // After an Enter the page may have changed the value (a sent message clears its box).
  window.__mrbdKeyboardSync = function () {
    if (host && host.textFocus) reportField('sync');
  };

  // Rokid Lumen's band API, for site scripts (an online app package's lumen_scripts) and any
  // page: lumen.band.on() takes the band's keys and Back before the page, lumen.highlight() and
  // lumen.toast() draw Lumen's own ring and pill, lumen.click() taps an element, and lumen.nav
  // is the generic band navigation of online apps (a ring moved between a site's links and
  // buttons). It exists without the host bridge too; Back then does nothing.
  // See docs/site-scripts.md.
  var lumen = window.lumen || (window.lumen = {});
  var BAND_KEYS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'enter' };
  var bandHandlers = [];
  var bandKeysTaken = {};

  // Only the band's own presses: trusted (a page can't drive Lumen with synthetic keys) and
  // unmodified (a phone or computer keyboard's shortcuts stay the page's).
  function bandKey(event) {
    var key = BAND_KEYS[event.key];
    if (!key || !event.isTrusted || event.isComposing) return null;
    if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return null;
    return key;
  }
  // The latest handler first. One that throws counts as not handled: a site script's bug
  // mustn't take the band away from the page.
  function askBand(key, event) {
    var handlers = bandHandlers.slice();
    for (var i = handlers.length - 1; i >= 0; i--) {
      try {
        // Exactly true: an async handler's promise would otherwise take every key.
        if (handlers[i].fn(key, event) === true) return true;
      } catch (e) {
        console.warn('[Lumen] band handler failed on ' + key + ':', e);
      }
    }
    return false;
  }
  function deepActive() {
    var el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
    return el;
  }
  lumen.band = {
    on: function (handler) {
      if (typeof handler !== 'function') return function () {};
      var entry = { fn: handler };
      bandHandlers.push(entry);
      return function () {
        var i = bandHandlers.indexOf(entry);
        if (i >= 0) bandHandlers.splice(i, 1);
      };
    }
  };
  // At document_start, so before any listener of the page's.
  window.addEventListener('keydown', function (event) {
    var key = bandKey(event);
    if (!key) return;
    // The navigation runs after every window listener of the page's, even one added after it
    // (a game listening on window): a listener added during the capture phase runs last in
    // this same event's bubble phase.
    window.removeEventListener('keydown', navKeydown);
    window.addEventListener('keydown', navKeydown);
    if (!bandHandlers.length) return;
    // Enter on a text field is the composer's.
    if (key === 'enter' && isKeyboardField(deepActive())) return;
    if (!askBand(key, event)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    bandKeysTaken[event.key] = true;
  }, true);
  window.addEventListener('keyup', function (event) {
    if (!bandKeysTaken[event.key]) return;
    delete bandKeysTaken[event.key];
    event.preventDefault();
    event.stopImmediatePropagation();
  }, true);

  // Lumen's overlays, styled through the CSSOM only (Instagram and YouTube allow no inline style
  // or markup: strict CSP and Trusted Types), each property !important against the page's CSS.
  var TOP_LAYER = '2147483647';
  function css(el, props) {
    for (var name in props) el.style.setProperty(name, props[name], 'important');
  }
  function attach(el) {
    var root = document.documentElement;
    if (root && el.parentNode !== root) root.appendChild(el);
  }

  // The highlight: a ring 4 px outside the element, nothing filled (the HUD is additive), with a
  // black halo that keeps it readable over a bright page. One fixed overlay that follows the
  // element every frame while shown, and clears itself when the element goes.
  var RING_OUT = 7; // the 4 px gap and the 3 px border
  var ring = null;
  var current = null; // { el: what Enter clicks, box: what the ring goes around }
  var ringPlaced = '';
  var following = false;
  function ensureRing() {
    if (!ring) {
      ring = document.createElement('div');
      ring.setAttribute('data-lumen', 'highlight');
      ring.setAttribute('aria-hidden', 'true');
      css(ring, {
        'position': 'fixed', 'display': 'none', 'left': '0px', 'top': '0px', 'width': '0px', 'height': '0px',
        'margin': '0', 'padding': '0', 'box-sizing': 'border-box', 'background': 'transparent',
        'border': '3px solid rgba(255, 255, 255, 0.86)', 'border-radius': '16px',
        'box-shadow': '0 0 0 4px #000', 'outline': 'none', 'pointer-events': 'none',
        'z-index': TOP_LAYER, 'transform': 'none', 'opacity': '1', 'visibility': 'visible'
      });
    }
    attach(ring);
    return ring;
  }
  function viewRect() { return { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight }; }
  // The containers that clip an element (a scrolling list, a carousel), nearest first.
  function clipsOf(el) {
    var clips = [];
    for (var p = el.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      var style = getComputedStyle(p);
      if ((style.overflowX !== 'visible' || style.overflowY !== 'visible') &&
          style.display !== 'inline' && style.display !== 'contents') clips.push(p);
      if (style.position === 'fixed') break;
    }
    return clips;
  }
  // The part of the screen where an element inside `clips` can show.
  function viewOf(clips) {
    var v = viewRect();
    for (var i = 0; i < clips.length; i++) {
      var c = clips[i].getBoundingClientRect();
      var left = c.left + clips[i].clientLeft;
      var top = c.top + clips[i].clientTop;
      v = {
        left: Math.max(v.left, left), top: Math.max(v.top, top),
        right: Math.min(v.right, left + clips[i].clientWidth), bottom: Math.min(v.bottom, top + clips[i].clientHeight)
      };
    }
    return v;
  }
  function placeRing(r, v) {
    var vw = window.innerWidth;
    var vh = window.innerHeight;
    var geometry = 'none';
    // Scrolled away (off screen, or out of its list) it waits, hidden; partly shown it rings the
    // part that shows, kept on screen so a full-width card still shows its sides.
    var shown = { left: Math.max(r.left, v.left), top: Math.max(r.top, v.top), right: Math.min(r.right, v.right), bottom: Math.min(r.bottom, v.bottom) };
    if (shown.right > shown.left && shown.bottom > shown.top) {
      var left = Math.round(Math.max(0, shown.left - RING_OUT));
      var top = Math.round(Math.max(0, shown.top - RING_OUT));
      var right = Math.round(Math.min(vw, shown.right + RING_OUT));
      var bottom = Math.round(Math.min(vh, shown.bottom + RING_OUT));
      geometry = left + ',' + top + ',' + (right - left) + ',' + (bottom - top);
    }
    // A still page costs no style writes.
    if (geometry === ringPlaced) return;
    ringPlaced = geometry;
    if (geometry === 'none') return css(ring, { 'display': 'none' });
    var p = geometry.split(',');
    css(ring, { 'display': 'block', 'left': p[0] + 'px', 'top': p[1] + 'px', 'width': p[2] + 'px', 'height': p[3] + 'px' });
  }
  function hiddenByStyle(el) {
    if (el.checkVisibility) return !el.checkVisibility({ visibilityProperty: true, checkVisibilityCSS: true });
    return getComputedStyle(el).visibility !== 'visible';
  }
  // Still in the page and laid out (not display:none, not collapsed, not visibility:hidden).
  function stillShown(el) {
    if (!el.isConnected) return false;
    var r = el.getBoundingClientRect();
    if (!r.width && !r.height) return false;
    return !hiddenByStyle(el);
  }
  function follow() {
    if (current && !(stillShown(current.el) && (current.box === current.el || stillShown(current.box)))) {
      setHighlight(null);
    }
    if (!current) {
      following = false;
      return;
    }
    attach(ring);
    placeRing(current.box.getBoundingClientRect(), viewOf(current.clips));
    requestAnimationFrame(follow);
  }
  function setHighlight(el, box) {
    box = box || el;
    current = el ? { el: el, box: box, clips: clipsOf(box) } : null;
    ringPlaced = '';
    if (!current) {
      if (ring) css(ring, { 'display': 'none' });
      return;
    }
    ensureRing();
    placeRing(current.box.getBoundingClientRect(), viewOf(current.clips));
    if (!following) {
      following = true;
      requestAnimationFrame(follow);
    }
  }
  // A highlighted text field has the focus, so Enter reaches the composer; leaving a field
  // blurs it, so Enter (and the composer) don't stay on a field the ring has left.
  function focusForHighlight(el) {
    var active = deepActive();
    if (isTextField(el)) {
      if (active !== el) {
        try { el.focus({ preventScroll: true }); } catch (e) {}
      }
    } else if (active && active !== el && isKeyboardField(active)) {
      active.blur();
    }
  }
  lumen.highlight = function (el) {
    if (!el || el.nodeType !== 1) return setHighlight(null);
    setHighlight(el, el);
    focusForHighlight(el);
  };
  lumen.highlighted = function () {
    if (current && !current.el.isConnected) setHighlight(null);
    return current ? current.el : null;
  };

  // The toast: a pill at the top centre (or the middle, for a "+10 s" chip), replaced by the next.
  var SVG = 'http://www.w3.org/2000/svg';
  var ICONS = {
    'heart': ['M12 20s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 10c0 5.65-7 10-7 10z'],
    'heart-filled': ['M12 20s-7-4.35-7-10a4 4 0 0 1 7-2.65A4 4 0 0 1 19 10c0 5.65-7 10-7 10z'],
    'forward': ['M20 12a8 8 0 1 1-2.34-5.66', 'M20 4v5h-5'],
    'back': ['M4 12a8 8 0 1 0 2.34-5.66', 'M4 4v5h5']
  };
  var toastEl = null;
  var toastTimer = 0;
  function toastIcon(name, size) {
    var svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('width', String(size));
    svg.setAttribute('height', String(size));
    svg.setAttribute('aria-hidden', 'true');
    css(svg, { 'flex': 'none', 'display': 'block', 'width': size + 'px', 'height': size + 'px' });
    ICONS[name].forEach(function (d) {
      var path = document.createElementNS(SVG, 'path');
      path.setAttribute('d', d);
      path.setAttribute('fill', name === 'heart-filled' ? '#fff' : 'none');
      path.setAttribute('stroke', '#fff');
      path.setAttribute('stroke-width', '2.2');
      path.setAttribute('stroke-linecap', 'round');
      path.setAttribute('stroke-linejoin', 'round');
      svg.appendChild(path);
    });
    return svg;
  }
  lumen.toast = function (text, options) {
    options = options || {};
    clearTimeout(toastTimer);
    if (toastEl && toastEl.parentNode) toastEl.parentNode.removeChild(toastEl);
    toastEl = null;
    // An empty text just takes the current toast away.
    if (text == null || text === '') return;
    var center = options.center === true;
    var el = document.createElement('div');
    el.setAttribute('data-lumen', 'toast');
    el.setAttribute('role', 'status');
    el.setAttribute('aria-live', 'polite');
    css(el, {
      'position': 'fixed', 'left': '50%', 'top': center ? '50%' : '24px',
      'transform': center ? 'translate(-50%, -50%)' : 'translateX(-50%)',
      'display': 'flex', 'align-items': 'center', 'gap': '10px', 'box-sizing': 'border-box',
      'height': center ? '64px' : '48px', 'max-width': 'calc(100vw - 32px)', 'margin': '0', 'padding': '0 22px',
      'background': '#000', 'border': '2px solid rgba(255, 255, 255, 0.43)', 'border-radius': '9999px',
      'color': '#fff', 'font-family': 'Roboto, "Noto Sans", system-ui, sans-serif',
      'font-size': center ? '28px' : '22px', 'font-weight': '500', 'font-style': 'normal',
      'line-height': '1', 'letter-spacing': 'normal', 'text-transform': 'none', 'white-space': 'nowrap',
      'overflow': 'hidden', 'pointer-events': 'none', 'z-index': TOP_LAYER, 'opacity': '1', 'visibility': 'visible'
    });
    if (ICONS[options.icon]) el.appendChild(toastIcon(options.icon, center ? 30 : 24));
    var label = document.createElement('span');
    label.textContent = String(text);
    css(label, { 'overflow': 'hidden', 'text-overflow': 'ellipsis', 'color': '#fff', 'font': 'inherit' });
    el.appendChild(label);
    attach(el);
    toastEl = el;
    var ms = +options.ms > 0 ? +options.ms : 1500;
    toastTimer = setTimeout(function () {
      if (el.parentNode) el.parentNode.removeChild(el);
      if (toastEl === el) toastEl = null;
    }, ms);
  };

  // A tap at the element's centre, as the page's own handlers expect one: pointer and mouse
  // events, then click, on what is at that point (a card's inner link navigates).
  function focusable(el) { return el.tabIndex >= 0 && !el.disabled; }
  lumen.click = function (el) {
    if (!el || el.nodeType !== 1 || !el.isConnected) return false;
    if (focusable(el)) {
      try { el.focus({ preventScroll: true }); } catch (e) {}
    }
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2;
    var y = r.top + r.height / 2;
    var hit = x >= 0 && y >= 0 && x < window.innerWidth && y < window.innerHeight ? document.elementFromPoint(x, y) : null;
    var target = hit && (hit === el || el.contains(hit)) ? hit : el;
    function init(buttons) {
      return {
        bubbles: true, cancelable: true, composed: true, view: window, detail: 1,
        clientX: x, clientY: y, screenX: x, screenY: y, button: 0, buttons: buttons,
        pointerId: 1, pointerType: 'mouse', isPrimary: true, width: 1, height: 1
      };
    }
    var Pointer = window.PointerEvent;
    if (Pointer) target.dispatchEvent(new Pointer('pointerdown', init(1)));
    target.dispatchEvent(new MouseEvent('mousedown', init(1)));
    if (Pointer) target.dispatchEvent(new Pointer('pointerup', init(0)));
    target.dispatchEvent(new MouseEvent('mouseup', init(0)));
    target.dispatchEvent(new MouseEvent('click', init(0)));
    return true;
  };

  // The generic band navigation (online apps): the arrows move the ring to the nearest visible
  // link or button that way (spatial navigation), Enter clicks it. Only for keys the page left
  // alone, so a site or a game that handles the arrows keeps them.
  var CLICKABLE = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, ' +
    '[role="button"], [role="link"], [role="tab"], [role="menuitem"], [role="option"], ' +
    '[role="checkbox"], [role="switch"], [role="radio"], [tabindex]:not([tabindex="-1"]), ' +
    '[contenteditable=""], [contenteditable="true"], [onclick]';
  var MIN_SIZE = 8;
  // Style and hit tests per key press, at most: a feed's page holds thousands of clickables.
  var MAX_CHECKED = 400;
  // Kept clear of the page's edges when the ring moves (a site's top bar and bottom tabs).
  var EDGE = 64;
  var navAnnounced = false;

  function area(r) { return Math.max(0, r.right - r.left) * Math.max(0, r.bottom - r.top); }
  function within(inner, outer) {
    return inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
      inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1;
  }
  function crosses(r, v) { return r.right > v.left && r.left < v.right && r.bottom > v.top && r.top < v.bottom; }
  function styleShown(el) {
    if (el.checkVisibility) {
      return el.checkVisibility({ opacityProperty: true, visibilityProperty: true, checkOpacity: true, checkVisibilityCSS: true });
    }
    var style = getComputedStyle(el);
    return style.visibility === 'visible' && style.opacity !== '0';
  }
  // What a tap on the element would hit is the element (or inside it): not under a dialog, a
  // sticky bar or another layer. A wrapped link's box centre may fall between its lines, so its
  // first line and a corner are tried too.
  function onTop(el, r, v) {
    var left = Math.max(r.left, v.left);
    var right = Math.min(r.right, v.right);
    var top = Math.max(r.top, v.top);
    var bottom = Math.min(r.bottom, v.bottom);
    var points = [[(left + right) / 2, (top + bottom) / 2]];
    var line = el.getClientRects()[0];
    if (line && crosses(line, v)) {
      points.push([(Math.max(line.left, left) + Math.min(line.right, right)) / 2, (Math.max(line.top, top) + Math.min(line.bottom, bottom)) / 2]);
    }
    points.push([left + Math.min(6, (right - left) / 2), top + Math.min(6, (bottom - top) / 2)]);
    for (var i = 0; i < points.length; i++) {
      var hit = document.elementFromPoint(points[i][0], points[i][1]);
      if (hit && (hit === el || el.contains(hit))) return true;
    }
    return false;
  }
  function visibleClickables(nodes) {
    var v = viewRect();
    // Links a screen above or below too, so a card half on screen is grouped whole (below).
    var band = { left: -Infinity, right: Infinity, top: -v.bottom, bottom: 2 * v.bottom };
    var found = [];
    var links = [];
    var checked = 0;
    for (var i = 0; i < nodes.length && checked < MAX_CHECKED; i++) {
      var el = nodes[i];
      if (el.disabled) continue;
      var r = el.getBoundingClientRect();
      if (el.tagName === 'A' && r.width && r.height && crosses(r, band)) links.push({ el: el, rect: r });
      if (r.width < MIN_SIZE || r.height < MIN_SIZE || !crosses(r, v)) continue;
      checked++;
      if (!styleShown(el) || !onTop(el, r, v)) continue;
      var box = el;
      // An inline link around a picture measures only its line: the ring goes around the picture.
      var media = el.tagName === 'A' ? el.querySelector('img, picture, video, svg, canvas') : null;
      var mr = media && media.getBoundingClientRect();
      if (mr && area(mr) > area(r)) {
        box = media;
        r = mr;
      }
      found.push({ el: el, box: box, rect: r });
    }
    return merge(found, links);
  }
  // One stop per thing: links to the same address close together (a video's thumbnail and its
  // title) become one, ringed as the card that holds them; a clickable inside another of nearly
  // the same size gives way to the outer one.
  function merge(found, links) {
    var byEl = new Map();
    found.forEach(function (c) { byEl.set(c.el, c); });
    var byHref = new Map();
    links.forEach(function (link) {
      var href = link.el.href;
      if (!href || href.indexOf('#') >= 0 || /^javascript:/i.test(href)) return;
      if (!byHref.has(href)) byHref.set(href, []);
      byHref.get(href).push(link);
    });
    var dropped = new Set();
    byHref.forEach(function (members) {
      var shown = members.filter(function (m) { return byEl.has(m.el); });
      if (members.length < 2 || !shown.length) return;
      // One off screen counts when it's rendered: a closed menu's link to the same page doesn't.
      members = members.filter(function (m) { return byEl.has(m.el) || styleShown(m.el); });
      if (members.length < 2) return;
      var card = members[0].el.parentElement;
      for (var depth = 0; card && depth < 6; depth++, card = card.parentElement) {
        if (card === document.body || card === document.documentElement) return;
        if (members.every(function (m) { return card.contains(m.el); })) break;
      }
      if (!card || depth >= 6) return;
      var cardRect = card.getBoundingClientRect();
      var union = { left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity };
      members.forEach(function (m) {
        union.left = Math.min(union.left, m.rect.left);
        union.top = Math.min(union.top, m.rect.top);
        union.right = Math.max(union.right, m.rect.right);
        union.bottom = Math.max(union.bottom, m.rect.bottom);
      });
      // The card must be mostly those links, not a whole section that happens to hold them.
      if (area(union) < 0.6 * area(cardRect)) return;
      // Enter clicks the biggest of those on screen (the thumbnail, else the title).
      var main = shown.reduce(function (a, b) { return area(b.rect) > area(a.rect) ? b : a; });
      var keep = byEl.get(main.el);
      shown.forEach(function (m) {
        var c = byEl.get(m.el);
        if (c !== keep) dropped.add(c);
      });
      keep.box = card;
      keep.rect = cardRect;
    });
    return found.filter(function (c) {
      if (dropped.has(c)) return false;
      var a = c.el.parentElement;
      for (var depth = 0; a && depth < 6; depth++, a = a.parentElement) {
        var outer = byEl.get(a);
        if (outer && !dropped.has(outer) && within(c.rect, outer.rect) && area(c.rect) >= 0.8 * area(outer.rect)) return false;
      }
      return true;
    });
  }
  function isPageScroller(el) {
    return el === document.scrollingElement || el === document.documentElement || el === document.body;
  }
  // The nearest container that scrolls `el` on that axis, the page's scroller at the end; null
  // for an element in a fixed or sticky bar (nothing scrolls it).
  function scrollerOf(el, vertical) {
    for (var p = el; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      var style = getComputedStyle(p);
      if (style.position === 'fixed' || style.position === 'sticky') return null;
      if (p === el) continue;
      var overflow = vertical ? style.overflowY : style.overflowX;
      if ((overflow === 'auto' || overflow === 'scroll' || overflow === 'overlay') &&
          (vertical ? p.scrollHeight > p.clientHeight + 1 : p.scrollWidth > p.clientWidth + 1)) return p;
    }
    return document.scrollingElement || document.documentElement;
  }
  function scrollPos(scroller, vertical) { return vertical ? scroller.scrollTop : scroller.scrollLeft; }
  // Instantly (a smooth scroll would still be moving when the next candidates are measured).
  function scrollAlong(scroller, vertical, delta) {
    var before = scrollPos(scroller, vertical);
    var target = isPageScroller(scroller) ? window : scroller;
    try {
      target.scrollBy({ top: vertical ? delta : 0, left: vertical ? 0 : delta, behavior: 'instant' });
    } catch (e) {
      if (vertical) scroller.scrollTop += delta;
      else scroller.scrollLeft += delta;
    }
    return scrollPos(scroller, vertical) !== before;
  }
  // About a screenful (70 %) of the container that way; whether it moved.
  function scrollStep(scroller, dir) {
    var vertical = dir === 'up' || dir === 'down';
    var page = isPageScroller(scroller);
    var size = vertical ? (page ? window.innerHeight : scroller.clientHeight) : (page ? window.innerWidth : scroller.clientWidth);
    return scrollAlong(scroller, vertical, Math.round(size * 0.7) * (dir === 'up' || dir === 'left' ? -1 : 1));
  }
  // Keeps the ringed element in view: the nearest edge into the container, clear of the page's
  // bars; an element taller than the view shows its top.
  function reveal(el) {
    [true, false].forEach(function (vertical) {
      for (var scroller = scrollerOf(el, vertical), guard = 0; scroller && guard < 8; guard++) {
        var page = isPageScroller(scroller);
        var r = el.getBoundingClientRect();
        var low;
        var high;
        var size = vertical ? window.innerHeight : window.innerWidth;
        if (page) {
          low = vertical ? EDGE : 8;
          high = size - low;
        } else {
          var c = scroller.getBoundingClientRect();
          low = Math.max(vertical ? c.top : c.left, 0) + 8;
          high = Math.min(vertical ? c.bottom : c.right, size) - 8;
        }
        var start = vertical ? r.top : r.left;
        var end = vertical ? r.bottom : r.right;
        var delta = 0;
        if (start < low) delta = start - low;
        else if (end > high) delta = Math.min(end - high, start - low);
        if (delta) scrollAlong(scroller, vertical, Math.round(delta));
        if (page) break;
        scroller = scrollerOf(scroller, vertical);
      }
    });
  }
  // Where a move starts: the ringed element, or the edge of its view (the screen, its list) that
  // it went past.
  function origin(rect, v) {
    var r = { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom };
    if (r.bottom <= v.top) r.top = r.bottom = v.top;
    else if (r.top >= v.bottom) r.top = r.bottom = v.bottom;
    if (r.right <= v.left) r.left = r.right = v.left;
    else if (r.left >= v.right) r.left = r.right = v.right;
    return r;
  }
  // The candidates that way, best first: the near edge past the current's centre; scored by the
  // gap along the move plus twice the gap across it (overlapping = 0), then the centres' offset.
  function ranked(dir, candidates, from, scope) {
    var vertical = dir === 'up' || dir === 'down';
    var o = origin(from.box.getBoundingClientRect(), viewOf(from.clips));
    var cx = (o.left + o.right) / 2;
    var cy = (o.top + o.bottom) / 2;
    var list = [];
    candidates.forEach(function (c) {
      var r = c.rect;
      if (c.el === from.el || c.box === from.box || (scope && !scope.contains(c.el))) return;
      // The current's own parts, and what holds it, aren't somewhere else.
      if (within(r, o) || within(o, r)) return;
      var along;
      if (dir === 'down') { if (!(r.top > cy)) return; along = r.top - o.bottom; }
      else if (dir === 'up') { if (!(r.bottom < cy)) return; along = o.top - r.bottom; }
      else if (dir === 'right') { if (!(r.left > cx)) return; along = r.left - o.right; }
      else { if (!(r.right < cx)) return; along = o.left - r.right; }
      var across = vertical ? Math.max(0, r.left - o.right, o.left - r.right) : Math.max(0, r.top - o.bottom, o.top - r.bottom);
      var offset = vertical ? Math.abs((r.left + r.right) / 2 - cx) : Math.abs((r.top + r.bottom) / 2 - cy);
      list.push({ c: c, score: Math.max(0, along) + 2 * across, offset: offset });
    });
    list.sort(function (a, b) { return a.score - b.score || a.offset - b.offset; });
    return list.map(function (x) { return x.c; });
  }
  // The best one that isn't in a fixed or sticky bar (a site's top bar, its bottom tabs): while
  // the content can still scroll that way, the content comes first.
  function bestInContent(list, vertical) {
    for (var i = 0; i < list.length; i++) {
      if (scrollerOf(list[i].box, vertical)) return list[i];
    }
    return null;
  }
  // No ring yet: the first fully visible candidate in reading order (top, then left).
  function first(candidates) {
    var v = viewRect();
    var whole = candidates.filter(function (c) { return within(c.rect, v); });
    var pool = whole.length ? whole : candidates;
    var pick = null;
    pool.forEach(function (c) {
      if (!pick) { pick = c; return; }
      var dy = c.rect.top - pick.rect.top;
      if (dy < -4 || (Math.abs(dy) <= 4 && c.rect.left < pick.rect.left)) pick = c;
    });
    return pick;
  }
  function navTo(c) {
    setHighlight(c.el, c.box);
    focusForHighlight(c.el);
    reveal(c.box);
  }
  function navMove(dir) {
    if (['up', 'down', 'left', 'right'].indexOf(dir) < 0) return false;
    var vertical = dir === 'up' || dir === 'down';
    var nodes = document.querySelectorAll(CLICKABLE);
    var candidates = visibleClickables(nodes);
    var from = current;
    if (from && !(stillShown(from.el) && stillShown(from.box))) {
      setHighlight(null);
      from = null;
    }
    if (!from) {
      var start = first(candidates);
      if (!start && scrollStep(document.scrollingElement || document.documentElement, dir)) {
        start = first(visibleClickables(nodes));
        if (!start) return true;
      }
      if (start) navTo(start);
      return !!start;
    }
    // In the content: the nearest that way inside the current's scrolling container; with none,
    // that container scrolls (then the next one out, the page last), and the nearest candidate
    // that came into view is next. From a fixed bar (no scroller), or once nothing scrolls that
    // way any more, the nearest of all, a bar's item included.
    var scroller = scrollerOf(from.box, vertical);
    for (var guard = 0; scroller && guard < 8; guard++) {
      var page = isPageScroller(scroller);
      var scope = page ? null : scroller;
      var pick = bestInContent(ranked(dir, candidates, from, scope), vertical);
      if (pick) {
        navTo(pick);
        return true;
      }
      if (scrollStep(scroller, dir)) {
        pick = bestInContent(ranked(dir, visibleClickables(nodes), from, scope), vertical);
        if (pick) navTo(pick);
        return true;
      }
      scroller = page ? null : scrollerOf(scroller, vertical);
    }
    var any = ranked(dir, candidates, from, null)[0];
    if (any) navTo(any);
    return !!any;
  }
  function navKeydown(event) {
    if (!lumen.nav.enabled || event.defaultPrevented) return;
    var key = bandKey(event);
    if (!key) return;
    if (key !== 'enter') {
      event.preventDefault();
      navMove(key);
      return;
    }
    // Enter on a field is the composer's (or, with the phone's keyboard, the page's).
    if (isKeyboardField(deepActive())) return;
    var el = lumen.highlighted();
    if (!el) return;
    event.preventDefault();
    if (isTextField(el)) focusForHighlight(el);
    else lumen.click(el);
  }
  window.addEventListener('keydown', navKeydown);
  lumen.nav = {
    enabled: false,
    move: function (direction) { return navMove(String(direction)); },
    clear: function () { setHighlight(null); }
  };
  // The host turns it on for an online app's pages (page-bridge.js: `bandNavigation`).
  window.__lumenBandNavigation = function (value) {
    lumen.nav.enabled = !!value;
    if (!lumen.nav.enabled) return setHighlight(null);
    if (!navAnnounced) {
      navAnnounced = true;
      console.info('[Lumen] Band navigation on');
    }
  };
  window.addEventListener('pagehide', function () { setHighlight(null); });

  // Back, as MRBD's shell does it: the page gets Escape first; if it neither handles it nor
  // navigates, the host goes back in history, or closes the app when there is none. A band
  // handler (lumen.band.on) that takes 'back' comes before all that.
  window.__mrbdBack = function () {
    if (askBand('back')) {
      if (host) host.backResult(true);
      return;
    }
    var target = document.activeElement || document.body || document.documentElement;
    var before = location.href;
    var init = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
    var notPrevented = target.dispatchEvent(new KeyboardEvent('keydown', init));
    target.dispatchEvent(new KeyboardEvent('keyup', init));
    setTimeout(function () {
      if (host) host.backResult(!notPrevented || location.href !== before);
    }, 150);
  };
})();
