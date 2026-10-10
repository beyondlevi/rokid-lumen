// Lumen's video cap: no page plays a video bigger than the glasses can show. Runs at
// document_start in the page's own world (manifest: "world": "MAIN"), before the site's scripts.
//
// - Instagram sends each video's DASH manifest inside its JSON (`video_dash_manifest`), with
//   renditions up to 1080x1920 in H.264 or VP9, and its progressive files in `video_versions`.
//   Every JSON.parse that carries one keeps only the renditions whose short side fits the
//   display (480 px), in H.264 when the manifest has it (decoded in hardware), so the player never
//   fetches, buffers or decodes more than the glasses show. Measured reason (RG glasses, 1.8 GB):
//   watching Reels ran them out of memory.
// - A player that asks MediaSource.isTypeSupported or canPlayType about a size or a frame rate
//   (YouTube does) hears "no" above the display's size or above 30 fps, Lumen's frame rate.
(function () {
  'use strict';
  var SHORT_SIDE = 480;
  var FRAME_RATE = 30;

  // The page's own functions, taken before any of its scripts can replace them.
  var apply = Reflect.apply;
  var keys = Object.keys;
  var isArray = Array.isArray;
  var indexOf = String.prototype.indexOf;
  var min = Math.min;
  var log = typeof console !== 'undefined' && console.info ? console.info.bind(console) : function () {};

  function attr(tag, name) {
    var match = new RegExp('\\s' + name + '="([^"]*)"').exec(tag);
    return match ? match[1] : '';
  }
  function shortSide(item) { return min(item.width, item.height); }
  function isH264(codecs) { return /^avc[13]/.test(codecs); }
  function label(item) { return item.width + 'x' + item.height + (item.codecs ? ' ' + item.codecs.split('.')[0] : ''); }

  /** The renditions to keep: those that fit, in H.264 when there is one; at least the smallest. */
  function choose(items, codecsOf) {
    var pool = items.filter(function (item) { return isH264(codecsOf(item)); });
    if (!pool.length) pool = items;
    var fit = pool.filter(function (item) { return shortSide(item) <= SHORT_SIDE; });
    if (fit.length) return fit;
    return [pool.reduce(function (best, item) { return shortSide(item) < shortSide(best) ? item : best; })];
  }

  /**
   * A DASH manifest (MPD) without the video renditions [choose] drops; an AdaptationSet left
   * with no rendition goes too. Audio is untouched. A manifest whose video sizes aren't all
   * known comes back as it was.
   */
  function capManifest(xml) {
    if (typeof xml !== 'string' || apply(indexOf, xml, ['<Representation']) < 0) return xml;
    var sets = [];
    var setPattern = /<AdaptationSet\b[^>]*>[\s\S]*?<\/AdaptationSet>/g;
    var set;
    while ((set = setPattern.exec(xml))) {
      var open = /^<AdaptationSet\b[^>]*>/.exec(set[0])[0];
      var kind = attr(open, 'contentType') || attr(open, 'mimeType').split('/')[0];
      var entry = { start: set.index, end: set.index + set[0].length, renditions: [] };
      var repPattern = /<Representation\b[^>]*?(?:\/>|>[\s\S]*?<\/Representation>)/g;
      var rep;
      while ((rep = repPattern.exec(set[0]))) {
        var tag = /^<Representation\b[^>]*>/.exec(rep[0])[0];
        var mime = attr(tag, 'mimeType') || attr(open, 'mimeType');
        var item = {
          start: set.index + rep.index,
          end: set.index + rep.index + rep[0].length,
          width: +(attr(tag, 'width') || attr(open, 'width')) || 0,
          height: +(attr(tag, 'height') || attr(open, 'height')) || 0,
          codecs: attr(tag, 'codecs') || attr(open, 'codecs'),
          set: entry
        };
        var video = kind === 'video' || /^video\//.test(mime) || (item.width > 0 && item.height > 0);
        if (video) entry.renditions.push(item);
      }
      if (entry.renditions.length) sets.push(entry);
    }
    var all = [];
    sets.forEach(function (entry) { all = all.concat(entry.renditions); });
    if (!all.length || all.some(function (item) { return !(item.width > 0 && item.height > 0); })) return xml;
    var kept = choose(all, function (item) { return item.codecs; });
    if (kept.length === all.length) return xml;

    var cuts = [];
    sets.forEach(function (entry) {
      var gone = entry.renditions.filter(function (item) { return kept.indexOf(item) < 0; });
      if (gone.length === entry.renditions.length) cuts.push(entry);
      else cuts = cuts.concat(gone);
    });
    cuts.sort(function (a, b) { return a.start - b.start; });
    var out = '';
    var at = 0;
    cuts.forEach(function (cut) {
      out += xml.slice(at, cut.start);
      at = cut.end;
    });
    out += xml.slice(at);
    report('manifest', kept, all);
    return out;
  }

  /** Progressive files (`video_versions`: width, height, url): the same cut as the manifest's. */
  function capVersions(list) {
    if (!list.length || list.some(function (item) {
      return !item || !(+item.width > 0 && +item.height > 0);
    })) return list;
    var items = list.map(function (item) { return { width: +item.width, height: +item.height, codecs: '', source: item }; });
    var kept = choose(items, function () { return 'avc1'; });
    if (kept.length === items.length) return list;
    report('files', kept, items);
    return kept.map(function (item) { return item.source; });
  }

  /** Walks a parsed JSON value and caps every video it describes, in place. */
  function capMedia(root) {
    var stack = [root];
    while (stack.length) {
      var node = stack.pop();
      if (!node || typeof node !== 'object') continue;
      if (isArray(node)) {
        for (var i = 0; i < node.length; i++) if (node[i] && typeof node[i] === 'object') stack.push(node[i]);
        continue;
      }
      var names = keys(node);
      for (var j = 0; j < names.length; j++) {
        var name = names[j];
        var value = node[name];
        if (name === 'video_dash_manifest' && typeof value === 'string') node[name] = capManifest(value);
        else if (name === 'video_versions' && isArray(value)) node[name] = capVersions(value);
        else if (value && typeof value === 'object') stack.push(value);
      }
    }
    return root;
  }

  function report(what, kept, all) {
    var dropped = all.filter(function (item) { return kept.indexOf(item) < 0; });
    log('[Lumen] Video capped (' + what + '): kept ' + kept.map(label).join(', ') +
      '; dropped ' + dropped.map(label).join(', '));
  }

  /** A type a player asks about that is bigger, or faster, than the display. */
  function tooBig(type) {
    if (typeof type !== 'string') return false;
    var width = /[;\s]width=(\d+)/i.exec(type);
    var height = /[;\s]height=(\d+)/i.exec(type);
    var rate = /[;\s]framerate=(\d+(?:\.\d+)?)/i.exec(type);
    var sides = [width, height].filter(Boolean).map(function (match) { return +match[1]; });
    if (sides.length && apply(min, null, sides) > SHORT_SIDE) return true;
    return !!rate && +rate[1] > FRAME_RATE + 0.5;
  }

  if (typeof window.__lumenVideoCapTest === 'function') {
    window.__lumenVideoCapTest({ capManifest: capManifest, capVersions: capVersions, capMedia: capMedia, tooBig: tooBig });
  }

  if (/(^|\.)instagram\.com$/.test(location.hostname)) {
    var parse = JSON.parse;
    var parseCapped = {
      parse: function (text, reviver) {
        var value = apply(parse, JSON, arguments);
        if (typeof text === 'string' && text.length > 200 &&
            (apply(indexOf, text, ['video_dash_manifest']) >= 0 || apply(indexOf, text, ['video_versions']) >= 0)) {
          try { capMedia(value); } catch (error) { log('[Lumen] Video cap failed: ' + error); }
        }
        return value;
      }
    }.parse;
    JSON.parse = parseCapped;
  }

  if (window.MediaSource && typeof MediaSource.isTypeSupported === 'function') {
    var isTypeSupported = MediaSource.isTypeSupported;
    MediaSource.isTypeSupported = {
      isTypeSupported: function (type) { return tooBig(type) ? false : apply(isTypeSupported, this, arguments); }
    }.isTypeSupported;
  }
  if (window.HTMLMediaElement && typeof HTMLMediaElement.prototype.canPlayType === 'function') {
    var canPlayType = HTMLMediaElement.prototype.canPlayType;
    HTMLMediaElement.prototype.canPlayType = {
      canPlayType: function (type) { return tooBig(type) ? '' : apply(canPlayType, this, arguments); }
    }.canPlayType;
  }
})();
