// Execute the embedded player scripts with controllable media events, without a network.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/barkatunnel/app/guide/VideoGuidePage.kt'), 'utf8');
const pages = [...source.matchAll(/return """([\s\S]*?)"""\.trimIndent\(\)/g)].map(m => m[1]
  .replaceAll('$attempt', '1').replaceAll('$API_MEDIA_URL', 'https://example.test/downloads/guide.mp4')
  .replaceAll('$MEDIA_URL', 'https://example.test/media/guide.mp4').replaceAll('$origin', 'https://com.barkatunnel.app')
  .replaceAll('$id', 'abcdefghijk'));
assert.equal(pages.length, 2);
function hosted() {
  const events = {}, documentEvents = {};
  const video = { autoplay: true, plays: 0, pauses: 0, loads: 0, error: { code: 2 },
    addEventListener(name, callback) { events[name] = callback; },
    play() { this.plays++; return Promise.resolve(); }, pause() { this.pauses++; }, load() { this.loads++; } };
  const document = { hidden: false, title: '', getElementById: () => video,
    addEventListener(name, callback) { documentEvents[name] = callback; } };
  const context = vm.createContext({ document });
  vm.runInContext(pages[0].match(/<script>([\s\S]*?)<\/script>/)[1], context);
  return { events, documentEvents, video, document, context };
}
{
  const { events, video, document } = hosted();
  assert.match(video.src, /\/media\//);
  events.canplay(); events.canplay();
  assert.equal(video.plays, 1, 'autoplay once, never restart after buffering');
  events.error(); assert.match(video.src, /\/downloads\//);
  events.canplay(); assert.equal(video.plays, 2, 'fallback also starts automatically');
  events.error(); assert.equal(document.title, 'guide-1-media-error-2');
  assert.equal(video.loads, 1, 'no retry loop');
}
{
  const { events, documentEvents, video, document } = hosted();
  document.hidden = true; documentEvents.visibilitychange();
  events.canplay(); events.error();
  document.hidden = false; events.canplay();
  assert.equal(video.plays, 0, 'no background autoplay or unsolicited resume');
  assert.equal(video.autoplay, false);
  assert.equal(video.pauses, 1);
}
{
  const { events, video, context } = hosted();
  events.canplay(); context.pauseGuide(); events.canplay();
  assert.equal(video.plays, 1, 'close/pause cancels deferred playback');
}
for (const hidden of [false, true]) {
  let options; let plays = 0;
  const context = vm.createContext({ document: { hidden, addEventListener() {} },
    YT: { Player: function (_, config) { options = config; } } });
  vm.runInContext(pages[1].match(/<script>([\s\S]*?)<\/script>/)[1], context);
  context.onYouTubeIframeAPIReady();
  options.events.onReady({ target: { playVideo() { plays++; } } });
  assert.equal(plays, hidden ? 0 : 1);
  assert.equal(options.playerVars.autoplay, hidden ? 0 : 1);
  context.pauseGuide();
  options.events.onReady({ target: { playVideo() { plays++; } } });
  assert.equal(plays, hidden ? 0 : 1);
}
console.log('LOT47: MP4/YouTube autoplay, fallback, pause, hidden page and retry limits passed.');
