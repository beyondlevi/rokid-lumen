// Tests for app/src/main/assets/mrbd-ext/video-cap.js: `node --test scripts/test-*.mjs`.
// The script runs in a fresh context that looks like an Instagram page (its own JSON,
// MediaSource and HTMLMediaElement), the way Gecko runs it at document_start.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../app/src/main/assets/mrbd-ext/video-cap.js', import.meta.url), 'utf8');

function page(hostname = 'www.instagram.com') {
  const logs = [];
  const context = {
    location: { hostname },
    console: { info: (line) => logs.push(line) },
    MediaSource: { isTypeSupported: () => true },
    HTMLMediaElement: function () {},
  };
  context.HTMLMediaElement.prototype.canPlayType = () => 'probably';
  context.window = context;
  context.__lumenVideoCapTest = (api) => { context.api = api; };
  vm.createContext(context);
  vm.runInContext(source, context);
  return { context, api: context.api, logs, JSON: vm.runInContext('JSON', context) };
}

// Shaped like Instagram's manifests (2026: H.264 and VP9 renditions, audio apart).
const MANIFEST = [
  '<?xml version="1.0"?><MPD type="static" mediaPresentationDuration="PT12.5S"><Period>',
  '<AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true">',
  '<Representation id="1" codecs="avc1.64001f" width="720" height="1280" bandwidth="1356000" FBQualityLabel="720p"><BaseURL>https://cdn/v720.mp4</BaseURL><SegmentBase indexRange="0-1"/></Representation>',
  '<Representation id="2" codecs="avc1.4d001f" width="540" height="960" bandwidth="800000"><BaseURL>https://cdn/v540.mp4</BaseURL></Representation>',
  '<Representation id="3" codecs="avc1.4d001e" width="360" height="640" bandwidth="309000"/>',
  '</AdaptationSet>',
  '<AdaptationSet contentType="video" mimeType="video/mp4">',
  '<Representation id="4" codecs="vp09.00.31.08" width="1080" height="1920" bandwidth="2100000"><BaseURL>https://cdn/vp1080.mp4</BaseURL></Representation>',
  '<Representation id="5" codecs="vp09.00.21.08" width="480" height="854" bandwidth="500000"><BaseURL>https://cdn/vp480.mp4</BaseURL></Representation>',
  '</AdaptationSet>',
  '<AdaptationSet contentType="audio" mimeType="audio/mp4">',
  '<Representation id="a" codecs="mp4a.40.5" audioSamplingRate="48000" bandwidth="72000"><BaseURL>https://cdn/a.mp4</BaseURL></Representation>',
  '</AdaptationSet></Period></MPD>',
].join('');

// Arrays made in the page's context have its own Array: compare them as plain values.
const plain = (value) => JSON.parse(JSON.stringify(value));
const ids = (xml) => [...xml.matchAll(/<Representation id="([^"]+)"/g)].map((m) => m[1]);

test('keeps the H.264 renditions that fit the display, and the audio', () => {
  const { api, logs } = page();
  const capped = api.capManifest(MANIFEST);
  assert.deepEqual(ids(capped), ['3', 'a']);
  assert.ok(!capped.includes('vp09'), 'the VP9 set goes, empty');
  assert.ok(capped.startsWith('<?xml') && capped.endsWith('</MPD>'));
  assert.match(logs[0], /kept 360x640 avc1; dropped 720x1280 avc1, 540x960 avc1, 1080x1920 vp09, 480x854 vp09/);
});

test('without H.264, keeps the other codec that fits', () => {
  const { api } = page();
  const vp9Only = MANIFEST.replace(/<AdaptationSet contentType="video"[\s\S]*?<\/AdaptationSet>/, '');
  assert.deepEqual(ids(api.capManifest(vp9Only)), ['5', 'a']);
});

test('when nothing fits, keeps the smallest rendition', () => {
  const { api } = page();
  const big = MANIFEST.replace('width="360" height="640"', 'width="1080" height="1920"')
    .replace(/<AdaptationSet contentType="video" mimeType="video\/mp4">[\s\S]*?<\/AdaptationSet>/, '');
  assert.deepEqual(ids(api.capManifest(big)), ['2', 'a']);
});

test('leaves alone a manifest that already fits, or whose sizes are unknown', () => {
  const { api, logs } = page();
  const small = '<MPD><Period><AdaptationSet contentType="video"><Representation id="1" codecs="avc1" width="360" height="640"/></AdaptationSet></Period></MPD>';
  assert.equal(api.capManifest(small), small);
  const unknown = MANIFEST.replace(' width="540" height="960"', '');
  assert.equal(api.capManifest(unknown), unknown);
  assert.equal(api.capManifest(''), '');
  assert.equal(api.capManifest(null), null);
  assert.equal(logs.length, 0);
});

test('caps progressive files to those that fit', () => {
  const { api } = page();
  const versions = [
    { type: 101, width: 720, height: 1280, url: 'a' },
    { type: 102, width: 480, height: 854, url: 'b' },
    { type: 103, width: 360, height: 640, url: 'c' },
  ];
  assert.deepEqual(plain(api.capVersions(versions).map((v) => v.url)), ['b', 'c']);
  const big = [{ width: 1080, height: 1920, url: 'x' }, { width: 720, height: 1280, url: 'y' }];
  assert.deepEqual(plain(api.capVersions(big).map((v) => v.url)), ['y']);
  const partial = [{ width: 1080, height: 1920, url: 'x' }, { url: 'z' }];
  assert.equal(api.capVersions(partial), partial);
});

test("Instagram's JSON comes out of JSON.parse capped, wherever the media sits", () => {
  const { JSON: pageJSON } = page();
  const response = JSON.stringify({
    data: { xdt_api__v1__clips__home__connection_v2: { edges: [
      { node: { media: { id: '1', video_dash_manifest: MANIFEST, video_versions: [
        { width: 720, height: 1280, url: 'a' }, { width: 360, height: 640, url: 'c' }] } } },
      { node: { media: { id: '2', carousel_media: [{ video_dash_manifest: MANIFEST }] } } },
    ] } },
  });
  const value = pageJSON.parse(response);
  const edges = value.data.xdt_api__v1__clips__home__connection_v2.edges;
  assert.deepEqual(ids(edges[0].node.media.video_dash_manifest), ['3', 'a']);
  assert.deepEqual(plain(edges[0].node.media.video_versions.map((v) => v.url)), ['c']);
  assert.deepEqual(ids(edges[1].node.media.carousel_media[0].video_dash_manifest), ['3', 'a']);
  // Anything else parses as before, a reviver included.
  assert.deepEqual(plain(pageJSON.parse('{"a":[1,2]}')), { a: [1, 2] });
  assert.equal(pageJSON.parse('{"n":2}', (k, v) => (k === 'n' ? v * 2 : v)).n, 4);
  assert.throws(() => pageJSON.parse('{'));
});

test('other sites keep the JSON.parse they had', () => {
  const { JSON: pageJSON } = page('m.youtube.com');
  const value = pageJSON.parse(JSON.stringify({ video_dash_manifest: MANIFEST, pad: 'x'.repeat(300) }));
  assert.equal(value.video_dash_manifest, MANIFEST);
});

test('players hear "no" for sizes and frame rates above the display', () => {
  const { context, api } = page('m.youtube.com');
  const ms = context.MediaSource;
  assert.equal(ms.isTypeSupported('video/webm; codecs="vp9"; width=1280; height=720'), false);
  assert.equal(ms.isTypeSupported('video/mp4; codecs="avc1.4d401e"; width=854; height=480'), true);
  assert.equal(ms.isTypeSupported('video/mp4; codecs="avc1.4d401e"; width=640; height=360; framerate=60'), false);
  assert.equal(ms.isTypeSupported('video/mp4; codecs="avc1.4d401e"; framerate=29.97'), true);
  assert.equal(ms.isTypeSupported('video/mp4; codecs="avc1.4d401e"'), true);
  const video = new context.HTMLMediaElement();
  assert.equal(video.canPlayType('video/webm; codecs="vp9"; width=3840; height=2160'), '');
  assert.equal(video.canPlayType('video/mp4'), 'probably');
  assert.equal(api.tooBig(undefined), false);
});
